package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
import io.jenkins.plugins.explain_error.provider.OllamaProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;


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

    private boolean enableQuota = false;
    private QuotaWindow quotaWindow = QuotaWindow.HOURLY;
    private int maxProviderCallsPerWindow = 100;

    private transient QuotaEnforcer quotaEnforcer;

    public GlobalConfigurationImpl() {
        load();
    }

    /**
     * Get the singleton instance of GlobalConfigurationImpl.
     * @return the GlobalConfigurationImpl instance
     */
    public static GlobalConfigurationImpl get() {
        GlobalConfigurationImpl config = GlobalConfiguration.all().get(GlobalConfigurationImpl.class);
        if (config != null) {
            return config;
        }
        return Jenkins.get().getDescriptorByType(GlobalConfigurationImpl.class);
    }

    public Object readResolve() {
        if (aiProvider == null) {
            if (provider != null) {
                aiProvider = switch (provider) {
                    case OPENAI -> new OpenAIProvider(apiUrl, model, apiKey);
                    case GEMINI -> new GeminiProvider(apiUrl, model, apiKey);
                    case OLLAMA -> new OllamaProvider(apiUrl, model);
                };
                provider = null;
                save();
            } else {
                aiProvider = new OpenAIProvider(null, OpenAIProvider.DEFAULT_MODEL, null);
            }
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

    public boolean isEnableQuota() {
        return enableQuota;
    }

    @DataBoundSetter
    public void setEnableQuota(boolean enableQuota) {
        this.enableQuota = enableQuota;
    }

    public QuotaWindow getQuotaWindow() {
        return quotaWindow != null ? quotaWindow : QuotaWindow.HOURLY;
    }

    @DataBoundSetter
    public void setQuotaWindow(QuotaWindow quotaWindow) {
        this.quotaWindow = quotaWindow != null ? quotaWindow : QuotaWindow.HOURLY;
    }

    public int getMaxProviderCallsPerWindow() {
        return maxProviderCallsPerWindow;
    }

    @DataBoundSetter
    public void setMaxProviderCallsPerWindow(int maxProviderCallsPerWindow) {
        this.maxProviderCallsPerWindow = Math.max(0, maxProviderCallsPerWindow);
    }

    /**
     * Returns the singleton {@link QuotaEnforcer}, creating one lazily if needed
     * (e.g. after deserialization when {@code transient} fields are not restored).
     */
    QuotaEnforcer getQuotaEnforcer() {
        if (quotaEnforcer == null) {
            quotaEnforcer = new QuotaEnforcer();
        }
        return quotaEnforcer;
    }

    /**
     * Attempts to acquire a quota slot for a real AI provider call.
     *
     * @return {@code true} if the call is allowed (quota disabled, or within the limit);
     *         {@code false} if the quota is enabled and has been exceeded
     */
    public boolean tryAcquireQuota() {
        if (!enableQuota) {
            return true;
        }
        return getQuotaEnforcer().tryAcquire(getQuotaWindow(), maxProviderCallsPerWindow);
    }

    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public ListBoxModel doFillQuotaWindowItems() {
        ListBoxModel items = new ListBoxModel();
        for (QuotaWindow window : QuotaWindow.values()) {
            items.add(window.getDisplayName(), window.name());
        }
        return items;
    }

    @POST
    public FormValidation doCheckMaxProviderCallsPerWindow(@QueryParameter int value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (value < 0) {
            return FormValidation.error("Max provider calls per window must be 0 or greater.");
        }
        return FormValidation.ok();
    }

    @Override
    public String getDisplayName() {
        return "Explain Error Plugin Configuration";
    }
}
