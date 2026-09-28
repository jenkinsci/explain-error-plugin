package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
import io.jenkins.plugins.explain_error.provider.LangGraphProvider;
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

    static final int DEFAULT_AUTO_EXPLAIN_MAX_PER_HOUR = 30;

    private transient Secret apiKey;
    private transient AIProvider provider;
    private transient String apiUrl;
    private transient String model;
    private boolean enableExplanation = true;
    private String customContext;
    private String language;
    private Double temperature;

    private BaseAIProvider aiProvider;

    private boolean enableQuota = false;
    private QuotaWindow quotaWindow = QuotaWindow.HOURLY;
    private int maxProviderCallsPerWindow = 100;

    private boolean enableAutoExplainOnFailure = false;
    private int autoExplainMaxLogLines = 100;
    private int autoExplainMaxPerHour = DEFAULT_AUTO_EXPLAIN_MAX_PER_HOUR;

    private transient QuotaEnforcer quotaEnforcer;
    private transient QuotaEnforcer autoExplainQuotaEnforcer;

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
                    case OLLAMA -> new OllamaProvider(apiUrl, model, null);
                    case LANGGRAPH -> new LangGraphProvider(apiUrl, model, apiKey);
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

    public boolean isEnableAutoExplainOnFailure() {
        return enableAutoExplainOnFailure;
    }

    @DataBoundSetter
    public void setEnableAutoExplainOnFailure(boolean enableAutoExplainOnFailure) {
        this.enableAutoExplainOnFailure = enableAutoExplainOnFailure;
    }

    public int getAutoExplainMaxLogLines() {
        return autoExplainMaxLogLines;
    }

    @DataBoundSetter
    public void setAutoExplainMaxLogLines(int autoExplainMaxLogLines) {
        this.autoExplainMaxLogLines = Math.max(1, autoExplainMaxLogLines);
    }

    /**
     * Maximum number of AI provider calls made for automatic explanations per hour. Explanations
     * reused from an earlier build that failed the same way do not count.
     */
    public int getAutoExplainMaxPerHour() {
        return autoExplainMaxPerHour > 0 ? autoExplainMaxPerHour : DEFAULT_AUTO_EXPLAIN_MAX_PER_HOUR;
    }

    @DataBoundSetter
    public void setAutoExplainMaxPerHour(int autoExplainMaxPerHour) {
        this.autoExplainMaxPerHour = Math.max(1, autoExplainMaxPerHour);
    }

    public String getCustomContext() {
        return customContext;
    }

    @DataBoundSetter
    public void setCustomContext(String customContext) {
        this.customContext = customContext;
    }

    public String getLanguage() {
        return language;
    }

    @DataBoundSetter
    public void setLanguage(String language) {
        this.language = language;
    }

    public Double getTemperature() {
        return temperature;
    }

    @DataBoundSetter
    public void setTemperature(Double temperature) {
        this.temperature = temperature;
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

    synchronized QuotaEnforcer getAutoExplainQuotaEnforcer() {
        if (autoExplainQuotaEnforcer == null) {
            autoExplainQuotaEnforcer = new QuotaEnforcer();
        }
        return autoExplainQuotaEnforcer;
    }

    /**
     * Attempts to acquire a slot of the hourly limit for automatic explanations. This limit always
     * applies to automatic explanations, in addition to the optional request quota.
     *
     * @return {@code true} if the call is within the limit, {@code false} otherwise
     */
    boolean tryAcquireAutoExplainQuota() {
        return getAutoExplainQuotaEnforcer().tryAcquire(QuotaWindow.HOURLY, getAutoExplainMaxPerHour());
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

    @POST
    public ListBoxModel doFillQuotaWindowItems() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel items = new ListBoxModel();
        for (QuotaWindow value : QuotaWindow.values()) {
            items.add(value.getDisplayName(), value.name());
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

    @POST
    public FormValidation doCheckAutoExplainMaxPerHour(@QueryParameter int value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (value < 1) {
            return FormValidation.error("Max automatic explanations per hour must be 1 or greater.");
        }
        return FormValidation.ok();
    }

    @Override
    public String getDisplayName() {
        return "Explain Error Plugin Configuration";
    }
}
