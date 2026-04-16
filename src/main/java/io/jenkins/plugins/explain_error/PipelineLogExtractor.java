package io.jenkins.plugins.explain_error;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
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
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import jenkins.model.Jenkins;
import org.springframework.security.core.Authentication;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    /** Maximum recursion depth when following downstream (sub-job) failures. */
    private static final int MAX_DOWNSTREAM_DEPTH = 5;

    /** Hard cap for recent builds scanned per job when falling back to UpstreamCause lookup. */
    private static final int MAX_UPSTREAM_CAUSE_CANDIDATES_PER_JOB = 100;

    private boolean isGraphViewPluginAvailable = false;
    private transient String url;
    private transient Run<?, ?> run;
    private int maxLines;
    private int downstreamDepth;
    private final boolean collectDownstreamLogs;
    private final Pattern downstreamJobPattern;
    private final Authentication authentication;
    private final ExtractionStats stats;

    public record ExtractionResult(
            List<String> logLines,
            @CheckForNull String url,
            boolean fallbackToBuildLog,
            boolean foundFailingNode,
            @CheckForNull String primaryNodeId,
            boolean downstreamCollectionEnabled,
            int downstreamMatchedCount,
            int downstreamReusedExplanationCount,
            int downstreamPermissionSkippedCount) {

        public ExtractionResult {
            logLines = List.copyOf(logLines);
        }

        public int getExtractedLineCount() {
            return logLines.size();
        }
    }

    private static final class ExtractionStats {
        private int downstreamMatchedCount;
        private int downstreamReusedExplanationCount;
        private int downstreamPermissionSkippedCount;

        void reset() {
            downstreamMatchedCount = 0;
            downstreamReusedExplanationCount = 0;
            downstreamPermissionSkippedCount = 0;
        }
    }

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
     * Handles the {@code catchError + sh(returnStatus:true) + error()} pipeline pattern.
     * <p>
     * In this pattern, the {@code error()} step records an {@link ErrorAction} but has no
     * {@link LogAction} — the actual failure output lives in the preceding {@code sh} step,
     * which ran with {@code returnStatus:true} so it never threw an exception and carries
     * no {@link ErrorAction}.
     * <p>
     * This method checks the immediate parent of the {@code error()} node. It only proceeds
     * when there is exactly one parent (ruling out parallel-merge join points where the
     * relevant log cannot be determined unambiguously).
     *
     * @param errorOrigin the {@code error()} step node (has {@link ErrorAction}, no {@link LogAction})
     * @return the immediate parent node if it has a {@link LogAction}, or {@code null} otherwise
     */
    private FlowNode findImmediateParentWithLog(FlowNode errorOrigin) {
        // Go one level up, only if errorOrigin has exactly one parent.
        // If there are multiple parents we are at a parallel-merge join point and
        // cannot determine which parent's log is relevant.
        List<FlowNode> parents = errorOrigin.getParents();
        if (parents.size() == 1) {
            FlowNode parent = parents.get(0);
            if (parent.getAction(LogAction.class) != null) {
                return parent;
            }
        }
        return null;
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
     *       total, covering parallel failures such as multiple Rspec pod crashes.
     *       When a failing node has no {@link LogAction} (e.g. the {@code error()} step in a
     *       {@code catchError + sh(returnStatus:true) + error()} pipeline), the immediate
     *       parent node is checked and used if, and only if, there is exactly one parent
     *       and it has a {@link LogAction}.</li>
     * </ol>
     * Falls back to {@code run.getLog(maxLines)} (last N lines of console) only if all
     * strategies produce no results.
     *
     * @return A non-null list of log lines combining all relevant failure output, capped at
     *         {@code maxLines}.
     * @throws IOException if there is an error reading the build logs.
     */
    public List<String> getFailedStepLog() throws IOException {
        return extractFailedStepLog().logLines();
    }

    public ExtractionResult extractFailedStepLog() throws IOException {
        return extractFailedStepLog(true);
    }

    private ExtractionResult extractFailedStepLog(boolean resetStats) throws IOException {
        if (resetStats) {
            stats.reset();
        }

        List<String> accumulated = new ArrayList<>();
        Set<FlowNode> nodes = new HashSet<>();
        String primaryNodeId = null;
        boolean fallbackToBuildLog = false;

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
                    // catchError + sh(returnStatus:true) + error() fallback:
                    // error() has ErrorAction but no LogAction. Check the immediate parent;
                    // if it is the only parent and carries a LogAction, use it instead.
                    if (logAction == null && errorAction != null) {
                        FlowNode immediateParent = findImmediateParentWithLog(origin);
                        if (immediateParent != null && !seenOriginIds.contains(immediateParent.getId())) {
                            origin = immediateParent;
                            logAction = immediateParent.getAction(LogAction.class);
                        }
                    }
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
        } else {
            // Final fallback: last N lines of the full build console log
            fallbackToBuildLog = true;
            setUrl("0");
            accumulated.addAll(run.getLog(maxLines));
        }

        // Collect logs from failed downstream (sub-job) builds, recursively
        if (collectDownstreamLogs && downstreamDepth == 0) {
            Set<String> visitedRunIds = new HashSet<>();
            visitedRunIds.add(run.getParent().getFullName() + "#" + run.getNumber());
            collectDownstreamLogs(accumulated, visitedRunIds);
        }

        return new ExtractionResult(
                accumulated,
                url,
                fallbackToBuildLog,
                primaryNodeId != null,
                primaryNodeId,
                collectDownstreamLogs && downstreamJobPattern != null,
                stats.downstreamMatchedCount,
                stats.downstreamReusedExplanationCount,
                stats.downstreamPermissionSkippedCount);
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
        this(run, maxLines, Jenkins.getAuthentication2(), false, null);
    }

    public PipelineLogExtractor(Run<?, ?> run, int maxLines, boolean collectDownstreamLogs, String downstreamJobPattern)
    {
        this(run, maxLines, Jenkins.getAuthentication2(), collectDownstreamLogs, downstreamJobPattern);
    }

    PipelineLogExtractor(Run<?, ?> run, int maxLines, Authentication authentication,
                         boolean collectDownstreamLogs, String downstreamJobPattern)
    {
        this(run, maxLines, 0, authentication, collectDownstreamLogs,
                compileDownstreamJobPattern(collectDownstreamLogs, downstreamJobPattern));
    }

    @VisibleForTesting
    PipelineLogExtractor(Run<?, ?> run, int maxLines, int downstreamDepth)
    {
        this(run, maxLines, downstreamDepth, Jenkins.getAuthentication2(), true, Pattern.compile(".*"));
    }

    private PipelineLogExtractor(Run<?, ?> run, int maxLines, int downstreamDepth, Authentication authentication,
                                 boolean collectDownstreamLogs,
                                 Pattern downstreamJobPattern)
    {
        this(run, maxLines, downstreamDepth, authentication, collectDownstreamLogs, downstreamJobPattern,
                new ExtractionStats());
    }

    private PipelineLogExtractor(Run<?, ?> run, int maxLines, int downstreamDepth, Authentication authentication,
                                 boolean collectDownstreamLogs, Pattern downstreamJobPattern,
                                 ExtractionStats stats)
    {
        this.run = run;
        this.maxLines = maxLines;
        this.downstreamDepth = downstreamDepth;
        this.collectDownstreamLogs = collectDownstreamLogs;
        this.downstreamJobPattern = downstreamJobPattern;
        this.authentication = authentication != null ? authentication : Jenkins.getAuthentication2();
        this.stats = stats;
        if (Jenkins.get().getPlugin("pipeline-graph-view") != null) {
            isGraphViewPluginAvailable = true;
        }
    }

    private static Pattern compileDownstreamJobPattern(boolean collectDownstreamLogs, String downstreamJobPattern) {
        if (!collectDownstreamLogs || downstreamJobPattern == null || downstreamJobPattern.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(downstreamJobPattern);
        } catch (PatternSyntaxException e) {
            LOGGER.log(Level.WARNING, "Invalid downstream job pattern \"{0}\". Downstream logs will not be collected.", downstreamJobPattern);
            return null;
        }
    }

    /**
     * Collects error logs from failed downstream (sub-job) builds triggered by this run.
     * <p>
     * Supports two discovery mechanisms:
     * <ol>
     *   <li><b>DownstreamBuildAction</b> (pipeline-build-step plugin): reads the
     *       {@link org.jenkinsci.plugins.workflow.support.steps.build.DownstreamBuildAction}
     *       attached to the current run to find builds triggered by the {@code build} step.</li>
     *   <li><b>Cause.UpstreamCause</b>: scans all jobs in Jenkins for builds whose
     *       {@link Cause.UpstreamCause} points back to this run. This covers cases where
     *       the pipeline-build-step plugin is not installed.</li>
     * </ol>
     * Recursion is bounded by {@link #MAX_DOWNSTREAM_DEPTH} to prevent infinite loops.
     *
     * @param accumulated the list to append downstream log lines into
     * @param visitedRunIds set of already-visited run IDs (job full name + "#" + build number)
     *                      used to prevent duplicate processing across recursive calls
     */
    void collectDownstreamLogs(List<String> accumulated, Set<String> visitedRunIds) {
        boolean foundViaDownstreamBuildAction = false;
        String runId = run.getParent().getFullName() + "#" + run.getNumber();
        if (!collectDownstreamLogs || downstreamJobPattern == null
                || downstreamDepth >= MAX_DOWNSTREAM_DEPTH || !hasRemainingCapacity(accumulated)) {
            return;
        }

        // Strategy A: DownstreamBuildAction (pipeline-build-step plugin)
        if (Jenkins.get().getPlugin("pipeline-build-step") != null) {
            try {
                foundViaDownstreamBuildAction = collectViaDownstreamBuildAction(accumulated, visitedRunIds);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Failed to collect downstream logs via DownstreamBuildAction for " + runId, e);
            }
        }

        if (foundViaDownstreamBuildAction || !hasRemainingCapacity(accumulated)) {
            return;
        }

        // Strategy B: Cause.UpstreamCause — scan builds that list this run as upstream
        try {
            collectViaUpstreamCause(accumulated, visitedRunIds);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to collect downstream logs via UpstreamCause for " + runId, e);
        }
    }

    /**
     * Discovers failed downstream builds via
     * {@link org.jenkinsci.plugins.workflow.support.steps.build.DownstreamBuildAction}
     * and appends their logs to {@code accumulated}.
     */
    private boolean collectViaDownstreamBuildAction(List<String> accumulated, Set<String> visitedRunIds) throws IOException {
        org.jenkinsci.plugins.workflow.support.steps.build.DownstreamBuildAction action =
                run.getAction(org.jenkinsci.plugins.workflow.support.steps.build.DownstreamBuildAction.class);
        if (action == null) {
            return false;
        }
        boolean foundMatchingDownstream = false;
        for (org.jenkinsci.plugins.workflow.support.steps.build.DownstreamBuildAction.DownstreamBuild db : action.getDownstreamBuilds()) {
            if (!hasRemainingCapacity(accumulated)) {
                return foundMatchingDownstream;
            }
            String jobFullName = db.getJobFullName();
            if (!matchesDownstreamJob(jobFullName)) {
                continue;
            }
            Run<?, ?> downstreamRun;
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                downstreamRun = db.getBuild();
            }
            if (downstreamRun == null) {
                continue;
            }
            foundMatchingDownstream = true;
            appendDownstreamRunLog(downstreamRun, accumulated, visitedRunIds);
        }
        return foundMatchingDownstream;
    }

    /**
     * Discovers failed downstream builds by scanning all jobs for builds whose
     * {@link Cause.UpstreamCause} points to this run, and appends their logs to
     * {@code accumulated}.
     */
    private void collectViaUpstreamCause(List<String> accumulated, Set<String> visitedRunIds) throws IOException {
        String thisJobName = run.getParent().getFullName();
        int thisBuildNumber = run.getNumber();
        long thisBuildStartTime = run.getTimeInMillis();

        try (ACLContext ignored = ACL.as2(authentication)) {
            for (hudson.model.Job<?, ?> job : Jenkins.get().getAllItems(hudson.model.Job.class)) {
                if (!hasRemainingCapacity(accumulated)) {
                    return;
                }
                if (!job.hasPermission(Item.READ) || !matchesDownstreamJob(job.getFullName())) {
                    continue;
                }
                // Skip the current job itself
                if (job.getFullName().equals(thisJobName)) {
                    continue;
                }
                Run<?, ?> lastBuild = job.getLastBuild();
                if (lastBuild == null) {
                    continue;
                }
                int scannedCandidates = 0;
                // Walk recent builds of this job to find ones triggered by our run
                for (Run<?, ?> candidate = lastBuild; candidate != null; candidate = candidate.getPreviousBuild()) {
                    if (!hasRemainingCapacity(accumulated) || scannedCandidates >= MAX_UPSTREAM_CAUSE_CANDIDATES_PER_JOB) {
                        break;
                    }
                    scannedCandidates++;
                    // Only look at builds that could have been triggered by our run
                    if (candidate.getTimeInMillis() < thisBuildStartTime) {
                        break;
                    }
                    for (Cause cause : candidate.getCauses()) {
                        if (cause instanceof Cause.UpstreamCause) {
                            Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause) cause;
                            if (upstreamCause.getUpstreamProject().equals(thisJobName)
                                    && upstreamCause.getUpstreamBuild() == thisBuildNumber) {
                                appendDownstreamRunLog(candidate, accumulated, visitedRunIds);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns {@code true} if the given run was aborted because a sibling branch triggered
     * a fail-fast interruption (e.g. via {@code parallelsAlwaysFailFast()} or
     * {@code parallel(failFast: true, ...)}).
     * <p>
     * Jenkins records the interruption cause in an {@link InterruptedBuildAction} attached to
     * the run. When the cause is a fail-fast signal, its
     * {@link CauseOfInterruption#getShortDescription()} contains the phrase "fail fast"
     * (case-insensitive). This distinguishes a sibling-aborted run from a run that was
     * independently aborted by a user or another mechanism.
     *
     * @param run the build to inspect
     * @return {@code true} if the build was interrupted by a fail-fast signal
     */
    boolean isAbortedByFailFast(Run<?, ?> run) {
        if (run.getResult() != Result.ABORTED) {
            return false;
        }
        for (InterruptedBuildAction action : run.getActions(InterruptedBuildAction.class)) {
            for (CauseOfInterruption cause : action.getCauses()) {
                String desc = cause.getShortDescription();
                if (desc != null && desc.toLowerCase(java.util.Locale.ROOT).contains("fail fast")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasRemainingCapacity(List<String> accumulated) {
        return accumulated.size() < maxLines;
    }

    private boolean matchesDownstreamJob(String jobFullName) {
        return downstreamJobPattern != null && downstreamJobPattern.matcher(jobFullName).matches();
    }

    /**
     * Appends the error content of a single downstream run to {@code accumulated},
     * then recurses into its own downstream builds.
     * <p>
     * <b>Fast path — reuse existing AI explanation:</b> if the downstream run already has an
     * {@link ErrorExplanationAction} (i.e. the sub-job called {@code explainError()} itself),
     * its pre-computed explanation text is used directly. This avoids a redundant AI call and
     * preserves the full context that was available when the sub-job ran.
     * <p>
     * <b>Slow path — extract raw logs:</b> when no {@link ErrorExplanationAction} is present,
     * a {@link PipelineLogExtractor} is created for the downstream run and its log lines are
     * appended as before.
     * <p>
     * Builds that were aborted by a fail-fast signal from a sibling branch are labelled
     * {@code ABORTED (interrupted by fail-fast, not the root cause)} in the section header
     * so that the AI can distinguish them from the build that actually caused the failure.
     *
     * @param downstreamRun  the downstream build to extract content from
     * @param accumulated    the list to append content lines into
     * @param visitedRunIds  set of already-visited run IDs to prevent duplicates
     */
    private void appendDownstreamRunLog(Run<?, ?> downstreamRun, List<String> accumulated,
                                        Set<String> visitedRunIds) throws IOException {
        Result downstreamResult = downstreamRun.getResult();
        if (downstreamResult == null || !downstreamResult.isWorseThan(Result.SUCCESS)) {
            return;
        }
        String jobFullName = downstreamRun.getParent().getFullName();
        int buildNumber = downstreamRun.getNumber();
        String runId = jobFullName + "#" + buildNumber;
        if (!visitedRunIds.add(runId)) {
            return; // already processed
        }
        int remaining = this.maxLines - accumulated.size();
        if (remaining <= 0) {
            return;
        }
        stats.downstreamMatchedCount++;
        if (!canReadDownstreamRun(downstreamRun)) {
            stats.downstreamPermissionSkippedCount++;
            appendHiddenDownstreamPlaceholder(accumulated);
            return;
        }

        boolean failFastAborted = isAbortedByFailFast(downstreamRun);
        String resultLabel = failFastAborted
            ? "ABORTED (interrupted by fail-fast, not the root cause)"
            : String.valueOf(downstreamResult);

        List<String> header = Arrays.asList(
            "### Downstream Job: " + jobFullName + " #" + buildNumber + " ###",
            "Result: " + resultLabel,
            "--- LOG CONTENT ---"
        );

        String runUrl = run.getUrl();

        // Fast path: sub-job already has an AI explanation — reuse it directly.
        ErrorExplanationAction existingExplanation = downstreamRun.getAction(ErrorExplanationAction.class);
        if (existingExplanation != null && existingExplanation.hasValidExplanation()) {
            stats.downstreamReusedExplanationCount++;
            // Redirect "View failure output" to the sub-job's own explanation URL when available.
            if (!failFastAborted && existingExplanation.getUrlString() != null && this.url != null
                    && runUrl != null && this.url.contains(runUrl)) {
                this.url = existingExplanation.getUrlString();
            }
            accumulated.addAll(header);
            accumulated.add("[AI explanation from sub-job]");
            accumulated.addAll(Arrays.asList(existingExplanation.getExplanation().split("\n", -1)));
            accumulated.add("### END OF DOWNSTREAM JOB: " + jobFullName + " ###");
            // No need to recurse further — the sub-job's explanation already covers its own
            // downstream failures (it was produced with full context at the time of the failure).
            return;
        }

        // Slow path: no existing explanation — extract raw logs as before.
        PipelineLogExtractor subExtractor = new PipelineLogExtractor(downstreamRun, remaining, downstreamDepth + 1,
                authentication, collectDownstreamLogs, downstreamJobPattern, stats);
        ExtractionResult subResult = subExtractor.extractFailedStepLog(false);
        List<String> subLog = subResult.logLines();
        if (subLog.isEmpty()) {
            return;
        }

        // If this sub-job genuinely failed (not just aborted by fail-fast) and the parent
        // URL still points to the parent job (i.e. no prior real sub-job failure has already
        // claimed the URL), redirect "View failure output" to the sub-job's failing node.
        if (!failFastAborted && subResult.url() != null && this.url != null
                && runUrl != null && this.url.contains(runUrl)) {
            this.url = subResult.url();
        }

        int remainingCapacity = maxLines - accumulated.size();
        if (remainingCapacity <= 0) {
            // No room left for this downstream section
            return;
        }

        // Append header, truncated if needed
        if (header.size() > remainingCapacity) {
            accumulated.addAll(header.subList(0, remainingCapacity));
            // No room left for sub-log or footer
            return;
        } else {
            accumulated.addAll(header);
            remainingCapacity -= header.size();
        }
        // Reserve at least one line for footer if possible
        final String endMarker = "### END OF DOWNSTREAM JOB: " + jobFullName + " ###";
        if (remainingCapacity <= 0) {
            // No space for sub-log or footer
            return;
        }
        int spaceForSubLog = remainingCapacity - 1; // keep one line for footer
        if (spaceForSubLog > 0) {
            if (subLog.size() > spaceForSubLog) {
                accumulated.addAll(subLog.subList(0, spaceForSubLog));
            } else {
                accumulated.addAll(subLog);
            }
            remainingCapacity = maxLines - accumulated.size();
        }
        // Append footer if there is still room
        if (remainingCapacity > 0) {
            accumulated.add(endMarker);
        }
        // Recurse into sub-job's own downstream builds only if capacity remains
        if (maxLines - accumulated.size() > 0) {
            subExtractor.collectDownstreamLogs(accumulated, visitedRunIds);
        }
    }

    private boolean canReadDownstreamRun(Run<?, ?> downstreamRun) {
        try (ACLContext ignored = ACL.as2(authentication)) {
            return downstreamRun.getParent().hasPermission(Item.READ);
        }
    }

    private void appendHiddenDownstreamPlaceholder(List<String> accumulated) {
        int remainingCapacity = maxLines - accumulated.size();
        if (remainingCapacity <= 0) {
            return;
        }
        List<String> placeholderLines = Arrays.asList(
                "### Downstream Job: [hidden] ###",
                "Result: UNAVAILABLE",
                "Downstream failure details omitted due to permissions.",
                "### END OF DOWNSTREAM JOB: [hidden] ###"
        );
        if (placeholderLines.size() <= remainingCapacity) {
            accumulated.addAll(placeholderLines);
        } else {
            accumulated.addAll(placeholderLines.subList(0, remainingCapacity));
        }
    }
}
