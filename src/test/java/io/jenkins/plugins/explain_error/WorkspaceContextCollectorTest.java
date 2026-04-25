package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceContextCollectorTest {

    @TempDir
    private Path tempDir;

    @Test
    void collect_includesConfiguredFilesInDeterministicOrder() throws Exception {
        write("pom.xml", "<project>demo</project>");
        write("Jenkinsfile", "pipeline { agent any }");
        write("src/test/AppTest.java", "class AppTest {}");

        String context = collect("src/test/**/*.java,pom.xml,Jenkinsfile", 20_000);

        assertTrue(context.indexOf("### Jenkinsfile") < context.indexOf("### pom.xml"));
        assertTrue(context.indexOf("### pom.xml") < context.indexOf("### src/test/AppTest.java"));
        assertTrue(context.contains("<project>demo</project>"));
        assertTrue(context.contains("class AppTest {}"));
    }

    @Test
    void collect_rejectsTraversalAndAbsolutePatterns() throws Exception {
        write("pom.xml", "<project>demo</project>");

        String context = collect("../pom.xml,/tmp/secret,pom.xml", 20_000);

        assertTrue(context.contains("### pom.xml"));
        assertTrue(context.contains("<project>demo</project>"));
        assertFalse(context.contains("/tmp/secret"));
    }

    @Test
    void collect_skipsSecretAndBuildOutputPaths() throws Exception {
        write(".env", "TOKEN=secret");
        write("credentials.txt", "password");
        write("target/report.txt", "generated");
        write("src/main/app.properties", "safe=true");

        String context = collect(".env,credentials.txt,target/report.txt,src/main/*.properties", 20_000);

        assertTrue(context.contains("### src/main/app.properties"));
        assertTrue(context.contains("safe=true"));
        assertFalse(context.contains("TOKEN=secret"));
        assertFalse(context.contains("password"));
        assertFalse(context.contains("generated"));
    }

    @Test
    void collect_enforcesByteLimit() throws Exception {
        write("pom.xml", "x".repeat(1_000));

        String context = collect("pom.xml", 200);

        assertTrue(context.length() < 300);
        assertTrue(context.contains("...[truncated]"));
    }

    private String collect(String patterns, int maxBytes) throws Exception {
        return new WorkspaceContextCollector().collect(new FilePath(tempDir.toFile()), patterns, maxBytes, null);
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
