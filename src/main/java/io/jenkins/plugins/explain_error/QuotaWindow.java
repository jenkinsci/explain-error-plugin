package io.jenkins.plugins.explain_error;

/**
 * Time window for usage-quota counting.
 */
public enum QuotaWindow {

    HOURLY("hourly", "Hourly", 3_600_000L),
    DAILY("daily", "Daily", 86_400_000L);

    private final String value;
    private final String displayName;
    private final long durationMillis;

    QuotaWindow(String value, String displayName, long durationMillis) {
        this.value = value;
        this.displayName = displayName;
        this.durationMillis = durationMillis;
    }

    public String getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getDurationMillis() {
        return durationMillis;
    }
}
