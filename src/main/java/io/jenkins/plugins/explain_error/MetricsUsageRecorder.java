package io.jenkins.plugins.explain_error;

import com.codahale.metrics.MetricRegistry;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.Locale;
import jenkins.metrics.api.Metrics;

/**
 * Records Explain Error usage events as low-cardinality Dropwizard metrics
 * exposed through the Jenkins Metrics plugin.
 *
 * <p>This recorder is automatically active when the Jenkins Metrics plugin is installed.
 * If the plugin is absent, the extension will not load and no metrics are exported.</p>
 *
 * <p>The following metrics are exported:
 * <ul>
 *   <li>{@code explain_error.requests.{entryPoint}.{result}} – request outcome counter</li>
 *   <li>{@code explain_error.provider_calls.{provider}.{model}.{result}} – provider call counter</li>
 *   <li>{@code explain_error.request_duration_ms} – request duration histogram</li>
 *   <li>{@code explain_error.input_log_lines} – input log line count histogram</li>
 * </ul>
 *
 * <p><strong>Privacy:</strong> no high-cardinality labels (job name, build number, username)
 * are used.</p>
 */
@Extension
public class MetricsUsageRecorder implements UsageRecorder {

    static final String PREFIX = "explain_error";

    private final MetricRegistry injectedRegistry;

    /**
     * Default no-arg constructor used by Jenkins when loading the extension.
     * The registry is resolved lazily from {@link Metrics#metricRegistry()} on each call.
     */
    public MetricsUsageRecorder() {
        this.injectedRegistry = null;
    }

    /**
     * Constructor for testing; uses the supplied registry instead of the Jenkins one.
     */
    MetricsUsageRecorder(MetricRegistry registry) {
        this.injectedRegistry = registry;
    }

    @CheckForNull
    private MetricRegistry registry() {
        if (injectedRegistry != null) {
            return injectedRegistry;
        }
        return Metrics.metricRegistry();
    }

    @Override
    public void record(@NonNull UsageEvent event) {
        MetricRegistry r = registry();
        if (r == null) {
            return;
        }

        // explain_error.requests.{entryPoint}.{result}
        r.counter(MetricRegistry.name(
                PREFIX, "requests",
                event.entryPoint().getValue(),
                event.result().getValue()))
                .inc();

        // explain_error.provider_calls.{provider}.{model}.{result}
        // Only for outcomes that involve a provider call (real or cached).
        UsageEvent.Result result = event.result();
        if (result == UsageEvent.Result.SUCCESS
                || result == UsageEvent.Result.CACHE_HIT
                || result == UsageEvent.Result.PROVIDER_ERROR
                || result == UsageEvent.Result.QUOTA_REJECTED) {
            r.counter(MetricRegistry.name(
                    PREFIX, "provider_calls",
                    sanitize(event.providerName()),
                    sanitize(event.model()),
                    result.getValue()))
                    .inc();
        }

        // explain_error.request_duration_ms
        r.histogram(MetricRegistry.name(PREFIX, "request_duration_ms"))
                .update(event.durationMillis());

        // explain_error.input_log_lines
        r.histogram(MetricRegistry.name(PREFIX, "input_log_lines"))
                .update(event.inputLogLineCount());
    }

    /**
     * Sanitizes a string for use as a metric name segment.
     * Converts to lower-case and replaces any character outside {@code [a-z0-9_-]} with {@code _}.
     */
    static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }
}
