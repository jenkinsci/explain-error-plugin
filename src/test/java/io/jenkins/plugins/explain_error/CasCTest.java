package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.CustomOktaAIProvider;
import io.jenkins.plugins.explain_error.provider.OllamaProvider;
import org.junit.jupiter.api.Test;

@WithJenkinsConfiguredWithCode
public class CasCTest {

    @Test
    @ConfiguredWithCode("casc_old.yaml")
    void loadOldConfig(JenkinsConfiguredWithCodeRule jcwcRule) {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider provider = config.getAiProvider();
        assertInstanceOf(OllamaProvider.class, provider);
        assertEquals("gemma3:1b", provider.getModel());
        assertEquals("http://localhost:11434", provider.getUrl());
    }

    @Test
    @ConfiguredWithCode("casc_new.yaml")
    void loadNewConfig(JenkinsConfiguredWithCodeRule jcwcRule) {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider provider = config.getAiProvider();
        assertInstanceOf(OllamaProvider.class, provider);
        assertEquals("gemma3:1b", provider.getModel());
        assertEquals("http://localhost:11434", provider.getUrl());
    }

    @Test
    @ConfiguredWithCode("casc_custom_okta.yaml")
    void loadCustomOktaProviderConfig(JenkinsConfiguredWithCodeRule jcwcRule) {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider provider = config.getAiProvider();

        assertInstanceOf(CustomOktaAIProvider.class, provider);
        CustomOktaAIProvider customOkta = (CustomOktaAIProvider) provider;
        assertEquals("https://chat-ai.example.com/openai/deployments/{model}/chat/completions", customOkta.getUrl());
        assertEquals("https://id.example.com/oauth2/default/v1/token", customOkta.getTokenUrl());
        assertEquals("gpt-5-nano", customOkta.getModel());
        assertEquals("test-client-id", customOkta.getClientId());
        assertEquals("test-client-secret", customOkta.getClientSecret().getPlainText());
        assertEquals("custom.scope", customOkta.getScope());
        assertEquals("api-key", customOkta.getAccessTokenHeader());
        assertNull(customOkta.getAccessTokenPrefix());
        assertEquals("2025-04-01-preview", customOkta.getApiVersion());
        assertEquals("test-app-key", customOkta.getAppKey().getPlainText());
        assertEquals("cec123", customOkta.getUserId());
        assertEquals(150, customOkta.getTimeoutSeconds());
    }
}
