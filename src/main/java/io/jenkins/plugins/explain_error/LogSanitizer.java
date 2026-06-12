package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.commons.lang3.StringUtils;

/**
 * Redacts sensitive values before build logs or workspace context are sent to an AI provider.
 */
public final class LogSanitizer {

    public static final String REDACTION_TOKEN = "[REDACTED_SECRET]";

    private static final List<RedactionRule> DEFAULT_SECRET_PATTERNS = List.of(
            new RedactionRule(Pattern.compile("(?i)(\\b(?:password|passwd|pwd|secret|token|api[_-]?key|"
                    + "access[_-]?key|client[_-]?secret|license[_-]?key)\\b\\s*[:=]\\s*)([^\\s'\";]+)"),
                    true),
            new RedactionRule(Pattern.compile("(?i)(authorization\\s*:\\s*bearer\\s+)[A-Za-z0-9._~+/=-]+"),
                    false),
            new RedactionRule(Pattern.compile("(?i)(basic\\s+)[A-Za-z0-9+/=]{12,}"), false),
            new RedactionRule(Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{20,}\\b"), false),
            new RedactionRule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), false),
            new RedactionRule(Pattern.compile(
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), false),
            new RedactionRule(Pattern.compile("(?i)\\b(?:https?|ssh)://[^\\s'\"<>]*(?:internal|intranet|corp|local|"
                    + "localhost|127\\.0\\.0\\.1|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|"
                    + "192\\.168\\.\\d{1,3}\\.\\d{1,3})[^\\s'\"<>]*"), false),
            new RedactionRule(Pattern.compile("(?i)\\b(?:git@|ssh://git@)[^\\s'\"<>]+[:/][^\\s'\"<>]+\\.git\\b"),
                    false),
            new RedactionRule(
                    Pattern.compile("(?i)(?:/Users|/home|/var/lib/jenkins|/private/var|C:\\\\Users)\\S+"),
                    false),
            new RedactionRule(Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
                    Pattern.CASE_INSENSITIVE), false));

    public SanitizedPayload sanitize(String payload, Policy policy) {
        if (StringUtils.isBlank(payload)) {
            return new SanitizedPayload("", 0, 0, 0);
        }

        Policy effectivePolicy = policy != null ? policy : Policy.enabled("", "");
        if (!effectivePolicy.enabled()) {
            return new SanitizedPayload(payload, 0, 0, countLines(payload));
        }

        String allowedPayload = applyAllowPolicy(payload, effectivePolicy.allowRegex());
        int droppedLines = countPolicyLines(payload) - countPolicyLines(allowedPayload);

        RedactionResult redacted = redact(allowedPayload, DEFAULT_SECRET_PATTERNS);
        int redactionCount = redacted.redactionCount();
        if (StringUtils.isNotBlank(effectivePolicy.denyRegex())) {
            redacted = redact(redacted.text(), List.of(new RedactionRule(
                    Pattern.compile(effectivePolicy.denyRegex()), false)));
            redactionCount += redacted.redactionCount();
        }

        return new SanitizedPayload(redacted.text(), redactionCount, Math.max(0, droppedLines),
                countLines(redacted.text()));
    }

    private String applyAllowPolicy(String payload, String allowRegex) {
        if (StringUtils.isBlank(allowRegex)) {
            return payload;
        }

        Pattern allowPattern = Pattern.compile(allowRegex);
        String[] lines = payload.split("\\R");
        List<String> allowedLines = new ArrayList<>();
        for (String line : lines) {
            if (allowPattern.matcher(line).find()) {
                allowedLines.add(line);
            }
        }
        return String.join("\n", allowedLines);
    }

    private RedactionResult redact(String payload, List<RedactionRule> rules) {
        String redacted = payload;
        int redactionCount = 0;
        for (RedactionRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(redacted);
            StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                redactionCount++;
                String replacement = rule.preserveFirstGroup() && matcher.groupCount() >= 1
                        ? Matcher.quoteReplacement(matcher.group(1) + REDACTION_TOKEN)
                        : REDACTION_TOKEN;
                matcher.appendReplacement(buffer, replacement);
            }
            matcher.appendTail(buffer);
            redacted = buffer.toString();
        }
        return new RedactionResult(redacted, redactionCount);
    }

    static int countLines(@CheckForNull String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }

        int lineCount = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineCount++;
            }
        }
        return lineCount;
    }

    private int countPolicyLines(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        return text.split("\\R").length;
    }

    public static void validateRegex(String regex) {
        if (StringUtils.isNotBlank(regex)) {
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw e;
            }
        }
    }

    public record Policy(boolean enabled, String allowRegex, String denyRegex) {
        public static Policy enabled(String allowRegex, String denyRegex) {
            return new Policy(true, StringUtils.defaultString(allowRegex), StringUtils.defaultString(denyRegex));
        }

        public static Policy disabled() {
            return new Policy(false, "", "");
        }
    }

    public record SanitizedPayload(String text, int redactionCount, int droppedLineCount, int sentLineCount) {
    }

    private record RedactionRule(Pattern pattern, boolean preserveFirstGroup) {
    }

    private record RedactionResult(String text, int redactionCount) {
    }
}
