package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.*;

import hudson.ProxyConfiguration;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.explain_error.autofix.FixAssistant;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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

    @Test
    void testCreateAssistantWithPrivateEndpointRoleAndTemperatureBuildsWithoutCallingAws() {
        // Building the clients is purely local: credentials and the STS role are only used on the first request.
        BedrockProvider provider = new BedrockProvider(
                "vpce-1234567890abcdef.bedrock-runtime.us-east-1.vpce.amazonaws.com",
                "anthropic.claude-3-5-sonnet-20240620-v1:0", "us-east-1",
                "arn:aws:iam::123456789012:role/JenkinsBedrockInvokeRole");

        assertNotNull(provider.createAssistant(null, null, 0.4));
        assertNotNull(provider.createFixAssistant(null, null));
    }

    @Test
    void testCreateAssistantWithEndpointOnlyUsesTheConfiguredRegion() {
        BedrockProvider provider = new BedrockProvider("https://bedrock.internal.example",
                "anthropic.claude-3-5-sonnet-20240620-v1:0", "eu-central-1", null);

        assertNotNull(provider.createAssistant(null, null, null));
    }

    @Test
    void testEffectiveEndpointForDiagnostics() {
        assertEquals("https://bedrock.internal.example",
                new BedrockProvider("https://bedrock.internal.example", "m", "eu-west-1", null)
                        .getEffectiveEndpointForDiagnostics());
        assertEquals("https://vpce.example.com",
                new BedrockProvider(" vpce.example.com ", "m", null, null).getEffectiveEndpointForDiagnostics());
        assertEquals("https://bedrock-runtime.eu-west-1.amazonaws.com",
                new BedrockProvider(null, "m", "eu-west-1", null).getEffectiveEndpointForDiagnostics());
        assertNull(new BedrockProvider(" ", "m", null, null).getEffectiveEndpointForDiagnostics());
    }

    @Test
    void testValidationMessageForMissingModel() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertTrue(new BedrockProvider(null, " ", "eu-west-1", null)
                .isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8)));
        assertEquals("No Model configured for AWS Bedrock.", out.toString(StandardCharsets.UTF_8).trim());

        out.reset();
        assertFalse(new BedrockProvider(null, "m", "eu-west-1", null)
                .isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8)));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEndpointValidationAcceptsBlankAndRejectsHostlessUrls() {
        assertEquals(FormValidation.Kind.OK, BedrockProvider.validateEndpoint(null).kind);
        assertEquals(FormValidation.Kind.OK, BedrockProvider.validateEndpoint("  ").kind);

        FormValidation hostless = BedrockProvider.validateEndpoint("file:///tmp/bedrock");
        assertEquals(FormValidation.Kind.ERROR, hostless.kind);
        assertTrue(hostless.getMessage().contains("Endpoint is not well formed"));
    }

    @Test
    void testDescriptorChecksAndDefaults() {
        BedrockProvider.DescriptorImpl descriptor = new BedrockProvider.DescriptorImpl();

        assertEquals("AWS Bedrock", descriptor.getDisplayName());
        assertEquals("eu.anthropic.claude-3-5-sonnet-20240620-v1:0", descriptor.getDefaultModel());
        assertEquals("eu-west-1", descriptor.getDefaultRegion());
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUrl("vpce.example.com").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckUrl("ftp://vpce.example.com").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckRoleArn(" ").kind);
        assertEquals(FormValidation.Kind.OK,
                descriptor.doCheckRoleArn("arn:aws:iam::123456789012:role/JenkinsBedrockInvokeRole").kind);
        assertEquals(FormValidation.Kind.OK,
                descriptor.doCheckRoleArn("arn:aws-us-gov:iam::123456789012:role/path/Role").kind);
        FormValidation invalidRole = descriptor.doCheckRoleArn("arn:aws:iam::123:user/someone");
        assertEquals(FormValidation.Kind.ERROR, invalidRole.kind);
        assertTrue(invalidRole.getMessage().contains("Role ARN must be an IAM role ARN"));
    }

    @Test
    void testProxyWithoutCredentialsOrExclusions() {
        assertEquals(Set.of(), BedrockProvider.parseNoProxyHosts(" "));
        assertEquals(Set.of(), BedrockProvider.parseNoProxyHosts(null));

        software.amazon.awssdk.http.apache.ProxyConfiguration awsProxy =
                BedrockProvider.buildAwsProxyConfiguration(new ProxyConfiguration("proxy.example.com", 3128));

        assertNotNull(awsProxy);
        assertEquals("proxy.example.com", awsProxy.host());
        assertEquals(3128, awsProxy.port());
        assertNull(awsProxy.username());
    }
}
