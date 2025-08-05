package io.jenkins.plugins.explain_error;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Action;
import hudson.model.Run;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Action to add "Explain Error" functionality to console output pages.
 * This action needs to be manually added to builds.
 */
public class ConsoleExplainErrorAction implements Action {

    private static final Logger LOGGER = Logger.getLogger(ConsoleExplainErrorAction.class.getName());

    private final Run<?, ?> run;

    public ConsoleExplainErrorAction(Run<?, ?> run) {
        this.run = run;
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
    public void doExplainConsoleError(StaplerRequest2 req, StaplerResponse2 rsp) throws ServletException, IOException {
        try {
            run.checkPermission(hudson.model.Item.READ);

            // Check if user wants to force a new explanation
            boolean forceNew = "true".equals(req.getParameter("forceNew"));

            // Check if an explanation already exists
            ErrorExplanationAction existingAction = run.getAction(ErrorExplanationAction.class);
            if (!forceNew && existingAction != null && existingAction.hasValidExplanation()) {
                // Return existing explanation with a flag indicating it's cached
                writeJsonResponse(rsp, createCachedResponse(existingAction.getExplanation()));
                return;
            }

            // Optionally allow maxLines as a parameter, default to 200
            int maxLines = 200;
            String maxLinesParam = req.getParameter("maxLines");
            if (maxLinesParam != null) {
                try { maxLines = Integer.parseInt(maxLinesParam); } catch (NumberFormatException ignore) {}
            }

            // Fetch the last N lines of the log
            java.util.List<String> logLines = run.getLog(maxLines);
            String errorText = String.join("\n", logLines);

            ErrorExplainer explainer = new ErrorExplainer();
            String explanation = explainer.explainErrorText(errorText, run);

            if (explanation != null && !explanation.trim().isEmpty()) {
                // Save the explanation as a build action (like the sidebar functionality)
                ErrorExplanationAction action = new ErrorExplanationAction(explanation, errorText);
                run.addOrReplaceAction(action);

                writeJsonResponse(rsp, explanation);
            } else {
                writeJsonResponse(rsp, "Error: Could not generate explanation. Please check your AI API configuration.");
            }
        } catch (Exception e) {
            LOGGER.severe("=== EXPLAIN ERROR REQUEST FAILED ===");
            LOGGER.severe("Error explaining console error: " + e.getMessage());
            writeJsonResponse(rsp, "Error: " + e.getMessage());
        }
    }

    /**
     * AJAX endpoint to check if an explanation already exists.
     * Returns JSON with hasExplanation boolean and timestamp if it exists.
     */
    @RequirePOST
    public void doCheckExistingExplanation(StaplerRequest2 req, StaplerResponse2 rsp) throws ServletException, IOException {
        try {
            run.checkPermission(hudson.model.Item.READ);

            ErrorExplanationAction existingAction = run.getAction(ErrorExplanationAction.class);
            boolean hasExplanation = existingAction != null && existingAction.hasValidExplanation();

            rsp.setContentType("application/json");
            rsp.setCharacterEncoding("UTF-8");
            PrintWriter writer = rsp.getWriter();

            if (hasExplanation) {
                String response = String.format(
                    "{\"hasExplanation\": true, \"timestamp\": \"%s\"}",
                    existingAction.getFormattedTimestamp()
                );
                writer.write(response);
            } else {
                writer.write("{\"hasExplanation\": false}");
            }

            writer.flush();
        } catch (Exception e) {
            LOGGER.severe("Error checking existing explanation: " + e.getMessage());
            rsp.setStatus(500);
        }
    }

    /**
     * AJAX endpoint to check build status.
     * Returns JSON with isBuilding boolean to determine if button should be shown.
     */
    @RequirePOST
    public void doCheckBuildStatus(StaplerRequest2 req, StaplerResponse2 rsp) throws ServletException, IOException {
        try {
            run.checkPermission(hudson.model.Item.READ);
            
            boolean isBuilding = run.isBuilding();
            
            rsp.setContentType("application/json");
            rsp.setCharacterEncoding("UTF-8");
            PrintWriter writer = rsp.getWriter();
            
            String response = String.format("{\"isBuilding\": %s}", isBuilding);
            writer.write(response);
            writer.flush();
        } catch (Exception e) {
            LOGGER.severe("Error checking build status: " + e.getMessage());
            rsp.setStatus(500);
        }
    }

    private void writeJsonResponse(StaplerResponse2 rsp, String message) throws IOException {
        rsp.setContentType("application/json");
        rsp.setCharacterEncoding("UTF-8");
        PrintWriter writer = rsp.getWriter();

        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonResponse = mapper.writeValueAsString(message);
            writer.write(jsonResponse);
        } catch (Exception e) {
            // Fallback to simple JSON string
            writer.write("\"" + message.replace("\"", "\\\"") + "\"");
        }
        writer.flush();
    }

    /**
     * Create a response indicating this is a cached result.
     * @param explanation The cached explanation
     * @return The response string with cached indicator
     */
    private String createCachedResponse(String explanation) {
        return explanation + "\n\n[Note: This is a previously generated explanation. Use the 'Generate New' option to create a new one.]";
    }

    public Run<?, ?> getRun() {
        return run;
    }
}
