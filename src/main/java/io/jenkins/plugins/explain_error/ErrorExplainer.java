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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;

/**
 * Service class responsible for explaining errors using AI.
 */
public class ErrorExplainer {
    static final String DOWNSTREAM_SECTION_START = "### Downstream Job: ";
    static final String DOWNSTREAM_SECTION_END = "### END OF DOWNSTREAM JOB: ";
    private static final String CONSOLE_PREFIX = "[explain-error] ";

    private String providerName;
    private String urlString;
    private final UsageRecorder usageRecorder;

    private static final Logger LOGGER = Logger.getLogger(ErrorExplainer.class.getName());

    public ErrorExplainer() {
        this(UsageRecorders.get());
    }

    ErrorExplainer(UsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
    }

    public String getProviderName() {
        return providerName;
    }

    public String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines) {
        return explainError(run, listener, logPattern, maxLines, null, null, false, null, null);
    }

    public String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language) {
        return explainError(run, listener, logPattern, maxLines, language, null, false, null, null);
    }

    public String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language, String customContext) {
        return explainError(run, listener, logPattern, maxLines, language, customContext, false, null, null);
    }

    public String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language,
                               String customContext, boolean collectDownstreamLogs, String downstreamJobPattern) {
        return explainError(run, listener, logPattern, maxLines, language, customContext,
                collectDownstreamLogs, downstreamJobPattern, null);
    }

    String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language,
                        String customContext, boolean collectDownstreamLogs, String downstreamJobPattern,
                        Authentication authentication) {
        return explainError(run, listener, logPattern, maxLines, language, customContext,
                collectDownstreamLogs, downstreamJobPattern, authentication, UsageEvent.EntryPoint.PIPELINE_STEP);
    }

    String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language,
                        String customContext, boolean collectDownstreamLogs, String downstreamJobPattern,
                        Authentication authentication, UsageEvent.EntryPoint entryPoint) {
        String jobInfo = run != null ? ("[" + run.getParent().getFullName() + " #" + run.getNumber() + "]") : "[unknown]";
        long startTimeNanos = System.nanoTime();
        ProviderResolution providerResolution = resolveProvider(run);
        BaseAIProvider provider = providerResolution.provider();
        int inputLogLineCount = 0;
        try {
            logToConsole(listener, "Starting explanation for " + jobInfo + ".");

            // Check if explanation is enabled (folder-level or global)
            if (!isExplanationEnabled(run)) {
                logToConsole(listener, "Explanation is disabled by configuration.");
                recordUsage(entryPoint, UsageEvent.Result.DISABLED, provider, startTimeNanos, 0,
                        collectDownstreamLogs);
                return null;
            }

            if (provider == null) {
                logToConsole(listener, "No AI provider is configured.");
                recordUsage(entryPoint, UsageEvent.Result.MISCONFIGURED, null, startTimeNanos, 0,
                        collectDownstreamLogs);
                return null;
            }
            this.providerName = provider.getProviderName();
            logToConsole(listener, "Explanation is enabled via " + providerResolution.sourceLabel()
                    + " configuration.");
            logToConsole(listener, "Using provider " + provider.getProviderName() + ", model " + provider.getModel()
                    + ".");

            if (provider.isNotValid(listener)) {
                logToConsole(listener, "Provider configuration is invalid.");
                recordUsage(entryPoint, UsageEvent.Result.MISCONFIGURED, provider, startTimeNanos, 0,
                        collectDownstreamLogs);
                return null;
            }

            // Extract error logs
            logToConsole(listener, "Extracting failure logs.");
            PipelineLogExtractor.ExtractionResult extractionResult = extractErrorLogs(run, maxLines,
                    collectDownstreamLogs, downstreamJobPattern, authentication);
            String errorLogs = filterErrorLogs(extractionResult.logLines(), logPattern);
            inputLogLineCount = countLines(errorLogs);
            logExtractionSummary(listener, extractionResult, maxLines);

            // Use step-level customContext if provided, otherwise fallback to global
            String effectiveCustomContext = StringUtils.isNotBlank(customContext) ? customContext : GlobalConfigurationImpl.get().getCustomContext();
            logToConsole(listener, "Custom context source: " + resolveCustomContextSource(customContext) + ".");

            // Get AI explanation
            try {
                logToConsole(listener, "Sending AI request.");
                String explanation = provider.explainError(errorLogs, listener, language, effectiveCustomContext);
                LOGGER.fine(jobInfo + " AI error explanation succeeded.");
                logToConsole(listener, "AI request completed successfully.");

                // Store explanation in build action
                ErrorExplanationAction action = new ErrorExplanationAction(explanation, urlString, errorLogs,
                        provider.getProviderName(), provider.getModel(), inputLogLineCount);
                run.addOrReplaceAction(action);
                logToConsole(listener, "Explanation saved to the build.");
                recordUsage(entryPoint, UsageEvent.Result.SUCCESS, provider, startTimeNanos, inputLogLineCount,
                        collectDownstreamLogs);

                return explanation;
            } catch (ExplanationException ee) {
                logToConsole(listener, "AI request failed: " + ee.getMessage());
                recordUsage(entryPoint, UsageEvent.Result.PROVIDER_ERROR, provider, startTimeNanos,
                        inputLogLineCount, collectDownstreamLogs);
                return null;
            }

            // Explanation is now available on the job page, no need to clutter console output

        } catch (IOException e) {
            LOGGER.severe(jobInfo + " Failed to explain error: " + e.getMessage());
            logToConsole(listener, "Failed to explain error: " + e.getMessage());
            return null;
        }
    }

    private PipelineLogExtractor.ExtractionResult extractErrorLogs(Run<?, ?> run, int maxLines,
                                                                   boolean collectDownstreamLogs,
                                                                   String downstreamJobPattern,
                                                                   Authentication authentication)
            throws IOException {
        PipelineLogExtractor logExtractor = new PipelineLogExtractor(run, maxLines, authentication,
                collectDownstreamLogs, downstreamJobPattern);
        PipelineLogExtractor.ExtractionResult result = logExtractor.extractFailedStepLog();
        this.urlString = result.url();
        return result;
    }

    String filterErrorLogs(List<String> logLines, String logPattern) {
        if (StringUtils.isBlank(logPattern)) {
            return String.join("\n", logLines);
        }

        Pattern pattern = Pattern.compile(logPattern, Pattern.CASE_INSENSITIVE);
        List<String> filteredLines = new ArrayList<>();
        boolean inDownstreamSection = false;

        for (String line : logLines) {
            if (isDownstreamSectionStart(line)) {
                inDownstreamSection = true;
            }

            if (inDownstreamSection || pattern.matcher(line).find()) {
                filteredLines.add(line);
            }

            if (inDownstreamSection && isDownstreamSectionEnd(line)) {
                inDownstreamSection = false;
            }
        }

        return String.join("\n", filteredLines);
    }

    private boolean isDownstreamSectionStart(String line) {
        return line != null && line.startsWith(DOWNSTREAM_SECTION_START);
    }

    private boolean isDownstreamSectionEnd(String line) {
        return line != null && line.startsWith(DOWNSTREAM_SECTION_END);
    }

    /**
     * Explains error text directly without extracting from logs.
     * Used for console output error explanation.
     */
    public ErrorExplanationAction explainErrorText(String errorText, String url, @NonNull  Run<?, ?> run) throws IOException, ExplanationException {
        return explainErrorText(errorText, url, run, UsageEvent.EntryPoint.CONSOLE_ACTION);
    }

    ErrorExplanationAction explainErrorText(String errorText, String url, @NonNull Run<?, ?> run,
                                            UsageEvent.EntryPoint entryPoint)
            throws IOException, ExplanationException {
        String jobInfo ="[" + run.getParent().getFullName() + " #" + run.getNumber() + "]";
        long startTimeNanos = System.nanoTime();
        int inputLogLineCount = countLines(errorText);
        ProviderResolution providerResolution = resolveProvider(run);
        BaseAIProvider provider = providerResolution.provider();

        // Check if explanation is enabled (folder-level or global)
        if (!isExplanationEnabled(run)) {
            recordUsage(entryPoint, UsageEvent.Result.DISABLED, provider, startTimeNanos,
                    inputLogLineCount, false);
            throw new ExplanationException("error", "AI error explanation is disabled.");
        }
        if (provider == null) {
            recordUsage(entryPoint, UsageEvent.Result.MISCONFIGURED, null, startTimeNanos, inputLogLineCount,
                    false);
            throw new ExplanationException("error", "No AI provider configured.");
        }
        this.providerName = provider.getProviderName();

        if (provider.isNotValid(null)) {
            recordUsage(entryPoint, UsageEvent.Result.MISCONFIGURED, provider, startTimeNanos,
                    inputLogLineCount, false);
            throw new ExplanationException("error", "The provider is not properly configured.");
        }

        try {
            // Get AI explanation with global custom context
            String explanation = provider.explainError(errorText, new LogTaskListener(LOGGER, Level.FINE), null,
                    GlobalConfigurationImpl.get().getCustomContext());
            LOGGER.fine(jobInfo + " AI error explanation succeeded.");
            LOGGER.fine("Explanation length: " + explanation.length());
            ErrorExplanationAction action = new ErrorExplanationAction(explanation, url, errorText,
                    provider.getProviderName(), provider.getModel(), inputLogLineCount);
            run.addOrReplaceAction(action);
            run.save();
            recordUsage(entryPoint, UsageEvent.Result.SUCCESS, provider, startTimeNanos, inputLogLineCount, false);

            return action;
        } catch (ExplanationException e) {
            recordUsage(entryPoint, UsageEvent.Result.PROVIDER_ERROR, provider, startTimeNanos,
                    inputLogLineCount, false);
            throw e;
        }
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
    private ProviderResolution resolveProvider(@CheckForNull Run<?, ?> run) {
        if (run != null) {
            // Try folder-level configuration first
            BaseAIProvider folderProvider = ExplainErrorFolderProperty.findFolderProvider(run.getParent().getParent());
            if (folderProvider != null) {
                String jobInfo = "[" + run.getParent().getFullName() + " #" + run.getNumber() + "]";
                LOGGER.fine(jobInfo + " Using FOLDER-LEVEL AI provider: " + folderProvider.getProviderName() + ", Model: " + folderProvider.getModel());
                return new ProviderResolution(folderProvider, "folder");
            }
        }

        // Fallback to global configuration
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider globalProvider = config.getAiProvider();
        if (globalProvider != null) {
            String jobInfo = run != null ? ("[" + run.getParent().getFullName() + " #" + run.getNumber() + "]") : "[unknown]";
            LOGGER.fine(jobInfo + " Using GLOBAL AI provider: " + globalProvider.getProviderName() + ", Model: " + globalProvider.getModel());
        }
        return new ProviderResolution(globalProvider, "global");
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

    private void logToConsole(TaskListener listener, String message) {
        listener.getLogger().println(CONSOLE_PREFIX + message);
    }

    private void logExtractionSummary(TaskListener listener, PipelineLogExtractor.ExtractionResult result,
                                      int maxLines) {
        if (result.fallbackToBuildLog()) {
            logToConsole(listener, "No failing step log found; using the last " + maxLines + " console lines.");
        } else if (result.foundFailingNode()) {
            logToConsole(listener, "Extracted " + result.getExtractedLineCount() + " log lines from the failing step.");
        } else {
            logToConsole(listener, "Extracted " + result.getExtractedLineCount() + " log lines.");
        }

        if (!result.downstreamCollectionEnabled()) {
            logToConsole(listener, "Downstream log collection is disabled.");
            return;
        }

        logToConsole(listener, "Downstream log collection enabled; matched "
                + result.downstreamMatchedCount() + " builds, reused "
                + result.downstreamReusedExplanationCount() + " existing explanations, skipped "
                + result.downstreamPermissionSkippedCount() + " due to permissions.");
    }

    private String resolveCustomContextSource(String stepCustomContext) {
        if (StringUtils.isNotBlank(stepCustomContext)) {
            return "step";
        }
        if (StringUtils.isNotBlank(GlobalConfigurationImpl.get().getCustomContext())) {
            return "global";
        }
        return "none";
    }

    private void recordUsage(UsageEvent.EntryPoint entryPoint, UsageEvent.Result result,
                             @CheckForNull BaseAIProvider provider, long startTimeNanos,
                             int inputLogLineCount, boolean downstreamLogsCollected) {
        usageRecorder.record(new UsageEvent(
                System.currentTimeMillis(),
                entryPoint,
                result,
                provider != null ? provider.getProviderName() : null,
                provider != null ? provider.getModel() : null,
                nanosToMillis(startTimeNanos),
                inputLogLineCount,
                downstreamLogsCollected));
    }

    private long nanosToMillis(long startTimeNanos) {
        return Math.max(0L, (System.nanoTime() - startTimeNanos) / 1_000_000L);
    }

    static int countLines(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }

        int lineCount = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineCount++;
            }
        }
        return lineCount;
    }

    private record ProviderResolution(@CheckForNull BaseAIProvider provider, String sourceLabel) {
    }
}
