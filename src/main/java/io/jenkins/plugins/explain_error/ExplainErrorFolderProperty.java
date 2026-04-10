package io.jenkins.plugins.explain_error;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.GET;

/**
 * Folder property for folder-level AI provider configuration.
 * Allows teams to configure their own AI provider settings at the folder level.
 */
public class ExplainErrorFolderProperty extends AbstractFolderProperty<AbstractFolder<?>> {

    private BaseAIProvider aiProvider;
    private boolean enableExplanation = true;

    // Folder-level quota
    private boolean enableQuota = false;
    private QuotaWindow quotaWindow = QuotaWindow.HOURLY;
    private int maxProviderCallsPerWindow = 100;

    // Runtime quota state — not persisted
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
     * Check if error explanation is enabled for this folder.
     * @return true if enabled, false otherwise
     */
    public boolean isEnableExplanation() {
        return enableExplanation;
    }

    /**
     * Set whether error explanation is enabled for this folder.
     * When disabled, also clears the AI provider to ensure fallback to global configuration.
     * @param enableExplanation true to enable, false to disable
     */
    @DataBoundSetter
    public void setEnableExplanation(boolean enableExplanation) {
        this.enableExplanation = enableExplanation;
        // Clear provider when disabled to ensure fallback to global
        if (!enableExplanation) {
            this.aiProvider = null;
        }
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
     * Attempts to acquire one quota slot for this folder.
     * Always returns {@code true} when the folder quota is disabled.
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
     * Recursively search for folder-level AI provider configuration.
     * Walks up the folder hierarchy until a configuration is found.
     * 
     * @param itemGroup the item group to search from
     * @return the AI provider if found at folder level, null otherwise
     */
    @CheckForNull
    public static BaseAIProvider findFolderProvider(@CheckForNull ItemGroup<?> itemGroup) {
        ExplainErrorFolderProperty fp = findActiveFolderProperty(itemGroup);
        return fp != null ? fp.getAiProvider() : null;
    }

    /**
     * Recursively searches for an active folder property that has a provider configured and
     * explanation enabled. Stops (returns {@code null}) if a folder explicitly disables
     * explanation rather than continuing to parent folders.
     *
     * @param itemGroup the item group to start searching from
     * @return the active folder property, or {@code null} if none found
     */
    @CheckForNull
    public static ExplainErrorFolderProperty findActiveFolderProperty(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }

        // Check if this item group is a folder with our property
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);

            if (property != null && property.getAiProvider() != null) {
                // Provider configured and enabled: use it
                if (property.isEnableExplanation()) {
                    return property;
                }
                // Provider configured but disabled: explicitly disable (stop searching)
                return null;
            }
            // No provider configured at this level, continue to parent
            return findActiveFolderProperty(folder.getParent());
        }

        return null;
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

    @Extension
    @Symbol("explainErrorFolder")
    public static class DescriptorImpl extends AbstractFolderPropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Explain Error Configuration";
        }

        @GET
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public ListBoxModel doFillQuotaWindowItems() {
            ListBoxModel items = new ListBoxModel();
            for (QuotaWindow window : QuotaWindow.values()) {
                items.add(window.getDisplayName(), window.name());
            }
            return items;
        }

        @GET
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckMaxProviderCallsPerWindow(@QueryParameter int value) {
            if (value < 0) {
                return FormValidation.error("Max provider calls per window must be 0 or greater.");
            }
            return FormValidation.ok();
        }
    }
}
