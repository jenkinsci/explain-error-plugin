package io.jenkins.plugins.explain_error;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ItemGroup;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Folder property for folder-level AI provider configuration.
 * Allows teams to configure their own AI provider settings at the folder level.
 */
public class ExplainErrorFolderProperty extends AbstractFolderProperty<AbstractFolder<?>> {

    private BaseAIProvider aiProvider;
    private boolean enableExplanation = true;

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
     * @param enableExplanation true to enable, false to disable
     */
    @DataBoundSetter
    public void setEnableExplanation(boolean enableExplanation) {
        this.enableExplanation = enableExplanation;
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
                // Explicitly disabled at this folder level: override parent configuration
                if (!property.isEnableExplanation()) {
                    return null;
                }

                BaseAIProvider provider = property.getAiProvider();
                if (provider != null) {
                    return provider;
                }
                // If enabled but no provider is configured, fall through to parent
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
    }
}
