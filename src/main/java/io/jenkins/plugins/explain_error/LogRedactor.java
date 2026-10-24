package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Masks secrets in the text sent to the AI provider: build logs, custom context and workspace files.
 * <p>
 * Jenkins already masks the credentials a build binds with {@code withCredentials}, but a log also shows
 * secrets that never went through a credential: a token in an environment dump, a password in a URL, a key
 * printed by a script. The built-in rules match the shape of common secrets; an administrator adds rules of
 * their own. A rule with a group named {@code secret} masks that group only, so that the model still sees
 * what the value was for ({@code password=[REDACTED]}); any other rule masks the whole match.
 * <p>
 * Anyone who can write to a build log decides what these rules run on, so every built-in rule takes time in
 * proportion to the length of the text: no quantifier there can go back over what it took more than a bounded
 * number of characters.
 * <p>
 * One instance serves one request and counts what it masked across every text it was given.
 */
final class LogRedactor {

    static final String MASK = "[REDACTED]";

    private static final Logger LOGGER = Logger.getLogger(LogRedactor.class.getName());

    /** A value already masked, by Jenkins ({@code ****}) or by an earlier rule, is not masked again. */
    private static final String NOT_MASKED = "(?!\\[REDACTED\\]|\\*{3,}+(?:[\\s\"',;]|$))";

    /** A reference to a value rather than the value: {@code ${PASSWORD}}, {@code %s}, {@code {{ token }}}. */
    private static final String NOT_A_REFERENCE = "(?![$%{])";

    /** What ends a line in a log, or inside a JSON string. */
    private static final String LINE_BREAK = "(?:\\\\[nr]|\\s)++";

    private static final String KEY_HEADER = "PRIVATE KEY(?: BLOCK)?-----";

    /** The first line of a private key. */
    private static final Pattern PRIVATE_KEY_BEGIN = Pattern.compile("-----BEGIN [A-Z0-9 ]{0,40}" + KEY_HEADER);

    /** The last line of a private key. */
    private static final Pattern PRIVATE_KEY_END = Pattern.compile("-----END [A-Z0-9 ]{0,40}" + KEY_HEADER);

    /**
     * What follows the first line of a key whose last line is not near: its base64 lines and headers, each a
     * line of its own, then its last line if it is there. A key cut short is masked as far as its base64 goes.
     */
    private static final Pattern PRIVATE_KEY_BODY = Pattern.compile("(?:" + LINE_BREAK
            + "(?:(?:[A-Za-z0-9+/=]|\\\\/){16,}+(?=[ \\t]*+(?:[\\r\\n\"']|\\\\[nr]|$))"
            + "|(?:Proc-Type|DEK-Info|Version|Comment|Hash):[^\\r\\n\\\\]*+))*+"
            + "(?:" + LINE_BREAK + "(?:[A-Za-z0-9+/=]{1,15}+" + LINE_BREAK + ")?-----END [A-Z0-9 ]{0,40}"
            + KEY_HEADER + ")?+");

    /**
     * The most characters from the first line of a key to its last. A PGP key with subkeys, its lines each
     * behind a timestamp, fits; a last line further away belongs to some other key.
     */
    private static final int MAX_KEY_LENGTH = 32 * 1024;

