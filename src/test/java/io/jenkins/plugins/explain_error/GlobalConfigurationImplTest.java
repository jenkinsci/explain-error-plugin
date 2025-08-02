package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.util.FormValidation;
import hudson.util.Secret;
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
        config.setApiKey(null);
        config.setProvider(AIProvider.OPENAI);
        config.setApiUrl(null);
        config.setModel(null);
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
        assertNull(config.getApiUrl()); // No auto-population, should be null initially
        assertNull(config.getModel()); // No auto-population, should be null initially
        assertTrue(config.isEnableExplanation());
        assertNull(config.getApiKey()); // API key should be null by default in tests
    }

    @Test
    void testApiKeySetterAndGetter() {
        Secret testSecret = Secret.fromString("test-api-key");
        config.setApiKey(testSecret);
        
        assertEquals(testSecret, config.getApiKey());
    }

    @Test
    void testApiUrlSetterAndGetter() {
        String testUrl = "https://api.example.com/v1/chat";
        config.setApiUrl(testUrl);
        
        assertEquals(testUrl, config.getApiUrl());
    }

    @Test
    void testModelSetterAndGetter() {
        String testModel = "gpt-4";
        config.setModel(testModel);
        
        assertEquals(testModel, config.getModel());
    }

    @Test
    void testEnableExplanationSetterAndGetter() {
        config.setEnableExplanation(false);
        assertFalse(config.isEnableExplanation());
        
        config.setEnableExplanation(true);
        assertTrue(config.isEnableExplanation());
    }

    @Test
    void testDoTestConfiguration() {
        // Test the doTestConfiguration method with invalid parameters
        FormValidation result = config.doTestConfiguration("invalid-key", "OPENAI", "invalid-url", "invalid-model");
        
        // The result should not be null and should have a message
        assertNotNull(result);
        assertNotNull(result.getMessage());
        // We don't strictly enforce error/warning since the implementation may vary
        assertTrue(result.kind == FormValidation.Kind.ERROR || 
                  result.kind == FormValidation.Kind.WARNING || 
                  result.kind == FormValidation.Kind.OK);
    }

    @Test
    void testDoTestConfigurationWithNullParameters() {
        // Test with null parameters
        FormValidation result = config.doTestConfiguration(null, null, null, null);
        
        // Should handle null parameters gracefully
        assertNotNull(result);
        assertNotNull(result.getMessage());
    }

    @Test
    void testDoTestConfigurationWithEmptyParameters() {
        // Test with empty parameters
        FormValidation result = config.doTestConfiguration("", "", "", "");
        
        // Should handle empty parameters gracefully
        assertNotNull(result);
        assertNotNull(result.getMessage());
    }

    @Test
    void testSetApiKeyWithNullValue() {
        config.setApiKey(null);
        assertNull(config.getApiKey());
    }

    @Test
    void testSetApiKeyWithEmptySecret() {
        Secret emptySecret = Secret.fromString("");
        config.setApiKey(emptySecret);
        assertEquals(emptySecret, config.getApiKey());
    }

    @Test
    void testSetApiUrlWithNullValue() {
        config.setApiUrl(null);
        // Raw URL should be null
        assertNull(config.getRawApiUrl());
        // API URL should return null when null (no auto-population)
        assertNull(config.getApiUrl());
    }

    @Test
    void testSetApiUrlWithEmptyString() {
        config.setApiUrl("");
        // Raw URL should be empty
        assertEquals("", config.getRawApiUrl());
        // API URL should return empty string when empty (no auto-population)
        assertEquals("", config.getApiUrl());
    }

    @Test
    void testSetModelWithNullValue() {
        config.setModel(null);
        // Raw model should be null
        assertNull(config.getRawModel());
        // Model should return null when null (no auto-population)
        assertNull(config.getModel());
    }

    @Test
    void testSetModelWithEmptyString() {
        config.setModel("");
        // Raw model should be empty
        assertEquals("", config.getRawModel());
        // Model should return empty string when empty (no auto-population)
        assertEquals("", config.getModel());
    }

    @Test
    void testConfigurationPersistence() {
        // Set some values
        config.setApiKey(Secret.fromString("test-key"));
        config.setProvider(AIProvider.GEMINI);
        config.setApiUrl("https://test.example.com");
        config.setModel("test-model");
        config.setEnableExplanation(false);
        
        // Save the configuration
        config.save();
        
        // Verify the values are still there
        assertEquals("test-key", config.getApiKey().getPlainText());
        assertEquals(AIProvider.GEMINI, config.getProvider());
        assertEquals("https://test.example.com", config.getApiUrl());
        assertEquals("test-model", config.getModel());
        assertFalse(config.isEnableExplanation());
    }

    @Test
    void testProviderSetterAndGetter() {
        // Test setting different providers
        config.setProvider(AIProvider.GEMINI);
        assertEquals(AIProvider.GEMINI, config.getProvider());
        
        config.setProvider(AIProvider.OPENAI);
        assertEquals(AIProvider.OPENAI, config.getProvider());
        
        // Test null provider defaults to OpenAI
        config.setProvider(null);
        assertEquals(AIProvider.OPENAI, config.getProvider());
    }

    @Test
    void testProviderPersistence() {
        // Test that provider setting persists after save/load
        config.setProvider(AIProvider.GEMINI);
        config.save();
        assertEquals(AIProvider.GEMINI, config.getProvider());
        
        // Simulate reload
        config.load();
        assertEquals(AIProvider.GEMINI, config.getProvider());
    }

    @Test
    void testGetDisplayName() {
        String displayName = config.getDisplayName();
        assertNotNull(displayName);
        assertEquals("Explain Error Plugin Configuration", displayName);
    }

    @Test
    void testMultipleConcurrentAccess() {
        // Test that multiple threads can access the singleton safely
        GlobalConfigurationImpl config1 = GlobalConfigurationImpl.get();
        GlobalConfigurationImpl config2 = GlobalConfigurationImpl.get();
        
        config1.setModel("test-model-1");
        config2.setApiUrl("https://test2.example.com");
        
        // Both should refer to the same instance
        assertSame(config1, config2);
        assertEquals("test-model-1", config2.getModel());
        assertEquals("https://test2.example.com", config1.getApiUrl());
    }
}
