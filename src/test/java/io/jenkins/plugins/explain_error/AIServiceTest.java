package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.util.Secret;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AIServiceTest {

    private GlobalConfigurationImpl config;
    private AIService aiService;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        // Get the global configuration instance
        config = GlobalConfigurationImpl.get();
        
        // Set minimal test values (no auto-population)
        config.setProvider(AIProvider.OPENAI);
        config.setApiUrl(null); // No auto-population
        config.setModel(null); // No auto-population
        config.setApiKey(Secret.fromString("test-api-key"));
        config.setEnableExplanation(true);
        
        aiService = new AIService(config);
    }

    @Test
    void testExplainErrorWithNullInput() throws IOException {
        String result = aiService.explainError(null);
        assertEquals("No error logs provided for explanation.", result);
    }

    @Test
    void testExplainErrorWithEmptyInput() throws IOException {
        String result = aiService.explainError("");
        assertEquals("No error logs provided for explanation.", result);
    }

    @Test
    void testExplainErrorWithBlankInput() throws IOException {
        String result = aiService.explainError("   ");
        assertEquals("No error logs provided for explanation.", result);
    }

    @Test
    void testExplainErrorWithValidInput() throws IOException {
        String errorLogs = "ERROR: Failed to compile\n" +
                          "FAILED: Task execution failed\n" +
                          "BUILD FAILED in 2s";
        
        // Since we don't have a real API key or network connection,
        // the service should handle the error gracefully
        String result = aiService.explainError(errorLogs);
        
        // The result should not be the "no error logs" message
        assertNotEquals("No error logs provided for explanation.", result);
        assertNotNull(result);
        assertFalse(result.trim().isEmpty());
        
        // The result should contain some indication of failure or error handling
        // since we don't have a real API connection
        assertTrue(result.contains("Failed to communicate with AI service") || 
                  result.contains("AI API request failed") ||
                  result.contains("Failed to get explanation") ||
                  result.contains("AI API Error") ||
                  result.contains("Failed to get explanation from AI service"));
    }

    @Test
    void testErrorLogsProcessing() throws IOException {
        String complexErrorLogs = "Started by user admin\n" +
                                 "Building in workspace /var/jenkins_home/workspace/test\n" +
                                 "ERROR: Could not find or load main class Application\n" +
                                 "FAILURE: Build failed with an exception.\n" +
                                 "* What went wrong:\n" +
                                 "Execution failed for task ':compileJava'.\n" +
                                 "> Compilation failed; see the compiler error output for details.\n" +
                                 "BUILD FAILED in 15s";
        
        String result = aiService.explainError(complexErrorLogs);
        
        // Should not return the "no error logs" message for valid input
        assertNotEquals("No error logs provided for explanation.", result);
        assertNotNull(result);
        assertFalse(result.trim().isEmpty());
    }

    @Test
    void testServiceWithNullApiKey() {
        config.setApiKey(null);
        
        String result = assertDoesNotThrow(() -> aiService.explainError("Some error"));
        
        // Should handle null API key gracefully - just verify we get some response
        assertNotNull(result);
        assertFalse(result.trim().isEmpty());
        // Should not be the "no error logs" message since we provided error logs
        assertNotEquals("No error logs provided for explanation.", result);
    }

    @Test
    void testServiceWithEmptyApiKey() {
        config.setApiKey(Secret.fromString(""));
        
        String result = assertDoesNotThrow(() -> aiService.explainError("Some error"));
        
        // Should handle empty API key gracefully - just verify we get some response
        assertNotNull(result);
        assertFalse(result.trim().isEmpty());
        // Should not be the "no error logs" message since we provided error logs
        assertNotEquals("No error logs provided for explanation.", result);
    }

    @Test
    void testServiceWithInvalidApiUrl() {
        config.setApiUrl("invalid-url");
        
        String result = assertDoesNotThrow(() -> aiService.explainError("Some error"));
        
        // Should handle invalid URL gracefully - just verify we get some response
        assertNotNull(result);
        assertFalse(result.trim().isEmpty());
        // Should not be the "no error logs" message since we provided error logs
        assertNotEquals("No error logs provided for explanation.", result);
    }

    @Test
    void testServiceWithNullModel() {
        config.setModel(null);
        
        String result = assertDoesNotThrow(() -> aiService.explainError("Some error"));
        
        // Should handle null model gracefully (might cause JSON serialization issues)
        assertNotNull(result);
    }

    @Test
    void testLongErrorLogs() throws IOException {
        // Test with moderately long error logs
        StringBuilder longErrorLog = new StringBuilder();
        for (int i = 0; i < 50; i++) { // Reduced size for testing
            longErrorLog.append("ERROR: Line ").append(i).append(" of error log\n");
        }
        
        String result = aiService.explainError(longErrorLog.toString());
        
        // Should handle long input without throwing exception
        assertNotEquals("No error logs provided for explanation.", result);
        assertNotNull(result);
    }

    @Test
    void testSpecialCharactersInErrorLogs() throws IOException {
        String errorLogsWithSpecialChars = "ERROR: Failed to process file 'test@#$%^&*().txt'\n" +
                                          "FAILURE: Build failed with special chars: <>&\"'\n" +
                                          "Unicode characters: ñáéíóú 中文 العربية";
        
        String result = aiService.explainError(errorLogsWithSpecialChars);
        
        // Should handle special characters without throwing exception
        assertNotEquals("No error logs provided for explanation.", result);
        assertNotNull(result);
    }

    @Test
    void testMultilineErrorLogs() throws IOException {
        String multilineErrorLogs = "ERROR: Multiple\n" +
                                   "lines\n" +
                                   "of\n" +
                                   "error\n" +
                                   "messages\n" +
                                   "BUILD FAILED";
        
        String result = aiService.explainError(multilineErrorLogs);
        
        // Should handle multiline input properly
        assertNotEquals("No error logs provided for explanation.", result);
        assertNotNull(result);
    }

    @Test
    void testJSONEscaping() throws IOException {
        String errorLogsWithJSON = "ERROR: JSON parsing failed\n" +
                                   "Expected: {\"key\": \"value\"}\n" +
                                   "Actual: {\"key\": \"broken";
        
        String result = aiService.explainError(errorLogsWithJSON);
        
        // Should handle JSON-like content without breaking
        assertNotEquals("No error logs provided for explanation.", result);
        assertNotNull(result);
    }

    @Test
    void testGeminiProviderConfiguration() {
        config.setProvider(AIProvider.GEMINI);
        config.setApiKey(Secret.fromString("test-gemini-key"));
        
        AIService geminiService = new AIService(config);
        String result = assertDoesNotThrow(() -> geminiService.explainError("Test error"));
        
        // Should create Gemini service successfully
        assertNotNull(result);
        assertFalse(result.trim().isEmpty());
        assertNotEquals("No error logs provided for explanation.", result);
    }

    @Test
    void testProviderDefaults() {
        // Test OpenAI - no auto-population
        config.setProvider(AIProvider.OPENAI);
        assertNull(config.getApiUrl()); // No auto-population
        assertNull(config.getModel()); // No auto-population
        
        // Test Gemini - no auto-population
        config.setProvider(AIProvider.GEMINI);
        config.setApiUrl(null); // Clear URL
        config.setModel(null);  // Clear model
        
        assertNull(config.getApiUrl()); // No auto-population
        assertNull(config.getModel()); // No auto-population
    }

    @Test
    void testProviderSwitching() {
        // Start with OpenAI
        config.setProvider(AIProvider.OPENAI);
        AIService openaiService = new AIService(config);
        
        // Switch to Gemini
        config.setProvider(AIProvider.GEMINI);
        AIService geminiService = new AIService(config);
        
        // Both should work (though will fail due to no network, but should not crash)
        String openaiResult = assertDoesNotThrow(() -> openaiService.explainError("Test error"));
        String geminiResult = assertDoesNotThrow(() -> geminiService.explainError("Test error"));
        
        assertNotNull(openaiResult);
        assertNotNull(geminiResult);
    }
}
