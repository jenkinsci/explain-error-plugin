package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Guards that the AWS Bedrock provider is wired up as a selectable AI provider
 * when its dependency is available.
 *
 * <p>The System configuration dropdown is populated by descriptor discovery over
 * {@link BaseAIProvider}. The Bedrock descriptor is registered only when the
 * {@code aws-java-sdk2-core} plugin is present — an intentionally optional
 * dependency, so instances that do not use Bedrock are not forced to carry the
 * AWS SDK. The test harness provides that dependency, so this test verifies the
 * descriptor is correctly registered (via {@code @OptionalExtension} and
 * {@code @Symbol}) whenever the AWS SDK plugin is installed.
 */
@WithJenkins
class BedrockProviderRegistrationTest {

    @Test
    void bedrockProviderIsSelectable(JenkinsRule jenkins) {
        boolean registered = jenkins.jenkins.getDescriptorList(BaseAIProvider.class).stream()
                .anyMatch(descriptor -> descriptor instanceof BedrockProvider.DescriptorImpl);
        assertTrue(registered,
                "AWS Bedrock provider descriptor must be registered and selectable in the AI Provider list");
    }
}
