package io.jenkins.plugins.explain_error;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import java.util.List;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GlobalConfigurationImplTest {

    private GlobalConfigurationImpl config;
    private JenkinsRule rule;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        rule = jenkins;
        config = GlobalConfigurationImpl.get();

        // Reset to clean state for each test (no auto-population)
        config.setAiProvider(new OpenAIProvider(null, "test-model", Secret.fromString("test-key")));
        config.setEnableExplanation(true);
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
    void testDefaultsToOpenAiProviderWhenUnconfigured() {
        config.setAiProvider(null);
        config.setProvider(null);
        config.setApiKey(null);
        config.setApiUrl(null);
        config.setModel(null);

        BaseAIProvider provider = config.getAiProvider();

        assertThat(provider, instanceOf(OpenAIProvider.class));
        assertEquals(OpenAIProvider.DEFAULT_MODEL, provider.getModel());
        assertNull(provider.getUrl());
    }

    @Test
    void testConfigurePageDefaultsProviderSelectionToOpenAi() throws Exception {
        config.setAiProvider(null);
        config.setProvider(null);
        config.setApiKey(null);
        config.setApiUrl(null);
        config.setModel(null);

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(false);
            HtmlPage page = client.goTo("configure");
            HtmlSelect providerSelect = findProviderSelect(page);

            List<HtmlOption> selectedOptions = providerSelect.getSelectedOptions();
            assertEquals(1, selectedOptions.size());
            assertEquals("OpenAI", selectedOptions.get(0).getText().trim());
        }
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

    private HtmlSelect findProviderSelect(HtmlPage page) {
        return page.getByXPath("//div[normalize-space()='AI Provider']/following::select[1]").stream()
                .filter(HtmlSelect.class::isInstance)
                .map(HtmlSelect.class::cast)
                .findFirst()
                .orElseThrow(() -> {
                    String xml = page.asXml();
                    int marker = xml.indexOf("Explain Error Plugin Configuration");
                    String snippet = marker >= 0
                            ? xml.substring(Math.max(0, marker - 500), Math.min(xml.length(), marker + 2500))
                            : xml.substring(0, Math.min(xml.length(), 3000));
                    return new AssertionError("AI provider dropdown not found on configure page. Snippet:\n" + snippet);
                });
    }
}
