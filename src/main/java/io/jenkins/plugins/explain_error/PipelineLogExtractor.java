package io.jenkins.plugins.explain_error;

import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;

import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleNote;
import hudson.model.Result;
import hudson.model.Run;
import jenkins.model.Jenkins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Logger;
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
     * Matches common error indicators: error(s), exception(s), failed, fatal (case-insensitive).
     */
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?i)\\b(errors?|exceptions?|failed|fatal)\\b",
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
     * Prepends execution metadata (Node ID, Name, Start Time) and appends a closing tag
     * to a list of log lines.
     * <p>
     * This structured bracketing establishes a temporal timeline and context boundary,
     * making it easier for AI models or log parsers to distinguish this specific
     * execution's output from other interleaved parallel executions.
     *
     * @param node The Jenkins FlowNode containing the execution context and timing.
     * @param logs The existing list of log lines for this node. If null, a new list is initialized.
     */
    private void addHeaderLog(FlowNode node, List<String> logs) {
        long startTimeMillis = TimingAction.getStartTime(node);
        String formattedStart = "Unknown Start Time";

        if (startTimeMillis > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
            formattedStart = sdf.format(new Date(startTimeMillis));
        }

        List<String> header = Arrays.asList(
            "### Node ID: " + node.getId() + " ###",
            "Node Name: " + node.getDisplayName(),
            "Start Time: " + formattedStart,
            "--- LOG CONTENT ---");

        logs.addAll(0, header);
        logs.add("### END OF LOG " + node.getId() + " ###");
    }

    /**
     * Finds the most recent (lowest) common ancestor for a given set of Jenkins Pipeline FlowNodes.
     * <p>
     * Jenkins pipeline execution forms a Directed Acyclic Graph (DAG). This method calculates the
     * intersection of all upstream ancestors for the provided nodes. It then determines the "nearest"
     * common ancestor by finding the node in that intersection with the highest integer ID,
     * as Jenkins assigns monotonically increasing integer strings as IDs during pipeline execution.
     * </p>
     *
     * @param nodes A {@link Set} of {@link FlowNode} objects for which to find the common ancestor.
     * @return The {@link FlowNode} representing the nearest common ancestor, or {@code null} if
     * the input set is null, empty, or if no common ancestor exists.
     */
    public FlowNode findCommonAncestor(Set<FlowNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }

        Iterator<FlowNode> iterator = nodes.iterator();
        Set<FlowNode> commonAncestors = getAncestors(iterator.next());

        while (iterator.hasNext()) {
            commonAncestors.retainAll(getAncestors(iterator.next()));
        }

        if (commonAncestors.isEmpty()) {
            return null;
        }

        FlowNode nearestAncestor = null;
        int highestId = -1;

        for (FlowNode node : commonAncestors) {
            int currentId = Integer.parseInt(node.getId());
            if (currentId > highestId) {
                highestId = currentId;
                nearestAncestor = node;
            }
        }

        return nearestAncestor;
    }

    /**
     * Traverses the pipeline graph upstream to gather all parent nodes of a given FlowNode.
    * <p>
    * This method uses a Breadth-First Search (BFS) algorithm to safely navigate up the
    * pipeline DAG without encountering stack overflow issues on deeply nested pipelines.
    * </p>
    *
    * @param startNode The {@link FlowNode} from which to begin the upstream traversal.
    * @return A {@link Set} containing the starting node and all of its upstream ancestors.
    */
    private Set<FlowNode> getAncestors(FlowNode startNode) {
        Set<FlowNode> ancestors = new HashSet<>();
        Queue<FlowNode> queue = new LinkedList<>();

        queue.add(startNode);

        while (!queue.isEmpty()) {
            FlowNode current = queue.poll();
            if (ancestors.add(current)) {
                queue.addAll(current.getParents());
            }
        }
        return ancestors;
    }

    /**
     * Extracts the log output of the step(s) that caused the pipeline failure,
     * combining results from multiple strategies so that parallel failures
     * (e.g. both a Rspec test failure and a RuboCop offense) are all captured.
     * <ol>
     *   <li><b>Strategy 1 — ErrorAction multi-collect:</b> walks the FlowGraph and collects
     *       logs from <em>all</em> nodes with {@link ErrorAction} and an associated
     *       {@link LogAction} (explicit uncaught exceptions). Unlike the original single-return
     *       approach, this accumulates logs from every failing step up to {@code maxLines}
     *       total, covering parallel failures such as multiple Rspec pod crashes.</li>
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
        Set<FlowNode> nodes = new HashSet<>();
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
                    int remainingLines = this.maxLines - accumulated.size();
                    if (remainingLines <= 0) {
                        break;
                    }
                    ErrorAction errorAction = node.getError();
                    FlowNode origin = null;
                    if (errorAction == null) {
                        WarningAction warn = node.getAction(WarningAction.class);
                        if (warn != null) {
                            var result = warn.getResult();
                            if (result != Result.FAILURE) {
                                continue;
                            }
                            origin = node;
                        }
                    } else {
                        origin = resolveOrigin(errorAction.getError(), execution);
                        if (origin == null || seenOriginIds.contains(origin.getId())) {
                            continue;
                        }
                    }
                    if (origin == null) {
                        continue;
                    }
                    LogAction logAction = origin.getAction(LogAction.class);
                    if (logAction == null) {
                        continue;
                    }
                    seenOriginIds.add(origin.getId());
                    List<String> stepLog = readLimitedLog(logAction.getLogText(), remainingLines);
                    if (stepLog == null || stepLog.isEmpty()) {
                        continue;
                    }

                    if (primaryNodeId == null) {
                        primaryNodeId = origin.getId();
                    }
                    nodes.add(origin);
                    addHeaderLog(origin, stepLog);
                    accumulated.addAll(stepLog);
                }
            }
        }

        if (nodes.size() > 1) {
            FlowNode ancestor = findCommonAncestor(nodes);
            if (ancestor != null) {
                primaryNodeId = ancestor.getId();
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

    /**
     * Finds the {@link FlowNode} that originally threw the given error within the given execution.
     * <p>
     * Delegates to {@link ErrorAction#findOrigin}. Package-private to allow overriding in unit
     * tests via Mockito {@code spy}, so that the null-origin and duplicate-origin branches of
     * the Strategy 1 loop can be exercised without depending on specific Jenkins CPS behaviour.
     *
     * @param error     the throwable stored in the node's {@link ErrorAction}
     * @param execution the current flow execution to search
     * @return the origin {@link FlowNode}, or {@code null} if not found
     */
    FlowNode resolveOrigin(Throwable error, FlowExecution execution) {
        return ErrorAction.findOrigin(error, execution);
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
