package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.util.Secret;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OllamaServiceTest {

    private GlobalConfigurationImpl config;
    private OllamaService ollamaService;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        config = GlobalConfigurationImpl.get();
        config.setProvider(AIProvider.OLLAMA);
        config.setApiUrl("http://localhost:11434/api/chat");
        config.setModel("gpt-oss");
        config.setApiKey(Secret.fromString(""));
        ollamaService = new OllamaService(config);
    }

    @Test
    void testExplainErrorWithNullInput() throws IOException {
        String result = ollamaService.explainError(null);
        assertEquals("No error logs provided for explanation.", result);
    }

    @Test
    void testExplainErrorWithValidInput() throws IOException {
        String result = ollamaService.explainError("Test error log for Ollama");
        assertNotNull(result);
        assertFalse(result.trim().isEmpty());
        assertNotEquals("No error logs provided for explanation.", result);
        // Should handle connection errors gracefully if Ollama is not running
        assertTrue(result.contains("Failed to communicate with AI service") ||
                   result.contains("AI API request failed") ||
                   result.contains("Failed to get explanation") ||
                   result.contains("AI API Error") ||
                   result.contains("Failed to get explanation from AI service") ||
                   result.contains("No explanation returned by Ollama."));
    }
}
