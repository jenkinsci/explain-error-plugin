package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
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
    private String urlString;

    private static final Logger LOGGER = Logger.getLogger(ErrorExplainer.class.getName());

    public String getProviderName() {
        return providerName;
    }

    public String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines) {
        return explainError(run, listener, logPattern, maxLines, null);
    }

    public String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language) {
        String jobInfo = run != null ? ("[" + run.getParent().getFullName() + " #" + run.getNumber() + "]") : "[unknown]";
        try {
            // Check if explanation is enabled (folder-level or global)
            if (!isExplanationEnabled(run)) {
                listener.getLogger().println("AI error explanation is disabled.");
                return null;
            }

            // Resolve provider (folder-level first, then global)
            BaseAIProvider provider = resolveProvider(run);
            if (provider == null) {
                listener.getLogger().println("No AI provider configured.");
                return null;
            }

            // Extract error logs
            String errorLogs = extractErrorLogs(run, logPattern, maxLines);

            // Get AI explanation
            try {
                String explanation = provider.explainError(errorLogs, listener, language);
                LOGGER.fine(jobInfo + " AI error explanation succeeded.");

                // Store explanation in build action
                ErrorExplanationAction action = new ErrorExplanationAction(explanation, urlString, errorLogs, provider.getProviderName());
                run.addOrReplaceAction(action);
                
                return explanation;
            } catch (ExplanationException ee) {
                listener.getLogger().println(ee.getMessage());
                return null;
            }

            // Explanation is now available on the job page, no need to clutter console output

        } catch (IOException e) {
            LOGGER.severe(jobInfo + " Failed to explain error: " + e.getMessage());
            listener.getLogger().println(jobInfo + " Failed to explain error: " + e.getMessage());
            return null;
        }
    }

    private String extractErrorLogs(Run<?, ?> run, String logPattern, int maxLines) throws IOException {
        PipelineLogExtractor logExtractor = new PipelineLogExtractor(run, maxLines);
        List<String> logLines =  logExtractor.getFailedStepLog();
        this.urlString = logExtractor.getUrl();

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
    public ErrorExplanationAction explainErrorText(String errorText, String url, @NonNull  Run<?, ?> run) throws IOException, ExplanationException {
        String jobInfo ="[" + run.getParent().getFullName() + " #" + run.getNumber() + "]";

        // Resolve provider (folder-level first, then global)
        BaseAIProvider provider = resolveProvider(run);
        if (provider == null) {
            throw new ExplanationException("error", "No AI provider configured.");
        }

        // Get AI explanation
        String explanation = provider.explainError(errorText, new LogTaskListener(LOGGER, Level.FINE));
        LOGGER.fine(jobInfo + " AI error explanation succeeded.");
        LOGGER.finer("Explanation length: " + explanation.length());
        this.providerName = provider.getProviderName();
        ErrorExplanationAction action = new ErrorExplanationAction(explanation, url, errorText, provider.getProviderName());
        run.addOrReplaceAction(action);
        run.save();

        return action;
    }

    /**
     * Resolve the AI provider to use for error explanation.
     * Resolution order:
     * 1. Folder-level configuration (if defined)
     * 2. Global configuration (fallback)
     * 
     * @param run the build run to resolve configuration for
     * @return the resolved AI provider, or null if not configured
     */
    @CheckForNull
    private BaseAIProvider resolveProvider(@CheckForNull Run<?, ?> run) {
        if (run != null) {
            // Try folder-level configuration first
            BaseAIProvider folderProvider = ExplainErrorFolderProperty.findFolderProvider(run.getParent().getParent());
            if (folderProvider != null) {
                String jobInfo = "[" + run.getParent().getFullName() + " #" + run.getNumber() + "]";
                LOGGER.info(jobInfo + " Using FOLDER-LEVEL AI provider: " + folderProvider.getProviderName() + ", Model: " + folderProvider.getModel());
                return folderProvider;
            }
        }

        // Fallback to global configuration
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider globalProvider = config.getAiProvider();
        if (globalProvider != null) {
            String jobInfo = run != null ? ("[" + run.getParent().getFullName() + " #" + run.getNumber() + "]") : "[unknown]";
            LOGGER.info(jobInfo + " Using GLOBAL AI provider: " + globalProvider.getProviderName() + ", Model: " + globalProvider.getModel());
        }
        return globalProvider;
    }

    /**
     * Check if error explanation is enabled.
     * Checks both folder-level and global configuration.
     * 
     * @param run the build run to check
     * @return true if explanation is enabled, false otherwise
     */
    private boolean isExplanationEnabled(@CheckForNull Run<?, ?> run) {
        // Check folder-level setting
        if (run != null) {
            boolean folderEnabled = ExplainErrorFolderProperty.isFolderExplanationEnabled(run.getParent().getParent());
            if (!folderEnabled) {
                LOGGER.fine("Error explanation disabled at folder level for " + run.getParent().getFullName());
                return false;
            }
        }

        // Check global setting
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        return config.isEnableExplanation();
    }
}
