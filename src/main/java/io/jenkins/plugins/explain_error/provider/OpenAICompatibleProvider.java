package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
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
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Generic provider for any OpenAI-compatible endpoint: LiteLLM, OpenWebUI,
 * self-hosted AI proxies and enterprise API gateways.
 *
 * <p>Unlike {@link OpenAIProvider}, the API key is optional so that
 * unauthenticated local gateways and proxies can be used without one.
 * The model name is free text because gateway model names are defined by the
 * gateway (e.g. {@code gpt-4o}, {@code azure/gpt-4o}, {@code claude-3-5-sonnet}).
 */
public class OpenAICompatibleProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(OpenAICompatibleProvider.class.getName());

    private Secret apiKey;

    @DataBoundConstructor
    public OpenAICompatibleProvider(String url, String model, Secret apiKey) {
        super(Util.fixEmptyAndTrim(url), Util.fixEmptyAndTrim(model));
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
        ChatModel model = buildChatModel(null);
        return AiServices.create(io.jenkins.plugins.explain_error.autofix.FixAssistant.class, model);
    }

    private ChatModel buildChatModel(@CheckForNull Double temperature) {
        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(newLangChainHttpClientBuilder())
                .baseUrl(getUrl())
                .modelName(getModel())
                .responseFormat(ResponseFormat.JSON)
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));
        String resolvedApiKey = Util.fixEmptyAndTrim(Secret.toString(apiKey));
        if (resolvedApiKey != null) {
            builder.apiKey(resolvedApiKey);
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return new ErrorMappingChatModel(builder.build());
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        if (listener != null) {
            if (Util.fixEmptyAndTrim(getUrl()) == null) {
                listener.getLogger().println("No URL configured for OpenAI Compatible.");
            } else if (Util.fixEmptyAndTrim(getModel()) == null) {
                listener.getLogger().println("No model configured for OpenAI Compatible.");
            }
        }
        return Util.fixEmptyAndTrim(getUrl()) == null
                || Util.fixEmptyAndTrim(getModel()) == null;
    }

    /**
     * Wraps a {@link ChatModel} to translate low-level HTTP failures — including
     * non-standard error responses returned by AI gateways and proxies — into
     * clear, actionable messages.
     */
    private static class ErrorMappingChatModel implements ChatModel {

        private final ChatModel delegate;

        ErrorMappingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            try {
                return delegate.chat(chatRequest);
            } catch (AuthenticationException e) {
                // langchain4j maps HTTP 401/403 to AuthenticationException; the message
                // is the raw gateway response body, which is unhelpful on its own.
                throw new RuntimeException(buildAuthErrorMessage(e), e);
            } catch (HttpException e) {
                throw new RuntimeException(buildHttpErrorMessage(e), e);
            }
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return delegate.supportedCapabilities();
        }

        private static String buildAuthErrorMessage(AuthenticationException e) {
            String body = e.getMessage();
            int statusCode = findHttpStatusCode(e);
            String statusPart = statusCode > 0 ? " (HTTP " + statusCode + ")" : "";
            return "Authentication failed" + statusPart + ". "
                    + "Verify that the API key is correct and that the endpoint accepts Bearer token authentication."
                    + (StringUtils.isBlank(body) ? "" : " Gateway response: " + body);
        }

        private static String buildHttpErrorMessage(HttpException e) {
            String body = e.getMessage();
            return "Request to the AI endpoint failed with HTTP " + e.statusCode()
                    + (StringUtils.isBlank(body) ? "." : ": " + body);
        }

        private static int findHttpStatusCode(Throwable throwable) {
            Throwable current = throwable;
            while (current != null) {
                if (current instanceof HttpException httpException) {
                    return httpException.statusCode();
                }
                current = current.getCause();
            }
            return -1;
        }
    }

    @Extension
    @Symbol("openaiCompatible")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "OpenAI Compatible";
        }

        @Override
        public String getDefaultModel() {
            return "";
        }

        @Override
        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("URL is required.");
            }
            return super.doCheckUrl(value);
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckModel(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Model is required.");
            }
            return FormValidation.ok();
        }

        /**
         * Method to test the AI API configuration.
         * This is called when the "Test Configuration" button is clicked.
         */
        @POST
        public FormValidation doTestConfiguration(@AncestorInPath Item context,
                                                  @QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) throws ExplanationException {
            checkConfigurePermission(context);

            OpenAICompatibleProvider provider = new OpenAICompatibleProvider(url, model, apiKey);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return testConfigurationFailed(provider, e);
            }
        }
    }
}
