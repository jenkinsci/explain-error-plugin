package io.jenkins.plugins.explain_error;

import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleNote;
import hudson.model.Result;
import hudson.model.Run;
import jenkins.model.Jenkins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.logging.Logger;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility for extracting log lines related to a failing build or pipeline step
 * and computing a URL that points back to the error source.
 * <p>
 * For {@link org.jenkinsci.plugins.workflow.job.WorkflowRun} (Pipeline) builds,
 * this class walks the flow graph to locate the node that originally threw the
 * error, reads a limited number of log lines from that step, and records a
 * node-specific URL that can be used to navigate to the failure location.
 * When no failing step log can be found, or when the build is not a pipeline,
 * it falls back to the standard build console log.
 * <p>
 * If the optional {@code pipeline-graph-view} plugin is installed, the
 * generated URL is compatible with its overview page so that consumers can
 * deep-link directly into the failing node from error explanations.
 */
public class PipelineLogExtractor {

    private static final Logger LOGGER = Logger.getLogger(PipelineLogExtractor.class.getName());
    public static final String URL_NAME = "stages";

    /**
     * Pattern to detect error-related content in build logs.
     * Matches common error indicators: error, exception, failed, fatal (case-insensitive).
     */
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?i)\\b(error|exception|failed|fatal)\\b",
            Pattern.MULTILINE
    );

    /** Lines of context to include before and after each error-pattern match. */
    private static final int ERROR_CONTEXT_LINES = 5;

    private boolean isGraphViewPluginAvailable = false;
    private transient String url;
    private transient Run<?, ?> run;
    private int maxLines;



    /**
     * Reads the provided log text and returns at most the last {@code maxLines} lines.
     * <p>
     * The entire log is streamed into memory, Jenkins {@link ConsoleNote} annotations are stripped,
     * and a sliding window is maintained over the lines: when the number of buffered lines reaches
     * {@code maxLines}, the oldest line is removed before adding the next one. This ensures that
     * only the most recent {@code maxLines} lines are retained.
     * <p>
     * Line terminators ({@code \n} and {@code \r}) are removed from each returned line. If no log
     * content is available or an error occurs while reading, an empty list is returned.
     *
     * @param logText  the annotated log text associated with a {@link FlowNode}
     * @param maxLines the maximum number of trailing log lines to return
     * @return a list containing up to the last {@code maxLines} lines of the log, or an empty list
     *         if the log is empty or an error occurs
     */
    private List<String> readLimitedLog(AnnotatedLargeText<? extends FlowNode> logText,
                                               int maxLines) {
        StringWriter writer = new StringWriter();
        try {
            long offset = logText.writeLogTo(0, writer);
            if (offset <= 0) {
                return Collections.emptyList();
            }
            String cleanLog = ConsoleNote.removeNotes(writer.toString());
            BufferedReader reader = new BufferedReader(new StringReader(cleanLog));
            LinkedList<String> queue = new LinkedList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (queue.size() >= maxLines) {
                    queue.removeFirst();
                }

                queue.add(line);
            }
            return new ArrayList<>(queue);
        } catch (IOException e) {
            LOGGER.severe("Unable to serialize the flow node log: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Scans the full build console log for lines matching common error patterns
     * and returns them with surrounding context lines, up to {@code maxLines} total.
     * <p>
     * This is used as a fallback when the FlowGraph walk fails to find a failed step
     * with a log — for example when errors occur inside {@code catchError} blocks where
     * the exception is swallowed and no {@code ErrorAction} is recorded on the FlowGraph,
     * or when errors appear early in a large build log and would be missed by the
     * last-N-lines approach.
     * <p>
     * Matched patterns include: {@code error}, {@code exception}, {@code failed}, and
     * {@code fatal} (case-insensitive, word-boundary anchored).
     *
     * @return list of error-context lines (ordered as they appear in the log),
     *         or an empty list if no patterns are found or the log cannot be read
     */
    private List<String> getErrorPatternLines() {
        List<String> allLines = new ArrayList<>();

        try (InputStream inputStream = run.getLogInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                allLines.add(ConsoleNote.removeNotes(line));
            }
        } catch (IOException e) {
            LOGGER.warning("Could not read full log for error pattern scan: " + e.getMessage());
            return Collections.emptyList();
        }

        if (allLines.isEmpty()) {
            return Collections.emptyList();
        }

        // Mark lines to include: error-pattern matches and surrounding context
        boolean[] include = new boolean[allLines.size()];
        for (int i = 0; i < allLines.size(); i++) {
            if (ERROR_PATTERN.matcher(allLines.get(i)).find()) {
                int start = Math.max(0, i - ERROR_CONTEXT_LINES);
                int end = Math.min(allLines.size() - 1, i + ERROR_CONTEXT_LINES);
                for (int j = start; j <= end; j++) {
                    include[j] = true;
                }
            }
        }

        // Collect included lines up to maxLines, preserving original order
        List<String> result = new ArrayList<>();
        for (int i = 0; i < allLines.size() && result.size() < maxLines; i++) {
            if (include[i]) {
                result.add(allLines.get(i));
            }
        }

        return result;
    }

    /**
     * Extracts the log output of the step(s) that caused the pipeline failure,
     * combining results from multiple strategies so that parallel failures
     * (e.g. both a Rspec test failure and a RuboCop offense) are all captured.
     * <p>
     * <ol>
     *   <li><b>Strategy 1 — ErrorAction multi-collect:</b> walks the FlowGraph and collects
     *       logs from <em>all</em> nodes with {@link ErrorAction} and an associated
     *       {@link LogAction} (explicit uncaught exceptions). Unlike the original single-return
     *       approach, this accumulates logs from every failing step up to {@code maxLines}
     *       total, covering parallel failures such as multiple Rspec pod crashes.</li>
     *   <li><b>Strategy 2 — WarningAction walk:</b> runs only when Strategy 1 found nothing.
     *       Walks the FlowGraph looking for step nodes whose log is enclosed by a
     *       {@link BlockStartNode} carrying a {@link WarningAction} worse than SUCCESS.
     *       Requires ≥2 lines matching the error pattern to avoid returning CI infrastructure
     *       steps (e.g. a curl status-update) ahead of the actual failing step log.</li>
     *   <li><b>Strategy 3 — Error pattern scan (always runs as supplement):</b> reads the
     *       full build console log and appends lines matching common error patterns (with
     *       surrounding context) that were not already captured by Strategies 1 or 2.
     *       This fills the gap left by {@code catchError + sh(returnStatus:true) + error()}
     *       pipelines (where no {@link LogAction} exists on the {@code error()} step) and
     *       catches errors that appear early in large build logs.</li>
     * </ol>
     * Falls back to {@code run.getLog(maxLines)} (last N lines of console) only if all
     * strategies produce no results.
     *
     * @return A non-null list of log lines combining all relevant failure output, capped at
     *         {@code maxLines}.
     * @throws IOException if there is an error reading the build logs.
     */
    public List<String> getFailedStepLog() throws IOException {
        List<String> accumulated = new ArrayList<>();
        String primaryNodeId = null;

        if (this.run instanceof WorkflowRun) {
            FlowExecution execution = ((WorkflowRun) this.run).getExecution();

            if (execution != null) {
                // Strategy 1: collect logs from ALL ErrorAction+LogAction nodes.
                // Multi-collect instead of returning on first match so that parallel failures
                // (e.g. multiple Rspec pods + a direct sh failure) are all captured.
                Set<String> seenOriginIds = new HashSet<>();
                FlowGraphWalker walker = new FlowGraphWalker(execution);
                for (FlowNode node : walker) {
                    ErrorAction errorAction = node.getAction(ErrorAction.class);
                    if (errorAction == null) continue;
                    FlowNode origin = ErrorAction.findOrigin(errorAction.getError(), execution);
                    if (origin == null || seenOriginIds.contains(origin.getId())) continue;
                    LogAction logAction = origin.getAction(LogAction.class);
                    if (logAction == null) continue;
                    List<String> stepLog = readLimitedLog(logAction.getLogText(), this.maxLines);
                    if (stepLog == null || stepLog.isEmpty()) continue;
                    seenOriginIds.add(origin.getId());
                    if (primaryNodeId == null) primaryNodeId = origin.getId();
                    accumulated.addAll(stepLog);
                }

                // Trim to maxLines (walker is reverse-chronological, so most recent errors first)
                if (accumulated.size() > this.maxLines) {
                    accumulated = new ArrayList<>(accumulated.subList(0, this.maxLines));
                }

                // Strategy 2: WarningAction walk — only when Strategy 1 found nothing.
                // Handles pipeline variants where the catchError BlockStartNode carries a
                // WarningAction (e.g. stageResult:'FAILURE' with a direct sh that exits non-zero).
                if (accumulated.isEmpty()) {
                    FlowGraphWalker warningWalker = new FlowGraphWalker(execution);
                    for (FlowNode node : warningWalker) {
                        LogAction logAction = node.getAction(LogAction.class);
                        if (logAction == null) continue;
                        BlockStartNode catchErrorBlock = null;
                        for (BlockStartNode enclosing : node.getEnclosingBlocks()) {
                            WarningAction warningAction = enclosing.getAction(WarningAction.class);
                            if (warningAction != null && warningAction.getResult().isWorseThan(Result.SUCCESS)) {
                                catchErrorBlock = enclosing;
                                break;
                            }
                        }
                        if (catchErrorBlock == null) continue;
                        List<String> result = readLimitedLog(logAction.getLogText(), this.maxLines);
                        if (result == null || result.isEmpty()) continue;
                        // Require at least 2 lines matching ERROR_PATTERN to avoid returning
                        // CI infrastructure steps (e.g. a curl status-update command that
                        // happens to contain "failed" in its JSON payload) ahead of the actual
                        // failing step log (e.g. RuboCop with multiple offense lines).
                        long errorLineCount = result.stream()
                                .filter(line -> ERROR_PATTERN.matcher(line).find())
                                .count();
                        if (errorLineCount < 2) continue;
                        accumulated.addAll(result);
                        primaryNodeId = catchErrorBlock.getId();
                        break;
                    }
                }
            }
        }

        // Strategy 3: scan the full console log for error-pattern lines.
        // Always runs as a supplement to fill gaps not covered by the FlowGraph walk —
        // most importantly the catchError + sh(returnStatus:true) + error() pattern where
        // the error() step has no LogAction, and errors that appear early in large build logs.
        // Only lines not already present in the accumulated result are added.
        int budget = this.maxLines - accumulated.size();
        if (budget > 0) {
            List<String> patternLines = getErrorPatternLines();
            if (!patternLines.isEmpty()) {
                Set<String> existingLines = new HashSet<>(accumulated);
                for (String line : patternLines) {
                    if (budget <= 0) break;
                    if (existingLines.add(line)) {
                        accumulated.add(line);
                        budget--;
                    }
                }
                LOGGER.info("Strategy 3 scan complete: " + accumulated.size() + " total lines accumulated");
            }
        }

        if (!accumulated.isEmpty()) {
            setUrl(primaryNodeId != null ? primaryNodeId : "0");
            return accumulated;
        }

        // Final fallback: last N lines of the full build console log
        setUrl("0");
        return run.getLog(maxLines);
    }

    private void setUrl(String node)
    {
        String rootUrl = Jenkins.get().getRootUrl();
        if (isGraphViewPluginAvailable) {
            url = rootUrl + run.getUrl() + URL_NAME + "?selected-node=" + node;
        } else {
            url = rootUrl + run.getUrl() + "console";
        }
    }

    /**
     * Returns the URL associated with the extracted log.
     * <p>
     * When {@link #getFailedStepLog()} finds a failed pipeline step with an attached log and the
     * {@code pipeline-graph-view} plugin is available, this will point to the pipeline overview page with the
     * failing node preselected. Otherwise, it falls back to the build's console output URL.
     * </p>
     *
     * @return the Jenkins URL for either the pipeline overview of the failing step or the build console output,
     *         or {@code null} if {@link #getFailedStepLog()} has not been invoked successfully.
     */
    public String getUrl() {
        return this.url;
    }

    public PipelineLogExtractor(Run<?, ?> run, int maxLines)
    {
        this.run = run;
        this.maxLines = maxLines;
        if (Jenkins.get().getPlugin("pipeline-graph-view") != null) {
            isGraphViewPluginAvailable = true;
        }
    }
}
