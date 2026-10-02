package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Configuration-level behaviour of the AI providers that needs neither a Jenkins instance nor
 * an AI endpoint: validation messages, diagnostics endpoints, model auto-completion and the
 * descriptor field checks.
 */
class ProviderSettingsTest {

    private static final Secret KEY = Secret.fromString("key");

    @Test
    void validationMessagesNameTheMissingSetting() {
        assertInvalid("No Api key configured for OpenAI.", new OpenAIProvider(null, "m", null));
        assertInvalid("No Model configured for OpenAI.", new OpenAIProvider(null, " ", KEY));
        assertInvalid("No API key configured for DeepSeek.", new DeepSeekProvider(null, "m", Secret.fromString(" ")));
        assertInvalid("No model configured for DeepSeek.", new DeepSeekProvider(null, null, KEY));
        assertInvalid("No Api key configured for Gemini.", new GeminiProvider(null, "m", Secret.fromString("")));
        assertInvalid("No Model configured for Gemini.", new GeminiProvider(null, "", KEY));
        assertInvalid("No url configured for Ollama.", new OllamaProvider(" ", "m", null));
        assertInvalid("No Model configured for Ollama.", new OllamaProvider("http://ollama:11434", null, null));
        assertInvalid("No endpoint configured for Microsoft Foundry.", new MicrosoftFoundryProvider(null, "m", KEY));
        assertInvalid("No API key configured for Microsoft Foundry.",
                new MicrosoftFoundryProvider("https://foundry.example", "m", Secret.fromString(" ")));
        assertInvalid("No model deployment configured for Microsoft Foundry.",
                new MicrosoftFoundryProvider("https://foundry.example", " ", KEY));
        assertInvalid("No URL configured for OpenAI Compatible.", new OpenAICompatibleProvider(null, "m", null));
        assertInvalid("No model configured for OpenAI Compatible.",
                new OpenAICompatibleProvider("https://gateway.example", null, null));
        assertInvalid("No URL configured for LangGraph Platform.", new LangGraphProvider(null, null, KEY));
        assertInvalid("No API key configured for LangGraph Platform.",
                new LangGraphProvider("https://langgraph.example", null, null));
        assertInvalid("No API key or credentials configured for Qwen.", new QwenProvider(null, "m", null, null));
        assertInvalid("No model configured for Qwen.", new QwenProvider(null, " ", KEY, null));
        assertInvalid("No API key or credentials configured for Anthropic.",
                new AnthropicProvider(null, "m", null, null, null));
        assertInvalid("No Model configured for Anthropic.", new AnthropicProvider(null, null, KEY, null, null));
    }

