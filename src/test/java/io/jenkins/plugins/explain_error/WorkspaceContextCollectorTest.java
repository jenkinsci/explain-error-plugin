package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.explain_error.WorkspacePathAnchorExtractor.Anchor;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void collectAnchors_includesTheNamedFilesInLogOrder() throws Exception {
        write("zeta.java", "class Zeta {}");
        write("alpha.java", "class Alpha {}");

        String context = collectAnchors(List.of(anchor("zeta.java"), anchor("alpha.java")), 20_000, null);

        assertTrue(context.startsWith("FILES NAMED BY THE FAILURE LOG\n"), context);
        assertTrue(context.indexOf("### zeta.java") < context.indexOf("### alpha.java"), context);
        assertTrue(context.contains("class Zeta {}"), context);
        assertTrue(context.contains("class Alpha {}"), context);
    }

    @Test
    void collectAnchors_showsTheLinesAroundTheNamedLine() throws Exception {
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            source.append("line ").append(i).append('\n');
        }
        write("src/App.java", source.toString());

        String context = collectAnchors(List.of(new Anchor("src/App.java", 300)), 20_000, null);

        assertTrue(context.contains("### src/App.java (lines 260-320, line 300 is marked with >)"), context);
        assertTrue(context.contains("300 > line 300"), context);
        assertTrue(context.contains("260 | line 260"), context);
        assertTrue(context.contains("320 | line 320"), context);
        assertFalse(context.contains("line 259\n"), context);
        assertFalse(context.contains("line 321"), context);
        assertFalse(context.contains("line 1\n"), context);
    }

    @Test
    void collectAnchors_readsTheBeginningWhenTheLineIsUnknownOrPastTheEnd() throws Exception {
        write("a.py", "first\nsecond\n");
        write("b.py", "only\n");

        String context = collectAnchors(List.of(anchor("a.py"), new Anchor("b.py", 99)), 20_000, null);

        assertTrue(context.contains("### a.py\n```text\nfirst\nsecond"), context);
        assertTrue(context.contains("### b.py\n```text\nonly"), context);
    }

    @Test
    void collectAnchors_rejectsUnsafeExcludedMissingAndGlobPaths() throws Exception {
        write("src/ok.java", "class Ok {}");
        write(".env", "TOKEN=secret");
        write("target/out.java", "generated");
        Files.createDirectories(tempDir.resolve("dir.java"));

        String context = collectAnchors(List.of(anchor("../outside.java"), anchor("/etc/passwd"), anchor(".env"),
                anchor("target/out.java"), anchor("missing.java"), anchor("src/*.java"), anchor("dir.java"),
                anchor("src/ok.java")), 20_000, null);

        assertTrue(context.contains("### src/ok.java"), context);
        assertTrue(context.contains("class Ok {}"), context);
        assertFalse(context.contains("TOKEN=secret"), context);
        assertFalse(context.contains("generated"), context);
        assertFalse(context.contains("missing.java"), context);
        assertFalse(context.contains("dir.java"), context);
        assertFalse(context.contains("*.java"), context);
    }

    @Test
    void collectAnchors_honoursTheBudgetAndTruncation() throws Exception {
        write("big.java", "x".repeat(1_000));

        String context = collectAnchors(List.of(anchor("big.java")), 400, null);

        assertTrue(context.getBytes(StandardCharsets.UTF_8).length <= 400, context);
        assertTrue(context.contains("...[truncated]"), context);
    }

    @Test
    void collectAnchors_returnsEmptyWhenNothingIsFound() throws Exception {
        assertEquals("", collectAnchors(List.of(), 20_000, null));
        assertEquals("", collectAnchors(List.of(anchor("missing.java")), 20_000, null));
        assertEquals("", collectAnchors(List.of(anchor("missing.java")), 0, null));
    }

    @Test
    void selectExisting_keepsSafeExistingFilesInOrderAndCaps() throws Exception {
        write("a.java", "class A {}");
        write("b.java", "class B {}");
        write("c.java", "class C {}");

        List<Anchor> selected = new WorkspaceContextCollector().selectExisting(new FilePath(tempDir.toFile()),
                List.of(new Anchor("c.java", 3), anchor("missing.java"), anchor("../outside.java"), anchor(".env"),
                        anchor("a.java"), anchor("c.java"), anchor("b.java")),
                2, null);

        assertEquals(List.of(new Anchor("c.java", 3), anchor("a.java")), selected);
    }

    @Test
    void selectExisting_rejectsSymlinks() throws Exception {
        write("real.java", "class Real {}");
        createSymlink(tempDir.resolve("link.java"), tempDir.resolve("real.java"));
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        List<Anchor> selected = new WorkspaceContextCollector().selectExisting(new FilePath(tempDir.toFile()),
                List.of(anchor("link.java"), anchor("real.java")), 5,
                new StreamTaskListener(log, StandardCharsets.UTF_8));

        assertEquals(List.of(anchor("real.java")), selected);
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("'link.java': symbolic links are not allowed"),
                log.toString(StandardCharsets.UTF_8));
    }

    @Test
    void collectAnchors_rejectsPathsThroughASymlinkedDirectory() throws Exception {
        write("real/secret.java", "class Secret {}");
        createSymlink(tempDir.resolve("linkdir"), tempDir.resolve("real"));

        String context = collectAnchors(List.of(anchor("linkdir/secret.java"), anchor("real/secret.java")),
                20_000, null);

        assertTrue(context.contains("### real/secret.java"), context);
        assertFalse(context.contains("### linkdir/secret.java"), context);
    }

    @Test
    void pathError_reportsTheFirstSafetyProblem() {
        WorkspaceContextCollector collector = new WorkspaceContextCollector();

        assertNull(collector.pathError("src/main/App.java"));
        assertTrue(collector.pathError("../outside.java").contains("traversal"));
        assertTrue(collector.pathError("/etc/passwd").contains("absolute"));
        assertEquals("path is excluded", collector.pathError(".env"));
    }

    private String collectAnchors(List<Anchor> anchors, int maxBytes, TaskListener listener) throws Exception {
        return new WorkspaceContextCollector().collectAnchors(new FilePath(tempDir.toFile()), anchors, maxBytes,
                listener);
    }

    private static Anchor anchor(String path) {
        return new Anchor(path, 0);
    }

    private String collect(String patterns, int maxBytes) throws Exception {
        return new WorkspaceContextCollector().collect(new FilePath(tempDir.toFile()), patterns, maxBytes, null);
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void createSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "symbolic links are not supported on this platform");
        }
    }
}
