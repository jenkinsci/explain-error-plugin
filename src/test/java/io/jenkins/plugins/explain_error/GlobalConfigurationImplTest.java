package io.jenkins.plugins.explain_error;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GlobalConfigurationImplTest {

    private GlobalConfigurationImpl config;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        config = GlobalConfigurationImpl.get();

        // Reset to clean state for each test (no auto-population)
        config.setAiProvider(new OpenAIProvider(null, "test-model", Secret.fromString("test-key")));
        config.setEnableExplanation(true);
    }

    @AfterEach
    void tearDown() {
        UsageStatisticsManager usage = UsageStatisticsManager.get();
        if (usage != null) {
            usage.flushPendingSaveForTesting();
        }
    }

    @Test
    void testGetSingletonInstance() {
        GlobalConfigurationImpl instance1 = GlobalConfigurationImpl.get();
        GlobalConfigurationImpl instance2 = GlobalConfigurationImpl.get();

        assertNotNull(instance1);
        assertNotNull(instance2);
        assertSame(instance1, instance2); // Should be the same singleton instance
    }

    @Test
    void testDefaultValues() {
        assertTrue(config.isEnableExplanation());
    }

    @Test
    void testEnableExplanationSetterAndGetter() {
        config.setEnableExplanation(false);
        assertFalse(config.isEnableExplanation());

        config.setEnableExplanation(true);
        assertTrue(config.isEnableExplanation());
    }

    @Test
    void testConfigurationPersistence() {
        // Set some values
        config.setAiProvider(new GeminiProvider("", "test-model", Secret.fromString("test-key")));
        config.setEnableExplanation(false);

        // Save the configuration
        config.save();

        config.load();

        // Verify the values are still there
        BaseAIProvider provider = config.getAiProvider();
        assertThat(provider, instanceOf(GeminiProvider.class));
        GeminiProvider gemini = (GeminiProvider) provider;
        assertEquals("test-key", gemini.getApiKey().getPlainText());
        assertEquals("test-model", gemini.getModel());
        assertThat(gemini.getUrl(), is(""));
        assertFalse(config.isEnableExplanation());
    }

    @Test
    void testGetDisplayName() {
        String displayName = config.getDisplayName();
        assertNotNull(displayName);
        assertEquals("Explain Error Plugin Configuration", displayName);
    }

    @Test
    void testUsageStatisticsAreExposedForConfigurationView() throws Exception {
        UsageStatisticsManager usage = UsageStatisticsManager.get();
        assertNotNull(usage);
        usage.resetForTesting();
        usage.recordCall("folder/test-job", java.time.Instant.now());

        assertEquals(1L, config.getTotalCalls());
        assertEquals(1L, config.getCallsThisMonth());
        assertEquals(1, config.getTopJobs().size());
        assertEquals("folder/test-job", config.getTopJobs().get(0).getKey());
        assertEquals(1L, config.getTopJobs().get(0).getValue());

        try (InputStream stream = GlobalConfigurationImpl.class.getResourceAsStream(
                "/io/jenkins/plugins/explain_error/GlobalConfigurationImpl/config.jelly")) {
            assertNotNull(stream, "Global configuration Jelly view should be packaged");
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(content.contains("Explain Error Plugin - Usage Statistics"));
            assertTrue(content.contains("Total AI Calls"));
            assertTrue(content.contains("Top Jobs by Usage"));
        }
    }
}
