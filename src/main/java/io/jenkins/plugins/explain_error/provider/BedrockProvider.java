package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.regions.Region;

public class BedrockProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(BedrockProvider.class.getName());

    private String region;

    @DataBoundConstructor
    public BedrockProvider(String url, String model, String region) {
        super(url, model);
        this.region = Util.fixEmptyAndTrim(region);
    }

    public String getRegion() {
        return region;
    }

    @Override
    public Assistant createAssistant() {
        var builder = BedrockChatModel.builder()
                .modelId(getModel())
                .defaultRequestParameters(
                        BedrockChatRequestParameters.builder()
                                .temperature(0.3)
                                .build())
                .timeout(Duration.ofSeconds(180))
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));

        if (region != null) {
            builder.region(Region.of(region));
        }

        ChatModel model = builder.build();
        return AiServices.create(Assistant.class, model);
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

    @Extension
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
        public FormValidation doTestConfiguration(@QueryParameter("model") String model,
                                                  @QueryParameter("region") String region) throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            BedrockProvider provider = new BedrockProvider(null, model, region);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! AWS Bedrock connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }
    }
}
