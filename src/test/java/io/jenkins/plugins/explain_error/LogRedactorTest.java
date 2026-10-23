package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the built-in rules mask, and what they leave alone. The secrets are put together at run time, so that
 * no secret scanner takes this file for a leak.
 */
class LogRedactorTest {

    private static final String MASK = LogRedactor.MASK;

    static final String AWS_KEY_ID = "AKIA" + "IOSFODNN7EXAMPLE";
    static final String GITHUB_TOKEN = "ghp" + "_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
    static final String GITLAB_TOKEN = "glpat" + "-" + "x1y2z3A4B5C6D7E8F9G0";
    static final String SLACK_TOKEN = "xox" + "b-1234567890-abcdefghij";
    static final String GOOGLE_KEY = "AIza" + "SyA1234567890abcdefghijklmnopqrstuv";
    static final String JWT = "eyJ" + "hbGciOiJIUzI1NiJ9.eyJ" + "zdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N";

    private static String redact(String text) {
        return new LogRedactor(LogRedactor.BUILT_IN).redact(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {AWS_KEY_ID, GITHUB_TOKEN, GITLAB_TOKEN, SLACK_TOKEN, GOOGLE_KEY, JWT})
    void tokensOfAKnownShapeAreMasked(String token) {
        String masked = redact("+ echo " + token + " done");

        assertEquals("+ echo " + MASK + " done", masked);
    }

    @Test
    void aValueGivenToAKeyNamedLikeASecretKeepsItsKey() {
        String log = String.join("\n",
                "DB_PASSWORD=hunter2hunter2",
                "export API_KEY='s3cr3t-value'",
                "{\"client_secret\": \"abc123def456\"}",
                "aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "mvn deploy --password hunter2 -B",
                "npm publish --token=npmsecretvalue");

        String masked = redact(log);

        assertEquals(String.join("\n",
                "DB_PASSWORD=" + MASK,
                "export API_KEY='" + MASK + "'",
                "{\"client_secret\": \"" + MASK + "\"}",
                "aws_secret_access_key = " + MASK,
                "mvn deploy --password " + MASK + " -B",
                "npm publish --token=" + MASK), masked);
    }

    @Test
    void theCredentialsOfAUrlOrAnAuthorizationHeaderAreMasked() {
        String masked = redact(String.join("\n",
                "Cloning https://deploy:Sup3rS3cret@git.example.com/repo.git",
                "curl -H 'Authorization: Bearer abc.def.1234567890'",
                "Authorization: Basic dXNlcjpwYXNzd29yZA1="));

        assertEquals(String.join("\n",
                "Cloning https://deploy:" + MASK + "@git.example.com/repo.git",
                "curl -H 'Authorization: Bearer " + MASK + "'",
                "Authorization: Basic " + MASK), masked);
    }

    @Test
    void aPrivateKeyIsMaskedWhole() {
        String log = "before\n-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\nAAAABG5vbmUAAAAEbm9u\n"
                + "-----END OPENSSH PRIVATE KEY-----\nafter";

        assertEquals("before\n" + MASK + "\nafter", redact(log));
    }

    @Test
    void aSecretInATokenUrlIsCountedOnce() {
        LogRedactor redactor = new LogRedactor(LogRedactor.BUILT_IN);

        String masked = redactor.redact("git clone https://x-access-token:" + GITHUB_TOKEN + "@github.com/o/r");

        assertEquals("git clone https://x-access-token:" + MASK + "@github.com/o/r", masked);
        assertEquals(1, redactor.count());
    }

    @Test
    void theCountAddsUpOverEveryTextGiven() {
        LogRedactor redactor = new LogRedactor(LogRedactor.BUILT_IN);

        redactor.redact("PASSWORD=one-secret and " + AWS_KEY_ID);
        redactor.redact("token: " + GITLAB_TOKEN);

        assertEquals(3, redactor.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "PWD=/home/jenkins/workspace/app",
        "withCredentials: credentialsId 'deploy-key' was not found",
        "Bearer authentication failed",
        "password=${DB_PASSWORD}",
        "Password: ****",
        "PASSWORD=[REDACTED]",
        "[ERROR] Failed to execute goal on project app: Could not resolve dependencies",
        "java.lang.NullPointerException at com.example.TokenParser.parse(TokenParser.java:42)",
        "Downloaded from central: https://repo.maven.apache.org/maven2/org/x/1.0/x-1.0.jar",
        "ssh://git@github.com:22/org/repo.git"})
    void whatIsNotASecretIsLeftAsItIs(String line) {
        LogRedactor redactor = new LogRedactor(LogRedactor.BUILT_IN);

        assertEquals(line, redactor.redact(line));
        assertEquals(0, redactor.count());
    }

    @Test
    void aPatternOfTheAdministratorMasksItsSecretGroupOrElseTheWholeMatch() {
        LogRedactor redactor = new LogRedactor(List.of(
                Pattern.compile("(?i)license=(?<secret>\\S+)"),
                Pattern.compile("\\b[a-z0-9]+\\.corp\\.example\\.com\\b")));

        String masked = redactor.redact("license=ABCD-1234 from build01.corp.example.com");

        assertEquals("license=" + MASK + " from " + MASK, masked);
        assertEquals(2, redactor.count());
    }

    @Test
    void patternLinesLeaveOutBlankLinesAndComments() {
        assertEquals(List.of("one", "two"), LogRedactor.patternLines("# internal hosts\n one \n\n\r\ntwo\n"));
        assertTrue(LogRedactor.patternLines(null).isEmpty());
    }

    @Test
    void withoutRulesNothingChanges() {
        LogRedactor redactor = new LogRedactor(List.of());

        assertEquals("PASSWORD=hunter2hunter2", redactor.redact("PASSWORD=hunter2hunter2"));
        assertFalse(redactor.isActive());
        assertNull(redactor.redact(null));
    }

    @Test
    void aLongLineIsMaskedQuickly() {
        String line = "a.".repeat(1000) + "x".repeat(2000);
        long start = System.nanoTime();

        redact(line.repeat(100));

        assertTrue(System.nanoTime() - start < 5_000_000_000L, "the rules do not backtrack without end");
    }
}
