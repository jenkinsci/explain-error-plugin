package io.jenkins.plugins.explain_error;

import hudson.model.Api;
import hudson.model.Run;
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
    private final String sentErrorLogs;
    private final int inputLogLineCount;
    private final int redactionCount;
    private final int droppedLineCount;
    private final long timestamp;
    private String providerName = "Unknown";
    private String providerModel = "Unknown";
    private transient Run<?, ?> run;

    public ErrorExplanationAction(String explanation, String urlString, String originalErrorLogs, String providerName) {
        this(explanation, urlString, originalErrorLogs, providerName, null,
                ErrorExplainer.countLines(originalErrorLogs));
    }

    public ErrorExplanationAction(String explanation, String urlString, String originalErrorLogs,
                                  String providerName, String providerModel, int inputLogLineCount) {
        this(explanation, urlString, originalErrorLogs, null, providerName, providerModel, inputLogLineCount, 0, 0);
    }

    public ErrorExplanationAction(String explanation, String urlString, String originalErrorLogs,
                                  String sentErrorLogs, String providerName, String providerModel,
                                  int inputLogLineCount, int redactionCount, int droppedLineCount) {
        this.explanation = explanation;
        this.originalErrorLogs = originalErrorLogs;
        this.sentErrorLogs = sentErrorLogs;
        this.timestamp = System.currentTimeMillis();
        this.providerName = providerName;
        this.providerModel = providerModel;
        this.urlString = urlString;
        this.inputLogLineCount = Math.max(0, inputLogLineCount);
        this.redactionCount = Math.max(0, redactionCount);
        this.droppedLineCount = Math.max(0, droppedLineCount);
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

    public final String getSentErrorLogs() {
        return sentErrorLogs;
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

    @Exported(visibility = 1)
    public final int getRedactionCount() {
        return redactionCount;
    }

    @Exported(visibility = 1)
    public final int getDroppedLineCount() {
        return droppedLineCount;
    }

    public final boolean hasSentPayloadAudit() {
        return sentErrorLogs != null && !sentErrorLogs.isBlank();
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
