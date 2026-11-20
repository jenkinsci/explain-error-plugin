package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.FilePath;
import hudson.model.Item;
import hudson.model.TaskListener;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.WorkspaceFileSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Local anchors first; one bounded AI selection call only when the anchors find no file. */
final class AdaptiveWorkspaceContext {

    static final int MAX_ANCHOR_FILES = 8;
    static final int MAX_SELECTED_FILES = 5;
    static final int MAX_CANDIDATES_TO_CHECK = 20;
    static final int MAX_SELECTION_LOG_BYTES = 12_000;
    static final int SELECTION_TIMEOUT_SECONDS = 30;

    private static final Logger LOGGER = Logger.getLogger(AdaptiveWorkspaceContext.class.getName());

    private static final ExecutorService SELECTOR_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jenkins-explain-error-workspace-selector");
        thread.setDaemon(true);
        return thread;
    });

    private AdaptiveWorkspaceContext() {
    }

    /**
     * Returns unredacted workspace context in the collector format, or an empty string when nothing
     * useful was found. Never throws: a selection or workspace failure falls back to an empty result.
     */
    static String collect(FilePath workspace, String redactedErrorLogs, int maxBytes,
                          BaseAIProvider provider, @CheckForNull Item item,
                          @CheckForNull TaskListener listener) {
        return collect(workspace, redactedErrorLogs, maxBytes, provider, item, listener,
                SELECTION_TIMEOUT_SECONDS);
    }

    static String collect(FilePath workspace, String redactedErrorLogs, int maxBytes,
                          BaseAIProvider provider, @CheckForNull Item item,
                          @CheckForNull TaskListener listener, int selectionTimeoutSeconds) {
        if (workspace == null || redactedErrorLogs == null || redactedErrorLogs.isBlank() || maxBytes <= 0) {
            return "";
        }

        WorkspaceContextCollector collector = new WorkspaceContextCollector();
        try {
            List<String> anchors = WorkspacePathAnchorExtractor.extract(redactedErrorLogs, workspace.getRemote());
            List<String> anchorFiles = collector.selectExisting(workspace, anchors, MAX_ANCHOR_FILES);
            LOGGER.fine("Adaptive workspace context: " + anchors.size() + " anchor(s), "
                    + anchorFiles.size() + " file(s)");
            if (!anchorFiles.isEmpty()) {
                String context = collector.collectFiles(workspace, anchorFiles, maxBytes, listener);
                if (!context.isBlank()) {
                    log(listener, "Using " + anchorFiles.size()
                            + " workspace file(s) named by the failure log.");
                    return context;
                }
            }

            log(listener, "No workspace files were named by the failure log; "
                    + "asking the AI provider to select some.");
            List<String> suggestions = selectWithTimeout(provider, item,
                    tailUtf8(redactedErrorLogs, MAX_SELECTION_LOG_BYTES), listener, selectionTimeoutSeconds);
            if (suggestions == null) {
                return "";
            }

            List<String> selected = collector.selectExisting(workspace,
                    suggestions.stream().limit(MAX_CANDIDATES_TO_CHECK).toList(), MAX_SELECTED_FILES);
            if (selected.isEmpty()) {
                log(listener, "Workspace file selection returned no readable files.");
                return "";
            }

            String context = collector.collectFiles(workspace, selected, maxBytes, listener);
            if (!context.isBlank()) {
                log(listener, "Using " + selected.size() + " workspace file(s) selected by the AI provider.");
            }
            return context;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.FINE, "Adaptive workspace context interrupted", e);
            return "";
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Adaptive workspace context failed", e);
            log(listener, "Workspace file selection failed; continuing with the configured workspace context.");
            return "";
        }
    }

    /** Returns the suggested paths, or {@code null} when the selection failed or timed out. */
    @CheckForNull
    private static List<String> selectWithTimeout(BaseAIProvider provider, @CheckForNull Item item,
                                                  String errorLogs, @CheckForNull TaskListener listener,
                                                  int timeoutSeconds) {
        Future<WorkspaceFileSelection> future = SELECTOR_EXECUTOR.submit(
                () -> provider.createWorkspaceFileSelector(item, null).selectFiles(errorLogs));
        try {
            WorkspaceFileSelection selection = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return selection == null ? List.of() : selection.paths();
        } catch (TimeoutException e) {
            future.cancel(true);
            LOGGER.log(Level.FINE, "Workspace file selection timed out", e);
            log(listener, "Workspace file selection timed out; continuing with the configured workspace context.");
            return null;
        } catch (ExecutionException e) {
            LOGGER.log(Level.FINE, "Workspace file selection failed", e);
            log(listener, "Workspace file selection failed; continuing with the configured workspace context.");
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            LOGGER.log(Level.FINE, "Workspace file selection interrupted", e);
            return null;
        }
    }

    private static String tailUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int start = bytes.length - maxBytes;
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private static void log(@CheckForNull TaskListener listener, String message) {
        if (listener != null) {
            listener.getLogger().println("[explain-error] " + message);
        }
    }
}
