package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockChatResponseMetadata;
import dev.langchain4j.model.bedrock.BedrockGuardrailConfiguration;
import dev.langchain4j.model.bedrock.GuardrailAssessment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.springframework.security.core.Authentication;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

public class BedrockProvider extends ChatModelAIProvider {

    private static final Logger LOGGER = Logger.getLogger(BedrockProvider.class.getName());
    private static final String ROLE_SESSION_NAME = "jenkins-explain-error-plugin";
    private static final String ROLE_ARN_PATTERN = "^arn:aws[a-zA-Z-]*:iam::\\d{12}:role/.+";
    // Same shapes the Bedrock Runtime API accepts for GuardrailConfiguration.
    private static final String GUARDRAIL_IDENTIFIER_PATTERN =
            "[a-z0-9]+|arn:aws(-[^:]+)?:bedrock:[a-z0-9-]{1,20}:\\d{12}:guardrail/[a-z0-9]+";
    private static final String GUARDRAIL_VERSION_PATTERN = "[1-9]\\d{0,7}|DRAFT";
    private static final String INCOMPLETE_GUARDRAIL_MESSAGE =
            "AWS Bedrock guardrail needs both a Guardrail Identifier and a Guardrail Version.";

    private String region;
    private String roleArn;
    private String guardrailIdentifier;
    private String guardrailVersion;

    @DataBoundConstructor
    public BedrockProvider(String url, String model, String region, String roleArn) {
        super(url, model);
        this.region = Util.fixEmptyAndTrim(region);
        this.roleArn = Util.fixEmptyAndTrim(roleArn);
    }

    public String getRegion() {
        return region;
    }

    @Override
    public String getEffectiveEndpointForDiagnostics() {
        String configured = Util.fixEmptyAndTrim(getUrl());
        if (configured != null) {
            // A private VPC endpoint may be configured as a bare hostname.
            return configured.contains("://") ? configured : "https://" + configured;
        }
        return region != null ? "https://bedrock-runtime." + region + ".amazonaws.com" : null;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public String getGuardrailIdentifier() {
        return guardrailIdentifier;
    }

    @DataBoundSetter
    public void setGuardrailIdentifier(String guardrailIdentifier) {
        this.guardrailIdentifier = Util.fixEmptyAndTrim(guardrailIdentifier);
    }

    public String getGuardrailVersion() {
        return guardrailVersion;
    }

    @DataBoundSetter
    public void setGuardrailVersion(String guardrailVersion) {
        this.guardrailVersion = Util.fixEmptyAndTrim(guardrailVersion);
    }

    // A half-configured guardrail must never be dropped silently: that would send unguarded requests.
    private boolean hasIncompleteGuardrail() {
        return (guardrailIdentifier == null) != (guardrailVersion == null);
    }

    @Override
    protected ChatModel createChatModel(@CheckForNull Item item, @CheckForNull Authentication authentication,
                                        @CheckForNull Double temperature) {
        if (hasIncompleteGuardrail()) {
            throw new IllegalStateException(INCOMPLETE_GUARDRAIL_MESSAGE);
        }

        var paramsBuilder = BedrockChatRequestParameters.builder();
        if (temperature != null) {
            paramsBuilder.temperature(temperature);
        }
        boolean guarded = guardrailIdentifier != null;
        if (guarded) {
            paramsBuilder.guardrailConfiguration(BedrockGuardrailConfiguration.builder()
                    .guardrailIdentifier(guardrailIdentifier)
                    .guardrailVersion(guardrailVersion)
                    .build());
        }

        var builder = BedrockChatModel.builder()
                .modelId(getModel())
                .defaultRequestParameters(paramsBuilder.build())
                .timeout(Duration.ofSeconds(180))
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));

        String endpoint = normalizeEndpoint(getUrl());
        if (endpoint != null || roleArn != null) {
            builder.client(buildBedrockRuntimeClient(endpoint));
        } else if (region != null) {
            builder.region(Region.of(region));
        }

