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
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Folder property for folder-level AI provider configuration.
 * Allows teams to configure their own AI provider settings at the folder level.
 */
public class ExplainErrorFolderProperty extends AbstractFolderProperty<AbstractFolder<?>> {

    private BaseAIProvider aiProvider;
    private boolean enableExplanation = true;

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
     * Walks up the folder hierarchy until a configuration is found.
     * 
     * @param itemGroup the item group to search from
     * @return the AI provider if found at folder level, null otherwise
     */
    @CheckForNull
    public static BaseAIProvider findFolderProvider(@CheckForNull ItemGroup<?> itemGroup) {
        if (itemGroup == null) {
            return null;
        }

        // Check if this item group is a folder with our property
        if (itemGroup instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) itemGroup;
            ExplainErrorFolderProperty property = folder.getProperties().get(ExplainErrorFolderProperty.class);

            if (property != null) {
                BaseAIProvider provider = property.getAiProvider();
                
                // If provider is configured, respect the enableExplanation flag
                if (provider != null) {
                    // Provider configured and enabled: use it
                    if (property.isEnableExplanation()) {
                        return provider;
                    }
                    // Provider configured but disabled: explicitly disable (return null and stop searching)
                    return null;
                }
                // No provider configured at this level, continue to parent/global even if enableExplanation is false
            }

            // Recursively check parent folder
            return findFolderProvider(folder.getParent());
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
    }
}
