package io.jenkins.plugins.explain_error.provider;

import com.cloudbees.plugins.credentials.CredentialsProvider;
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
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;
import org.springframework.security.core.Authentication;

public class QwenProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(QwenProvider.class.getName());
    public static final String DEFAULT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String DEFAULT_MODEL = "qwen-plus";

    private Secret apiKey;
    private String credentialsId;

    @DataBoundConstructor
    public QwenProvider(String url, String model, Secret apiKey, String credentialsId) {
        super(resolveUrl(url), model);
        this.apiKey = apiKey;
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public Secret getApiKey() {
        return apiKey;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @Override
    public Assistant createAssistant() {
        ChatModel model = buildChatModel(null, null);
        return AiServices.create(Assistant.class, model);
    }

    @Override
    public Assistant createAssistant(@CheckForNull Item item, @CheckForNull Authentication authentication) {
        ChatModel model = buildChatModel(item, authentication);
        return AiServices.create(Assistant.class, model);
    }

    @Override
    public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant() {
        ChatModel model = buildChatModel(null, null);
        return AiServices.create(io.jenkins.plugins.explain_error.autofix.FixAssistant.class, model);
    }

    @Override
    public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant(@CheckForNull Item item,
                                                                                     @CheckForNull Authentication authentication) {
        ChatModel model = buildChatModel(item, authentication);
        return AiServices.create(io.jenkins.plugins.explain_error.autofix.FixAssistant.class, model);
    }

    private ChatModel buildChatModel(@CheckForNull Item item, @CheckForNull Authentication authentication) {
        String resolvedApiKey = resolveApiKey(item, authentication);
        if (resolvedApiKey == null) {
            throw new IllegalStateException("No API key configured for Qwen");
        }

        return OpenAiChatModel.builder()
                .httpClientBuilder(newLangChainHttpClientBuilder())
                .baseUrl(getUrl())
                .apiKey(resolvedApiKey)
                .modelName(getModel())
                .temperature(0.3)
                .responseFormat(ResponseFormat.JSON)
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE))
                .build();
    }

    /**
     * Resolve API key from credentials or fallback to direct secret field.
     */
    private String resolveApiKey(@CheckForNull Item item, @CheckForNull Authentication authentication) {
        String credId = Util.fixEmptyAndTrim(getCredentialsId());
        if (credId != null) {
            StringCredentials credentials = resolveCredentials(item, authentication);
            if (credentials != null) {
                return credentials.getSecret().getPlainText();
            }
        }
        return apiKey != null ? apiKey.getPlainText() : null;
    }

    private StringCredentials resolveCredentials(@CheckForNull Item item, @CheckForNull Authentication authentication) {
        String id = Util.fixEmptyAndTrim(getCredentialsId());
        if (id == null) {
            return null;
        }
        if (Jenkins.getInstanceOrNull() == null) {
            return null;
        }
        return CredentialsProvider.findCredentialByIdInItem(
                id,
                StringCredentials.class,
                item,
                authentication != null ? authentication : ACL.SYSTEM2,
                Collections.emptyList());
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        return isNotValid(listener, null, null);
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener, @CheckForNull Item item,
                              @CheckForNull Authentication authentication) {
        String credId = Util.fixEmptyAndTrim(getCredentialsId());
        String directApiKey = Util.fixEmptyAndTrim(Secret.toString(getApiKey()));
        String modelName = Util.fixEmptyAndTrim(getModel());

        boolean hasCredentials = false;
        if (credId != null) {
            StringCredentials credentials = resolveCredentials(item, authentication);
            hasCredentials = (credentials != null);
        }

        boolean hasApiKey = (directApiKey != null);
        boolean hasAnyAuth = hasCredentials || hasApiKey;

        if (listener != null) {
            if (!hasAnyAuth) {
                listener.getLogger().println("No API key or credentials configured for Qwen.");
            } else if (credId != null && !hasCredentials) {
                listener.getLogger().println("Qwen credentials not found for ID: " + credId);
            } else if (modelName == null) {
                listener.getLogger().println("No model configured for Qwen.");
            }
        }

        return !hasAnyAuth || modelName == null;
    }

    private static String resolveUrl(String url) {
        String trimmedUrl = Util.fixEmptyAndTrim(url);
        return trimmedUrl != null ? trimmedUrl : DEFAULT_URL;
    }

    @Extension
    @Symbol("qwen")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        private static final String[] MODELS = new String[]{
                "qwen-plus",
                "qwen-plus-latest",
                "qwen-flash",
                "qwen-flash-latest",
                "qwen-turbo",
                "qwen-turbo-latest",
                "qwen3-max",
                "qwen3.5-plus",
                "qwen3.5-flash",
                "qwen3-coder-plus",
                "qwen3-coder-flash"
        };

        @NonNull
        @Override
        public String getDisplayName() {
            return "Qwen";
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
        public FormValidation doTestConfiguration(@QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("credentialsId") String credentialsId,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            QwenProvider provider = new QwenProvider(url, model, apiKey, credentialsId);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }
    }
}