        return guarded ? new GuardedBedrockChatModel(builder) : builder.build();
    }

    /**
     * Reports a guardrail block as a readable error. Bedrock answers a blocked request with a
     * normal response carrying the guardrail's canned message, which would otherwise surface
     * as a structured-output parsing failure.
     */
    private static final class GuardedBedrockChatModel extends BedrockChatModel {

        GuardedBedrockChatModel(Builder builder) {
            super(builder);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            ChatResponse response = super.doChat(request);
            if (isBlockedByGuardrail(response)) {
                String blockedMessage = Util.fixEmptyAndTrim(response.aiMessage().text());
                throw new ContentFilteredException(blockedMessage == null
                        ? "AWS Bedrock guardrail blocked the request."
                        : "AWS Bedrock guardrail blocked the request: " + blockedMessage);
            }
            return response;
        }
    }

    private static boolean isBlockedByGuardrail(ChatResponse response) {
        if (response.finishReason() != FinishReason.CONTENT_FILTER) {
            return false;
        }
        List<GuardrailAssessment.Action> actions = List.of();
        if (response.metadata() instanceof BedrockChatResponseMetadata metadata
                && metadata.guardrailAssessmentSummary() != null) {
            var summary = metadata.guardrailAssessmentSummary();
            actions = Stream.of(summary.inputAssessments(), summary.outputAssessments())
                    .filter(Objects::nonNull)
                    .flatMap(List::stream)
                    .map(GuardrailAssessment::action)
                    .toList();
        }
        // A guardrail that only masked sensitive values left a usable answer; anything else replaced it.
        return actions.contains(GuardrailAssessment.Action.BLOCKED)
                || !actions.contains(GuardrailAssessment.Action.ANONYMIZED);
    }

    private BedrockRuntimeClient buildBedrockRuntimeClient(@CheckForNull String endpoint) {
        var clientBuilder = BedrockRuntimeClient.builder()
                .httpClientBuilder(newAwsHttpClientBuilder());

        Region awsRegion = region == null ? null : Region.of(region);
        if (awsRegion != null) {
            clientBuilder.region(awsRegion);
        }
        if (endpoint != null) {
            clientBuilder.endpointOverride(URI.create(endpoint));
        }
        if (roleArn != null) {
            clientBuilder.credentialsProvider(buildAssumeRoleCredentialsProvider(awsRegion));
        }

        return clientBuilder.build();
    }

    private AwsCredentialsProvider buildAssumeRoleCredentialsProvider(@CheckForNull Region awsRegion) {
        var stsClientBuilder = StsClient.builder()
                .httpClientBuilder(newAwsHttpClientBuilder());
        if (awsRegion != null) {
            stsClientBuilder.region(awsRegion);
        }

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClientBuilder.build())
                .refreshRequest(AssumeRoleRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName(ROLE_SESSION_NAME)
                        .build())
                .build();
    }

    private ApacheHttpClient.Builder newAwsHttpClientBuilder() {
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        ProxyConfiguration proxyConfiguration = jenkins != null ? jenkins.getProxy() : null;
        software.amazon.awssdk.http.apache.ProxyConfiguration awsProxyConfiguration =
                buildAwsProxyConfiguration(proxyConfiguration);
        if (awsProxyConfiguration != null) {
            httpClientBuilder.proxyConfiguration(awsProxyConfiguration);
        }
        return httpClientBuilder;
    }

    @CheckForNull
    static software.amazon.awssdk.http.apache.ProxyConfiguration buildAwsProxyConfiguration(
            @CheckForNull ProxyConfiguration proxyConfiguration) {
        if (proxyConfiguration == null || Util.fixEmptyAndTrim(proxyConfiguration.getName()) == null) {
            return null;
        }

        var builder = software.amazon.awssdk.http.apache.ProxyConfiguration.builder()
                .endpoint(URI.create("http://" + proxyConfiguration.getName() + ":" + proxyConfiguration.getPort()));

        String userName = Util.fixEmptyAndTrim(proxyConfiguration.getUserName());
        if (userName != null) {
            builder.username(userName);
            builder.password(Secret.toString(proxyConfiguration.getSecretPassword()));
        }

        Set<String> nonProxyHosts = parseNoProxyHosts(proxyConfiguration.getNoProxyHost());
        if (!nonProxyHosts.isEmpty()) {
            builder.nonProxyHosts(nonProxyHosts);
        }
        return builder.build();
    }

    static Set<String> parseNoProxyHosts(@CheckForNull String noProxyHost) {
        String value = Util.fixEmptyAndTrim(noProxyHost);
        if (value == null) {
            return Set.of();
        }
        return Arrays.stream(value.split("[\\s,|]+"))
                .map(Util::fixEmptyAndTrim)
                .filter(host -> host != null)
                .map(BedrockProvider::toAwsNonProxyHostPattern)
                .collect(Collectors.toSet());
    }

    /**
     * Converts a Jenkins-style noProxyHost pattern (glob) to a Java regex pattern
     * suitable for the AWS SDK's SdkProxyRoutePlanner, which uses
     * {@link String#matches(String)} for host matching.
     *
     * <p>The conversion follows the same logic as Jenkins'
     * {@code ProxyConfiguration.getNoProxyHostPatterns()}:
     * <ul>
     *   <li>{@code .} is escaped to {@code \.}</li>
     *   <li>{@code *} is expanded to {@code .*}</li>
     * </ul>
     */
    static String toAwsNonProxyHostPattern(String globPattern) {
        return globPattern.replace(".", "\\.").replace("*", ".*");
    }

    @CheckForNull
    static String normalizeEndpoint(@CheckForNull String endpoint) {
        String value = Util.fixEmptyAndTrim(endpoint);
        if (value == null) {
            return null;
        }
        if (!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            return "https://" + value;
        }
        return value;
    }

    static FormValidation validateEndpoint(@CheckForNull String value) {
        String endpoint = normalizeEndpoint(value);
        if (endpoint == null) {
            return FormValidation.ok();
        }
        try {
            URI uri = new URL(endpoint).toURI();
            String scheme = uri.getScheme();
            if (uri.getHost() == null) {
                return FormValidation.error("Endpoint is not well formed.");
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return FormValidation.error("Endpoint must use http or https");
            }
            if (uri.getUserInfo() != null) {
                return FormValidation.error(
                        "Credentials must not be embedded in the endpoint. Use the dedicated secret fields instead.");
            }
        } catch (MalformedURLException | URISyntaxException e) {
            return FormValidation.error(e, "Endpoint is not well formed.");
        }
        return FormValidation.ok();
    }

    static FormValidation validateGuardrailIdentifier(@CheckForNull String value, @CheckForNull String version) {
        String identifier = Util.fixEmptyAndTrim(value);
        if (identifier == null) {
            return Util.fixEmptyAndTrim(version) == null
                    ? FormValidation.ok()
                    : FormValidation.error("Guardrail Identifier is required when a Guardrail Version is set.");
        }
        if (!identifier.matches(GUARDRAIL_IDENTIFIER_PATTERN)) {
            return FormValidation.error("Guardrail Identifier must be a guardrail ID or ARN, for example "
                    + "arn:aws:bedrock:us-east-1:123456789012:guardrail/abc123.");
        }
        return FormValidation.ok();
    }

    static FormValidation validateGuardrailVersion(@CheckForNull String value, @CheckForNull String identifier) {
        String version = Util.fixEmptyAndTrim(value);
        if (version == null) {
            return Util.fixEmptyAndTrim(identifier) == null
                    ? FormValidation.ok()
                    : FormValidation.error("Guardrail Version is required when a Guardrail Identifier is set.");
        }
        if (!version.matches(GUARDRAIL_VERSION_PATTERN)) {
            return FormValidation.error("Guardrail Version must be DRAFT or a version number, for example 1.");
        }
        return FormValidation.ok();
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        boolean missingModel = Util.fixEmptyAndTrim(getModel()) == null;
        boolean incompleteGuardrail = hasIncompleteGuardrail();
        if (listener != null) {
            if (missingModel) {
                listener.getLogger().println("No Model configured for AWS Bedrock.");
            }
            if (incompleteGuardrail) {
                listener.getLogger().println(INCOMPLETE_GUARDRAIL_MESSAGE);
            }
        }
        return missingModel || incompleteGuardrail;
    }

    @OptionalExtension(requirePlugins="aws-java-sdk2-core")
    @Symbol("bedrock")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "AWS Bedrock";
        }

        public String getDefaultModel() {
            return "eu.anthropic.claude-3-5-sonnet-20240620-v1:0";
        }

        public String getDefaultRegion() {
            return "eu-west-1";
        }

        /**
         * Method to test the AI API configuration.
         * This is called when the "Test Configuration" button is clicked.
         */
        @POST
        public FormValidation doTestConfiguration(@AncestorInPath Item context,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model,
                                                  @QueryParameter("region") String region,
                                                  @QueryParameter("roleArn") String roleArn,
                                                  @QueryParameter("guardrailIdentifier") String guardrailIdentifier,
                                                  @QueryParameter("guardrailVersion") String guardrailVersion) {
            BedrockProvider provider = new BedrockProvider(url, model, region, roleArn);
            provider.setGuardrailIdentifier(guardrailIdentifier);
            provider.setGuardrailVersion(guardrailVersion);
            return runConfigurationTest(context, provider);
        }

        @POST
        @Override
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckUrl(@QueryParameter String value) {
            return validateEndpoint(value);
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckRoleArn(@QueryParameter String value) {
            String roleArn = Util.fixEmptyAndTrim(value);
            if (roleArn == null) {
                return FormValidation.ok();
            }
            if (!roleArn.matches(ROLE_ARN_PATTERN)) {
                return FormValidation.error("Role ARN must be an IAM role ARN, for example "
                        + "arn:aws:iam::123456789012:role/JenkinsBedrockInvokeRole.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckGuardrailIdentifier(@AncestorInPath Item context,
                                                         @QueryParameter String value,
                                                         @QueryParameter String guardrailVersion) {
            checkConfigurePermission(context);
            return validateGuardrailIdentifier(value, guardrailVersion);
        }

        @POST
        public FormValidation doCheckGuardrailVersion(@AncestorInPath Item context,
                                                      @QueryParameter String value,
                                                      @QueryParameter String guardrailIdentifier) {
            checkConfigurePermission(context);
            return validateGuardrailVersion(value, guardrailIdentifier);
        }
    }
}
