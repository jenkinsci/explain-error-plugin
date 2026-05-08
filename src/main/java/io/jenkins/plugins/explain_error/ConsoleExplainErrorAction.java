package io.jenkins.plugins.explain_error;

import com.google.common.annotations.VisibleForTesting;
import hudson.model.Result;
import hudson.model.Run;
import jenkins.model.RunAction2;
import jenkins.model.Jenkins;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.logging.Logger;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Action to add "Explain Error" functionality to console output pages.
 * This action needs to be manually added to builds.
 */
public class ConsoleExplainErrorAction implements RunAction2 {

    private static final Logger LOGGER = Logger.getLogger(ConsoleExplainErrorAction.class.getName());

    private transient Run<?, ?> run;
    private String urlString;

    public ConsoleExplainErrorAction(Run<?, ?> run) {
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
        return null; // No icon in sidebar - this is for AJAX functionality only
    }

    @Override
    public String getDisplayName() {
        return null; // No display name in sidebar
    }

    @Override
    public String getUrlName() {
        return "console-explain-error";
    }

    /**
     * AJAX endpoint to explain error from console output.
     * Called via JavaScript from the console output page.
     */
    @RequirePOST
    public void doExplainConsoleError(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        long startTimeNanos = System.nanoTime();
        try {
            run.checkPermission(hudson.model.Item.READ);

            GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
            if (!config.isEnableExplanation()) {
                BaseAIProvider provider = config.getAiProvider();
                recordUsage(UsageEvent.Result.DISABLED,
                        provider != null ? provider.getProviderName() : null,
                        provider != null ? provider.getModel() : null,
                        startTimeNanos,
                        0);
                writeJsonResponse(rsp, "warning", "Unknown", "AI error explanation is disabled in global configuration.");
                return;
            }

            // Check if user wants to force a new explanation
            boolean forceNew = "true".equals(req.getParameter("forceNew"));

            // Check if an explanation already exists
            ErrorExplanationAction existingAction = run.getAction(ErrorExplanationAction.class);
            if (!forceNew && existingAction != null && existingAction.hasValidExplanation()) {
                recordUsage(UsageEvent.Result.CACHE_HIT, existingAction.getProviderName(),
                        existingAction.getProviderModel(), startTimeNanos, existingAction.getInputLogLineCount());
                // Return existing explanation with a flag indicating it's cached
                writeJsonResponse(rsp, "success", existingAction.getProviderName(), createCachedResponse(existingAction.getExplanation()));
                return;
            }

            // Optionally allow maxLines as a parameter, default to 200
            int maxLines = parseMaxLines(req, 200);

            // Fetch the last N lines of the log
            PipelineLogExtractor logExtractor = new PipelineLogExtractor(run, maxLines, Jenkins.getAuthentication2(),
                    false, null);
            List<String> logLines = logExtractor.getFailedStepLog();
            this.urlString = logExtractor.getUrl();

            String errorText = String.join("\n", logLines);

            ErrorExplainer explainer = new ErrorExplainer();
            try {
                ErrorExplanationAction action = explainer.explainErrorText(errorText, urlString, run);
                writeJsonResponse(rsp, "success", action.getProviderName(), action.getExplanation());
            } catch (ExplanationException ee) {
                writeJsonResponse(rsp, ee.getLevel(), explainer.getProviderName(), ee.getMessage());
            }
        } catch (Exception e) {
            LOGGER.severe("=== EXPLAIN ERROR REQUEST FAILED ===");
            LOGGER.severe("Error explaining console error: " + e.getMessage());
            writeJsonResponse(rsp, "error", "Unknown", "Error: " + e.getMessage());
        }
    }

