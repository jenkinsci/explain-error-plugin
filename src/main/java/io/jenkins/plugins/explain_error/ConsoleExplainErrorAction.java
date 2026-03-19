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
        try {
            run.checkPermission(hudson.model.Item.READ);

            GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
            if (!config.isEnableExplanation()) {
                writeJsonResponse(rsp, "warning", "Unkown" , "AI error explanation is disabled in global configuration.");
                return;
            }

            // Check if user wants to force a new explanation
            boolean forceNew = "true".equals(req.getParameter("forceNew"));

            // Check if an explanation already exists
            ErrorExplanationAction existingAction = run.getAction(ErrorExplanationAction.class);
            if (!forceNew && existingAction != null && existingAction.hasValidExplanation()) {
                // Return existing explanation with a flag indicating it's cached
                writeJsonResponse(rsp, "success", existingAction.getProviderName(), createCachedResponse(existingAction.getExplanation()));
                return;
            }

            // Optionally allow maxLines as a parameter, default to 200
            int maxLines = 200;
            String maxLinesParam = req.getParameter("maxLines");
            if (maxLinesParam != null) {
                try { maxLines = Integer.parseInt(maxLinesParam); } catch (NumberFormatException ignore) {}
            }

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
            writeJsonResponse(rsp, "error", "Unkown" , "Error: " + e.getMessage());
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

            Integer buildingStatus = run.isBuilding() ? 1 : 0;

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
}
