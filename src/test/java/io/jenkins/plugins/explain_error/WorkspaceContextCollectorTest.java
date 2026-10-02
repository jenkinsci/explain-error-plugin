package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.FilePath;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceContextCollectorTest {

    private static final String INTRO = "WORKSPACE CONTEXT\n"
            + "The following files were selected from the Jenkins workspace. "
            + "Use them only as supporting context for the failure analysis.\n\n";
    private static final int INTRO_BYTES = INTRO.getBytes(StandardCharsets.UTF_8).length;
    /** Bytes of the "### pom.xml" header and the closing fence around a file's content. */
    private static final int POM_OVERHEAD = "### pom.xml\n```text\n".length() + "\n```\n\n".length();
    private static final int TRUNCATION_MARKER = "\n...[truncated]".length();

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

    @Test
    void collect_returnsNothingWithoutWorkspaceBudgetPatternsOrMatches() throws Exception {
        write("pom.xml", "<project/>");
        WorkspaceContextCollector collector = new WorkspaceContextCollector();

        assertEquals("", collector.collect(null, "pom.xml", 20_000, null));
        assertEquals("", collect("pom.xml", 0));
        assertEquals("", collect(" , ,", 20_000));
        assertEquals("", collect("missing.txt,*.gradle", 20_000));
    }

    @Test
    void collect_usesDefaultPatternsWhenNoneAreConfigured() throws Exception {
        write("pom.xml", "<project>defaults</project>");
        write("ci.yaml", "steps: []");
        write("notes.txt", "not collected");

        String context = collect(" ", 20_000);

        assertTrue(context.contains("### pom.xml"), context);
        assertTrue(context.contains("### ci.yaml"), context);
        assertFalse(context.contains("notes.txt"), context);
    }

    @Test
    void collect_skipsDirectoriesUnsafeAndExcludedPaths() throws Exception {
        write("pom.xml", "<project>safe</project>");
        write("src/Main.java", "class Main {}");
        for (String excluded : new String[] {"secrets.yml", "build/out.yml", "dist/app.yml", "node_modules/a.yml",
                ".git/config.yml", ".gradle/cache.yml", "config/.env.yml", "config/credentials.yml"}) {
            write(excluded, "excluded");
        }
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        String context = new WorkspaceContextCollector().collect(new FilePath(tempDir.toFile()),
                "src,./pom.xml,\\etc\\passwd,C:\\Windows\\win.ini,docs/../../secret.txt,**/*.yml,?om.xml,"
                        + "[p]om.xml,{pom,build}.xml",
                20_000, new StreamTaskListener(log, StandardCharsets.UTF_8));

        // "./pom.xml" and the glob patterns resolve to the same file, which is included once.
        assertEquals(context.indexOf("### pom.xml"), context.lastIndexOf("### pom.xml"), context);
        assertTrue(context.contains("<project>safe</project>"), context);
        assertFalse(context.contains("class Main {}"), "directories are never read");
        assertFalse(context.contains("excluded"), context);
        String messages = log.toString(StandardCharsets.UTF_8);
        assertTrue(messages.contains(
                "Skipping workspace context pattern '\\etc\\passwd': absolute paths are not allowed"), messages);
        assertTrue(messages.contains("'C:\\Windows\\win.ini': absolute paths are not allowed"), messages);
        assertTrue(messages.contains("'docs/../../secret.txt': path traversal is not allowed"), messages);
        assertTrue(messages.contains("Skipping workspace context file 'secrets.yml': path is excluded"), messages);
    }

    @Test
    void collect_stopsWhenTheBudgetIsExhausted() throws Exception {
        write("pom.xml", "<project/>");

        // No room after the introduction.
        assertEquals(INTRO.stripTrailing(), collect("pom.xml", INTRO_BYTES));
        // Room for less than the file header and fence.
        assertEquals(INTRO.stripTrailing(), collect("pom.xml", INTRO_BYTES + POM_OVERHEAD - 1));
        // Room for the header but not for any content plus the truncation marker.
        assertEquals(INTRO.stripTrailing(), collect("pom.xml", INTRO_BYTES + POM_OVERHEAD + TRUNCATION_MARKER));
    }

    @Test
    void collect_truncatesMultiByteContentOnACharacterBoundary() throws Exception {
        // FilePath#readToString decodes with the platform charset, so write the file the same way.
        Files.writeString(tempDir.resolve("pom.xml"), "\u00e9".repeat(100), Charset.defaultCharset());

        String context = collect("pom.xml", INTRO_BYTES + POM_OVERHEAD + TRUNCATION_MARKER + 11);

        assertTrue(context.contains("```text\n" + "\u00e9".repeat(5) + "\n...[truncated]"), context);
        assertFalse(context.contains("\ufffd"), "a multi-byte character must never be split");
    }

    @Test
    void collect_skipsFilesThatCannotBeRead() throws Exception {
        write("pom.xml", "<project>secret</project>");
        write("Jenkinsfile", "pipeline {}");
        Path unreadable = tempDir.resolve("pom.xml");
        assumeTrue(unreadable.toFile().setReadable(false) && !Files.isReadable(unreadable),
                "file permissions cannot be restricted on this platform or user");
        try {
            ByteArrayOutputStream log = new ByteArrayOutputStream();

            String context = new WorkspaceContextCollector().collect(new FilePath(tempDir.toFile()),
                    "pom.xml,Jenkinsfile", 20_000, new StreamTaskListener(log, StandardCharsets.UTF_8));

            assertTrue(context.contains("pipeline {}"), context);
            assertFalse(context.contains("<project>secret</project>"), context);
            assertTrue(log.toString(StandardCharsets.UTF_8).contains("Skipping workspace context file"));
        } finally {
            unreadable.toFile().setReadable(true);
        }
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