    @Test
    void validProvidersPrintNothing() {
        List<BaseAIProvider> providers = List.of(
                new OpenAIProvider(null, "m", KEY),
                new DeepSeekProvider(null, "m", KEY),
                new GeminiProvider(null, "m", KEY),
                new OllamaProvider("http://ollama:11434", "m", null),
                new MicrosoftFoundryProvider("https://foundry.example", "m", KEY),
                new OpenAICompatibleProvider("https://gateway.example", "m", null),
                new LangGraphProvider("https://langgraph.example", null, KEY),
                new QwenProvider(null, "m", KEY, null),
                new AnthropicProvider(null, "m", KEY, null, null));
        for (BaseAIProvider provider : providers) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertFalse(provider.isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8)),
                    provider.getClass().getSimpleName());
            assertEquals("", out.toString(StandardCharsets.UTF_8), provider.getClass().getSimpleName());
        }
    }

    @Test
    void credentialsIdWithoutJenkinsFallsBackToTheDirectApiKey() {
        // Without a running Jenkins the credentials store cannot be queried; the direct key still applies.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QwenProvider qwen = new QwenProvider(null, "m", KEY, " qwen-credentials ");
        assertEquals("qwen-credentials", qwen.getCredentialsId());
        assertFalse(qwen.isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8)));
        assertEquals("Qwen credentials not found for ID: qwen-credentials",
                out.toString(StandardCharsets.UTF_8).trim());

        out.reset();
        AnthropicProvider anthropic = new AnthropicProvider(null, "m", KEY, "anthropic-credentials", 0);
        assertEquals("anthropic-credentials", anthropic.getCredentialsId());
        assertEquals(AnthropicProvider.DEFAULT_MAX_TOKENS, anthropic.getMaxTokens(), "non-positive max tokens");
        assertFalse(anthropic.isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8)));
        assertEquals("Anthropic credentials not found for ID: anthropic-credentials",
                out.toString(StandardCharsets.UTF_8).trim());

        assertTrue(new QwenProvider(null, "m", null, "qwen-credentials").isNotValid(null));
        assertTrue(new AnthropicProvider(null, "m", null, "anthropic-credentials", null).isNotValid(null));
    }

    @Test
    void creatingAnAssistantWithoutAnyKeyFailsFast() {
        IllegalStateException qwen = assertThrows(IllegalStateException.class,
                () -> new QwenProvider(null, "m", null, null).createAssistant(null, null, null));
        assertEquals("No API key configured for Qwen", qwen.getMessage());

        IllegalStateException anthropic = assertThrows(IllegalStateException.class,
                () -> new AnthropicProvider(null, "m", null, "missing", null).createAssistant(null, null, null));
        assertEquals("No API key configured for Anthropic", anthropic.getMessage());
    }

    @Test
    void diagnosticsUseTheConfiguredOrDefaultEndpoint() {
        assertEquals("https://api.openai.com/v1",
                new OpenAIProvider(" ", "m", KEY).getEffectiveEndpointForDiagnostics());
        assertEquals("https://proxy.example/v1",
                new OpenAIProvider("https://proxy.example/v1", "m", KEY).getEffectiveEndpointForDiagnostics());
        assertEquals("https://api.anthropic.com/v1/",
                new AnthropicProvider(null, "m", KEY, null, null).getEffectiveEndpointForDiagnostics());
        assertEquals("https://claude.example",
                new AnthropicProvider("https://claude.example", "m", KEY, null, null)
                        .getEffectiveEndpointForDiagnostics());
        assertEquals("https://generativelanguage.googleapis.com",
                new GeminiProvider(null, "m", KEY).getEffectiveEndpointForDiagnostics());
        assertEquals("https://gemini.example",
                new GeminiProvider("https://gemini.example", "m", KEY).getEffectiveEndpointForDiagnostics());
        assertEquals(new DeepSeekProvider.DescriptorImpl().getDefaultUrl(),
                new DeepSeekProvider(null, "m", KEY).getEffectiveEndpointForDiagnostics());
        assertEquals(new QwenProvider.DescriptorImpl().getDefaultUrl(),
                new QwenProvider(" ", "m", KEY, null).getEffectiveEndpointForDiagnostics());
        assertEquals("https://langgraph.example",
                new LangGraphProvider(" https://langgraph.example ", null, KEY).getEffectiveEndpointForDiagnostics());
    }

    @Test
    void microsoftFoundryNormalizesTheEndpoint() {
        assertNull(new MicrosoftFoundryProvider(" ", "m", KEY).getUrl());
        assertEquals("https://foundry.example/openai/v1",
                new MicrosoftFoundryProvider("https://foundry.example/", "m", KEY).getUrl());
        assertEquals("https://foundry.example/openai/v1",
                new MicrosoftFoundryProvider("https://foundry.example/openai/v1/", "m", KEY).getUrl());
        assertEquals("https://foundry.example/openai/v1",
                new MicrosoftFoundryProvider("https://foundry.example/openai/v1", "m", KEY).getUrl());
    }

    @Test
    void modelAutoCompletionFiltersByPrefix() {
        assertEquals(List.of("gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-5-pro"),
                new OpenAIProvider.DescriptorImpl().doAutoCompleteModel("GPT-5").getValues());
        assertEquals(List.of("claude-opus-4-7", "claude-opus-4-6"),
                new AnthropicProvider.DescriptorImpl().doAutoCompleteModel("claude-opus").getValues());
        assertEquals(List.of("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner"),
                new DeepSeekProvider.DescriptorImpl().doAutoCompleteModel(null).getValues());
        assertEquals(List.of("deepseek-reasoner"),
                new DeepSeekProvider.DescriptorImpl().doAutoCompleteModel("deepseek-r").getValues());
        assertEquals(List.of("qwen3-max", "qwen3.5-plus", "qwen3.5-flash", "qwen3-coder-plus", "qwen3-coder-flash"),
                new QwenProvider.DescriptorImpl().doAutoCompleteModel("Qwen3").getValues());
        assertEquals(11, new QwenProvider.DescriptorImpl().doAutoCompleteModel(null).getValues().size());
        assertTrue(new OpenAIProvider.DescriptorImpl().doAutoCompleteModel("unknown").getValues().isEmpty());
    }

    @Test
    void descriptorsExposeDisplayNamesAndDefaults() {
        assertEquals("OpenAI", new OpenAIProvider.DescriptorImpl().getDisplayName());
        assertEquals(OpenAIProvider.DEFAULT_MODEL, new OpenAIProvider.DescriptorImpl().getDefaultModel());
        assertFalse(new DeepSeekProvider.DescriptorImpl().getDefaultModel().isBlank());
        assertFalse(new QwenProvider.DescriptorImpl().getDefaultModel().isBlank());
        assertEquals("Anthropic (Claude)", new AnthropicProvider.DescriptorImpl().getDisplayName());
        assertEquals(AnthropicProvider.DEFAULT_MODEL, new AnthropicProvider.DescriptorImpl().getDefaultModel());
        assertEquals(AnthropicProvider.DEFAULT_MAX_TOKENS,
                new AnthropicProvider.DescriptorImpl().getDefaultMaxTokens());
        assertEquals("DeepSeek", new DeepSeekProvider.DescriptorImpl().getDisplayName());
        assertTrue(new DeepSeekProvider.DescriptorImpl().getDefaultUrl().startsWith("https://"));
        assertEquals("Qwen", new QwenProvider.DescriptorImpl().getDisplayName());
        assertTrue(new QwenProvider.DescriptorImpl().getDefaultUrl().startsWith("https://"));
        assertEquals("Google Gemini", new GeminiProvider.DescriptorImpl().getDisplayName());
        assertEquals("gemini-2.0-flash", new GeminiProvider.DescriptorImpl().getDefaultModel());
        assertEquals("Ollama", new OllamaProvider.DescriptorImpl().getDisplayName());
        assertEquals("gemma3:1b", new OllamaProvider.DescriptorImpl().getDefaultModel());
        assertEquals("Microsoft Foundry", new MicrosoftFoundryProvider.DescriptorImpl().getDisplayName());
        assertEquals(MicrosoftFoundryProvider.DEFAULT_MODEL,
                new MicrosoftFoundryProvider.DescriptorImpl().getDefaultModel());
        assertEquals("OpenAI Compatible", new OpenAICompatibleProvider.DescriptorImpl().getDisplayName());
        assertEquals("", new OpenAICompatibleProvider.DescriptorImpl().getDefaultModel());
    }

    @Test
    void urlValidationAcceptsOnlyAbsoluteHttpUrlsWithoutCredentials() {
        BaseAIProvider.BaseProviderDescriptor descriptor = new OpenAIProvider.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUrl(null).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUrl(" ").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUrl("https://api.example.com/v1").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUrl("http://localhost:8080").kind);
        assertError("URL must use http or https", descriptor.doCheckUrl("ftp://files.example.com"));
        assertError("url is not well formed.", descriptor.doCheckUrl("file:///etc/passwd"));
        assertError("Credentials must not be embedded in the URL",
                descriptor.doCheckUrl("https://user:secret@api.example.com"));
        assertError("URL is not well formed.", descriptor.doCheckUrl("not a url"));
        assertError("URL is not well formed.", descriptor.doCheckUrl("https://api.example.com/a b"));
    }

    @Test
    void requiredFieldChecksOfSpecificDescriptors() {
        assertError("URL is required.", new OllamaProvider.DescriptorImpl().doCheckUrl(" "));
        assertEquals(FormValidation.Kind.OK,
                new OllamaProvider.DescriptorImpl().doCheckUrl("http://ollama:11434").kind);

        MicrosoftFoundryProvider.DescriptorImpl foundry = new MicrosoftFoundryProvider.DescriptorImpl();
        assertError("Endpoint is required.", foundry.doCheckUrl(null));
        assertEquals(FormValidation.Kind.OK, foundry.doCheckUrl("https://foundry.example").kind);
        assertError("Model deployment is required.", foundry.doCheckModel(" "));
        assertError("Model deployment is required.", foundry.doCheckModel(null));
        assertEquals(FormValidation.Kind.OK, foundry.doCheckModel("gpt-4o").kind);

        OpenAICompatibleProvider.DescriptorImpl compatible = new OpenAICompatibleProvider.DescriptorImpl();
        assertError("URL is required.", compatible.doCheckUrl(""));
        assertEquals(FormValidation.Kind.OK, compatible.doCheckUrl("https://gateway.example/v1").kind);
        assertError("Model is required.", compatible.doCheckModel(" "));
        assertEquals(FormValidation.Kind.OK, compatible.doCheckModel("gateway-model").kind);
    }

    @Test
    void azureOpenAiValidationAndDescriptorChecks() {
        assertInvalid("No endpoint configured for Azure OpenAI.",
                new AzureOpenAIProvider(null, "dep", "v1", "cred", null));
        assertInvalid("No deployment configured for Azure OpenAI.",
                new AzureOpenAIProvider("https://azure.example", " ", "v1", "cred", null));
        assertInvalid("No API version configured for Azure OpenAI.",
                new AzureOpenAIProvider("https://azure.example", "dep", null, "cred", null));
        assertInvalid("No credentials ID configured for Azure OpenAI.",
                new AzureOpenAIProvider("https://azure.example", "dep", "v1", "", null));
        // Without a running Jenkins the credentials store cannot be queried.
        assertInvalid("Azure OpenAI credentials not found for ID: cred",
                new AzureOpenAIProvider("https://azure.example", "dep", "v1", "cred", null));

        AzureOpenAIProvider provider = new AzureOpenAIProvider(" https://azure.example ", " dep ", " v1 ", " cred ",
                null);
        assertEquals("https://azure.example", provider.getEndpoint());
        assertEquals("dep", provider.getDeployment());
        assertEquals("v1", provider.getApiVersion());
        assertEquals("cred", provider.getCredentialsId());
        assertEquals(AzureOpenAIProvider.ApiType.CHAT_COMPLETIONS, provider.getApiType());

        AzureOpenAIProvider.DescriptorImpl descriptor = new AzureOpenAIProvider.DescriptorImpl();
        assertEquals("Azure OpenAI", descriptor.getDisplayName());
        assertEquals(AzureOpenAIProvider.DEFAULT_DEPLOYMENT, descriptor.getDefaultModel());
        assertEquals(AzureOpenAIProvider.DEFAULT_API_VERSION, descriptor.getDefaultApiVersion());
        assertError("Endpoint is required.", descriptor.doCheckEndpoint(" "));
        assertError("URL must use http or https", descriptor.doCheckEndpoint("ftp://azure.example"));
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckEndpoint("https://resource.openai.azure.com").kind);
        assertError("Deployment is required.", descriptor.doCheckDeployment(null));
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckDeployment("gpt-4o").kind);
        assertError("API version is required.", descriptor.doCheckApiVersion(" ", "CHAT_COMPLETIONS"));
        assertError("Invalid API type.", descriptor.doCheckApiVersion("2025-01-01-preview", "BOGUS"));
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckApiVersion("2025-01-01-preview", " ").kind);
        assertError("Credentials ID is required.", descriptor.doCheckCredentialsId(""));
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCredentialsId("azure-key").kind);
    }

    @Test
    void assistantFactoryOverloadsDelegateToTheFullContextFactory() throws Exception {
        List<Double> temperatures = new ArrayList<>();
        BaseAIProvider recording = new OpenAIProvider(null, "m", KEY) {
            @Override
            public Assistant createAssistant(Item item, Authentication authentication, Double temperature) {
                temperatures.add(temperature);
                return (logs, language, context) -> new JenkinsLogAnalysis("Language " + language, null, null, null);
            }
        };

        recording.createAssistant(null, null);
        recording.createAssistant(0.7);
        recording.createAssistant();
        String explanation = recording.explainError("ERROR: boom", null, "German");

        assertEquals(java.util.Arrays.asList(null, 0.7, null, null), temperatures);
        assertEquals("Summary: Language German\n", explanation);
    }

    @Test
    void assistantCreationFailuresAndEmptyAnswersAreReported() {
        BaseAIProvider failing = new OpenAIProvider(null, "m", KEY) {
            @Override
            public Assistant createAssistant(Item item, Authentication authentication, Double temperature) {
                throw new IllegalStateException("cannot build the client");
            }
        };
        ExplanationException creation = assertThrows(ExplanationException.class,
                () -> failing.explainError("ERROR: boom", null));
        assertEquals("Failed to create assistant", creation.getMessage());
        assertEquals("cannot build the client", creation.getCause().getMessage());

        BaseAIProvider silent = new OpenAIProvider(null, "m", KEY) {
            @Override
            public Assistant createAssistant(Item item, Authentication authentication, Double temperature) {
                return (logs, language, context) -> null;
            }
        };
        ExplanationException empty = assertThrows(ExplanationException.class,
                () -> silent.explainError("ERROR: boom", null));
        assertEquals("API request failed: the provider returned no analysis", empty.getMessage());
    }

    private static void assertInvalid(String expectedMessage, BaseAIProvider provider) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(provider.isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8)),
                provider.getClass().getSimpleName());
        assertEquals(expectedMessage, out.toString(StandardCharsets.UTF_8).trim());
        assertTrue(provider.isNotValid(null), "the result must not depend on the listener");
    }

    private static void assertError(String expectedMessagePart, FormValidation validation) {
        assertEquals(FormValidation.Kind.ERROR, validation.kind, validation.getMessage());
        assertTrue(validation.getMessage().contains(expectedMessagePart), validation.getMessage());
    }
}
