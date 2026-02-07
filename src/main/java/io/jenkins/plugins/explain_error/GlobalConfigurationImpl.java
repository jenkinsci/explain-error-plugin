package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
import io.jenkins.plugins.explain_error.provider.OllamaProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundSetter;
import org.jenkinsci.Symbol;


/**
 * Global configuration for the plugin.
 */
@Extension
@Symbol("explainError")
public class GlobalConfigurationImpl extends GlobalConfiguration {

    private transient Secret apiKey;
    private transient AIProvider provider;
    private transient String apiUrl;
    private transient String model;
    private boolean enableExplanation = true;
    private String customContext;

    private BaseAIProvider aiProvider;

    public GlobalConfigurationImpl() {
        load();
    }

    /**
     * Get the singleton instance of GlobalConfigurationImpl.
     * @return the GlobalConfigurationImpl instance
     */
    public static GlobalConfigurationImpl get() {
        return Jenkins.get().getDescriptorByType(GlobalConfigurationImpl.class);
    }

    public Object readResolve() {
        if (aiProvider == null && provider != null) {
            aiProvider = switch (provider) {
                case OPENAI -> new OpenAIProvider(apiUrl, model, apiKey);
                case GEMINI -> new GeminiProvider(apiUrl, model, apiKey);
                case OLLAMA -> new OllamaProvider(apiUrl, model);
            };
            save();
        }
        return this;
    }

    // Getters and setters
    public BaseAIProvider getAiProvider() {
        if (aiProvider == null) {
            readResolve();
        }
        return aiProvider;
    }

    public void setAiProvider(BaseAIProvider aiProvider) {
        this.aiProvider = aiProvider;
        save();
    }

    public Secret getApiKey() {
        return apiKey;
    }

    @DataBoundSetter
    public void setApiKey(Secret apiKey) {
        this.apiKey = apiKey;
    }

    public AIProvider getProvider() {
        return provider;
    }

    @DataBoundSetter
    public void setProvider(AIProvider provider) {
        this.provider = provider;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    @DataBoundSetter
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getModel() {
        return model;
    }

    /**
     * Get the raw configured model without defaults, used for validation.
     */
    public String getRawModel() {
        return model;
    }

    @DataBoundSetter
    public void setModel(String model) {
        this.model = model;
    }

    public boolean isEnableExplanation() {
        return enableExplanation;
    }

    @DataBoundSetter
    public void setEnableExplanation(boolean enableExplanation) {
        this.enableExplanation = enableExplanation;
    }

    public String getCustomContext() {
        return customContext;
    }

    @DataBoundSetter
    public void setCustomContext(String customContext) {
        this.customContext = customContext;

    }

    @Override
    public String getDisplayName() {
        return "Explain Error Plugin Configuration";
    }
}
