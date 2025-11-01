package io.jenkins.plugins.explain_error;

import hudson.model.Run;
import jenkins.model.RunAction2;
import javax.annotation.Nonnull;

/**
 * Build action to store and display error explanations.
 */
public class ErrorExplanationAction implements RunAction2 {

    private final String explanation;
    private final String originalErrorLogs;
    private final long timestamp;
    private final String providerName;

    private transient Run<?, ?> run;

    public ErrorExplanationAction(@Nonnull String explanation, String originalErrorLogs, @Nonnull String providerName) {
        this.explanation = explanation;
        this.originalErrorLogs = originalErrorLogs;
        this.providerName = providerName;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String getIconFileName() {
        return "symbol-cube";
    }

    @Override
    public String getDisplayName() {
        if(providerName != null && !providerName.isEmpty()) {
            return "AI Error Explanation (" + providerName + ")";
        }
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
        return explanation != null && !explanation.trim().isEmpty();
    }
}
