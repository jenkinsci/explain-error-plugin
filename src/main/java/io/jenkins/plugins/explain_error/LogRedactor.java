package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
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
 * One instance serves one request and counts what it masked across every text it was given.
 */
final class LogRedactor {

    static final String MASK = "[REDACTED]";

    private static final Logger LOGGER = Logger.getLogger(LogRedactor.class.getName());

    /** A value already masked, by Jenkins ({@code ****}) or by an earlier rule, is not masked again. */
    private static final String NOT_MASKED = "(?!\\[REDACTED\\]|\\*{3,}(?:[\\s\"',;]|$))";

    /** Key names that say their value is a secret, as in {@code DB_PASSWORD=...} or {@code "apiKey": "..."}. */
    private static final String SECRET_KEY = "(?:password|passwd|secret|token|api[_-]?key|access[_-]?key"
            + "|private[_-]?key)";

    /** A reference to a value rather than the value: {@code ${PASSWORD}}, {@code %s}, {@code {{ token }}}. */
    private static final String NOT_A_REFERENCE = "(?![$%{])";

    static final List<Pattern> BUILT_IN = List.of(
            // A private key, PEM-encoded, from its first line to its last
            Pattern.compile("-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]{0,40}PRIVATE KEY-----"),
            // AWS access key IDs
            Pattern.compile("\\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\\b"),
            // GitHub tokens: personal, OAuth, app, refresh, fine-grained
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})\\b"),
            // GitLab personal, project and group access tokens
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}"),
            // Slack tokens
            Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
            // Google API keys
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            // Stripe live keys
            Pattern.compile("\\b[rs]k_live_[0-9A-Za-z]{16,}"),
            // npm access tokens
            Pattern.compile("\\bnpm_[A-Za-z0-9]{36}\\b"),
            // JSON Web Tokens: a header and a payload, both base64url JSON, and a signature
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            // The credentials of an Authorization header, or of a bearer token on its own
            Pattern.compile("(?i)\\b(?:authorization\\s*[:=]\\s*(?:bearer|basic|token)|bearer)\\s+" + NOT_MASKED
                    // a token has a digit somewhere; "Bearer authentication failed" has none
                    + "(?=[A-Za-z0-9._~+/-]*\\d)(?<secret>[A-Za-z0-9._~+/-]{8,}=*)"),
            // The password of a URL: https://user:password@host
            Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]{1,20}://[^\\s:/@]{1,100}:" + NOT_MASKED
                    + "(?<secret>[^\\s/@]{1,200})@"),
            // A value given to a key named like a secret: PASSWORD=..., "token": "...", api_key: ...
            Pattern.compile("(?i)\\b[a-z0-9_.-]{0,40}" + SECRET_KEY
                    + "[a-z0-9_.-]{0,40}[\"']?\\s{0,3}[:=]\\s{0,3}[\"']?" + NOT_MASKED + NOT_A_REFERENCE + "(?<secret>[^\\s\"',;]{4,})"),
            // A value given to a command-line option named like a secret: --password hunter2, --token=...
            Pattern.compile("(?i)(?<![\\w-])--?(?:password|passwd|token|secret|api[_-]?key)(?:=|\\s+)" + NOT_MASKED
                    + NOT_A_REFERENCE + "(?<secret>[^\\s\"'-][^\\s\"']{3,})"));

    private final List<Pattern> rules;
    private int count;

    LogRedactor(List<Pattern> rules) {
        this.rules = List.copyOf(rules);
    }

    /** The rules the global configuration asks for: the built-in ones if on, then the administrator's own. */
    @NonNull
    static LogRedactor fromConfiguration() {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        List<Pattern> rules = new ArrayList<>();
        if (config.isRedactSecrets()) {
            rules.addAll(BUILT_IN);
        }
        for (String line : patternLines(config.getRedactionPatterns())) {
            try {
                rules.add(Pattern.compile(line));
            } catch (PatternSyntaxException e) {
                // The form reports it; a pattern saved some other way is left out rather than stop explanations
                LOGGER.fine("A redaction pattern is not a valid regular expression, so it is not used: "
                        + e.getDescription());
            }
        }
        return new LogRedactor(rules);
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
     * @return {@code text} with every secret the rules find replaced by {@link #MASK}; {@code null} for
     *         {@code null}
     */
    @CheckForNull
    String redact(@CheckForNull String text) {
        if (text == null || text.isEmpty() || rules.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern rule : rules) {
            result = apply(rule, result);
        }
        return result;
    }

    private String apply(Pattern rule, String text) {
        Matcher matcher = rule.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        boolean secretGroup = rule.pattern().contains("(?<secret>");
        StringBuilder result = new StringBuilder(text.length());
        int last = 0;
        do {
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

    /** How many secrets were masked in all the texts given so far. */
    int count() {
        return count;
    }

    /** Whether there is any rule to apply. */
    boolean isActive() {
        return !rules.isEmpty();
    }
}
