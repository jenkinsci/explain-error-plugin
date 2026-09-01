package io.jenkins.plugins.explain_error;

import com.google.common.annotations.VisibleForTesting;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.model.RunAction2;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Action for the Pipeline Graph View integration.
 * Provides AJAX endpoints to check node status and explain errors
 * for a specific selected node in the Pipeline Graph View.
 */
public class GraphViewExplainErrorAction implements RunAction2 {

    private static final Logger LOGGER = Logger.getLogger(GraphViewExplainErrorAction.class.getName());

    /** Matches the {@code selected-node} query parameter recorded in an explanation's URL. */
    private static final Pattern SELECTED_NODE_PATTERN = Pattern.compile("[?&]selected-node=([^&]+)");

    private transient Run<?, ?> run;
    private String urlString;

    public GraphViewExplainErrorAction(Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "graph-explain-error";
    }

    /**
     * AJAX endpoint to check build status.
     * Returns JSON with buildingStatus: 0 = SUCCESS, 1 = RUNNING, 2 = FINISHED and FAILURE.
     */
    @RequirePOST
    public void doCheckBuildStatus(StaplerRequest2 req, StaplerResponse2 rsp) {
        try {
            run.checkPermission(hudson.model.Item.READ);

            int buildingStatus = run.isBuilding() ? 1 : 0;

            if (buildingStatus == 0) {
                Result result = run.getResult();
                if (result == Result.SUCCESS) {
                    buildingStatus = 0;
                } else {
                    buildingStatus = 2;
                }
            }

            rsp.setContentType("application/json");
            rsp.setCharacterEncoding("UTF-8");
            PrintWriter writer = rsp.getWriter();
            writer.write("{\"buildingStatus\": " + buildingStatus + "}");
            writer.flush();
        } catch (Exception e) {
            LOGGER.warning("Error checking build status: " + e.getMessage());
            rsp.setStatus(500);
        }
    }

    /**
     * AJAX endpoint to check whether a specific flow node is a failed node.
     * Returns {@code isFailed: true} if the node (or any of its descendants)
     * has an {@link ErrorAction} or a {@link WarningAction} with result
     * {@link Result#FAILURE}.
     */
    @RequirePOST
    public void doCheckNodeStatus(StaplerRequest2 req, StaplerResponse2 rsp) {
        try {
            run.checkPermission(hudson.model.Item.READ);

            String nodeId = req.getParameter("nodeId");
            boolean isFailed = isFailedNode(nodeId);

            rsp.setContentType("application/json");
            rsp.setCharacterEncoding("UTF-8");
            PrintWriter writer = rsp.getWriter();

            JSONObject json = new JSONObject();
            json.put("isFailed", isFailed);
            writer.write(json.toString());
            writer.flush();
        } catch (Exception e) {
            LOGGER.warning("Error checking node status: " + e.getMessage());
            rsp.setStatus(500);
        }
    }

    /**
     * AJAX endpoint to explain an error for a specific selected node.
     * First checks if an existing {@link ErrorExplanationAction} is already
     * present (cache hit). Otherwise extracts logs from the specified node
     * and calls the AI provider.
     */
    @RequirePOST
    public void doExplainNodeError(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        long startTimeNanos = System.nanoTime();
        try {
            run.checkPermission(hudson.model.Item.READ);

            GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
            if (!config.isEnableExplanation()) {
                BaseAIProvider provider = config.getAiProvider();
                recordUsage(UsageEvent.Result.DISABLED,
                        provider != null ? provider.getProviderName() : null,
                        provider != null ? provider.getModel() : null,
                        startTimeNanos, 0);
                writeJsonResponse(rsp, "warning", "Unknown",
                        "AI error explanation is disabled in global configuration.");
                return;
            }

            String nodeId = req.getParameter("nodeId");
            if (nodeId == null || nodeId.isBlank()) {
                writeJsonResponse(rsp, "error", "Unknown", "No node selected.");
                return;
            }

            // Check if user wants to force a new explanation
            boolean forceNew = "true".equals(req.getParameter("forceNew"));

            // Check if an explanation already exists for THIS node. ErrorExplanationAction is a
            // single action per run (shared with the Console integration), so a pipeline with
            // multiple independently-failed nodes (e.g. parallel branches) must not reuse an
            // explanation that was generated for a different node than the one currently selected.
            ErrorExplanationAction existingAction = run.getAction(ErrorExplanationAction.class);
            if (!forceNew && existingAction != null && existingAction.hasValidExplanation()
                    && cachedExplanationMatchesNode(existingAction, nodeId)) {
                this.urlString = existingAction.getUrlString();
                recordUsage(UsageEvent.Result.CACHE_HIT, existingAction.getProviderName(),
                        existingAction.getProviderModel(), startTimeNanos,
                        existingAction.getInputLogLineCount());
                writeJsonResponse(rsp, "success", existingAction.getProviderName(),
                        createCachedResponse(existingAction.getExplanation()));
                return;
            }

            // Extract logs from the selected node
            PipelineLogExtractor logExtractor = new PipelineLogExtractor(run, 200,
                    Jenkins.getAuthentication2(), false, null);
            List<String> logLines = logExtractor.extractNodeLog(nodeId);
            this.urlString = logExtractor.getUrl();

            if (logLines.isEmpty()) {
                writeJsonResponse(rsp, "error", "Unknown",
                        "No log output found for the selected node.");
                return;
            }

            String errorText = String.join("\n", logLines);

            ErrorExplainer explainer = new ErrorExplainer();
            try {
                ErrorExplanationAction action = explainer.explainErrorText(errorText, urlString, run);
                writeJsonResponse(rsp, "success", action.getProviderName(), action.getExplanation());
            } catch (ExplanationException ee) {
                writeJsonResponse(rsp, ee.getLevel(), explainer.getProviderName(), ee.getMessage());
            }
        } catch (Exception e) {
            LOGGER.severe("Error explaining node error: " + e.getMessage());
            writeJsonResponse(rsp, "error", "Unknown", "Error: " + e.getMessage());
        }
    }

