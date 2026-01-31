package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

public class DeepSeekProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(DeepSeekProvider.class.getName());
    private static final String DEFAULT_URL = "https://api.deepseek.com";

    protected Secret apiKey;

    @DataBoundConstructor
    public DeepSeekProvider(String url, String model, Secret apiKey) {
        super(url, model);
        this.apiKey = apiKey;
    }

    public Secret getApiKey() {
        return apiKey;
    }

    @Override
    public Assistant createAssistant() {
        // DeepSeek provides an OpenAI-compatible API
        String baseUrl = Util.fixEmptyAndTrim(getUrl());
        if (baseUrl == null) {
            baseUrl = DEFAULT_URL;
        }

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(getApiKey().getPlainText())
                .modelName(getModel())
                .temperature(0.3)
                .responseFormat(ResponseFormat.JSON)
                .strictJsonSchema(true)
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE))
                .build();

        return AiServices.create(Assistant.class, model);
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        if (listener != null) {
            if (Util.fixEmptyAndTrim(Secret.toString(getApiKey())) == null) {
                listener.getLogger().println("No Api key configured for DeepSeek.");
            } else if (Util.fixEmptyAndTrim(getModel()) == null) {
                listener.getLogger().println("No Model configured for DeepSeek.");
            }
        }
        return Util.fixEmptyAndTrim(Secret.toString(getApiKey())) == null ||
                Util.fixEmptyAndTrim(getModel()) == null;
    }

    @Extension
    @Symbol("deepseek")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        private static final String[] MODELS = new String[]{
                "deepseek-chat",
                "deepseek-coder",
                "deepseek-reasoner"
        };

        @NonNull
        @Override
        public String getDisplayName() {
            return "DeepSeek";
        }

        public String getDefaultModel() {
            return "deepseek-chat";
        }

        @GET
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public AutoCompletionCandidates doAutoCompleteModel(@QueryParameter String value) {
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            for (String model : MODELS) {
                if (model.toLowerCase().contains(value.toLowerCase())) {
                    c.add(model);
                }
            }
            return c;
        }

        /**
         * Method to test the AI API configuration.
         * This is called when the "Test Configuration" button is clicked.
         */
        @POST
        public FormValidation doTestConfiguration(@QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            DeepSeekProvider provider = new DeepSeekProvider(url, model, apiKey);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }

    }

}
