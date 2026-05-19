package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.*;

import hudson.ProxyConfiguration;
import hudson.util.FormValidation;
import io.jenkins.plugins.explain_error.autofix.FixAssistant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BedrockProviderTest {

    @Test
    void testCreateAssistantDoesNotThrowOnBuild() {
        // This test verifies that the assistant creation doesn't fail on the builder configuration itself
        // It will fail when trying to actually call the API, but that's expected without real credentials
        BedrockProvider provider = new BedrockProvider(
                null, "anthropic.claude-3-5-sonnet-20240620-v1:0", "eu-west-1", null);
        
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
    void testCreateFixAssistantDoesNotThrowOnBuild() {
        // This smoke test catches LangChain4j builder regressions before any real AWS API call is made.
        BedrockProvider provider = new BedrockProvider(
                null, "anthropic.claude-3-5-sonnet-20240620-v1:0", "eu-west-1", null);

        assertDoesNotThrow(() -> {
            try {
                FixAssistant assistant = provider.createFixAssistant();
                assertNotNull(assistant, "Fix assistant should be created");
            } catch (Exception e) {
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
        BedrockProvider provider = new BedrockProvider(null, null, "eu-west-1", null);
        assertTrue(provider.isNotValid(null), "Should be invalid with null model");
    }

    @Test
    void testValidationWithEmptyModel() {
        BedrockProvider provider = new BedrockProvider(null, "", "eu-west-1", null);
        assertTrue(provider.isNotValid(null), "Should be invalid with empty model");
    }

    @Test
    void testValidationWithValidModel() {
        BedrockProvider provider = new BedrockProvider(
                null, "anthropic.claude-3-5-sonnet-20240620-v1:0", "eu-west-1", null);
        assertFalse(provider.isNotValid(null), "Should be valid with model");
    }

    @Test
    void testRegionConfiguration() {
        BedrockProvider provider = new BedrockProvider(null, "test-model", "us-east-1", null);
        assertEquals("us-east-1", provider.getRegion());
    }

    @Test
    void testNullRegion() {
        BedrockProvider provider = new BedrockProvider(null, "test-model", null, null);
        assertNull(provider.getRegion());
    }

    @Test
    void testEmptyRegionIsTrimmedToNull() {
        BedrockProvider provider = new BedrockProvider(null, "test-model", "   ", null);
        assertNull(provider.getRegion(), "Empty/whitespace region should be trimmed to null");
    }

    @Test
    void testRoleArnConfiguration() {
        BedrockProvider provider = new BedrockProvider(
                null,
                "test-model",
                "us-east-1",
                " arn:aws:iam::123456789012:role/JenkinsBedrockInvokeRole ");

        assertEquals("arn:aws:iam::123456789012:role/JenkinsBedrockInvokeRole", provider.getRoleArn());
    }

    @Test
    void testEmptyRoleArnIsTrimmedToNull() {
        BedrockProvider provider = new BedrockProvider(null, "test-model", "us-east-1", "   ");
        assertNull(provider.getRoleArn(), "Empty/whitespace role ARN should be trimmed to null");
    }

    @Test
    void testEndpointConfiguration() {
        BedrockProvider provider = new BedrockProvider(
                "https://vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com",
                "test-model",
                "us-east-1",
                null);

        assertEquals(
                "https://vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com",
                provider.getUrl());
    }

    @Test
    void testHostOnlyEndpointDefaultsToHttps() {
        assertEquals(
                "https://vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com",
                BedrockProvider.normalizeEndpoint(
                        " vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com "));
    }

    @Test
    void testEndpointWithSchemeIsUnchanged() {
        assertEquals(
                "http://127.0.0.1:4566",
                BedrockProvider.normalizeEndpoint("http://127.0.0.1:4566"));
    }

    @Test
    void testEmptyEndpointNormalizesToNull() {
        assertNull(BedrockProvider.normalizeEndpoint("   "));
    }

    @Test
    void testHostOnlyEndpointValidationIsAccepted() {
        FormValidation validation = BedrockProvider.validateEndpoint(
                "vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com");

        assertEquals(FormValidation.Kind.OK, validation.kind);
    }

    @Test
    void testHttpsEndpointValidationIsAccepted() {
        FormValidation validation = BedrockProvider.validateEndpoint(
                "https://vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com");

        assertEquals(FormValidation.Kind.OK, validation.kind);
    }

    @Test
    void testEndpointValidationRejectsUnsupportedSchemes() {
        FormValidation validation = BedrockProvider.validateEndpoint(
                "ftp://vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com");

        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        assertTrue(validation.getMessage().contains("Endpoint must use http or https"));
    }

    @Test
    void testEndpointValidationRejectsEmbeddedCredentials() {
        FormValidation validation = BedrockProvider.validateEndpoint(
                "https://user:password@vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com");

        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        assertTrue(validation.getMessage().contains("Credentials must not be embedded"));
    }

    @Test
    void testEndpointValidationRejectsMalformedEndpoint() {
        FormValidation validation = BedrockProvider.validateEndpoint("https://");

        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        assertTrue(validation.getMessage().contains("Endpoint is not well formed"));
    }

    @Test
    void testParseNoProxyHostsSupportsJenkinsSeparators() {
        assertEquals(
                Set.of(
                        "localhost",
                        ".*\\.internal\\.example\\.com",
                        "vpce-1234567890abcdef\\.bedrock-runtime\\.us-east-1\\.vpce\\.amazonaws\\.com"),
                BedrockProvider.parseNoProxyHosts(
                        "localhost, *.internal.example.com|"
                                + "vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com"));
    }

    @Test
    void testToAwsNonProxyHostPattern() {
        assertEquals("localhost", BedrockProvider.toAwsNonProxyHostPattern("localhost"));
        assertEquals(".*\\.example\\.com", BedrockProvider.toAwsNonProxyHostPattern("*.example.com"));
        assertEquals("host\\.internal\\.example\\.com",
                BedrockProvider.toAwsNonProxyHostPattern("host.internal.example.com"));
    }

    @Test
    void testBuildAwsProxyConfigurationIncludesNoProxyHosts() {
        ProxyConfiguration jenkinsProxy = new ProxyConfiguration(
                "proxy.example.com",
                8080,
                "proxy-user",
                "proxy-password",
                "localhost|vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com");

        software.amazon.awssdk.http.apache.ProxyConfiguration awsProxy =
                BedrockProvider.buildAwsProxyConfiguration(jenkinsProxy);

        assertNotNull(awsProxy);
        assertEquals("http", awsProxy.scheme());
        assertEquals("proxy.example.com", awsProxy.host());
        assertEquals(8080, awsProxy.port());
        assertEquals("proxy-user", awsProxy.username());
        assertEquals("proxy-password", awsProxy.password());
        assertEquals(
                Set.of("localhost",
                        "vpce-1234567890abcdef\\.bedrock-runtime\\.us-east-1\\.vpce\\.amazonaws\\.com"),
                awsProxy.nonProxyHosts());
    }

    @Test
    void testBuildAwsProxyConfigurationSkipsMissingProxy() {
        assertNull(BedrockProvider.buildAwsProxyConfiguration(null));
        assertNull(BedrockProvider.buildAwsProxyConfiguration(new ProxyConfiguration("", 8080)));
    }
}
