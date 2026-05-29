package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    private final LogSanitizer sanitizer = new LogSanitizer();

    @Test
    void sanitize_redactsCommonSecretsAndInternalDetails() {
        String payload = """
                ERROR password=super-secret
                Authorization: Bearer abc.def.ghi
                clone https://git.internal.example.com/team/private-repo.git
                workspace /Users/alice/work/private-job/target/report.txt
                contact customer@example.com
                """;

        LogSanitizer.SanitizedPayload sanitized = sanitizer.sanitize(payload, LogSanitizer.Policy.enabled("", ""));

        assertFalse(sanitized.text().contains("super-secret"));
        assertFalse(sanitized.text().contains("abc.def.ghi"));
        assertFalse(sanitized.text().contains("git.internal.example.com"));
        assertFalse(sanitized.text().contains("/Users/alice"));
        assertFalse(sanitized.text().contains("customer@example.com"));
        assertTrue(sanitized.text().contains(LogSanitizer.REDACTION_TOKEN));
        assertEquals(5, sanitized.redactionCount());
    }

    @Test
    void sanitize_appliesAllowPolicyBeforeRedaction() {
        String payload = """
                INFO harmless line
                ERROR token=abc123
                FAILED customer id 42
                """;

        LogSanitizer.SanitizedPayload sanitized = sanitizer.sanitize(payload,
                LogSanitizer.Policy.enabled("ERROR|FAILED", ""));

        assertFalse(sanitized.text().contains("INFO harmless line"));
        assertTrue(sanitized.text().contains("ERROR token=" + LogSanitizer.REDACTION_TOKEN));
        assertTrue(sanitized.text().contains("FAILED customer id 42"));
        assertEquals(1, sanitized.droppedLineCount());
    }

    @Test
    void sanitize_appliesDenyPolicyAsRedaction() {
        LogSanitizer.SanitizedPayload sanitized = sanitizer.sanitize("customer-id=acme-123",
                LogSanitizer.Policy.enabled("", "acme-\\d+"));

        assertEquals("customer-id=" + LogSanitizer.REDACTION_TOKEN, sanitized.text());
        assertEquals(1, sanitized.redactionCount());
    }

    @Test
    void sanitize_customDenyPolicyRedactsWholeMatchWhenRegexHasGroups() {
        LogSanitizer.SanitizedPayload sanitized = sanitizer.sanitize("tenant=acme-123",
                LogSanitizer.Policy.enabled("", "(acme)-(\\d+)"));

        assertEquals("tenant=" + LogSanitizer.REDACTION_TOKEN, sanitized.text());
    }

    @Test
    void sanitize_canBeDisabled() {
        String payload = "password=super-secret";

        LogSanitizer.SanitizedPayload sanitized = sanitizer.sanitize(payload, LogSanitizer.Policy.disabled());

        assertEquals(payload, sanitized.text());
        assertEquals(0, sanitized.redactionCount());
    }

    @Test
    void validateRegex_rejectsInvalidRegex() {
        assertThrows(PatternSyntaxException.class, () -> LogSanitizer.validateRegex("["));
    }
}
