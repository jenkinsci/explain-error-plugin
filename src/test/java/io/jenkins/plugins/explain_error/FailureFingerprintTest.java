package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class FailureFingerprintTest {

    private static String fingerprint(String log) {
        return FailureFingerprint.of(log, "English", null, "OpenAI", "gpt-4o");
    }

    @Test
    void whatChangesOnEveryRunIsLeftOut() {
        String log = """
                [2026-09-29T10:11:12.345Z] Running /tmp/jenkins7310809385784384571.sh

                Checking out Revision 5e69e8a10ff5a2b30026b4cf9a4daa6c5dbde701 (refs/remotes/origin/main)
                Started   at 09:15:02 on   2026-09-29, build #42
                \tdurable-4f3a2b1c9d: killed after 12.5 s
                Downloaded platform-bom-2.7.1.pom (215 kB at 675 kB/s)
                npm error log: /home/ci/.npm/_logs/2026-10-10T12_38_32_871Z-debug-0.log
                [INFO] Total time:  1 min 3 s, pid 482913
                """;

        assertEquals("""
                [<time>] Running /tmp/jenkins<id>.sh
                Checking out Revision <id> (refs/remotes/origin/main)
                Started at <time> on <time>, build #<n>
                durable-<id>: killed after <duration>
                Downloaded platform-bom-2.7.1.pom (<size> at <size>)
                npm error log: /home/ci/.npm/_logs/<time>-debug-0.log
                [INFO] Total time: <duration> <duration>, pid <id>
                """, FailureFingerprint.normalize(log));
    }

    @Test
    void numbersThatSayWhatFailedAreKept() {
        String log = """
                ERROR: No matching distribution found for requests==2.99.0
                at dev.acme.payments.CurrencyConverter.convert(CurrencyConverter.java:21)
                expected: <5> but was: <4>
                [ERROR] Tests run: 9, Failures: 0, Errors: 1, Skipped: 0
                connection to server at "warehouse.acme.dev" (10.4.2.17), port 5432 failed
                script returned exit code 137
                """;

        assertEquals(log, FailureFingerprint.normalize(log));
    }

    @Test
    void wordsThatOnlyLookLikeIdsAreKept() {
        // No digit in them, so they are words, not hexadecimal ids
        assertEquals("deadbeefcafe facade\n", FailureFingerprint.normalize("deadbeefcafe facade"));
    }

    @Test
    void theSameFailureOnAnotherRunHasTheSameFingerprint() {
        String first = """
                [app] $ /bin/sh -xe /tmp/jenkins2353765091683982412.sh
                + mvn -q compile
                [ERROR] /var/lib/jenkins/workspace/app/src/App.java:[12,8] cannot find symbol
                [ERROR] Total time:  4.211 s, finished at 2026-09-29T10:11:12Z
                ERROR: script returned exit code 1
                """;
        String second = """
                [app] $ /bin/sh -xe /tmp/jenkins1006501353895019591.sh
                + mvn -q compile
                [ERROR] /var/lib/jenkins/workspace/app/src/App.java:[12,8] cannot find symbol
                [ERROR] Total time:  3.907 s, finished at 2026-09-29T11:42:03Z
                ERROR: script returned exit code 1
                """;

        assertEquals(fingerprint(first), fingerprint(second));
    }

    @Test
    void aFailureThatDiffersOnlyInAVersionOrALineIsAnotherFailure() {
        assertNotEquals(fingerprint("No matching distribution found for requests==2.99.0"),
                fingerprint("No matching distribution found for requests==2.98.0"));
        assertNotEquals(fingerprint("at CurrencyConverter.convert(CurrencyConverter.java:21)"),
                fingerprint("at CurrencyConverter.convert(CurrencyConverter.java:57)"));
        assertNotEquals(fingerprint("expected: <5> but was: <4>"), fingerprint("expected: <5> but was: <3>"));
        assertNotEquals(fingerprint("script returned exit code 1"), fingerprint("script returned exit code 137"));
    }

    @Test
    void whereTheFailingStepSitsInThePipelineIsLeftOut() {
        String first = """
                ### Node ID: 12 ###
                Node Name: Shell Script
                Start Time: 2026-10-12T15:20:33.123+0300
                --- LOG CONTENT ---
                + python -m etl.run
                psycopg.OperationalError: port 5432 failed
                ### END OF LOG 12 ###
                """;
        String withAStepAddedBefore = first.replace(" 12 ###", " 13 ###").replace("15:20:33.123", "16:01:07.456");

        assertEquals(fingerprint(first), fingerprint(withAStepAddedBefore));
        assertEquals("""
                ### Node ID: <node> ###
                Node Name: Shell Script
                Start Time: <time>
                --- LOG CONTENT ---
                + python -m etl.run
                psycopg.OperationalError: port 5432 failed
                ### END OF LOG <node> ###
                """, FailureFingerprint.normalize(first));
        // Only in those two lines: a log line that happens to carry a small number keeps it
        assertNotEquals(fingerprint("retry 12 of 20"), fingerprint("retry 13 of 20"));
    }

    @Test
    void aDifferentFailureHasADifferentFingerprint() {
        assertNotEquals(fingerprint("error: cannot find symbol StringUtils"),
                fingerprint("error: cannot find symbol ObjectUtils"));
    }

    @Test
    void theRequestContextIsPartOfTheFingerprint() {
        String log = "error: cannot find symbol StringUtils";
        String english = FailureFingerprint.of(log, "English", null, "OpenAI", "gpt-4o");

        assertNotEquals(english, FailureFingerprint.of(log, "中文", null, "OpenAI", "gpt-4o"));
        assertNotEquals(english, FailureFingerprint.of(log, "English", "Mention the runbook", "OpenAI", "gpt-4o"));
        assertNotEquals(english, FailureFingerprint.of(log, "English", null, "OpenAI", "gpt-5"));
        assertNotEquals(english, FailureFingerprint.of(log, "English", null, "DeepSeek", "gpt-4o"));
    }

    @Test
    void contextValuesDoNotRunTogether() {
        assertNotEquals(FailureFingerprint.of("log", "ab", "c"), FailureFingerprint.of("log", "a", "bc"));
    }

    @Test
    void aMissingLogHasAStableFingerprint() {
        assertEquals(FailureFingerprint.of(null, "English"), FailureFingerprint.of("", "English"));
        assertEquals("", FailureFingerprint.normalize(null));
    }
}
