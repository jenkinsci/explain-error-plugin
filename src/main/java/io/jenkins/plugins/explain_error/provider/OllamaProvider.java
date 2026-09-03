package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.ollama.OllamaChatModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.springframework.security.core.Authentication;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class OllamaProvider extends ChatModelAIProvider {

    private static final Logger LOGGER = Logger.getLogger(OllamaProvider.class.getName());

    private Secret apiKey;

    @DataBoundConstructor
    public OllamaProvider(String url, String model, Secret apiKey) {
        super(url, model);
        this.apiKey = apiKey;
    }

    public Secret getApiKey() {
        return apiKey;
    }

    @Override
    protected ChatModel createChatModel(@CheckForNull Item item, @CheckForNull Authentication authentication,
                                        @CheckForNull Double temperature) {
        var builder = OllamaChatModel.builder()
                .baseUrl(getUrl())
                .modelName(getModel())
                .responseFormat(ResponseFormat.JSON)
                .think(false)
                .timeout(Duration.ofSeconds(180))
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));
        String resolvedApiKey = Util.fixEmptyAndTrim(Secret.toString(apiKey));
        if (resolvedApiKey != null) {
            builder.customHeaders(Map.of("Authorization", "Bearer " + resolvedApiKey));
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        if (listener != null) {
            if (Util.fixEmptyAndTrim(getUrl()) == null) {
                listener.getLogger().println("No url configured for Ollama.");
            } else if (Util.fixEmptyAndTrim(getModel()) == null) {
                listener.getLogger().println("No Model configured for Ollama.");
            }
        }
        return Util.fixEmptyAndTrim(getUrl()) == null ||
                Util.fixEmptyAndTrim(getModel()) == null;
    }

    @Extension
    @Symbol("ollama")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Ollama";
        }

        public String getDefaultModel() {
            return "gemma3:1b";
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("URL is required.");
            }
            return super.doCheckUrl(value);
        }

        /**
         * Method to test the AI API configuration.
         * This is called when the "Test Configuration" button is clicked.
         */
        @POST
        public FormValidation doTestConfiguration(@AncestorInPath Item context,
                                                  @QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) {
            return runConfigurationTest(context, new OllamaProvider(url, model, apiKey));
        }
    }
}
