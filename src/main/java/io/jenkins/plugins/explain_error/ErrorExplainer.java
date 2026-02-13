package io.jenkins.plugins.explain_error;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.ItemGroup;
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
        return explainError(run, listener, logPattern, maxLines, null, null);
    }

    public String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language) {
        return explainError(run, listener, logPattern, maxLines, language, null);
    }

    public String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language, String customContext) {
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

            // Use step-level customContext if provided, otherwise fallback to global
            String effectiveCustomContext = StringUtils.isNotBlank(customContext) ? customContext : GlobalConfigurationImpl.get().getCustomContext();

            // Get AI explanation
            try {
                String explanation = provider.explainError(errorLogs, listener, language, effectiveCustomContext);
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

        // Check if explanation is enabled (folder-level or global)
        if (!isExplanationEnabled(run)) {
            throw new ExplanationException("error", "AI error explanation is disabled.");
        }
        // Resolve provider (folder-level first, then global)
        BaseAIProvider provider = resolveProvider(run);
        if (provider == null) {
            throw new ExplanationException("error", "No AI provider configured.");
        }

        // Get AI explanation with global custom context
        String explanation = provider.explainError(errorText, new LogTaskListener(LOGGER, Level.FINE), null, GlobalConfigurationImpl.get().getCustomContext());
        LOGGER.fine(jobInfo + " AI error explanation succeeded.");
        LOGGER.fine("Explanation length: " + explanation.length());
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
                LOGGER.fine(jobInfo + " Using FOLDER-LEVEL AI provider: " + folderProvider.getProviderName() + ", Model: " + folderProvider.getModel());
                return folderProvider;
            }
        }

        // Fallback to global configuration
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider globalProvider = config.getAiProvider();
        if (globalProvider != null) {
            String jobInfo = run != null ? ("[" + run.getParent().getFullName() + " #" + run.getNumber() + "]") : "[unknown]";
            LOGGER.fine(jobInfo + " Using GLOBAL AI provider: " + globalProvider.getProviderName() + ", Model: " + globalProvider.getModel());
        }
        return globalProvider;
    }

    /**
     * Check if error explanation is enabled.
     * Folder-level configuration takes precedence over global configuration.
     * If no folder-level configuration exists, falls back to global configuration.
     * 
     * @param run the build run to check
     * @return true if explanation is enabled, false otherwise
     */
    private boolean isExplanationEnabled(@CheckForNull Run<?, ?> run) {
        if (run != null) {
            // Check if there's an explicit folder-level property with configured provider
            ExplainErrorFolderProperty folderProperty = findFolderPropertyWithProvider(run.getParent().getParent());
            if (folderProperty != null) {
                // Folder-level provider is configured, use its enableExplanation setting
                boolean folderEnabled = folderProperty.isEnableExplanation();
                if (!folderEnabled) {
                    LOGGER.fine("Error explanation explicitly disabled at folder level for " + run.getParent().getFullName());
                } else {
                    LOGGER.fine("Error explanation enabled at folder level for " + run.getParent().getFullName());
                }
                return folderEnabled;
            } else {
                LOGGER.fine("No folder-level provider found for " + run.getParent().getFullName() + ", falling back to global configuration");
            }
        }

        // No folder-level provider configured, fall back to global
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        boolean globalEnabled = config.isEnableExplanation();
        LOGGER.fine("Global configuration enabled: " + globalEnabled);
        return globalEnabled;
    }

    /**
     * Find folder property with configured provider by walking up the folder hierarchy.
     * Only returns a property if it has an AI provider configured AND explanation is enabled.
     * If a folder has a provider but explanation is disabled, it continues searching parent folders.
     * 
     * @param itemGroup the item group to search from
     * @return the folder property with provider if found and enabled, null otherwise
     */
    @CheckForNull
    private ExplainErrorFolderProperty findFolderPropertyWithProvider(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }

        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);
            
            if (property != null) {
                LOGGER.fine("Found folder property for " + folder.getFullName() + 
                           ", enableExplanation=" + property.isEnableExplanation() + 
                           ", hasProvider=" + (property.getAiProvider() != null));
            }
            
            // Only return property if it has a provider configured AND is enabled
            // If disabled at folder level, continue searching parent folders or fallback to global
            if (property != null && property.getAiProvider() != null && property.isEnableExplanation()) {
                LOGGER.fine("Using folder-level provider from " + folder.getFullName());
                return property;
            }
            
            // Recursively check parent folder
            return findFolderPropertyWithProvider(folder.getParent());
        }

        return null;
    }
}