    /**
     * AJAX endpoint to explain a selected Pipeline Graph View node.
     */
    @RequirePOST
    public void doExplainNodeError(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        long startTimeNanos = System.nanoTime();
        String nodeId = req.getParameter("nodeId");
        nodeId = nodeId != null ? nodeId.trim() : "";

        try {
            run.checkPermission(hudson.model.Item.READ);

            if (nodeId.isEmpty()) {
                recordUsage(UsageEvent.EntryPoint.PIPELINE_GRAPH_NODE, UsageEvent.Result.DISABLED,
                        null, null, startTimeNanos, 0);
                writeJsonResponse(rsp, "warning", "Unknown", "No Pipeline node was selected.");
                return;
            }

            boolean forceNew = "true".equals(req.getParameter("forceNew"));
            StepErrorExplanationAction stepAction = run.getAction(StepErrorExplanationAction.class);
            StepErrorExplanationAction.Entry existing = stepAction != null ? stepAction.getExplanation(nodeId) : null;
            if (!forceNew && existing != null && existing.hasValidExplanation()) {
                this.urlString = existing.getUrlString();
                recordUsage(UsageEvent.EntryPoint.PIPELINE_GRAPH_NODE, UsageEvent.Result.CACHE_HIT,
                        existing.getProviderName(), existing.getProviderModel(), startTimeNanos,
                        existing.getInputLogLineCount());
                writeJsonResponse(rsp, "success", existing.getProviderName(),
                        createCachedResponse(existing.getExplanation()));
                return;
            }

            int maxLines = parseMaxLines(req, 200);
            PipelineLogExtractor logExtractor = new PipelineLogExtractor(run, maxLines, Jenkins.getAuthentication2(),
                    false, null);
            PipelineLogExtractor.ExtractionResult extractionResult = logExtractor.extractNodeLog(nodeId);
            this.urlString = extractionResult.url();
            if (extractionResult.logLines().isEmpty()) {
                recordUsage(UsageEvent.EntryPoint.PIPELINE_GRAPH_NODE, UsageEvent.Result.DISABLED,
                        null, null, startTimeNanos, 0);
                writeJsonResponse(rsp, "warning", "Unknown",
                        "No log output found for the selected Pipeline node.");
                return;
            }

            String errorText = String.join("\n", extractionResult.logLines());
            ErrorExplainer explainer = new ErrorExplainer();
            try {
                ErrorExplanationAction explanation = explainer.explainErrorText(errorText, urlString, run,
                        UsageEvent.EntryPoint.PIPELINE_GRAPH_NODE, false);
                StepErrorExplanationAction cacheAction = getOrCreateStepAction();
                cacheAction.putExplanation(nodeId, explanation);
                run.save();
                writeJsonResponse(rsp, "success", explanation.getProviderName(), explanation.getExplanation());
            } catch (ExplanationException ee) {
                writeJsonResponse(rsp, ee.getLevel(), explainer.getProviderName(), ee.getMessage());
            }
        } catch (Exception e) {
            LOGGER.severe("=== EXPLAIN PIPELINE NODE REQUEST FAILED ===");
            LOGGER.severe("Error explaining pipeline node: " + e.getMessage());
            writeJsonResponse(rsp, "error", "Unknown", "Error: " + e.getMessage());
        }
    }

    /**
     * AJAX endpoint to check build status.
     * Returns JSON with buildingStatus to determine if button should be shown. 0 - SUCCESS, 1 - RUNNING, 2 - FINISHED and FAILURE
     */
    @RequirePOST
    public void doCheckBuildStatus(StaplerRequest2 req, StaplerResponse2 rsp) {
        try {
            run.checkPermission(hudson.model.Item.READ);

            int buildingStatus = 0;
            if (run.isBuilding()) {
                buildingStatus = 1;
            } else if (run.getResult() == Result.FAILURE) {
                buildingStatus = 2;
            }

            rsp.setContentType("application/json");
            rsp.setCharacterEncoding("UTF-8");
            PrintWriter writer = rsp.getWriter();

            String response = String.format("{\"buildingStatus\": %s}", buildingStatus);
            writer.write(response);
            writer.flush();
        } catch (Exception e) {
            LOGGER.severe("Error checking build status: " + e.getMessage());
            rsp.setStatus(500);
        }
    }

    private void writeJsonResponse(StaplerResponse2 rsp, String status, String providerName, String message) throws IOException {
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
     * @param explanation The cached explanation
     * @return The response string with cached indicator
     */
    @VisibleForTesting
    String createCachedResponse(String explanation) {
        return explanation + "\n\n[Note: This is a previously generated explanation. Use the 'Generate New' option to create a new one.]";
    }

    public Run<?, ?> getRun() {
        return run;
    }

    private void recordUsage(UsageEvent.Result result, String providerName, String model,
                             long startTimeNanos, int inputLogLineCount) {
        recordUsage(UsageEvent.EntryPoint.CONSOLE_ACTION, result, providerName, model,
                startTimeNanos, inputLogLineCount);
    }

    private void recordUsage(UsageEvent.EntryPoint entryPoint, UsageEvent.Result result, String providerName,
                             String model, long startTimeNanos, int inputLogLineCount) {
        UsageRecorders.get().record(new UsageEvent(
                System.currentTimeMillis(),
                entryPoint,
                result,
                providerName,
                model,
                Math.max(0L, (System.nanoTime() - startTimeNanos) / 1_000_000L),
                inputLogLineCount,
                false));
    }

    private StepErrorExplanationAction getOrCreateStepAction() {
        StepErrorExplanationAction action = run.getAction(StepErrorExplanationAction.class);
        if (action != null) {
            return action;
        }
        action = new StepErrorExplanationAction();
        run.addAction(action);
        return action;
    }

    private int parseMaxLines(StaplerRequest2 req, int defaultValue) {
        String maxLinesParam = req.getParameter("maxLines");
        if (maxLinesParam == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(maxLinesParam);
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException ignore) {
            return defaultValue;
        }
    }
}
