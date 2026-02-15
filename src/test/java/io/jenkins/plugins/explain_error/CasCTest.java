package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.explain_error.provider.AnthropicProvider;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.DeepSeekProvider;
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
    @ConfiguredWithCode("casc_anthropic.yaml")
    void loadAnthropicConfig(JenkinsConfiguredWithCodeRule jcwcRule) {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider provider = config.getAiProvider();
        assertInstanceOf(AnthropicProvider.class, provider);
        assertEquals("claude-3-5-sonnet-20241022", provider.getModel());
        assertEquals("https://api.anthropic.com", provider.getUrl());
    }

    @Test
    @ConfiguredWithCode("casc_deepseek.yaml")
    void loadDeepSeekConfig(JenkinsConfiguredWithCodeRule jcwcRule) {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        BaseAIProvider provider = config.getAiProvider();
        assertInstanceOf(DeepSeekProvider.class, provider);
        assertEquals("deepseek-chat", provider.getModel());
        assertEquals("https://api.deepseek.com", provider.getUrl());
    }
}