    /**
     * Determines whether a flow node (or any node it encloses) represents
     * a failure.
     * <p>
     * A node is considered failed if:
     * <ul>
     *   <li>It has an {@link ErrorAction} directly, or</li>
     *   <li>It has a {@link WarningAction} with {@link Result#FAILURE}, or</li>
     *   <li>It is a block node (e.g. a stage) and a node enclosed by it fails
     *       by either of the above criteria.</li>
     * </ul>
     * Containment is determined via {@link FlowNode#getEnclosingBlocks()}
     * rather than the parent chain: parents link to execution-order
     * predecessors, so following them would wrongly mark every node that ran
     * before the failure as failed.
     *
     * @param nodeId the flow node ID to check
     * @return {@code true} if the node or a node it encloses failed
     */
    @VisibleForTesting
    boolean isFailedNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return false;
        }
        if (!(run instanceof WorkflowRun)) {
            return false;
        }
        FlowExecution execution = ((WorkflowRun) run).getExecution();
        if (execution == null) {
            return false;
        }

        FlowNode targetNode = findNode(execution, nodeId);
        if (targetNode == null) {
            return false;
        }

        if (isFailure(targetNode)) {
            return true;
        }

        FlowGraphWalker walker = new FlowGraphWalker(execution);
        for (FlowNode node : walker) {
            if (isFailure(node) && isEnclosedBy(node, nodeId)) {
                return true;
            }
        }
        return false;
    }

    private static FlowNode findNode(FlowExecution execution, String nodeId) {
        for (FlowNode node : new FlowGraphWalker(execution)) {
            if (node.getId().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    private static boolean isFailure(FlowNode node) {
        if (node.getError() != null) {
            return true;
        }
        WarningAction warn = node.getAction(WarningAction.class);
        return warn != null && warn.getResult() == Result.FAILURE;
    }

    private static boolean isEnclosedBy(FlowNode node, String blockId) {
        for (FlowNode enclosing : node.getEnclosingBlocks()) {
            if (enclosing.getId().equals(blockId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a cached {@link ErrorExplanationAction} was generated for the given node.
     * <p>
     * The explanation's URL encodes the node it was generated from as a
     * {@code selected-node} query parameter (see {@link PipelineLogExtractor#getUrl()}). This is
     * used instead of storing the node ID separately so that existing serialized actions (from
     * before this check was added, or generated via the console/pipeline-step entry points)
     * degrade safely: a URL without a matching {@code selected-node} simply misses the cache and
     * a fresh explanation is generated.
     *
     * @param action the cached explanation to check
     * @param nodeId the currently selected node ID
     * @return {@code true} if the cached explanation was generated for {@code nodeId}
     */
    @VisibleForTesting
    static boolean cachedExplanationMatchesNode(ErrorExplanationAction action, String nodeId) {
        String cachedUrl = action.getUrlString();
        if (cachedUrl == null || nodeId == null) {
            return false;
        }
        Matcher matcher = SELECTED_NODE_PATTERN.matcher(cachedUrl);
        return matcher.find() && matcher.group(1).equals(nodeId);
    }

    private void writeJsonResponse(StaplerResponse2 rsp, String status, String providerName,
                                   String message) throws IOException {
        rsp.setContentType("application/json");
        rsp.setCharacterEncoding("UTF-8");
        PrintWriter writer = rsp.getWriter();

        JSONObject json = new JSONObject();
        json.put("status", status);
        json.put("providerName", providerName);
        json.put("message", message);
        json.put("url", urlString);
        writer.write(json.toString());
        writer.flush();
    }

    /**
     * Create a response indicating this is a cached result.
     */
    @VisibleForTesting
    String createCachedResponse(String explanation) {
        return explanation
                + "\n\n[Note: This is a previously generated explanation. "
                + "Use the 'Generate New' option to create a new one.]";
    }

    public Run<?, ?> getRun() {
        return run;
    }

    private void recordUsage(UsageEvent.Result result, String providerName, String model,
                             long startTimeNanos, int inputLogLineCount) {
        UsageRecorders.get().record(new UsageEvent(
                System.currentTimeMillis(),
                UsageEvent.EntryPoint.CONSOLE_ACTION,
                result,
                providerName,
                model,
                Math.max(0L, (System.nanoTime() - startTimeNanos) / 1_000_000L),
                inputLogLineCount,
                false));
    }
}
