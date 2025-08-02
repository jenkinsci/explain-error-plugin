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

    public Run<?, ?> getRun() {
        return run;
    }
}
