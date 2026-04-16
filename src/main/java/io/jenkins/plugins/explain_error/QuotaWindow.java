package io.jenkins.plugins.explain_error;

/**
 * Time window options for rate-limiting AI provider calls.
 */
public enum QuotaWindow {

    HOURLY("Hourly", 60L * 60 * 1000),
    DAILY("Daily", 24L * 60 * 60 * 1000);

    private final String displayName;
    private final long durationMillis;

    QuotaWindow(String displayName, long durationMillis) {
        this.displayName = displayName;
        this.durationMillis = durationMillis;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getDurationMillis() {
        return durationMillis;
    }
}
