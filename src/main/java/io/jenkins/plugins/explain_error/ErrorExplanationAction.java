package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Api;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Build action to store and display error explanations.
 */
@ExportedBean(defaultVisibility = 999)
public class ErrorExplanationAction implements RunAction2 {

    private final String explanation;
    private final String urlString;
    private final transient String originalErrorLogs;
    private final int inputLogLineCount;
    private final long timestamp;
    private String providerName = "Unknown";
    private String providerModel = "Unknown";
    private transient Run<?, ?> run;

    // Structured fields of the AI analysis; null for explanations stored by older plugin versions
    private String errorSummary;
    private List<String> resolutionSteps;
    private List<String> bestPractices;
    private String errorSignature;

    public ErrorExplanationAction(String explanation, String urlString, String originalErrorLogs, String providerName) {
        this(explanation, urlString, originalErrorLogs, providerName, null,
                ErrorExplainer.countLines(originalErrorLogs));
    }

    public ErrorExplanationAction(String explanation, String urlString, String originalErrorLogs,
                                  String providerName, String providerModel, int inputLogLineCount) {
        this.explanation = explanation;
        this.originalErrorLogs = originalErrorLogs;
        this.timestamp = System.currentTimeMillis();
        this.providerName = providerName;
        this.providerModel = providerModel;
        this.urlString = urlString;
        this.inputLogLineCount = Math.max(0, inputLogLineCount);
    }

    public Object readResolve() {
        if (providerName == null) {
            providerName = "Unknown";
        }
        if (providerModel == null) {
            providerModel = "Unknown";
        }
        return this;
    }

    @Override
    public String getIconFileName() {
        return "symbol-sparkles-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "AI Error Explanation";
    }

    @Override
    public String getUrlName() {
        return "error-explanation";
    }

    public Api getApi() {
        return new Api(this);
    }

    @Exported
    public String getExplanation() {
        return explanation;
    }

    public String getOriginalErrorLogs() {
        return originalErrorLogs;
    }

    @Exported(visibility = 1)
    public String getErrorSummary() {
        return errorSummary;
    }

    @Exported(visibility = 1)
    public List<String> getResolutionSteps() {
        return resolutionSteps;
    }

    @Exported(visibility = 1)
    public List<String> getBestPractices() {
        return bestPractices;
    }

    @Exported(visibility = 1)
    public String getErrorSignature() {
        return errorSignature;
    }

    /**
     * Stores the structured AI analysis alongside the rendered explanation.
     * Lists are copied so the persisted build XML never references immutable JDK collections.
     */
    void setStructuredData(@CheckForNull JenkinsLogAnalysis analysis) {
        if (analysis == null) {
            return;
        }
        this.errorSummary = analysis.errorSummary();
        this.resolutionSteps = copyOf(analysis.resolutionSteps());
        this.bestPractices = copyOf(analysis.bestPractices());
        this.errorSignature = analysis.errorSignature();
    }

    @CheckForNull
    private static List<String> copyOf(@CheckForNull List<String> values) {
        return values == null ? null : new ArrayList<>(values);
    }

    @Exported(visibility = 1)
    public long getTimestamp() {
        return timestamp;
    }

    @Exported(visibility = 1)
    public String getFormattedTimestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp));
    }

    @Exported(visibility = 1)
    public String getProviderName() {
        return providerName;
    }

    @Exported(visibility = 1)
    public String getProviderModel() {
        return providerModel;
    }

    @Exported(visibility = 1)
    public String getUrlString() {
        return urlString;
    }

    @Exported(visibility = 1)
    public int getInputLogLineCount() {
        return inputLogLineCount;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    /**
     * Get the associated run.
     * @return the run this action is attached to
     */
    public Run<?, ?> getRun() {
        return run;
    }

    /**
     * Check if this action has a valid explanation.
     * @return true if explanation is not null, not empty, and not just whitespace
     */
    public boolean hasValidExplanation() {
        return explanation != null && !explanation.isBlank();
    }
}
