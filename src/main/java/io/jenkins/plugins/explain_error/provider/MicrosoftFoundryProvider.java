package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class MicrosoftFoundryProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(MicrosoftFoundryProvider.class.getName());
    public static final String DEFAULT_MODEL = "gpt-4o";
    private static final String OPENAI_V1_PATH = "/openai/v1";

    private final Secret apiKey;

    @DataBoundConstructor
    public MicrosoftFoundryProvider(String url, String model, Secret apiKey) {
        super(resolveUrl(url), model);
        this.apiKey = apiKey;
    }

    public Secret getApiKey() {
        return apiKey;
    }

    @Override
    public Assistant createAssistant() {
        return createAssistant(null);
    }

    @Override
    public Assistant createAssistant(@CheckForNull Double temperature) {
        ChatModel model = buildChatModel(temperature);
        return AiServices.create(Assistant.class, model);
    }

    @Override
    public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant() {
        ChatModel model = buildChatModel();
        return AiServices.create(io.jenkins.plugins.explain_error.autofix.FixAssistant.class, model);
    }

    private ChatModel buildChatModel() {
        return buildChatModel(null);
    }

    private ChatModel buildChatModel(@CheckForNull Double temperature) {
        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(newLangChainHttpClientBuilder())
                .baseUrl(getUrl())
                .apiKey(getApiKey().getPlainText())
                .modelName(getModel())
                .responseFormat(ResponseFormat.JSON)
                .strictJsonSchema(true)
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        String endpoint = Util.fixEmptyAndTrim(getUrl());
        String configuredModel = Util.fixEmptyAndTrim(getModel());
        String configuredApiKey = Util.fixEmptyAndTrim(Secret.toString(getApiKey()));

        if (listener != null) {
            if (endpoint == null) {
                listener.getLogger().println("No endpoint configured for Microsoft Foundry.");
            } else if (configuredApiKey == null) {
                listener.getLogger().println("No API key configured for Microsoft Foundry.");
            } else if (configuredModel == null) {
                listener.getLogger().println("No model deployment configured for Microsoft Foundry.");
            }
        }

        return endpoint == null || configuredApiKey == null || configuredModel == null;
    }

    private static String resolveUrl(String url) {
        String trimmedUrl = Util.fixEmptyAndTrim(url);
        if (trimmedUrl == null) {
            return null;
        }

        String normalizedUrl = trimmedUrl.endsWith("/")
                ? trimmedUrl.substring(0, trimmedUrl.length() - 1)
                : trimmedUrl;
        if (normalizedUrl.endsWith(OPENAI_V1_PATH)) {
            return normalizedUrl;
        }
        return normalizedUrl + OPENAI_V1_PATH;
    }

    @Extension
    @Symbol("microsoftFoundry")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Microsoft Foundry";
        }

        @Override
        public String getDefaultModel() {
            return DEFAULT_MODEL;
        }

        @POST
        @Override
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Endpoint is required.");
            }
            return super.doCheckUrl(value);
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckModel(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Model deployment is required.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doTestConfiguration(@AncestorInPath Item context,
                                                  @QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) throws ExplanationException {
            checkConfigurePermission(context);

            MicrosoftFoundryProvider provider = new MicrosoftFoundryProvider(url, model, apiKey);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }
    }
}
