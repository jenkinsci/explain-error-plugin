package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
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
    static final String OPENAI_KEY = "sk-" + "proj-" + "Ab3dEf6hIj9kLm2nOp5qRs8tUv1w";
    static final String ANTHROPIC_KEY = "sk-" + "ant-api03-" + "Ab3dEf6hIj9kLm2nOp5qRs8tUv1w";

    private static final String KEY_BODY = "MIIEowIBAAKCAQEA7yn3bRHQ5FHMQ\nTW9yZSBiYXNlNjQgdGV4dCBoZXJl\nAB12";

    private static String redact(String text) {
        return LogRedactor.builtIn().redact(text);
    }

    private static String header(String line) {
        return "-----" + line + "-----";
    }

    @ParameterizedTest
    @ValueSource(strings = {AWS_KEY_ID, GITHUB_TOKEN, GITLAB_TOKEN, SLACK_TOKEN, GOOGLE_KEY, JWT, OPENAI_KEY,
        ANTHROPIC_KEY})
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
                "{\\\"password\\\":\\\"esc4ped-json\\\"}",
                "aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "SECRET_KEY_BASE=0123456789abcdef",
                "  password: \"hunter\"",
                "  password: hunter2",
                "spring.datasource.password=s3cret!",
                "//registry.npmjs.org/:_authToken=npm-secret-1",
                "DefaultEndpointsProtocol=https;AccountName=acme;AccountKey=a1b2c3d4==;EndpointSuffix=core.windows.net",
                "GET /callback?access_token=abc123xyz&state=ok",
                "mvn deploy -Dgpg.passphrase=pass-phrase-1 -B",
                "mvn deploy --password hunter2 -B",
                "mysql --password 'p@ss!w0rd' -h db",
                "npm publish --token=npmsecretvalue",
                "curl -u admin:S3cr3t https://example.com",
                "Internal KB: token=kb-secret-123",
                "PGPASSWORD=hunter2hunter2 psql -h db",
                "POSTGRES_PASSWORD: example",
                "password: correcthorsebatterystaple",
                "  spring.datasource.password: changeme",
                "DB_PASSWORD=ab&cdefgh",
                "mysql --password \"it's s3cret\" -h db",
                "curl -uadmin:S3cr3t https://example.com",
                "curl -sSf https://example.com/upload \\\n  -u admin:S3cr3t",
                "[INFO] password: changeme",
                "#8 0.312 password: hunter",
                "curl -X POST -H \"Content-Type: application/json; charset=utf-8\" -u admin:S3cr3tPass https://x",
                "curl -d 'a|b' -u admin:S3cr3t https://x");

        String masked = redact(log);

        assertEquals(String.join("\n",
                "DB_PASSWORD=" + MASK,
                "export API_KEY='" + MASK + "'",
                "{\"client_secret\": \"" + MASK + "\"}",
                "{\\\"password\\\":\\\"" + MASK + "\\\"}",
                "aws_secret_access_key = " + MASK,
                "SECRET_KEY_BASE=" + MASK,
                "  password: \"" + MASK + "\"",
                "  password: " + MASK,
                "spring.datasource.password=" + MASK,
                "//registry.npmjs.org/:_authToken=" + MASK,
                "DefaultEndpointsProtocol=https;AccountName=acme;AccountKey=" + MASK
                        + ";EndpointSuffix=core.windows.net",
                "GET /callback?access_token=" + MASK + "&state=ok",
                "mvn deploy -Dgpg.passphrase=" + MASK + " -B",
                "mvn deploy --password " + MASK + " -B",
                "mysql --password '" + MASK + "' -h db",
                "npm publish --token=" + MASK,
                "curl -u admin:" + MASK + " https://example.com",
                "Internal KB: token=" + MASK,
                "PGPASSWORD=" + MASK + " psql -h db",
                "POSTGRES_PASSWORD: " + MASK,
                "password: " + MASK,
                "  spring.datasource.password: " + MASK,
                "DB_PASSWORD=" + MASK,
                "mysql --password \"" + MASK + "\" -h db",
                "curl -uadmin:" + MASK + " https://example.com",
                "curl -sSf https://example.com/upload \\\n  -u admin:" + MASK,
                "[INFO] password: " + MASK,
                "#8 0.312 password: " + MASK,
                "curl -X POST -H \"Content-Type: application/json; charset=utf-8\" -u admin:" + MASK + " https://x",
                "curl -d 'a|b' -u admin:" + MASK + " https://x"), masked);
    }

    @Test
    void anXmlElementNamedLikeASecretIsMasked() {
        String masked = redact("<server><id>nexus</id><username>ci</username><password>pom-secret-1</password>"
                + "</server>\n<flyway.password>x</flyway.password>\n<password>${env.NEXUS_PASSWORD}</password>\n"
                + "<password type=\"plain\">pom-secret-2</password>");

        assertEquals("<server><id>nexus</id><username>ci</username><password>" + MASK + "</password>"
                + "</server>\n<flyway.password>" + MASK + "</flyway.password>\n"
                + "<password>${env.NEXUS_PASSWORD}</password>\n<password type=\"plain\">" + MASK + "</password>",
                masked);
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

    @ParameterizedTest
    @ValueSource(strings = {"OPENSSH PRIVATE KEY", "RSA PRIVATE KEY", "PRIVATE KEY", "ENCRYPTED PRIVATE KEY",
        "PGP PRIVATE KEY BLOCK"})
    void aPrivateKeyIsMaskedWhole(String kind) {
        String log = "before\n" + header("BEGIN " + kind) + "\nVersion: 1\n\n" + KEY_BODY + "\n"
                + header("END " + kind) + "\nafter";

        assertEquals("before\n" + MASK + "\nafter", redact(log));
    }

    @Test
    void aPrivateKeyInAJsonStringIsMaskedWhole() {
        String json = "\"private_key\": \"" + header("BEGIN PRIVATE KEY") + "\\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC"
                + "\\nBKcwggSjAgEAAoIBAQC7\\n" + header("END PRIVATE KEY") + "\\n\",";

        assertEquals("\"private_key\": \"" + MASK + "\\n\",", redact(json));
    }

    @Test
    void aPrivateKeyOnLinesWithAPrefixIsMaskedFromItsFirstLineToItsLast() {
        String log = "#8 0.312 " + header("BEGIN RSA PRIVATE KEY") + "\n#8 0.312 MIIEowIBAAKCAQEA7yn3bRHQ5FHMQ\n"
                + "2024-05-01T10:00:00Z TW9yZSBiYXNlNjQgdGV4dCBoZXJl\n#8 0.313 " + header("END RSA PRIVATE KEY")
                + "\n#8 0.314 DONE";

        assertEquals("#8 0.312 " + MASK + "\n#8 0.314 DONE", redact(log));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\n", "\\r\\n"})
    void theEndOfAPrivateKeyInAJsonStringIsMaskedWithItsBody(String lineBreak) {
        String json = "\"key\": \"MIIEvQIBADANBgkqhkiG9w0BAQEFAASC" + lineBreak + "BKcwggSjAgEAAoIBAQC7" + lineBreak
                + header("END PRIVATE KEY") + lineBreak + "\"";

        assertEquals("\"key\": \"" + MASK + lineBreak + "\"", redact(json));
    }

    @Test
    void aKeyHeaderOnItsOwnTakesNoLogLinesWithIt() {
        String key = header("BEGIN RSA PRIVATE KEY") + "\n" + KEY_BODY + "\n" + header("END RSA PRIVATE KEY");
        String scan = "found '" + header("BEGIN RSA PRIVATE KEY") + "' in deploy/id_rsa\nERROR: deploy failed\n" + key;
        String otherKind = header("BEGIN OPENSSH PRIVATE KEY") + "\nERROR: deploy failed\n"
                + header("END PGP PRIVATE KEY BLOCK");

        assertEquals("found '" + MASK + "' in deploy/id_rsa\nERROR: deploy failed\n" + MASK, redact(scan));
        assertEquals(MASK + "\nERROR: deploy failed\n" + MASK, redact(otherKind));
    }

    @Test
    void aPrivateKeyCutShortIsMaskedUpToWhereItWasCut() {
        String start = "key:\n" + header("BEGIN RSA PRIVATE KEY") + "\n" + KEY_BODY.substring(0, 58)
                + "\n0123456789abcdef0123456789abcdef refs/heads/main";
        String end = "ERROR: before the key\n" + KEY_BODY + "\n" + header("END RSA PRIVATE KEY") + "\nafter";

        assertEquals("key:\n" + MASK + "\n0123456789abcdef0123456789abcdef refs/heads/main", redact(start));
        assertEquals("ERROR: before the key\n" + MASK + "\nafter", redact(end));
    }

    @Test
    void aSecretInATokenUrlIsCountedOnce() {
        LogRedactor redactor = LogRedactor.builtIn();

        String masked = redactor.redact("git clone https://x-access-token:" + GITHUB_TOKEN + "@github.com/o/r");

        assertEquals("git clone https://x-access-token:" + MASK + "@github.com/o/r", masked);
        assertEquals(1, redactor.count());
    }

    @Test
    void theCountAddsUpOverEveryTextGiven() {
        LogRedactor redactor = LogRedactor.builtIn();

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
        "Password:\nERROR: build failed",
        "ERROR: Invalid token: expired",
        "token_type: bearer",
        "secretName: registry-credentials",
        "DB_PASSWORD_FILE=/run/secrets/db_password",
        "sha256: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        "[ERROR] Failed to execute goal on project app: Could not resolve dependencies",
        "[ERROR] Failed to execute goal on project token-service: Compilation failure",
        "[ERROR] /src/main/java/com/acme/TokenService.java:[142,17] cannot find symbol",
        "java.lang.NullPointerException at com.example.TokenParser.parse(TokenParser.java:42)",
        "\tat org.acme.token.DefaultTokenServices.createAccessToken(DefaultTokenServices.java:123)",
        "src/auth/token.ts:120:5 - error TS2322: Type 'string' is not assignable to type 'number'.",
        "/app/src/password_reset.py:104: error: Incompatible types in assignment",
        "Could not find artifact io.jsonwebtoken:jjwt-impl:jar:0.12.0 in central",
        "Downloading software.amazon.awssdk:secretsmanager:jar:2.20.100",
        "Collecting tokenizers==0.15.0",
        "ERROR: No matching distribution found for tiktoken==0.5.1",
        "image: ghcr.io/acme/vault-secrets-operator:0.4.0",
        "Downloaded from central: https://repo.maven.apache.org/maven2/org/x/1.0/x-1.0.jar",
        "ssh://git@github.com:22/org/repo.git",
        "Running on agent-token-builder in /home/jenkins/agent",
        "$ docker run -t -d -u 1000:1000 -w /home/jenkins/workspace/app maven:3 cat",
        "docker exec -u root:root build-1 ls",
        "sudo -u jenkins:jenkins ls",
        "curl -s https://example.com/install.sh | docker run -u 1000:1000 -i alpine sh",
        "curl -s -H 'Accept: text/plain' https://example.com/x; docker exec -u 0:0 build-1 ls",
        "Failed to read secret: permission denied",
        "password=null",
        "token=undefined",
        "\"max_tokens\": 4096",
        "model.generate(max_new_tokens=2048)",
        "\"prompt_tokens\": 1520, \"completion_tokens\": 3001, \"total_tokens\": 4521",
        "remaining tokens: 12000",
        "MAX_TOKENS=4096",
        "image: ghcr.io/external-secrets/external-secrets:v0.9.11"})
    void whatIsNotASecretIsLeftAsItIs(String line) {
        LogRedactor redactor = LogRedactor.builtIn();

        assertEquals(line, redactor.redact(line));
        assertEquals(0, redactor.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_PASSWORD", "clientSecret", "apiKey", "API_KEY", "AccountKey", "SharedAccessKey",
        "AWS_SECRET_ACCESS_KEY", "SECRET_KEY_BASE", "_authToken", "gpg.passphrase", "DBPassword", "ns:password",
        "PGPASSWORD", "KEYSTOREPASSWORD", "NPMTOKEN"})
    void keysNamedLikeASecret(String key) {
        assertTrue(LogRedactor.isSecretKey(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TokenService.java", "password_reset", "jsonwebtoken", "DB_PASSWORD_FILE",
        "token_type", "secretName", "secretsmanager", "tokenizers", "credentialsId", "PWD", "key", "monkey",
        "max_tokens", "MAX_TOKENS", "external-secrets", "tiktoken"})
    void keysThatAreNot(String key) {
        assertFalse(LogRedactor.isSecretKey(key), key);
    }

    @Test
    void aPatternOfTheAdministratorMasksItsSecretGroupOrElseTheWholeMatch() {
        LogRedactor redactor = new LogRedactor(false, List.of(
                Pattern.compile("(?i)license=(?<secret>\\S+)"),
                Pattern.compile("\\b[a-z0-9]+\\.corp\\.example\\.com\\b"),
                // Looks like it has a group named secret, but has none
                Pattern.compile("\\(?<secret>x")));

        String masked = redactor.redact("license=ABCD-1234 from build01.corp.example.com and <secret>x");

        assertEquals("license=" + MASK + " from " + MASK + " and " + MASK, masked);
        assertEquals(3, redactor.count());
    }

    @Test
    void patternLinesLeaveOutBlankLinesAndComments() {
        assertEquals(List.of("one", "two"), LogRedactor.patternLines("# internal hosts\n one \n\n\r\ntwo\n"));
        assertTrue(LogRedactor.patternLines(null).isEmpty());
    }

    @Test
    void withoutRulesNothingChanges() {
        LogRedactor redactor = new LogRedactor(false, List.of());

        assertEquals("PASSWORD=hunter2hunter2", redactor.redact("PASSWORD=hunter2hunter2"));
        assertFalse(redactor.isActive());
        assertNull(redactor.redact(null));
    }

    /**
     * Whoever writes the log chooses what the rules run on. A thousand lines of 2,000 characters, as many as the
     * Explain Error button reads, built to make a rule go back over what it read, take a few seconds at most; a
     * rule whose time grows with the square of the length takes many minutes on them.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "-----BEGIN RSA PRIVATE KEY-----",
        "-----END RSA PRIVATE KEY-----",
        "MIIEowIBAAKCAQEA7yn3bRHQ5FHMQ\n",
        "token.",
        "a-",
        "password",
        "sk-proj-",
        "Bearer ",
        "Authorization: Bearer ",
        "https://a:",
        "<password>",
        "--password ",
        "-u a:",
        "KB:",
        "x=token=",
        "curl ",
        "curl -u a:",
        "curl \"a;\" ",
        "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA7yn3bRHQ5FHMQ\n"})
    void rulesTakeTimeInProportionToTheLog(String piece) {
        String line = piece.repeat(2000 / piece.length());
        String log = String.join("\n", Collections.nCopies(1000, line));

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> redact(log),
                "the rules must not go back over the log: " + piece);
    }
}
