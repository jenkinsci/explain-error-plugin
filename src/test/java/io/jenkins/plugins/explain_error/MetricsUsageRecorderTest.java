package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricsUsageRecorderTest {

    private MetricRegistry registry;
    private MetricsUsageRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new MetricRegistry();
        recorder = new MetricsUsageRecorder(registry);
    }

    private UsageEvent event(UsageEvent.EntryPoint ep, UsageEvent.Result result,
                             String provider, String model, long durationMs, int logLines) {
        return new UsageEvent(System.currentTimeMillis(), ep, result, provider, model,
                durationMs, logLines, false);
    }

    @Test
    void requestCounterIncrementedOnPipelineStepSuccess() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.SUCCESS,
                "OpenAI", "gpt-4o", 500L, 42));

        assertEquals(1, registry.counter("explain_error.requests.pipeline_step.success").getCount());
        assertEquals(0, registry.counter("explain_error.requests.pipeline_step.provider_error").getCount());
    }

    @Test
    void requestCounterIncrementedForConsoleActionCacheHit() {
        recorder.record(event(UsageEvent.EntryPoint.CONSOLE_ACTION, UsageEvent.Result.CACHE_HIT,
                "Gemini", "gemini-pro", 10L, 15));

        assertEquals(1, registry.counter("explain_error.requests.console_action.cache_hit").getCount());
    }

    @Test
    void requestCounterIncrementedForDisabled() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.DISABLED,
                "OpenAI", "gpt-4o", 0L, 0));

        assertEquals(1, registry.counter("explain_error.requests.pipeline_step.disabled").getCount());
    }

    @Test
    void requestCounterIncrementedForMisconfigured() {
        recorder.record(event(UsageEvent.EntryPoint.CONSOLE_ACTION, UsageEvent.Result.MISCONFIGURED,
                "OpenAI", "gpt-4o", 0L, 0));

        assertEquals(1, registry.counter("explain_error.requests.console_action.misconfigured").getCount());
    }

    @Test
    void providerCallsCounterIncrementedOnSuccess() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.SUCCESS,
                "OpenAI", "gpt-4o", 500L, 42));

        assertEquals(1, registry.counter("explain_error.provider_calls.openai.gpt-4o.success").getCount());
    }

    @Test
    void providerCallsCounterIncrementedOnCacheHit() {
        recorder.record(event(UsageEvent.EntryPoint.CONSOLE_ACTION, UsageEvent.Result.CACHE_HIT,
                "OpenAI", "gpt-4o-mini", 5L, 20));

        assertEquals(1, registry.counter("explain_error.provider_calls.openai.gpt-4o-mini.cache_hit").getCount());
    }

    @Test
    void providerCallsCounterIncrementedOnProviderError() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.PROVIDER_ERROR,
                "Ollama", "llama3", 800L, 30));

        assertEquals(1, registry.counter("explain_error.provider_calls.ollama.llama3.provider_error").getCount());
    }

    @Test
    void providerCallsCounterIncrementedOnQuotaRejected() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.QUOTA_REJECTED,
                "OpenAI", "gpt-4o", 10L, 5));

        assertEquals(1, registry.counter("explain_error.provider_calls.openai.gpt-4o.quota_rejected").getCount());
    }

    @Test
    void providerCallsNotIncrementedForDisabled() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.DISABLED,
                "OpenAI", "gpt-4o", 0L, 0));

        assertTrue(registry.getCounters().entrySet().stream()
                .filter(e -> e.getKey().contains("provider_calls"))
                .allMatch(e -> e.getValue().getCount() == 0),
                "provider_calls counter must not be incremented for DISABLED result");
    }

    @Test
    void providerCallsNotIncrementedForMisconfigured() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.MISCONFIGURED,
                "OpenAI", "gpt-4o", 0L, 0));

        assertTrue(registry.getCounters().entrySet().stream()
                .filter(e -> e.getKey().contains("provider_calls"))
                .allMatch(e -> e.getValue().getCount() == 0),
                "provider_calls counter must not be incremented for MISCONFIGURED result");
    }

    @Test
    void durationHistogramUpdated() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.SUCCESS,
                "OpenAI", "gpt-4o", 1234L, 10));

        assertEquals(1, registry.histogram("explain_error.request_duration_ms").getCount());
        assertEquals(1234L, registry.histogram("explain_error.request_duration_ms").getSnapshot().getMax());
    }

    @Test
    void inputLogLinesHistogramUpdated() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.SUCCESS,
                "OpenAI", "gpt-4o", 500L, 77));

        assertEquals(1, registry.histogram("explain_error.input_log_lines").getCount());
        assertEquals(77, registry.histogram("explain_error.input_log_lines").getSnapshot().getMax());
    }

    @Test
    void multipleEventsAccumulate() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.SUCCESS,
                "OpenAI", "gpt-4o", 500L, 42));
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.SUCCESS,
                "OpenAI", "gpt-4o", 600L, 30));
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.PROVIDER_ERROR,
                "OpenAI", "gpt-4o", 1000L, 25));

        assertEquals(2, registry.counter("explain_error.requests.pipeline_step.success").getCount());
        assertEquals(1, registry.counter("explain_error.requests.pipeline_step.provider_error").getCount());
        assertEquals(2, registry.counter("explain_error.provider_calls.openai.gpt-4o.success").getCount());
        assertEquals(1, registry.counter("explain_error.provider_calls.openai.gpt-4o.provider_error").getCount());
        assertEquals(3, registry.histogram("explain_error.request_duration_ms").getCount());
        assertEquals(3, registry.histogram("explain_error.input_log_lines").getCount());
    }

    @Test
    void cacheHitsCountedSeparatelyFromRealProviderCalls() {
        recorder.record(event(UsageEvent.EntryPoint.CONSOLE_ACTION, UsageEvent.Result.SUCCESS,
                "OpenAI", "gpt-4o", 500L, 20));
        recorder.record(event(UsageEvent.EntryPoint.CONSOLE_ACTION, UsageEvent.Result.CACHE_HIT,
                "OpenAI", "gpt-4o", 2L, 20));

        assertEquals(1, registry.counter("explain_error.provider_calls.openai.gpt-4o.success").getCount());
        assertEquals(1, registry.counter("explain_error.provider_calls.openai.gpt-4o.cache_hit").getCount());
    }

    @Test
    void sanitizeHandlesSpecialCharactersInProviderAndModel() {
        recorder.record(event(UsageEvent.EntryPoint.PIPELINE_STEP, UsageEvent.Result.SUCCESS,
                "My Custom Provider", "my-model/v2.0", 100L, 5));

        // Exactly one provider_calls counter with count=1 exists
        long total = registry.getCounters().entrySet().stream()
                .filter(e -> e.getKey().startsWith("explain_error.provider_calls"))
                .mapToLong(e -> e.getValue().getCount())
                .sum();
        assertEquals(1, total);
        // Names are sanitized (spaces → _, slashes → _, dots → _)
        assertEquals(1, registry.counter(
                "explain_error.provider_calls.my_custom_provider.my-model_v2_0.success").getCount());
    }

    @Test
    void sanitizeReturnsUnknownForBlankValue() {
        assertEquals("unknown", MetricsUsageRecorder.sanitize(null));
        assertEquals("unknown", MetricsUsageRecorder.sanitize(""));
        assertEquals("unknown", MetricsUsageRecorder.sanitize("   "));
    }

    @Test
    void sanitizeLowercasesAndPreservesAlphanumericAndDash() {
        assertEquals("gpt-4o-mini", MetricsUsageRecorder.sanitize("gpt-4o-mini"));
        assertEquals("openai", MetricsUsageRecorder.sanitize("OpenAI"));
        assertEquals("my_model_v2", MetricsUsageRecorder.sanitize("My Model/V2"));
    }
}
