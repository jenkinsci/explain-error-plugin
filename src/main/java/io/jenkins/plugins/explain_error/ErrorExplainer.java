package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.LogTaskListener;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Service class responsible for explaining errors using AI.
 */
public class ErrorExplainer {

    private String providerName;
    private static final Logger LOGGER = Logger.getLogger(ErrorExplainer.class.getName());

    public String getProviderName() {
        return providerName;
    }

    public void explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines) {
        String jobInfo = run != null ? ("[" + run.getParent().getFullName() + " #" + run.getNumber() + "]") : "[unknown]";
        try {
            GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

            if (!config.isEnableExplanation()) {
                listener.getLogger().println("AI error explanation is disabled in global configuration.");
                return;
            }

            BaseAIProvider provider = config.getAiProvider();

            // Extract error logs
            String errorLogs = extractErrorLogs(run, logPattern, maxLines);

            // Get AI explanation
            try {
                String explanation = provider.explainError(errorLogs, listener);
                LOGGER.fine(jobInfo + " AI error explanation succeeded.");

                // Store explanation in build action
                ErrorExplanationAction action = new ErrorExplanationAction(explanation, errorLogs, provider.getProviderName());
                run.addOrReplaceAction(action);
            } catch (ExplanationException ee) {
                listener.getLogger().println(ee.getMessage());
            }

            // Explanation is now available on the job page, no need to clutter console output

        } catch (IOException e) {
            LOGGER.severe(jobInfo + " Failed to explain error: " + e.getMessage());
            listener.getLogger().println(jobInfo + " Failed to explain error: " + e.getMessage());
        }
    }

    private String extractErrorLogs(Run<?, ?> run, String logPattern, int maxLines) throws IOException {
        List<String> logLines = run.getLog(maxLines);

        if (StringUtils.isBlank(logPattern)) {
            // Return last few lines if no pattern specified
            return String.join("\n", logLines);
        }

        Pattern pattern = Pattern.compile(logPattern, Pattern.CASE_INSENSITIVE);
        StringBuilder errorLogs = new StringBuilder();

        for (String line : logLines) {
            if (pattern.matcher(line).find()) {
                errorLogs.append(line).append("\n");
            }
        }

        return errorLogs.toString();
    }

    /**
     * Explains error text directly without extracting from logs.
     * Used for console output error explanation.
     */
    public ErrorExplanationAction explainErrorText(String errorText, @NonNull  Run<?, ?> run) throws IOException, ExplanationException {
        String jobInfo ="[" + run.getParent().getFullName() + " #" + run.getNumber() + "]";

        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        BaseAIProvider provider = config.getAiProvider();

        // Get AI explanation
        String explanation = provider.explainError(errorText, new LogTaskListener(LOGGER, Level.FINE));
        LOGGER.fine(jobInfo + " AI error explanation succeeded.");
        LOGGER.finer("Explanation length: " + explanation.length());
        this.providerName = provider.getProviderName();
        ErrorExplanationAction action = new ErrorExplanationAction(explanation, errorText, provider.getProviderName());
        run.addOrReplaceAction(action);
        run.save();

        return action;
    }
}
