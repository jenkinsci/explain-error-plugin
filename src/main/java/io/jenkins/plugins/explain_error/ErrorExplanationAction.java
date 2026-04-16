package io.jenkins.plugins.explain_error;

import hudson.model.Run;
import jenkins.model.RunAction2;

/**
 * Build action to store and display error explanations.
 */
public class ErrorExplanationAction implements RunAction2 {

    private final String explanation;
    private final String urlString;
    private final transient String originalErrorLogs;
    private final int inputLogLineCount;
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

    public String getExplanation() {
        return explanation;
    }

    public String getOriginalErrorLogs() {
        return originalErrorLogs;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedTimestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp));
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderModel() {
        return providerModel;
    }

    public String getUrlString() {
        return urlString;
    }

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
