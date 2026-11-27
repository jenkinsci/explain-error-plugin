package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdaptiveWorkspaceContextTest {

    @TempDir
    private Path tempDir;

    @Test
    void anchorsHitReadTheNamedFilesAndDoNotCallTheSelector() throws Exception {
        write("src/main/java/com/example/App.java", "class App { void fail() {} }");
        FakeAIProvider provider = new FakeAIProvider();
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        String context = AdaptiveWorkspaceContext.collect(workspace(), "\tat com.example.App.fail(App.java:7)\n",
                20_000, provider, null, new StreamTaskListener(log, StandardCharsets.UTF_8));

        assertTrue(context.contains("### src/main/java/com/example/App.java"), context);
        assertTrue(context.contains("class App { void fail() {} }"), context);
        assertEquals(0, provider.getSelectionCallCount());
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("workspace file(s) named by the failure log"),
                log.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anchorsMissCallTheSelectorExactlyOnce() throws Exception {
        write("config/app.properties", "retries=3");
        FakeAIProvider provider = new FakeAIProvider();
        provider.setSelectedWorkspaceFiles(List.of("config/app.properties"));
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        String context = AdaptiveWorkspaceContext.collect(workspace(), "ERROR: boom", 20_000, provider, null,
                new StreamTaskListener(log, StandardCharsets.UTF_8));

        assertTrue(context.contains("### config/app.properties"), context);
        assertTrue(context.contains("retries=3"), context);
        assertEquals(1, provider.getSelectionCallCount());
        assertTrue(provider.getLastSelectionErrorLogs().contains("ERROR: boom"));
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("selected by the AI provider"),
                log.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unsafeAndMissingSuggestionsAreIgnored() throws Exception {
        write("src/main/App.java", "class App {}");
        write(".env", "TOKEN=secret");
        FakeAIProvider provider = new FakeAIProvider();
        provider.setSelectedWorkspaceFiles(List.of("../outside.java", "/etc/passwd", ".env",
                "target/generated.java", "missing.java", "src/main/App.java"));

        String context = AdaptiveWorkspaceContext.collect(workspace(), "ERROR: boom", 20_000, provider, null, null);

        assertTrue(context.contains("### src/main/App.java"), context);
        assertFalse(context.contains("TOKEN=secret"), context);
        assertFalse(context.contains("missing.java"), context);
        assertEquals(1, provider.getSelectionCallCount());
    }

    @Test
    void selectorFailureReturnsEmptyAndDoesNotThrow() throws Exception {
        write("src/main/App.java", "class App {}");
        FakeAIProvider provider = new FakeAIProvider();
        provider.setThrowOnSelection(true);
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        String context = AdaptiveWorkspaceContext.collect(workspace(), "ERROR: boom", 20_000, provider, null,
                new StreamTaskListener(log, StandardCharsets.UTF_8));

        assertEquals("", context);
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("selection failed"),
                log.toString(StandardCharsets.UTF_8));
    }

    @Test
    void selectorTimeoutReturnsEmpty() throws Exception {
        write("src/main/App.java", "class App {}");
        FakeAIProvider provider = new FakeAIProvider();
        provider.setSelectedWorkspaceFiles(List.of("src/main/App.java"));
        provider.setSelectionDelayMillis(5_000);
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        long start = System.nanoTime();

        String context = AdaptiveWorkspaceContext.collect(workspace(), "ERROR: boom", 20_000, provider, null,
                new StreamTaskListener(log, StandardCharsets.UTF_8), 1);

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals("", context);
        assertTrue(elapsedMillis < 4_000, "selection should time out quickly, took " + elapsedMillis + " ms");
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("timed out"),
                log.toString(StandardCharsets.UTF_8));
    }

    @Test
    void selectionInputIsBoundedAndDoesNotSplitUtf8() throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        String logs = "é".repeat(7_000) + "\nTAIL: ERROR";

        String context = AdaptiveWorkspaceContext.collect(workspace(), logs, 20_000, provider, null, null);

        assertEquals("", context);
        String sent = provider.getLastSelectionErrorLogs();
        assertTrue(sent.getBytes(StandardCharsets.UTF_8).length
                <= AdaptiveWorkspaceContext.MAX_SELECTION_LOG_BYTES, "input must be tailed");
        assertFalse(sent.contains("�"), "a multi-byte character must never be split");
        assertTrue(sent.contains("TAIL: ERROR"), sent);
    }

    @Test
    void collectorBudgetIsApplied() throws Exception {
        write("src/main/java/p/Big.java", "x".repeat(1_000));

        String context = AdaptiveWorkspaceContext.collect(workspace(), "\tat p.Big.m(Big.java:1)\n",
                400, new FakeAIProvider(), null, null);

        assertTrue(context.length() < 500, context);
        assertTrue(context.contains("...[truncated]"), context);
    }

    @Test
    void noWorkspaceOrLogsReturnsEmpty() throws Exception {
        FakeAIProvider provider = new FakeAIProvider();

        assertEquals("", AdaptiveWorkspaceContext.collect(null, "ERROR: boom", 20_000, provider, null, null));
        assertEquals("", AdaptiveWorkspaceContext.collect(workspace(), "", 20_000, provider, null, null));
        assertEquals("", AdaptiveWorkspaceContext.collect(workspace(), "ERROR: boom", 0, provider, null, null));
        assertNull(provider.getLastSelectionErrorLogs());
    }

    private FilePath workspace() {
        return new FilePath(tempDir.toFile());
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
