package io.jenkins.plugins.explain_error;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Item;
import hudson.Extension;
import hudson.Util;
import hudson.model.ItemGroup;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Folder property for folder-level AI provider configuration.
 * Allows teams to configure their own AI provider settings at the folder level.
 *
 * <p>{@code enableExplanation} is the box that opens the folder's section on its Configure page: when it is
 * off, the folder sets nothing and its jobs follow the parent folder or the global configuration. Turning
 * explanation off for a folder is {@code disableExplanation}.
 */
public class ExplainErrorFolderProperty extends AbstractFolderProperty<AbstractFolder<?>> {

    /** Name of the optional block that wraps the provider selector on the folder's Configure page. */
    static final String PROVIDER_OVERRIDE = "providerOverride";

    private BaseAIProvider aiProvider;
    private boolean enableExplanation = true;
    private boolean disableExplanation;
    private String language;
    private String customContext;
    private Double temperature;

    private boolean enableQuota = false;
    private QuotaWindow quotaWindow = QuotaWindow.HOURLY;
    private int maxProviderCallsPerWindow = 100;
    private transient QuotaEnforcer quotaEnforcer;

    @DataBoundConstructor
    public ExplainErrorFolderProperty() {
    }

    /**
     * Get the AI provider configured for this folder.
     * @return the AI provider, or null if not configured
     */
    @CheckForNull
    public BaseAIProvider getAiProvider() {
        return aiProvider;
    }

    /**
     * Set the AI provider for this folder.
     * @param aiProvider the AI provider to use
     */
    @DataBoundSetter
    public void setAiProvider(BaseAIProvider aiProvider) {
        this.aiProvider = aiProvider;
    }

    /**
     * Whether this folder uses its section of settings. Despite the name, {@code false} does not turn
     * explanation off: the folder then sets nothing, see {@link #isDisableExplanation()}.
     * @return true if the folder's settings apply
     */
    public boolean isEnableExplanation() {
        return enableExplanation;
    }

    /**
     * Set whether this folder uses its section of settings.
     * When false, also clears the AI provider so that the parent folder or the global provider applies.
     * @param enableExplanation true to use the folder's settings
     */
    @DataBoundSetter
    public void setEnableExplanation(boolean enableExplanation) {
        this.enableExplanation = enableExplanation;
        // Clear provider when disabled to ensure fallback to global
        if (!enableExplanation) {
            this.aiProvider = null;
        }
    }

    /**
     * Whether explanation is turned off for the jobs in this folder and its subfolders: no Explain Error
     * button, no automatic explanation, and the {@code explainError()} step does nothing. A subfolder can
     * turn it on again with a provider of its own.
     * @return true if explanation is turned off here
     */
    public boolean isDisableExplanation() {
        return disableExplanation;
    }

    @DataBoundSetter
    public void setDisableExplanation(boolean disableExplanation) {
        this.disableExplanation = disableExplanation;
    }

    public String getLanguage() {
        return language;
    }

    @DataBoundSetter
    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCustomContext() {
        return customContext;
    }

