package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BedrockProviderTest {

    @Test
    void testCreateAssistantDoesNotThrowOnBuild() {
        // This test verifies that the assistant creation doesn't fail on the builder configuration itself
        // It will fail when trying to actually call the API, but that's expected without real credentials
        BedrockProvider provider = new BedrockProvider(null, "anthropic.claude-3-5-sonnet-20240620-v1:0", "eu-west-1");
        
        // This should not throw any IllegalArgumentException or similar from invalid configuration
        // The responseFormat parameter was causing this issue before
        assertDoesNotThrow(() -> {
            try {
                BaseAIProvider.Assistant assistant = provider.createAssistant();
                assertNotNull(assistant, "Assistant should be created");
            } catch (Exception e) {
                // We expect failures related to credentials/network, not configuration
                // If it's a configuration error, it will typically be IllegalArgumentException
                assertFalse(
                    e.getClass().getSimpleName().contains("IllegalArgument") || 
                    e.getMessage() != null && e.getMessage().contains("Unknown field"),
                    "Should not fail due to configuration errors: " + e.getMessage()
                );
            }
        });
    }

    @Test
    void testValidationWithNullModel() {
        BedrockProvider provider = new BedrockProvider(null, null, "eu-west-1");
        assertTrue(provider.isNotValid(null), "Should be invalid with null model");
    }

    @Test
    void testValidationWithEmptyModel() {
        BedrockProvider provider = new BedrockProvider(null, "", "eu-west-1");
        assertTrue(provider.isNotValid(null), "Should be invalid with empty model");
    }

    @Test
    void testValidationWithValidModel() {
        BedrockProvider provider = new BedrockProvider(null, "anthropic.claude-3-5-sonnet-20240620-v1:0", "eu-west-1");
        assertFalse(provider.isNotValid(null), "Should be valid with model");
    }

    @Test
    void testRegionConfiguration() {
        BedrockProvider provider = new BedrockProvider(null, "test-model", "us-east-1");
        assertEquals("us-east-1", provider.getRegion());
    }

    @Test
    void testNullRegion() {
        BedrockProvider provider = new BedrockProvider(null, "test-model", null);
        assertNull(provider.getRegion());
    }

    @Test
    void testEmptyRegionIsTrimmedToNull() {
        BedrockProvider provider = new BedrockProvider(null, "test-model", "   ");
        assertNull(provider.getRegion(), "Empty/whitespace region should be trimmed to null");
    }
}
