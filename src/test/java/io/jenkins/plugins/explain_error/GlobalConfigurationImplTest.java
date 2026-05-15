package io.jenkins.plugins.explain_error;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.AzureOpenAIProvider;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.CustomOktaAIProvider;
import io.jenkins.plugins.explain_error.provider.DeepSeekProvider;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import io.jenkins.plugins.explain_error.provider.QwenProvider;
import java.util.List;
import jenkins.model.Jenkins;
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
    void testUnconfiguredProviderSelectionDefaultsToOpenAi() {
        config.setAiProvider(null);
        config.setProvider(null);
        config.setApiKey(null);
        config.setApiUrl(null);
        config.setModel(null);

        assertEquals("OpenAI", config.getAiProvider().getDescriptor().getDisplayName());
    }

    @Test
    void testConfigurePageIncludesCustomOktaProviderOption() {
        assertTrue(providerDisplayNames().contains("Custom Okta AI"),
                "AI provider dropdown should include the 'Custom Okta AI' option");
    }

    @Test
    void testConfigurePageIncludesAzureOpenAiProviderOption() {
        assertTrue(providerDisplayNames().contains("Azure OpenAI"),
                "AI provider dropdown should include the 'Azure OpenAI' option");
    }

    @Test
    void testConfigurePageIncludesDeepSeekProviderOption() {
        assertTrue(providerDisplayNames().contains("DeepSeek"),
                "AI provider dropdown should include the 'DeepSeek' option");
    }

    @Test
    void testConfigurePageIncludesQwenProviderOption() {
        assertTrue(providerDisplayNames().contains("Qwen"),
                "AI provider dropdown should include the 'Qwen' option");
    }

    @Test
    void testConfigurationPersistenceForCustomOktaProvider() {
        CustomOktaAIProvider provider = new CustomOktaAIProvider(
                "https://chat-ai.example.com/openai/deployments/{model}/chat/completions",
                "https://id.example.com/oauth2/default/v1/token",
                "gpt-5-nano",
                "client-id",
                Secret.fromString("client-secret"));
        provider.setAppKey(Secret.fromString("app-key"));
        config.setAiProvider(provider);
        config.save();

        config.load();

        BaseAIProvider reloaded = config.getAiProvider();
        assertThat(reloaded, instanceOf(CustomOktaAIProvider.class));
        CustomOktaAIProvider customOkta = (CustomOktaAIProvider) reloaded;
        assertEquals("client-id", customOkta.getClientId());
        assertEquals("client-secret", customOkta.getClientSecret().getPlainText());
        assertEquals("app-key", customOkta.getAppKey().getPlainText());
    }

    @Test
    void testConfigurationPersistenceForAzureOpenAiProvider() {
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                "https://my-resource.openai.azure.com",
                "gpt-4o-enterprise",
                "2025-01-01-preview",
                "azure-openai-key",
                null);
        config.setAiProvider(provider);
        config.save();

        config.load();

        BaseAIProvider reloaded = config.getAiProvider();
        assertThat(reloaded, instanceOf(AzureOpenAIProvider.class));
        AzureOpenAIProvider azure = (AzureOpenAIProvider) reloaded;
        assertEquals("https://my-resource.openai.azure.com", azure.getEndpoint());
        assertEquals("gpt-4o-enterprise", azure.getDeployment());
        assertEquals("2025-01-01-preview", azure.getApiVersion());
        assertEquals("azure-openai-key", azure.getCredentialsId());
    }

    @Test
    void testConfigurationPersistenceForDeepSeekProvider() {
        DeepSeekProvider provider = new DeepSeekProvider(
                "https://api.deepseek.com",
                "deepseek-v4-pro",
                Secret.fromString("deepseek-key"));
        config.setAiProvider(provider);
        config.save();

        config.load();

        BaseAIProvider reloaded = config.getAiProvider();
        assertThat(reloaded, instanceOf(DeepSeekProvider.class));
        DeepSeekProvider deepSeek = (DeepSeekProvider) reloaded;
        assertEquals("https://api.deepseek.com", deepSeek.getUrl());
        assertEquals("deepseek-v4-pro", deepSeek.getModel());
        assertEquals("deepseek-key", deepSeek.getApiKey().getPlainText());
    }

    @Test
    void testConfigurationPersistenceForQwenProvider() {
        QwenProvider provider = new QwenProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus",
                Secret.fromString("qwen-key"));
        config.setAiProvider(provider);
        config.save();

        config.load();

        BaseAIProvider reloaded = config.getAiProvider();
        assertThat(reloaded, instanceOf(QwenProvider.class));
        QwenProvider qwen = (QwenProvider) reloaded;
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", qwen.getUrl());
        assertEquals("qwen-plus", qwen.getModel());
        assertEquals("qwen-key", qwen.getApiKey().getPlainText());
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

    private List<String> providerDisplayNames() {
        return Jenkins.get().getDescriptorList(BaseAIProvider.class).stream()
                .map(descriptor -> descriptor.getDisplayName())
                .toList();
    }
}
