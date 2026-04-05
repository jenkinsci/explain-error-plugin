package io.jenkins.plugins.explain_error;

import java.util.Objects;

/**
 * Immutable event describing a single Explain Error request outcome.
 */
public record UsageEvent(
        long timestampMillis,
        EntryPoint entryPoint,
        Result result,
        String providerName,
        String model,
        long durationMillis,
        int inputLogLineCount,
        boolean downstreamLogsCollected) {

    public UsageEvent {
        Objects.requireNonNull(entryPoint, "entryPoint must not be null");
        Objects.requireNonNull(result, "result must not be null");
        providerName = providerName != null ? providerName : "Unknown";
        model = model != null ? model : "Unknown";
        durationMillis = Math.max(0L, durationMillis);
        inputLogLineCount = Math.max(0, inputLogLineCount);
    }

    public enum EntryPoint {
        PIPELINE_STEP("pipeline_step"),
        CONSOLE_ACTION("console_action");

        private final String value;

        EntryPoint(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public enum Result {
        SUCCESS("success"),
        CACHE_HIT("cache_hit"),
        DISABLED("disabled"),
        MISCONFIGURED("misconfigured"),
        PROVIDER_ERROR("provider_error"),
        QUOTA_REJECTED("quota_rejected");

        private final String value;

        Result(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
