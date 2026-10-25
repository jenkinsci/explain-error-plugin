package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.console.HyperlinkNote;
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
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;

/**
 * Service class responsible for explaining errors using AI.
 */
public class ErrorExplainer {
    private static final String CONSOLE_PREFIX = "[explain-error] ";
    static final String REUSE_LOOKBACK_PROPERTY = ErrorExplainer.class.getName() + ".reuseLookbackBuilds";

    private String providerName;
    private String urlString;
    private String lastErrorLogs;
    private JenkinsLogAnalysis lastAnalysis;
    private boolean providerCalled;
    private final UsageRecorder usageRecorder;

    private static final Logger LOGGER = Logger.getLogger(ErrorExplainer.class.getName());

    /** Said when nothing is sent because something was masked and the configuration says not to send it. */
    static final String BLOCKED = "Nothing was sent to the AI provider: the configuration says not to send a log"
            + " in which something was masked.";

    /** What the Explain Error button gets back when explanation is turned off for the job. */
    static final String TURNED_OFF = "AI error explanation is turned off for this job.";

    public ErrorExplainer() {
        this(UsageRecorders.get());
    }

    ErrorExplainer(UsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
    }

    public String getProviderName() {
        return providerName;
    }

    /**
     * Returns the error logs extracted during the last call to {@link #explainError}.
     * Returns {@code null} if {@code explainError} has not been called yet.
     */
    public String getLastErrorLogs() {
        return lastErrorLogs;
    }

    /**
     * Whether the last call to {@link #explainError} got as far as asking the AI provider,
     * whatever the provider then answered. A request that was turned away before that
     * (disabled, misconfigured, over quota) did not cost a provider call.
     */
    boolean wasProviderCalled() {
        return providerCalled;
    }

    /**
     * Returns the structured analysis produced by the last successful call to {@link #explainError},
     * or {@code null} if no explanation has been generated.
     */
    @CheckForNull
    public JenkinsLogAnalysis getLastAnalysis() {
        return lastAnalysis;
    }