    /** Secrets recognised by their shape alone. */
    private static final List<Pattern> TOKENS = List.of(
            // AWS access key IDs
            Pattern.compile("\\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\\b"),
            // GitHub tokens: personal, OAuth, app, refresh, fine-grained
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})\\b"),
            // GitLab personal, project and group access tokens
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}+"),
            // Slack tokens
            Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}+"),
            // Google API keys
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            // Stripe live keys
            Pattern.compile("\\b[rs]k_live_[0-9A-Za-z]{16,}+"),
            // OpenAI and Anthropic API keys: a digit somewhere, which a word joined with dashes has not
            Pattern.compile("\\bsk-(?=[A-Za-z_-]{0,60}+\\d)(?:proj-|svcacct-|admin-|ant-(?:[a-z]{3}\\d\\d-)?)?"
                    + "[A-Za-z0-9_-]{20,}+"),
            // npm access tokens
            Pattern.compile("\\bnpm_[A-Za-z0-9]{36}\\b"),
            // JSON Web Tokens: a header and a payload, both base64url JSON, and a signature
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}+\\.eyJ[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}+"),
            // The credentials of an Authorization header, or of a bearer token on its own; a token has a digit
            // somewhere, and "Bearer authentication failed" has none
            Pattern.compile("(?i)\\b(?:authorization[ \\t]*+[:=][ \\t]*+(?:bearer|basic|token)|bearer)[ \\t]++"
                    + NOT_MASKED + "(?=[A-Za-z._~+/-]{0,100}+\\d)(?<secret>[A-Za-z0-9._~+/-]{8,}+=*+)"),
            // The password of a URL: https://user:password@host
            Pattern.compile("(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]{1,20}+://[^\\s:/@]{1,100}+:" + NOT_MASKED
                    + "(?<secret>[^\\s/@]{1,200}+)@"),
            // A command-line option named like a secret, with its value quoted: --password 'p@ss w0rd'
            Pattern.compile("(?i)(?<![\\w-])--?(?:password|passwd|passphrase|token|secret|api[_-]?key)"
                    + "(?:=|[ \\t]++)(?<quote>[\"'])" + NOT_MASKED + NOT_A_REFERENCE
                    + "(?<secret>(?:(?!\\k<quote>)[^\\r\\n]){1,200}+)\\k<quote>"),
            // ... and without quotes: --password hunter2, --token=...
            Pattern.compile("(?i)(?<![\\w-])--?(?:password|passwd|passphrase|token|secret|api[_-]?key)"
                    + "(?:=|[ \\t]++)" + NOT_MASKED + NOT_A_REFERENCE + "(?<secret>[^\\s\"'-][^\\s\"']{3,}+)"));

    /**
     * The password of {@code -u user:password}, masked only after {@code curl} on the same line: docker and sudo
     * take {@code -u user:group}, which is not secret.
     */
    private static final Pattern USER_OPTION = Pattern.compile("(?<![\\w-])(?:-u[ \\t]*+|--user(?:[ \\t]++|=))[\"']?"
            + "[^\\s:\"'@]{1,100}+:" + NOT_MASKED + NOT_A_REFERENCE + "(?<secret>[^\\s\"']{1,200}+)");

    /**
     * A key given a value: {@code KEY=}, {@code key: }, {@code "key": "}, also in a JSON string
     * ({@code \"key\":\"}) and after {@code -D}. Whether the key names a secret is decided by
     * {@link #isSecretKey}, which a regular expression could only do by trying every part of the key; only then is
     * a value looked for after it, so that what follows a key that does not name a secret is searched for keys in
     * turn.
     */
    private static final Pattern KEY = Pattern.compile("(?<![A-Za-z0-9_.-])(?:--?D?)?"
            + "(?<key>[A-Za-z_][A-Za-z0-9_.-]{0,59}+)(?:\\\\?[\"'])?[ \\t]{0,3}+(?<separator>[:=])[ \\t]{0,3}+"
            + "(?<quote>\\\\?[\"'])?");

    /** The value after a {@link #KEY}. */
    private static final Pattern VALUE = Pattern.compile(NOT_MASKED + NOT_A_REFERENCE + "[^\\s\"'\\\\,;]{4,}+");

    /** The value after a {@link #KEY} in a query string, which ends at the next parameter. */
    private static final Pattern QUERY_VALUE = Pattern.compile(NOT_MASKED + NOT_A_REFERENCE
            + "[^\\s\"'\\\\,;&#]{4,}+");

    /** Values that say there is none: {@code password=null} tells why something failed. */
    private static final Pattern NO_VALUE = Pattern.compile("(?i)null|undefined|none|nil|empty|true|false");

    /** An XML element with a value: {@code <password>...</password>}, as in Maven's settings.xml. */
    private static final Pattern XML_ELEMENT = Pattern.compile("<(?<key>[A-Za-z_][A-Za-z0-9_.:-]{0,59}+)"
            + "(?:[ \\t][^<>\\r\\n]{0,200}+)?>[ \\t]*+" + NOT_MASKED + NOT_A_REFERENCE
            + "(?<secret>[^<\\r\\n]{1,500}+)</\\k<key>>");

    /** Words that name a secret when they end a key: {@code DB_PASSWORD}, {@code clientSecret}. */
    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "passphrase", "secret", "token",
            "apikey", "accesskey", "privatekey", "secretkey");

    /** Words that end a key written in capitals as one word: {@code PGPASSWORD}, {@code NPMTOKEN}. */
    private static final List<String> SECRET_ENDINGS = List.of("password", "passwd", "passphrase", "secret",
            "token");

    /** Words that name a secret before {@code key}: {@code API_KEY}, {@code AccountKey}, {@code private-key}. */
    private static final Set<String> BEFORE_KEY = Set.of("api", "access", "private", "secret", "account", "auth",
            "signing", "encryption", "master", "client", "license");

    /** A word that may follow the ones above and still name the secret itself: {@code SECRET_KEY_BASE}. */
    private static final Set<String> AFTER_SECRET = Set.of("value", "base", "b64", "base64");

    /** Where a key name divides into words: separators, and where camel case starts a word. */
    private static final Pattern WORD_BREAK =
            Pattern.compile("[_.:-]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    private static final Pattern LETTERS = Pattern.compile("[A-Za-z]++");

    private final boolean builtIn;
    private final List<Pattern> patterns;
    private int count;

    /**
     * @param builtIn  whether the built-in rules apply
     * @param patterns rules of the administrator's own
     */
    LogRedactor(boolean builtIn, List<Pattern> patterns) {
        this.builtIn = builtIn;
        this.patterns = List.copyOf(patterns);
    }

    /** The built-in rules alone. */
    static LogRedactor builtIn() {
        return new LogRedactor(true, List.of());
    }

    /** The rules the global configuration asks for: the built-in ones if on, then the administrator's own. */
    @NonNull
    static LogRedactor fromConfiguration() {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        List<Pattern> patterns = new ArrayList<>();
        for (String line : patternLines(config.getRedactionPatterns())) {
            try {
                patterns.add(Pattern.compile(line));
            } catch (PatternSyntaxException e) {
                // The form reports it; a pattern saved some other way is left out rather than stop explanations
                LOGGER.fine("A redaction pattern is not a valid regular expression, so it is not used: "
                        + e.getDescription());
            }
        }
        return new LogRedactor(config.isRedactSecrets(), patterns);
    }

    /** The patterns of the setting: one per line, leaving out blank lines and those starting with {@code #}. */
    static List<String> patternLines(@CheckForNull String patterns) {
        List<String> lines = new ArrayList<>();
        if (patterns == null) {
            return lines;
        }
        for (String line : patterns.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * Whether a key names a secret: its last word is one of {@link #SECRET_WORDS}, or {@code key} after one of
     * {@link #BEFORE_KEY}, maybe followed by one of {@link #AFTER_SECRET}; or, written in capitals, it ends in
     * one of {@link #SECRET_ENDINGS}. {@code TokenService}, {@code password_reset}, {@code jsonwebtoken},
     * {@code max_tokens} and {@code DB_PASSWORD_FILE} do not.
     */
    static boolean isSecretKey(String key) {
        List<String> words = new ArrayList<>();
        for (String word : WORD_BREAK.split(key)) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        int last = words.size() - 1;
        if (last > 0 && AFTER_SECRET.contains(words.get(last).toLowerCase(Locale.ROOT))) {
            last--;
        }
        if (last < 0) {
            return false;
        }
        String original = words.get(last);
        String word = original.toLowerCase(Locale.ROOT);
        if (SECRET_WORDS.contains(word)) {
            return true;
        }
        if ("key".equals(word)) {
            return last > 0 && BEFORE_KEY.contains(words.get(last - 1).toLowerCase(Locale.ROOT));
        }
        return original.equals(original.toUpperCase(Locale.ROOT)) && SECRET_ENDINGS.stream().anyMatch(word::endsWith);
    }

    /**
     * @return {@code text} with every secret the rules find replaced by {@link #MASK}; {@code null} for
     *         {@code null}
     */
    @CheckForNull
    String redact(@CheckForNull String text) {
        if (text == null || text.isEmpty() || !isActive()) {
            return text;
        }
        String result = text;
        if (builtIn) {
            result = maskPrivateKeys(result);
            result = maskKeyEnds(result);
            for (Pattern token : TOKENS) {
                result = apply(token, result, match -> true);
            }
            result = apply(USER_OPTION, result, new AfterCurl(result));
            result = maskKeyValues(result);
            result = apply(XML_ELEMENT, result, match -> isSecretKey(match.group("key")));
        }
        for (Pattern pattern : patterns) {
            result = apply(pattern, result, match -> true);
        }
        return result;
    }

    /**
     * Masks private keys from their first line: to their last line when it comes within {@link #MAX_KEY_LENGTH},
     * is of the same kind and no other first line comes before it, which also takes in lines that start with a
     * timestamp or another prefix; else as far as their base64 goes, so that a key header printed on its own, as
     * a secret scanner does, takes no log lines with it. The next first and last lines found are kept for the next
     * keys while they are ahead of them, so the text is read once.
     */
    private String maskPrivateKeys(String text) {
        Matcher begin = PRIVATE_KEY_BEGIN.matcher(text);
        if (!begin.find()) {
            return text;
        }
        Matcher end = PRIVATE_KEY_END.matcher(text);
        Matcher nextBegin = PRIVATE_KEY_BEGIN.matcher(text);
        Matcher body = PRIVATE_KEY_BODY.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        int last = 0;
        int endStart = -1;
        int endEnd = -1;
        String endKind = null;
        boolean moreEnds = true;
        int nextBeginStart = -1;
        boolean moreBegins = true;
        do {
            if (moreEnds && endStart < begin.end()) {
                moreEnds = end.find(begin.end());
                if (moreEnds) {
                    endStart = end.start();
                    endEnd = end.end();
                    endKind = end.group().substring("-----END".length());
                }
            }
            if (moreBegins && nextBeginStart < begin.end()) {
                moreBegins = nextBegin.find(begin.end());
                nextBeginStart = moreBegins ? nextBegin.start() : -1;
            }
            int stop;
            if (moreEnds && endStart - begin.end() <= MAX_KEY_LENGTH && (!moreBegins || endStart < nextBeginStart)
                    && begin.group().substring("-----BEGIN".length()).equals(endKind)) {
                stop = endEnd;
            } else {
                body.region(begin.end(), text.length());
                stop = body.lookingAt() ? body.end() : begin.end();
            }
            result.append(text, last, begin.start()).append(MASK);
            last = stop;
            count++;
        } while (last < text.length() && begin.find(last));
        return result.append(text, last, text.length()).toString();
    }

    /**
     * Masks the base64 lines before the last line of a private key whose first line is not in the text, as
     * when the key starts before the lines sent. Walks back from each last line, so the time stays in
     * proportion to the length of the text.
     */
    private String maskKeyEnds(String text) {
        Matcher end = PRIVATE_KEY_END.matcher(text);
        if (!end.find()) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        int last = 0;
        do {
            int start = keyBodyStart(text, end.start(), last);
            result.append(text, last, start).append(MASK);
            last = end.end();
            count++;
        } while (end.find());
        return result.append(text, last, text.length()).toString();
    }

    /**
     * Where the base64 lines that end at {@code end} start, going no further back than {@code limit}. Each line
     * is base64 from its start to its end; the last one may be short.
     */
    private static int keyBodyStart(String text, int end, int limit) {
        int start = end;
        int position = end;
        boolean lastLine = true;
        while (position > limit) {
            int lineEnd = position;
            while (lineEnd > limit) {
                if (Character.isWhitespace(text.charAt(lineEnd - 1))) {
                    lineEnd--;
                } else if (isEscapedLineBreak(text, lineEnd, limit)) {
                    lineEnd -= 2;
                } else {
                    break;
                }
            }
            int lineStart = lineEnd;
            while (lineStart > limit && !isEscapedLineBreak(text, lineStart, limit)) {
                char c = text.charAt(lineStart - 1);
                // A slash in a JSON string may be written \/
                if (isBase64(c) || c == '\\' && lineStart < lineEnd && text.charAt(lineStart) == '/') {
                    lineStart--;
                } else {
                    break;
                }
            }
            int length = lineEnd - lineStart;
            if (length == 0 || length < 16 && !lastLine || !isLineStart(text, lineStart, limit)) {
                break;
            }
            start = lineStart;
            position = lineStart;
            lastLine = false;
        }
        return start;
    }

    private static boolean isBase64(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/'
                || c == '=';
    }

    /** Whether {@code \n} or {@code \r}, as written in a JSON string, ends just before {@code position}. */
    private static boolean isEscapedLineBreak(String text, int position, int limit) {
        if (position - 2 < limit || text.charAt(position - 2) != '\\') {
            return false;
        }
        char c = text.charAt(position - 1);
        return c == 'n' || c == 'r';
    }

    /** Whether a line starts at {@code position}: after a line break, or after the quote that opens a string. */
    private static boolean isLineStart(String text, int position, int limit) {
        if (position == limit) {
            return true;
        }
        char before = text.charAt(position - 1);
        return before == '\n' || before == '\r' || before == '"' || before == '\''
                || isEscapedLineBreak(text, position, limit);
    }

    /**
     * Masks the values given to keys named like a secret. {@code password=null} and the like are left: they say
     * there is no value. So is a single word after a colon when the key is a single word in a sentence:
     * {@code Invalid token: expired} says what went wrong, where {@code password: changeme} at the start of a
     * line of YAML gives the value.
     */
    private String maskKeyValues(String text) {
        Matcher key = KEY.matcher(text);
        Matcher value = VALUE.matcher(text);
        Matcher queryValue = QUERY_VALUE.matcher(text);
        StringBuilder result = null;
        int last = 0;
        int from = 0;
        while (from < text.length() && key.find(from)) {
            from = key.end();
            String name = key.group("key");
            if (!isSecretKey(name)) {
                continue;
            }
            int keyStart = key.start();
            boolean inQuery = keyStart > 0 && (text.charAt(keyStart - 1) == '?' || text.charAt(keyStart - 1) == '&');
            Matcher found = inQuery ? queryValue : value;
            found.region(key.end(), text.length());
            if (!found.lookingAt() || NO_VALUE.matcher(found.group()).matches()
                    || ":".equals(key.group("separator")) && key.group("quote") == null
                    && LETTERS.matcher(found.group()).matches() && isInASentence(text, keyStart, name)) {
                continue;
            }
            if (result == null) {
                result = new StringBuilder(text.length());
            }
            result.append(text, last, found.start()).append(MASK);
            last = found.end();
            from = found.end();
            count++;
        }
        return result == null ? text : result.append(text, last, text.length()).toString();
    }

    /**
     * Whether a key of one word comes after a word, as in {@code ERROR: Invalid token} or
     * {@code on field 'secret'}, rather than first on its line, after a list or map mark, or after a prefix such
     * as {@code [INFO]} or a timestamp.
     */
    private static boolean isInASentence(String text, int keyStart, String key) {
        if (!LETTERS.matcher(key).matches()) {
            return false;
        }
        int position = keyStart;
        if (position > 0 && (text.charAt(position - 1) == '\'' || text.charAt(position - 1) == '"')) {
            position--;
        }
        while (position > 0 && (text.charAt(position - 1) == ' ' || text.charAt(position - 1) == '\t')) {
            position--;
        }
        return position > 0 && Character.isLetter(text.charAt(position - 1));
    }

    /**
     * Masks what {@code rule} finds and {@code accept} agrees with: the group named {@code secret} if the rule
     * has one and it took part in the match, or else the whole match.
     */
    private String apply(Pattern rule, String text, Predicate<Matcher> accept) {
        Matcher matcher = rule.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        boolean secretGroup = hasSecretGroup(matcher);
        StringBuilder result = new StringBuilder(text.length());
        int last = 0;
        do {
            if (!accept.test(matcher)) {
                continue;
            }
            int start = matcher.start();
            int end = matcher.end();
            if (secretGroup && matcher.start("secret") >= 0) {
                start = matcher.start("secret");
                end = matcher.end("secret");
            }
            if (end > start) {
                result.append(text, last, start).append(MASK);
                last = end;
                count++;
            }
        } while (matcher.find());
        return result.append(text, last, text.length()).toString();
    }

    /**
     * Whether {@code curl} comes before a match in its command: on its line or a line it continues with a
     * backslash, and not before a pipe, {@code ;} or {@code &&} outside quotes ({@code -H "...; charset=utf-8"}
     * is one argument). The matches come in order, so the text is read once, as far as the last match.
     */
    private static final class AfterCurl implements Predicate<Matcher> {

        private final String text;
        private int read;
        private int lineStart;
        private int curl = -1;
        /** The quote of the argument being read, or 0 outside quotes. */
        private char quote;

        AfterCurl(String text) {
            this.text = text;
        }

        @Override
        public boolean test(Matcher match) {
            for (; read < match.start(); read++) {
                char c = text.charAt(read);
                if (c == '\n' || c == '\r') {
                    if (!isContinued(read)) {
                        lineStart = read + 1;
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"') {
                    if (quote == 0) {
                        quote = c;
                    } else if (quote == c && !(c == '"' && read > 0 && text.charAt(read - 1) == '\\')) {
                        quote = 0;
                    }
                } else if (quote == 0 && isCommandSeparator(c, read)) {
                    lineStart = read + 1;
                } else if (c == 'c' && text.startsWith("curl", read) && isWordStart(read) && isWordEnd(read + 4)) {
                    curl = read;
                }
            }
            return curl >= lineStart;
        }

        /** Whether a pipe, {@code ;} or {@code &&} at {@code position} ends a command. */
        private boolean isCommandSeparator(char c, int position) {
            return c == '|' || c == ';' || c == '&' && position + 1 < text.length() && text.charAt(position + 1) == '&';
        }

        /** Whether the line break at {@code position} follows a backslash, which continues the command. */
        private boolean isContinued(int position) {
            int before = position - 1;
            if (text.charAt(position) == '\n' && before >= 0 && text.charAt(before) == '\r') {
                before--;
            }
            return before >= 0 && text.charAt(before) == '\\';
        }

        private boolean isWordStart(int position) {
            return position == 0 || !Character.isLetterOrDigit(text.charAt(position - 1));
        }

        private boolean isWordEnd(int position) {
            return position >= text.length() || !Character.isLetterOrDigit(text.charAt(position));
        }
    }

    /** Whether the pattern of a matcher that found something has a group named {@code secret}. */
    private static boolean hasSecretGroup(Matcher matcher) {
        try {
            matcher.start("secret");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** How many secrets were masked in all the texts given so far. */
    int count() {
        return count;
    }

    /** Whether there is any rule to apply. */
    boolean isActive() {
        return builtIn || !patterns.isEmpty();
    }
}
