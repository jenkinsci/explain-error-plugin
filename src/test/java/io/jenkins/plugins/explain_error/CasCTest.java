package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.CustomProvider;
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
    @ConfiguredWithCode("casc_custom.yaml")
    void loadCustomProviderConfig(JenkinsConfiguredWithCodeRule jcwcRule) {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider provider = config.getAiProvider();

        assertInstanceOf(CustomProvider.class, provider);
        CustomProvider custom = (CustomProvider) provider;
        assertEquals("https://custom-ai.example.com/v1", custom.getUrl());
        assertEquals("custom-reasoner-v1", custom.getModel());
        assertEquals("test-custom-api-key", custom.getApiKey().getPlainText());
        assertEquals("x-api-key", custom.getApiKeyHeader());
        assertNull(custom.getApiKeyPrefix());
        assertEquals(120, custom.getTimeoutSeconds());
    }
}