    /**
     * Returns the resolved AI provider for the given run (folder-level first, then global).
     * Delegates to the private {@link #resolveProvider(Run)} method.
     *
     * @param run the build run to resolve the provider for
     * @return the resolved AI provider, or {@code null} if none is configured
     */
    @CheckForNull
    public BaseAIProvider getResolvedProvider(@CheckForNull Run<?, ?> run) {
        return resolveProvider(run).provider();
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
                collectDownstreamLogs, downstreamJobPattern, authentication, null, UsageEvent.EntryPoint.PIPELINE_STEP);
    }

    String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language,
                        String customContext, boolean collectDownstreamLogs, String downstreamJobPattern,
                        Authentication authentication, @CheckForNull Double stepTemperature) {
        return explainError(run, listener, logPattern, maxLines, language, customContext,
                collectDownstreamLogs, downstreamJobPattern, authentication, stepTemperature, UsageEvent.EntryPoint.PIPELINE_STEP);
    }

    String explainError(Run<?, ?> run, TaskListener listener, String logPattern, int maxLines, String language,
                        String customContext, boolean collectDownstreamLogs, String downstreamJobPattern,
                        Authentication authentication, @CheckForNull Double stepTemperature,
                        UsageEvent.EntryPoint entryPoint) {
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

            if (provider.isNotValid(listener, run != null ? run.getParent() : null, null)) {
                logToConsole(listener, "Provider configuration is invalid.");
                recordUsage(entryPoint, UsageEvent.Result.MISCONFIGURED, provider, startTimeNanos, 0,
                        collectDownstreamLogs);
                return null;
            }

            // Extract error logs
            logToConsole(listener, "Extracting failure logs.");
            Pattern effectiveLogPattern = resolveEffectiveLogPattern(listener, logPattern);
            PipelineLogExtractor.ExtractionResult extractionResult = extractErrorLogs(run, maxLines,
                    collectDownstreamLogs, downstreamJobPattern, authentication, effectiveLogPattern);
            String errorLogs = String.join("\n", extractionResult.logLines());
            logExtractionSummary(listener, extractionResult, maxLines);

            // Resolve language: step → folder → global → null (provider defaults to "English")
            String effectiveLanguage = resolveEffectiveLanguage(run, language);
            logToConsole(listener, "Language: " + (effectiveLanguage != null ? effectiveLanguage : "default (English)") + ".");

            // Resolve custom context: step → folder → global
            String effectiveCustomContext = resolveEffectiveCustomContext(run, customContext);
            logToConsole(listener, "Custom context source: " + resolveCustomContextSource(run, customContext) + ".");

            // Resolve temperature: step → folder → global → null (provider defaults apply)
            Double effectiveTemperature = resolveEffectiveTemperature(run, stepTemperature);
            logToConsole(listener, "Temperature: " + (effectiveTemperature != null ? effectiveTemperature : "unset (provider default)") + ".");

            // Secrets are masked before the text is compared, kept or sent
            LogRedactor redactor = LogRedactor.fromConfiguration();
            errorLogs = redactor.redact(errorLogs);
            effectiveCustomContext = redactor.redact(effectiveCustomContext);
            int redactedCount = redactor.count();
            if (redactedCount > 0) {
                logToConsole(listener, describeRedaction(redactedCount));
                if (GlobalConfigurationImpl.get().isBlockWhenRedacted()) {
                    logToConsole(listener, BLOCKED);
                    recordUsage(entryPoint, UsageEvent.Result.BLOCKED, provider, startTimeNanos, 0,
                            collectDownstreamLogs);
                    return null;
                }
            }

            // An automatic explanation reuses the automatic explanation of an earlier build that failed
            // the same way. This comes before the quota: an explanation that is reused costs no provider
            // call. The step and the console button neither reuse nor leave a fingerprint to be matched.
            String fingerprint = null;
            if (entryPoint == UsageEvent.EntryPoint.RUN_LISTENER && run != null) {
                fingerprint = FailureFingerprint.of(errorLogs, effectiveLanguage, effectiveCustomContext,
                        provider.getProviderName(), provider.getModel(),
                        effectiveTemperature != null ? effectiveTemperature.toString() : null);
                ErrorExplanationAction reused = reuseMatchingExplanation(run, fingerprint, errorLogs);
                if (reused != null) {
                    reused.setRedactedCount(redactedCount);
                    run.addOrReplaceAction(reused);
                    logToConsole(listener, "Build #" + reused.getReusedFromBuild()
                            + " failed the same way; reusing its explanation.");
                    recordUsage(entryPoint, UsageEvent.Result.CACHE_HIT, provider, startTimeNanos,
                            reused.getInputLogLineCount(), collectDownstreamLogs);
                    return reused.getExplanation();
                }
            }

            // Check quota before making a real provider call (folder-level overrides global)
            QuotaCheckResult quotaCheck = tryAcquireQuota(run);
            if (!quotaCheck.allowed()) {
                logToConsole(listener, quotaCheck.rejectionMessage());
                recordUsage(entryPoint, UsageEvent.Result.QUOTA_REJECTED, provider, startTimeNanos, 0,
                        collectDownstreamLogs);
                return null;
            }
            this.lastErrorLogs = errorLogs;
            inputLogLineCount = countLines(errorLogs);

            // Get AI explanation
            try {
                logToConsole(listener, "Sending AI request.");
                LOGGER.fine(describeRequest(jobInfo, provider, inputLogLineCount, redactedCount));
                this.providerCalled = true;
                JenkinsLogAnalysis analysis = provider.analyzeError(errorLogs, listener, effectiveLanguage,
                        effectiveCustomContext, run != null ? run.getParent() : null, null, effectiveTemperature);
                this.lastAnalysis = analysis;
                String explanation = analysis.toText(ExplanationLabels.forLanguage(effectiveLanguage));
                LOGGER.fine(jobInfo + " AI error explanation succeeded.");
                logToConsole(listener, "AI request completed successfully.");

                if (run == null) {
                    logToConsole(listener, "Explanation generated, but no build context was available to save it.");
                    recordUsage(entryPoint, UsageEvent.Result.SUCCESS, provider, startTimeNanos, inputLogLineCount,
                        collectDownstreamLogs);
                    return explanation;
                }

                // Store explanation in build action
                ErrorExplanationAction action = new ErrorExplanationAction(explanation, urlString, errorLogs,
                        provider.getProviderName(), provider.getModel(), inputLogLineCount);
                action.setStructuredData(analysis);
                action.setLanguage(effectiveLanguage);
                action.setFailureFingerprint(fingerprint);
                action.setRedactedCount(redactedCount);
                run.addOrReplaceAction(action);
                logToConsole(listener, buildSavedExplanationMessage(run, action));
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

    /** The console line that says how much was masked. */
    static String describeRedaction(int count) {
        return "Masked " + count + (count == 1 ? " secret" : " secrets") + " in what is sent to the AI provider.";
    }

    /** The system log line that records a request: what was sent, to which model, and how much was masked. */
    private static String describeRequest(String jobInfo, BaseAIProvider provider, int lineCount,
                                          int redactedCount) {
        return jobInfo + " Sending " + lineCount + " log lines to " + provider.getProviderName() + " ("
                + provider.getModel() + "); " + redactedCount + " masked.";
    }

    private PipelineLogExtractor.ExtractionResult extractErrorLogs(Run<?, ?> run, int maxLines,
                                                                   boolean collectDownstreamLogs,
                                                                   String downstreamJobPattern,
                                                                   Authentication authentication,
                                                                   @CheckForNull Pattern logPattern)
            throws IOException {
        PipelineLogExtractor logExtractor = new PipelineLogExtractor(run, maxLines, authentication,
                collectDownstreamLogs, downstreamJobPattern);
        logExtractor.setLogPattern(logPattern);
        PipelineLogExtractor.ExtractionResult result = logExtractor.extractFailedStepLog();
        this.urlString = result.url();
        return result;
    }

    /**
     * How many earlier builds are searched for an explanation that can be reused. Read on every
     * request, so that {@code 0} switches reuse off without a restart.
     */
    static int reuseLookbackBuilds() {
        return Math.max(0, SystemProperties.getInteger(REUSE_LOOKBACK_PROPERTY, 10));
    }

    /**
     * Looks for the most recent earlier build of the same job that has an explanation and, if it
     * failed the same way, returns a copy of that explanation for {@code run}.
     *
     * @return the reused explanation, or {@code null} if the most recent explained build failed
     *         differently, was explained without a fingerprint (by hand, or by an older version
     *         of the plugin), or none of the builds looked at was explained
     */
    @CheckForNull
    private ErrorExplanationAction reuseMatchingExplanation(Run<?, ?> run, String fingerprint, String errorLogs) {
        int lookback = reuseLookbackBuilds();
        Run<?, ?> previous = run.getPreviousBuild();
        for (int i = 0; previous != null && i < lookback; i++, previous = previous.getPreviousBuild()) {
            ErrorExplanationAction action = previous.getAction(ErrorExplanationAction.class);
            if (action == null) {
                continue;
            }
            if (action.hasValidExplanation() && fingerprint.equals(action.getFailureFingerprint())) {
                return action.reuseFor(previous.getNumber(), urlString, errorLogs);
            }
            return null;
        }
        return null;
    }

    /**
     * The log pattern of the step, or else the global one. A pattern that is not a regular expression is
     * left out, and the console says so, rather than failing the explanation.
     */
    @CheckForNull
    private Pattern resolveEffectiveLogPattern(TaskListener listener, @CheckForNull String stepLogPattern) {
        String regex = StringUtils.isNotBlank(stepLogPattern) ? stepLogPattern
                : GlobalConfigurationImpl.get().getLogPattern();
        try {
            return PipelineLogExtractor.compileLogPattern(regex);
        } catch (PatternSyntaxException e) {
            logToConsole(listener, "Log pattern is not a valid regular expression, so it is not used: "
                    + e.getDescription() + ".");
            return null;
        }
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

        if (provider.isNotValid(null, run.getParent(), null)) {
            recordUsage(entryPoint, UsageEvent.Result.MISCONFIGURED, provider, startTimeNanos,
                    inputLogLineCount, false);
            throw new ExplanationException("error", "The provider is not properly configured.");
        }

        // Secrets are masked before anything is sent; with nothing to be sent, no quota is used
        LogRedactor redactor = LogRedactor.fromConfiguration();
        String text = redactor.redact(errorText);
        String effectiveCustomContext = redactor.redact(resolveEffectiveCustomContext(run, null));
        int redactedCount = redactor.count();
        // A private key masked whole takes up one line; count what is sent, as the step does
        inputLogLineCount = countLines(text);
        if (redactedCount > 0 && GlobalConfigurationImpl.get().isBlockWhenRedacted()) {
            recordUsage(entryPoint, UsageEvent.Result.BLOCKED, provider, startTimeNanos, 0, false);
            throw new ExplanationException("warning", describeRedaction(redactedCount) + " " + BLOCKED);
        }

        // Check quota before making a real provider call (folder-level overrides global)
        QuotaCheckResult quotaCheck = tryAcquireQuota(run);
        if (!quotaCheck.allowed()) {
            recordUsage(entryPoint, UsageEvent.Result.QUOTA_REJECTED, provider, startTimeNanos,
                    inputLogLineCount, false);
            throw new ExplanationException("warning", quotaCheck.rejectionMessage());
        }

        try {
            // Resolve language and temperature from folder/global
            String effectiveLanguage = resolveEffectiveLanguage(run, null);
            Double effectiveTemperature = resolveEffectiveTemperature(run, null);
            LOGGER.fine(describeRequest(jobInfo, provider, inputLogLineCount, redactedCount));

            // Get AI explanation with resolved settings
            JenkinsLogAnalysis analysis = provider.analyzeError(text, new LogTaskListener(LOGGER, Level.FINE),
                    effectiveLanguage, effectiveCustomContext, run.getParent(), null, effectiveTemperature);
            this.lastAnalysis = analysis;
            String explanation = analysis.toText(ExplanationLabels.forLanguage(effectiveLanguage));
            LOGGER.fine(jobInfo + " AI error explanation succeeded.");
            LOGGER.fine("Explanation length: " + explanation.length());
            ErrorExplanationAction action = new ErrorExplanationAction(explanation, url, text,
                    provider.getProviderName(), provider.getModel(), inputLogLineCount);
            action.setStructuredData(analysis);
            action.setLanguage(effectiveLanguage);
            action.setRedactedCount(redactedCount);
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
     * Check if error explanation is enabled for a build.
     * The nearest folder that decides wins: one that turns explanation off disables it, one with a
     * provider of its own enables it, even while the global switch is off. When no folder decides, the
     * global configuration applies.
     *
     * @param run the build run to check
     * @return true if explanation is enabled, false otherwise
     */
    boolean isExplanationEnabled(@CheckForNull Run<?, ?> run) {
        if (run != null) {
            Boolean folderDecision = ExplainErrorFolderProperty.findFolderDecision(run.getParent().getParent());
            if (folderDecision != null) {
                LOGGER.fine("Error explanation " + (folderDecision ? "enabled" : "turned off")
                        + " at folder level for " + run.getParent().getFullName());
                return folderDecision;
            }
        }
        boolean globalEnabled = GlobalConfigurationImpl.get().isEnableExplanation();
        LOGGER.fine("Global configuration enabled: " + globalEnabled);
        return globalEnabled;
    }

    /**
     * Whether a new explanation can be asked for: explanation is enabled for the build and the provider
     * that would answer, folder or global, is configured.
     *
     * @param run the build to explain
     * @return true if the Explain Error button should be offered
     */
    boolean canExplain(@NonNull Run<?, ?> run) {
        if (!isExplanationEnabled(run)) {
            return false;
        }
        BaseAIProvider provider = providerFor(run);
        return provider != null && !provider.isNotValid(null, run.getParent(), null);
    }

    /**
     * @param run the build to explain
     * @return the provider an explanation of the build would come from, folder or global
     */
    @CheckForNull
    BaseAIProvider providerFor(@CheckForNull Run<?, ?> run) {
        return resolveProvider(run).provider();
    }

    private void logToConsole(TaskListener listener, String message) {
        listener.getLogger().println(CONSOLE_PREFIX + message);
    }

    private String buildSavedExplanationMessage(Run<?, ?> run, ErrorExplanationAction action) {
        String label = action.getDisplayName();
        // Relative to the Jenkins root, so that a Jenkins without a root URL configured gets a link too
        String link = HyperlinkNote.encodeTo('/' + run.getUrl() + action.getUrlName() + '/', label);
        return "Explanation saved to the build. Open " + link + ".";
    }

    /**
     * Attempts to acquire a quota slot. Folder-level quota (nearest ancestor with
     * {@code enableQuota=true}) takes precedence over the global quota.
     *
     * @param run the current build run (may be null)
     * @return a {@link QuotaCheckResult} indicating whether the call is allowed
     */
    private QuotaCheckResult tryAcquireQuota(@CheckForNull Run<?, ?> run) {
        // Walk up the folder hierarchy to find the nearest folder-level quota
        if (run != null) {
            ExplainErrorFolderProperty folderQuota =
                    ExplainErrorFolderProperty.findFolderWithQuota(run.getParent().getParent());
            if (folderQuota != null) {
                boolean allowed = folderQuota.tryAcquireQuota();
                if (!allowed) {
                    String msg = "Provider call quota exceeded (folder level). Limit: "
                            + folderQuota.getMaxProviderCallsPerWindow()
                            + " calls per " + folderQuota.getQuotaWindow().getDisplayName().toLowerCase()
                            + " window.";
                    return new QuotaCheckResult(false, msg);
                }
                return QuotaCheckResult.ALLOWED;
            }
        }

        // Fall back to global quota
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        if (!config.tryAcquireQuota()) {
            String msg = "Provider call quota exceeded. Limit: " + config.getMaxProviderCallsPerWindow()
                    + " calls per " + config.getQuotaWindow().getDisplayName().toLowerCase() + " window.";
            return new QuotaCheckResult(false, msg);
        }
        return QuotaCheckResult.ALLOWED;
    }

    private record QuotaCheckResult(boolean allowed, String rejectionMessage) {
        static final QuotaCheckResult ALLOWED = new QuotaCheckResult(true, null);
    }

    private void logExtractionSummary(TaskListener listener, PipelineLogExtractor.ExtractionResult result,
                                      int maxLines) {
        if (result.logPatternMatchedNothing()) {
            logToConsole(listener, "The log pattern matched no line; using the lines without it.");
        }
        if (result.fallbackToBuildLog()) {
            logToConsole(listener, "No failing step log found; using the last " + maxLines + " console lines.");
        } else if (result.failedStepCount() > 1) {
            logToConsole(listener, "Extracted " + result.getExtractedLineCount() + " log lines from "
                    + result.failedStepCount() + " failing steps.");
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

    private String resolveCustomContextSource(@CheckForNull Run<?, ?> run, String stepCustomContext) {
        if (StringUtils.isNotBlank(stepCustomContext)) {
            return "step";
        }
        if (run != null) {
            String folderContext = ExplainErrorFolderProperty.findFolderCustomContext(run.getParent().getParent());
            if (StringUtils.isNotBlank(folderContext)) {
                return "folder";
            }
        }
        if (StringUtils.isNotBlank(GlobalConfigurationImpl.get().getCustomContext())) {
            return "global";
        }
        return "none";
    }

    /**
     * Resolve effective language: step → folder → global → null (provider defaults to "English").
     */
    @CheckForNull
    private String resolveEffectiveLanguage(@CheckForNull Run<?, ?> run, @CheckForNull String stepLanguage) {
        if (StringUtils.isNotBlank(stepLanguage)) {
            return StringUtils.trimToNull(stepLanguage);
        }
        if (run != null) {
            String folderLanguage = ExplainErrorFolderProperty.findFolderLanguage(run.getParent().getParent());
            if (StringUtils.isNotBlank(folderLanguage)) {
                return folderLanguage.trim();
            }
        }
        String globalLanguage = GlobalConfigurationImpl.get().getLanguage();
        if (StringUtils.isNotBlank(globalLanguage)) {
            return globalLanguage.trim();
        }
        return null;
    }

    /** The custom context of the folder, or else the global one: what a step that sets none gets. */
    @CheckForNull
    String inheritedCustomContext(@CheckForNull Run<?, ?> run) {
        return resolveEffectiveCustomContext(run, null);
    }

    /**
     * Resolve effective custom context: step → folder → global.
     */
    @CheckForNull
    private String resolveEffectiveCustomContext(@CheckForNull Run<?, ?> run, @CheckForNull String stepCustomContext) {
        if (StringUtils.isNotBlank(stepCustomContext)) {
            return stepCustomContext;
        }
        if (run != null) {
            String folderContext = ExplainErrorFolderProperty.findFolderCustomContext(run.getParent().getParent());
            if (StringUtils.isNotBlank(folderContext)) {
                return folderContext;
            }
        }
        return GlobalConfigurationImpl.get().getCustomContext();
    }

    /**
     * Resolve effective temperature: step → folder → global → null (provider defaults apply).
     */
    @CheckForNull
    private Double resolveEffectiveTemperature(@CheckForNull Run<?, ?> run, @CheckForNull Double stepTemperature) {
        if (stepTemperature != null) {
            return stepTemperature;
        }
        if (run != null) {
            Double folderTemp = ExplainErrorFolderProperty.findFolderTemperature(run.getParent().getParent());
            if (folderTemp != null) {
                return folderTemp;
            }
        }
        return GlobalConfigurationImpl.get().getTemperature();
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
