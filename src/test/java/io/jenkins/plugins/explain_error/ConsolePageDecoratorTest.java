package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.util.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ConsolePageDecoratorTest {

    private ConsolePageDecorator decorator;
    private GlobalConfigurationImpl config;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        decorator = new ConsolePageDecorator();
        config = GlobalConfigurationImpl.get();

        // Reset to default state
        config.setEnableExplanation(true);
        config.setApiKey(Secret.fromString("test-api-key"));
        config.setProvider(AIProvider.OPENAI);
        config.setModel("gpt-3.5-turbo");
    }

    @Test
    void testDecoratorCreation() {
        assertNotNull(decorator);
    }

    @Test
    void testIsExplainErrorEnabledWithValidConfig() {
        // With valid configuration, should return true
        assertTrue(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWhenDisabled() {
        config.setEnableExplanation(false);

        // Should return false when explanation is disabled
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWithNullApiKey() {
        config.setApiKey(null);

        // Should return false when API key is null
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWithEmptyApiKey() {
        config.setApiKey(Secret.fromString(""));

        // Should return false when API key is empty
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWithBlankApiKey() {
        config.setApiKey(Secret.fromString("   "));

        // Should return false when API key is blank
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testExtensionAnnotation() {
        // Test that the decorator is properly annotated as an Extension
        assertTrue(decorator.getClass().isAnnotationPresent(hudson.Extension.class));
    }

    @Test
    void testInheritance() {
        // Test that the decorator extends PageDecorator
        assertTrue(decorator instanceof hudson.model.PageDecorator);
    }

    @Test
    void testMultipleConditionsDisabled() {
        // Test when multiple conditions are not met
        config.setEnableExplanation(false);
        config.setApiKey(null);

        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testPartiallyValidConfig() {
        // Test with some valid and some invalid settings
        config.setEnableExplanation(true);
        config.setApiKey(null);

        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testAnotherPartiallyValidConfig() {
        // Test with different combination
        config.setEnableExplanation(true);
        config.setApiKey(Secret.fromString("")); // Invalid key

        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testConfigurationChanges() {
        // Test that the decorator responds to configuration changes
        assertTrue(decorator.isExplainErrorEnabled());

        config.setEnableExplanation(false);
        assertFalse(decorator.isExplainErrorEnabled());

        config.setEnableExplanation(true);
        assertTrue(decorator.isExplainErrorEnabled());

        config.setApiKey(null);
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testEdgeCaseApiKey() {
        // Test with various edge cases for API key
        config.setApiKey(Secret.fromString("a")); // Very short key
        assertTrue(decorator.isExplainErrorEnabled()); // Should still work, validation is elsewhere

        config.setApiKey(Secret.fromString("\t\n\r ")); // Whitespace only
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testConsistentBehavior() {
        // Test that multiple calls return consistent results
        boolean result1 = decorator.isExplainErrorEnabled();
        boolean result2 = decorator.isExplainErrorEnabled();
        boolean result3 = decorator.isExplainErrorEnabled();

        assertEquals(result1, result2);
        assertEquals(result2, result3);
    }

    @Test
    void testValidMinimalConfiguration() {
        // Test with minimal valid configuration
        config.setEnableExplanation(true);
        config.setApiKey(Secret.fromString("k"));

        assertTrue(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsPluginActive() {
        // isPluginActive should return the same as isExplainErrorEnabled
        assertEquals(decorator.isExplainErrorEnabled(), decorator.isPluginActive());
        
        config.setEnableExplanation(false);
        assertEquals(decorator.isExplainErrorEnabled(), decorator.isPluginActive());
        
        config.setEnableExplanation(true);
        config.setApiKey(null);
        assertEquals(decorator.isExplainErrorEnabled(), decorator.isPluginActive());
    }
}
