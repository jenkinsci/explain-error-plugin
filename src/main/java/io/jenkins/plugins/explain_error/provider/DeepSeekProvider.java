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
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

public class DeepSeekProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(DeepSeekProvider.class.getName());
    public static final String DEFAULT_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private Secret apiKey;

    @DataBoundConstructor
    public DeepSeekProvider(String url, String model, Secret apiKey) {
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
        ChatModel model = buildChatModel(null);
        return AiServices.create(io.jenkins.plugins.explain_error.autofix.FixAssistant.class, model);
    }

    private ChatModel buildChatModel(@CheckForNull Double temperature) {
        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(newLangChainHttpClientBuilder())
                .baseUrl(getUrl())
                .apiKey(getApiKey().getPlainText())
                .modelName(getModel())
                .responseFormat(ResponseFormat.JSON)
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        if (listener != null) {
            if (Util.fixEmptyAndTrim(Secret.toString(getApiKey())) == null) {
                listener.getLogger().println("No API key configured for DeepSeek.");
            } else if (Util.fixEmptyAndTrim(getModel()) == null) {
                listener.getLogger().println("No model configured for DeepSeek.");
            }
        }
        return Util.fixEmptyAndTrim(Secret.toString(getApiKey())) == null
                || Util.fixEmptyAndTrim(getModel()) == null;
    }

    private static String resolveUrl(String url) {
        String trimmedUrl = Util.fixEmptyAndTrim(url);
        return trimmedUrl != null ? trimmedUrl : DEFAULT_URL;
    }

    @Extension
    @Symbol("deepseek")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        private static final String[] MODELS = new String[]{
                "deepseek-v4-flash",
                "deepseek-v4-pro",
                "deepseek-chat",
                "deepseek-reasoner"
        };

        @NonNull
        @Override
        public String getDisplayName() {
            return "DeepSeek";
        }

        @Override
        public String getDefaultModel() {
            return DEFAULT_MODEL;
        }

        public String getDefaultUrl() {
            return DEFAULT_URL;
        }

        @GET
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public AutoCompletionCandidates doAutoCompleteModel(@QueryParameter String value) {
            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            String prefix = value == null ? "" : value.toLowerCase();
            for (String model : MODELS) {
                if (model.toLowerCase().startsWith(prefix)) {
                    candidates.add(model);
                }
            }
            return candidates;
        }

        @POST
        public FormValidation doTestConfiguration(@AncestorInPath Item context,
                                                  @QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) throws ExplanationException {
            checkConfigurePermission(context);

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