    @DataBoundSetter
    public void setCustomContext(String customContext) {
        this.customContext = customContext;
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

    /** True when this property would change nothing for the jobs in its folder. */
    boolean hasNoSettings() {
        return !disableExplanation
                && aiProvider == null
                && Util.fixEmptyAndTrim(language) == null
                && Util.fixEmptyAndTrim(customContext) == null
                && temperature == null
                && !enableQuota;
    }

    QuotaEnforcer getQuotaEnforcer() {
        if (quotaEnforcer == null) {
            quotaEnforcer = new QuotaEnforcer();
        }
        return quotaEnforcer;
    }

    /**
     * Attempts to acquire a quota slot for a real AI provider call.
     *
     * @return {@code true} if the call is allowed; {@code false} if the quota is exceeded
     */
    public boolean tryAcquireQuota() {
        if (!enableQuota) {
            return true;
        }
        return getQuotaEnforcer().tryAcquire(getQuotaWindow(), maxProviderCallsPerWindow);
    }

    /**
     * Recursively search for a folder-level quota configuration.
     * Walks up the folder hierarchy and returns the nearest folder that has
     * {@code enableQuota=true}, or {@code null} if none is found.
     *
     * @param itemGroup the item group to search from
     * @return the nearest folder property with quota enabled, or null
     */
    @CheckForNull
    public static ExplainErrorFolderProperty findFolderWithQuota(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);
            if (property != null && property.isEnableQuota()) {
                return property;
            }
            return findFolderWithQuota(folder.getParent());
        }
        return null;
    }

    /**
     * Recursively search for folder-level AI provider configuration.
     * Walks up the folder hierarchy until a folder with a provider of its own is found. A folder whose
     * section is not in use ({@code enableExplanation} false) is passed over, provider or not.
     *
     * @param itemGroup the item group to search from
     * @return the AI provider if found at folder level, null otherwise
     */
    @CheckForNull
    public static BaseAIProvider findFolderProvider(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);
            if (property != null && property.providesOwnProvider()) {
                return property.getAiProvider();
            }
            return findFolderProvider(folder.getParent());
        }
        return null;
    }

    /**
     * Whether a folder decides that its jobs are explained or not. The nearest folder that decides wins:
     * one that turns explanation off says no, one with a provider of its own says yes. Folders that do
     * neither are passed over.
     *
     * @param itemGroup the item group to search from
     * @return {@code false} if the nearest deciding folder turns explanation off, {@code true} if it has a
     *         provider of its own, {@code null} if no folder decides and the global configuration applies
     */
    @CheckForNull
    public static Boolean findFolderDecision(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);
            if (property != null && property.isDisableExplanation()) {
                return Boolean.FALSE;
            }
            if (property != null && property.providesOwnProvider()) {
                return Boolean.TRUE;
            }
            return findFolderDecision(folder.getParent());
        }
        return null;
    }

    private boolean providesOwnProvider() {
        return aiProvider != null && enableExplanation && !disableExplanation;
    }

    /**
     * Check if error explanation is enabled at folder level.
     * Walks up the folder hierarchy to find the configuration.
     * 
     * @param itemGroup the item group to search from
     * @return true if enabled at folder level (default true if not configured)
     */
    public static boolean isFolderExplanationEnabled(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return true; // Default to enabled
        }

        // Check if this item group is a folder with our property
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);
            
            if (property != null) {
                return property.isEnableExplanation();
            }
        }

        // Recursively check parent folder
        if (itemGroup instanceof AbstractFolder) {
            return isFolderExplanationEnabled(((AbstractFolder<?>) itemGroup).getParent());
        }

        return true; // Default to enabled
    }

    /**
     * Recursively search for folder-level language configuration.
     * Walks up the folder hierarchy until a configured value is found.
     *
     * @param itemGroup the item group to search from
     * @return the language if found at folder level, null otherwise
     */
    @CheckForNull
    public static String findFolderLanguage(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);
            if (property != null && property.getLanguage() != null && !property.getLanguage().isBlank()) {
                return property.getLanguage();
            }
            return findFolderLanguage(folder.getParent());
        }
        return null;
    }

    /**
     * Recursively search for folder-level custom context configuration.
     * Walks up the folder hierarchy until a configured value is found.
     *
     * @param itemGroup the item group to search from
     * @return the custom context if found at folder level, null otherwise
     */
    @CheckForNull
    public static String findFolderCustomContext(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);
            if (property != null && property.getCustomContext() != null && !property.getCustomContext().isBlank()) {
                return property.getCustomContext();
            }
            return findFolderCustomContext(folder.getParent());
        }
        return null;
    }

    /**
     * Recursively search for folder-level temperature configuration.
     * Walks up the folder hierarchy until a configured value is found.
     *
     * @param itemGroup the item group to search from
     * @return the temperature if found at folder level, null otherwise
     */
    @CheckForNull
    public static Double findFolderTemperature(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);
            if (property != null && property.getTemperature() != null) {
                return property.getTemperature();
            }
            return findFolderTemperature(folder.getParent());
        }
        return null;
    }

    @Extension
    @Symbol("explainErrorFolder")
    public static class DescriptorImpl extends AbstractFolderPropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Explain Error Configuration";
        }

        /**
         * The provider selector sits in its own optional block on the form, so a folder can set a
         * language, context or quota without overriding the provider. Left unticked, the form
         * submits no provider at all; a selector on its own would submit whichever provider
         * happens to be listed first.
         */
        @Override
        public AbstractFolderProperty<?> newInstance(@CheckForNull StaplerRequest2 req, @NonNull JSONObject formData)
                throws FormException {
            Object providerOverride = formData.remove(PROVIDER_OVERRIDE);
            if (providerOverride instanceof JSONObject block) {
                formData.put("aiProvider", block.get("aiProvider"));
            }
            ExplainErrorFolderProperty property = (ExplainErrorFolderProperty) super.newInstance(req, formData);
            // Every folder's Configure page carries this section. Saving one that leaves it empty
            // should not add a property to the folder.
            return property == null || property.hasNoSettings() ? null : property;
        }

        @POST
        public ListBoxModel doFillQuotaWindowItems(@AncestorInPath AbstractFolder<?> folder) {
            folder.checkPermission(Item.CONFIGURE);
            ListBoxModel items = new ListBoxModel();
            for (QuotaWindow value : QuotaWindow.values()) {
                items.add(value.getDisplayName(), value.name());
            }
            return items;
        }

        @POST
        public FormValidation doCheckMaxProviderCallsPerWindow(@AncestorInPath AbstractFolder<?> folder,
                                                               @QueryParameter int value) {
            folder.checkPermission(Item.CONFIGURE);
            if (value < 0) {
                return FormValidation.error("Max provider calls per window must be 0 or greater.");
            }
            return FormValidation.ok();
        }
    }
}
