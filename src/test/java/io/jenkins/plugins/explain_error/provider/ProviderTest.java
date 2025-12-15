package io.jenkins.plugins.explain_error.provider;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ProviderTest {

    @Test
    void testExplainErrorWithNullInput() throws IOException, ExplanationException {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError(null, null));
        assertEquals("No error logs provided for explanation.", result.getMessage());
    }

    @Test
    void testExplainErrorWithEmptyInput() throws IOException, ExplanationException {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("", null));
        assertEquals("No error logs provided for explanation.", result.getMessage());
    }

    @Test
    void testExplainErrorWithBlankInput() throws IOException, ExplanationException {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("   ", null));
        assertEquals("No error logs provided for explanation.", result.getMessage());
    }

    @Test
    void testErrorLogsProcessing() throws IOException, ExplanationException {
        BaseAIProvider provider = new TestProvider();
        String complexErrorLogs = "Started by user admin\n" +
                                 "Building in workspace /var/jenkins_home/workspace/test\n" +
                                 "ERROR: Could not find or load main class Application\n" +
                                 "FAILURE: Build failed with an exception.\n" +
                                 "* What went wrong:\n" +
                                 "Execution failed for task ':compileJava'.\n" +
                                 "> Compilation failed; see the compiler error output for details.\n" +
                                 "BUILD FAILED in 15s";

        String result = provider.explainError(complexErrorLogs, null);

        // Should not return the "no error logs" message for valid input
        assertEquals("Summary: Request was successful\n", result);
    }

    @Test
    void testErrorLogsProcessingFailure() throws IOException {
        TestProvider provider = new TestProvider();
        provider.setThrowError(true);
        String logs = "All is good.";

        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError(logs, null));
        assertEquals("API request failed: Request failed.", result.getMessage());
    }

    @Test
    void testOpenAIWithNullApiKey() {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", null);
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOpenAIWithEmptyApiKey() {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", Secret.fromString(""));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOpenAIWithNullModel() {
        BaseAIProvider provider = new OpenAIProvider(null, null, Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOpenAIWithEmptyModel() {
        BaseAIProvider provider = new OpenAIProvider(null, "", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testGeminiNullApiKey() {
        BaseAIProvider provider = new GeminiProvider(null, "test-model", null);
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testGeminiEmptyApiKey() {
        BaseAIProvider provider = new GeminiProvider(null, "test-model", Secret.fromString(""));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testGeminiEmptyModel() {
        BaseAIProvider provider = new GeminiProvider(null, "", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testGeminiNullModel() {
        BaseAIProvider provider = new GeminiProvider(null, null, Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOllamaNullModel() {
        BaseAIProvider provider = new OllamaProvider("http://localhost:1234", null);
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOllamaEmptyModel() {
        BaseAIProvider provider = new OllamaProvider("http://localhost:1234", "");
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOllamaEmptyUrl() {
        BaseAIProvider provider = new OllamaProvider("", "test-model");
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOllamaNullUrl() {
        BaseAIProvider provider = new OllamaProvider(null, "test-model");
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }
}
