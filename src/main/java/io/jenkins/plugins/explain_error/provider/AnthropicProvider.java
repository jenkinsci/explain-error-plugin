package io.jenkins.plugins.explain_error.provider;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
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

public class AnthropicProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(AnthropicProvider.class.getName());
    // Using Claude Sonnet 4.6 as default - supports temperature and is stable
    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    public static final int DEFAULT_MAX_TOKENS = 4096;

    protected Secret apiKey;
    protected String credentialsId;
    protected Integer maxTokens;

    @DataBoundConstructor
    public AnthropicProvider(String url, String model, Secret apiKey, String credentialsId, Integer maxTokens) {
        super(url, model);
        this.apiKey = apiKey;
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        this.maxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
    }

    public Secret getApiKey() {
        return apiKey;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public Integer getMaxTokens() {
        return maxTokens;
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
            throw new IllegalStateException("No API key configured for Anthropic");
        }

        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .httpClientBuilder(newLangChainHttpClientBuilder())
                .baseUrl(Util.fixEmptyAndTrim(getUrl())) // Will use default if null
                .apiKey(resolvedApiKey)
                .modelName(getModel())
                .maxTokens(getMaxTokens()) // Configurable output token limit
                .logRequests(LOGGER.isLoggable(Level.FINE))
                .logResponses(LOGGER.isLoggable(Level.FINE));

        // Temperature is deprecated in Claude Opus 4.7+ per Anthropic migration guide
        // Setting temperature on 4.7+ returns 400 error
        // Claude 4.6 and earlier still support temperature
        boolean skipTemperature = isClaude47OrNewer(getModel());
        if (skipTemperature) {
            LOGGER.fine("Skipping temperature parameter for Claude 4.7+ model: " + getModel());
        } else {
            builder.temperature(0.3);
        }

        return builder.build();
    }

    /**
     * Check if the model is Claude 4.7 or newer (temperature parameter is deprecated).
     * According to Anthropic migration guide, temperature returns 400 error on Claude Opus 4.7+.
     * Claude 4.6 and earlier still support temperature.
     */
    private boolean isClaude47OrNewer(String modelName) {
        if (modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase();
        
        // Claude 4.7+ models (any variant: opus, sonnet, haiku)
        if (lower.startsWith("claude-opus-4-7") || 
            lower.startsWith("claude-sonnet-4-7") || 
            lower.startsWith("claude-haiku-4-7")) {
            return true;
        }
        
        // Claude 4.8, 4.9, etc.
        if (lower.matches("claude-(opus|sonnet|haiku)-4-[8-9].*")) {
            return true;
        }
        
        // Claude 5.x and beyond
        if (lower.matches("claude-(opus|sonnet|haiku)-[5-9]\\d*-.*") ||
            lower.matches("claude-(opus|sonnet|haiku)-[5-9]\\d*")) {
            return true;
        }
        
        return false;
    }

    /**
     * Resolve API key from credentials or fallback to direct secret field.
     */
    private String resolveApiKey(@CheckForNull Item item, @CheckForNull Authentication authentication) {
        // Try credentials first
        String credId = Util.fixEmptyAndTrim(getCredentialsId());
        if (credId != null) {
            StringCredentials credentials = resolveCredentials(item, authentication);
            if (credentials != null) {
                return credentials.getSecret().getPlainText();
            }
        }
        // Fall back to direct API key field
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
                listener.getLogger().println("No API key or credentials configured for Anthropic.");
            } else if (credId != null && !hasCredentials) {
                listener.getLogger().println("Anthropic credentials not found for ID: " + credId);
            } else if (modelName == null) {
                listener.getLogger().println("No Model configured for Anthropic.");
            }
        }

        return !hasAnyAuth || modelName == null;
    }

    @Extension
    @Symbol("anthropic")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        private static final String[] MODELS = new String[]{
                // Claude 4.7 (temperature deprecated)
                "claude-opus-4-7",
                // Claude 4.6
                "claude-opus-4-6",
                "claude-sonnet-4-6",
                // Claude 4.5
                "claude-haiku-4-5-20251001",
                "claude-sonnet-4-5-20250929",
                // Claude 3.x (legacy, for reference)
                "claude-3-5-sonnet-20241022",
                "claude-3-5-haiku-20241022",
                "claude-3-opus-20240229",
        };

        @NonNull
        @Override
        public String getDisplayName() {
            return "Anthropic (Claude)";
        }

        public String getDefaultModel() {
            return DEFAULT_MODEL;
        }

        public int getDefaultMaxTokens() {
            return DEFAULT_MAX_TOKENS;
        }

        @GET
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public AutoCompletionCandidates doAutoCompleteModel(@QueryParameter String value) {
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            for (String model : MODELS) {
                if (model.toLowerCase().startsWith(value.toLowerCase())) {
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
                                                  @QueryParameter("credentialsId") String credentialsId,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model,
                                                  @QueryParameter("maxTokens") Integer maxTokens) throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            AnthropicProvider provider = new AnthropicProvider(url, model, apiKey, credentialsId, maxTokens);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }
    }
}
