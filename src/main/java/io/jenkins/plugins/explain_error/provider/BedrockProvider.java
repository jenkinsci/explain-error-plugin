package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

public class BedrockProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(BedrockProvider.class.getName());
    private static final String ROLE_SESSION_NAME = "jenkins-explain-error-plugin";
    private static final String ROLE_ARN_PATTERN = "^arn:aws[a-zA-Z-]*:iam::\\d{12}:role/.+";

    private String region;
    private String roleArn;

    @DataBoundConstructor
    public BedrockProvider(String url, String model, String region, String roleArn) {
        super(url, model);
        this.region = Util.fixEmptyAndTrim(region);
        this.roleArn = Util.fixEmptyAndTrim(roleArn);
    }

    public String getRegion() {
        return region;
    }

    public String getRoleArn() {
        return roleArn;
    }

    @Override
    public Assistant createAssistant() {
        ChatModel model = buildChatModel();
        return AiServices.create(Assistant.class, model);
    }

    @Override
    public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant() {
        ChatModel model = buildChatModel();
        return AiServices.create(io.jenkins.plugins.explain_error.autofix.FixAssistant.class, model);
    }

    private ChatModel buildChatModel() {
        var builder = BedrockChatModel.builder()
                .modelId(getModel())
                .defaultRequestParameters(
                        BedrockChatRequestParameters.builder()
                                .temperature(0.3)
                                .build())
                .timeout(Duration.ofSeconds(180))
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));

        String endpoint = normalizeEndpoint(getUrl());
        if (endpoint != null || roleArn != null) {
            builder.client(buildBedrockRuntimeClient(endpoint));
        } else if (region != null) {
            builder.region(Region.of(region));
        }

        return builder.build();
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

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        if (listener != null) {
            if (Util.fixEmptyAndTrim(getModel()) == null) {
                listener.getLogger().println("No Model configured for AWS Bedrock.");
            }
        }
        return Util.fixEmptyAndTrim(getModel()) == null;
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
        public FormValidation doTestConfiguration(@QueryParameter("url") String url,
                                                  @QueryParameter("model") String model,
                                                  @QueryParameter("region") String region,
                                                  @QueryParameter("roleArn") String roleArn)
                throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            BedrockProvider provider = new BedrockProvider(url, model, region, roleArn);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! AWS Bedrock connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }

        @POST
        @Override
        public FormValidation doCheckUrl(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            return validateEndpoint(value);
        }

        @POST
        public FormValidation doCheckRoleArn(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

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
    }
}
