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
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
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

    // Quota configuration
    private boolean enableQuota = false;
    private QuotaWindow quotaWindow = QuotaWindow.HOURLY;
    private int maxProviderCallsPerWindow = 100;

    // User-level quota configuration
    private boolean enableUserQuota = false;
    private QuotaWindow userQuotaWindow = QuotaWindow.DAILY;
    private int maxUserCallsPerWindow = 50;

    // Fallback provider (used when primary provider call fails)
    private BaseAIProvider fallbackAiProvider;

    // Runtime quota state — not persisted
    private transient QuotaEnforcer quotaEnforcer = new QuotaEnforcer();
    private transient UserQuotaEnforcer userQuotaEnforcer = new UserQuotaEnforcer();

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
        save();
    }

    public QuotaWindow getQuotaWindow() {
        return quotaWindow != null ? quotaWindow : QuotaWindow.HOURLY;
    }

    @DataBoundSetter
    public void setQuotaWindow(QuotaWindow quotaWindow) {
        this.quotaWindow = quotaWindow != null ? quotaWindow : QuotaWindow.HOURLY;
        save();
    }

    public int getMaxProviderCallsPerWindow() {
        return maxProviderCallsPerWindow;
    }

    @DataBoundSetter
    public void setMaxProviderCallsPerWindow(int maxProviderCallsPerWindow) {
        this.maxProviderCallsPerWindow = Math.max(0, maxProviderCallsPerWindow);
        save();
    }

    public boolean isEnableUserQuota() {
        return enableUserQuota;
    }

    @DataBoundSetter
    public void setEnableUserQuota(boolean enableUserQuota) {
        this.enableUserQuota = enableUserQuota;
        save();
    }

    public QuotaWindow getUserQuotaWindow() {
        return userQuotaWindow != null ? userQuotaWindow : QuotaWindow.DAILY;
    }

    @DataBoundSetter
    public void setUserQuotaWindow(QuotaWindow userQuotaWindow) {
        this.userQuotaWindow = userQuotaWindow != null ? userQuotaWindow : QuotaWindow.DAILY;
        save();
    }

    public int getMaxUserCallsPerWindow() {
        return maxUserCallsPerWindow;
    }

    @DataBoundSetter
    public void setMaxUserCallsPerWindow(int maxUserCallsPerWindow) {
        this.maxUserCallsPerWindow = Math.max(0, maxUserCallsPerWindow);
        save();
    }

    public BaseAIProvider getFallbackAiProvider() {
        return fallbackAiProvider;
    }

    public void setFallbackAiProvider(BaseAIProvider fallbackAiProvider) {
        this.fallbackAiProvider = fallbackAiProvider;
        save();
    }

    /**
     * Attempts to acquire one quota slot for a real provider call.
     * Always returns {@code true} when quota is disabled.
     *
     * @return {@code true} if the call is permitted, {@code false} if the quota is exhausted
     */
    public boolean tryAcquireQuota() {
        if (!enableQuota) {
            return true;
        }
        if (quotaEnforcer == null) {
            quotaEnforcer = new QuotaEnforcer();
        }
        return quotaEnforcer.tryAcquire(getQuotaWindow(), maxProviderCallsPerWindow);
    }

    /**
     * Attempts to acquire one per-user quota slot.
     * Always returns {@code true} when user quota is disabled or for system/anonymous users.
     *
     * @param userId the user identity (from {@code Authentication#getName()})
     * @return {@code true} if the call is permitted, {@code false} if the user quota is exhausted
     */
    public boolean tryAcquireUserQuota(String userId) {
        if (!enableUserQuota || userId == null
                || "SYSTEM".equals(userId) || "anonymous".equals(userId)) {
            return true;
        }
        if (userQuotaEnforcer == null) {
            userQuotaEnforcer = new UserQuotaEnforcer();
        }
        return userQuotaEnforcer.tryAcquire(userId, getUserQuotaWindow(), maxUserCallsPerWindow);
    }

    @POST
    public ListBoxModel doFillQuotaWindowItems() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel items = new ListBoxModel();
        for (QuotaWindow window : QuotaWindow.values()) {
            items.add(window.getDisplayName(), window.name());
        }
        return items;
    }

    @POST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public FormValidation doCheckMaxProviderCallsPerWindow(@QueryParameter int value) {
        if (value < 0) {
            return FormValidation.error("Max provider calls per window must be 0 or greater.");
        }
        return FormValidation.ok();
    }

    @POST
    public ListBoxModel doFillUserQuotaWindowItems() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel items = new ListBoxModel();
        for (QuotaWindow window : QuotaWindow.values()) {
            items.add(window.getDisplayName(), window.name());
        }
        return items;
    }

    @POST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public FormValidation doCheckMaxUserCallsPerWindow(@QueryParameter int value) {
        if (value < 0) {
            return FormValidation.error("Max user calls per window must be 0 or greater.");
        }
        return FormValidation.ok();
    }

    @Override
    public String getDisplayName() {
        return "Explain Error Plugin Configuration";
    }
}
