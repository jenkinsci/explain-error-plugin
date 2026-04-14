package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Generic provider for OpenAI-compatible custom AI endpoints.
 */
public class CustomProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(CustomProvider.class.getName());
    public static final String DEFAULT_MODEL = "gpt-4.1-mini";
    public static final String DEFAULT_API_KEY_HEADER = "Authorization";
    public static final String DEFAULT_API_KEY_PREFIX = "Bearer";
    public static final int DEFAULT_TIMEOUT_SECONDS = 180;

    private Secret apiKey;
    private String apiKeyHeader;
    private String apiKeyPrefix;
    private Integer timeoutSeconds;

    @DataBoundConstructor
    public CustomProvider(String url, String model, Secret apiKey) {
        super(Util.fixEmptyAndTrim(url), Util.fixEmptyAndTrim(model));
        this.apiKey = apiKey;
        this.apiKeyHeader = DEFAULT_API_KEY_HEADER;
        this.apiKeyPrefix = DEFAULT_API_KEY_PREFIX;
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    public Secret getApiKey() {
        return apiKey;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    @DataBoundSetter
    public void setApiKeyHeader(String apiKeyHeader) {
        this.apiKeyHeader = Util.fixEmptyAndTrim(apiKeyHeader);
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    @DataBoundSetter
    public void setApiKeyPrefix(String apiKeyPrefix) {
        this.apiKeyPrefix = Util.fixEmptyAndTrim(apiKeyPrefix);
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @DataBoundSetter
    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Assistant createAssistant() {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(getUrl())
                .apiKey(Secret.toString(getApiKey()))
                .modelName(getModel())
                .temperature(0.3)
                .responseFormat(ResponseFormat.JSON)
                .strictJsonSchema(true)
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));

        Map<String, String> headers = buildCustomHeaders();
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }

        ChatModel model = builder.build();
        return AiServices.create(Assistant.class, model);
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        String apiKeyValue = Util.fixEmptyAndTrim(Secret.toString(getApiKey()));
        String modelValue = Util.fixEmptyAndTrim(getModel());
        String urlValue = Util.fixEmptyAndTrim(getUrl());

        if (listener != null) {
            if (urlValue == null) {
                listener.getLogger().println("No URL configured for Custom provider.");
            } else if (apiKeyValue == null) {
                listener.getLogger().println("No API key configured for Custom provider.");
            } else if (modelValue == null) {
                listener.getLogger().println("No model configured for Custom provider.");
            }
        }

        return urlValue == null || apiKeyValue == null || modelValue == null;
    }

    private int resolveTimeoutSeconds() {
        if (timeoutSeconds == null || timeoutSeconds < 1) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return timeoutSeconds;
    }

    private Map<String, String> buildCustomHeaders() {
        String apiKeyValue = Util.fixEmptyAndTrim(Secret.toString(getApiKey()));
        if (apiKeyValue == null) {
            return Map.of();
        }

        String headerName = Util.fixEmptyAndTrim(getApiKeyHeader());
        if (headerName == null) {
            headerName = DEFAULT_API_KEY_HEADER;
        }

        String prefix = Util.fixEmptyAndTrim(getApiKeyPrefix());
        String headerValue = prefix == null ? apiKeyValue : prefix + " " + apiKeyValue;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(headerName, headerValue);
        return headers;
    }

    @Extension
    @Symbol("custom")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Custom";
        }

        @Override
        public String getDefaultModel() {
            return DEFAULT_MODEL;
        }

        public String getDefaultApiKeyHeader() {
            return DEFAULT_API_KEY_HEADER;
        }

        public String getDefaultApiKeyPrefix() {
            return DEFAULT_API_KEY_PREFIX;
        }

        public int getDefaultTimeoutSeconds() {
            return DEFAULT_TIMEOUT_SECONDS;
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @Override
        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("URL is required.");
            }
            return super.doCheckUrl(value);
        }

        @POST
        public FormValidation doCheckTimeoutSeconds(@QueryParameter Integer value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (value == null) {
                return FormValidation.ok();
            }
            if (value < 1) {
                return FormValidation.error("Timeout must be greater than 0.");
            }
            if (value > 600) {
                return FormValidation.warning("Timeout above 600 seconds may cause long waits.");
            }
            return FormValidation.ok();
        }

        /**
         * Method to test the AI API configuration.
         * This is called when the "Test Configuration" button is clicked.
         */
        @POST
        public FormValidation doTestConfiguration(@QueryParameter("url") String url,
                                                  @QueryParameter("model") String model,
                                                  @QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("apiKeyHeader") String apiKeyHeader,
                                                  @QueryParameter("apiKeyPrefix") String apiKeyPrefix,
                                                  @QueryParameter("timeoutSeconds") Integer timeoutSeconds)
                throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            CustomProvider provider = new CustomProvider(url, model, apiKey);
            provider.setApiKeyHeader(apiKeyHeader);
            provider.setApiKeyPrefix(apiKeyPrefix);
            provider.setTimeoutSeconds(timeoutSeconds);

            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }
    }
}
