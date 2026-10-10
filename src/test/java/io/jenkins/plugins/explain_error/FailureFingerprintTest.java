package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class FailureFingerprintTest {

    @Test
    void normalizeReplacesNumbersAndHexIdsAndDropsBlankLines() {
        String log = """
                [2026-09-29T10:11:12.345Z]   Running   /tmp/jenkins8812345.sh

                durable-4f3a2b1c9d: exit code 137 after 12.5 s
                """;

        assertEquals("""
                [0-0-0T0:0:0.0Z] Running /tmp/jenkins0.sh
                durable-#: exit code 0 after 0.0 s
                """, FailureFingerprint.normalize(log));
    }

    @Test
    void normalizeKeepsWordsWithoutDigits() {
        assertEquals("deadbeefcafe facade\n", FailureFingerprint.normalize("deadbeefcafe facade"));
    }

    @Test
    void sameFailureOnAnotherRunHasTheSameFingerprint() {
        String first = """
                + mvn -q compile
                [ERROR] /var/lib/jenkins/workspace/app/src/App.java:[12,8] cannot find symbol
                [ERROR] Total time:  4.211 s, finished at 2026-09-29T10:11:12Z
                ERROR: script returned exit code 1
                """;
        String second = """
                + mvn -q compile
                [ERROR] /var/lib/jenkins/workspace/app/src/App.java:[12,8] cannot find symbol
                [ERROR] Total time:  3.907 s, finished at 2026-09-29T11:42:03Z
                ERROR: script returned exit code 1
                """;

        assertEquals(FailureFingerprint.of(first, "English", null, "OpenAI", "gpt-4o"),
                FailureFingerprint.of(second, "English", null, "OpenAI", "gpt-4o"));
    }

    @Test
    void differentFailureHasADifferentFingerprint() {
        assertNotEquals(FailureFingerprint.of("error: cannot find symbol StringUtils", "English"),
                FailureFingerprint.of("error: cannot find symbol ObjectUtils", "English"));
    }

    @Test
    void requestContextIsPartOfTheFingerprint() {
        String log = "error: cannot find symbol StringUtils";
        String english = FailureFingerprint.of(log, "English", null, "OpenAI", "gpt-4o");

        assertNotEquals(english, FailureFingerprint.of(log, "中文", null, "OpenAI", "gpt-4o"));
        assertNotEquals(english, FailureFingerprint.of(log, "English", "Mention the runbook", "OpenAI", "gpt-4o"));
        assertNotEquals(english, FailureFingerprint.of(log, "English", null, "OpenAI", "gpt-5"));
    }

    @Test
    void contextValuesDoNotRunTogether() {
        assertNotEquals(FailureFingerprint.of("log", "ab", "c"), FailureFingerprint.of("log", "a", "bc"));
    }

    @Test
    void nullLogHasAStableFingerprint() {
        assertEquals(FailureFingerprint.of(null, "English"), FailureFingerprint.of("", "English"));
    }
}
