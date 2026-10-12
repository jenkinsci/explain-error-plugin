package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Job property that overrides the global "Automatically explain failed builds" setting for one job,
 * so a noisy job can opt out while auto-explain is enabled, or a job can opt in while it is disabled.
 * Without this property the job follows the global setting.
 */
public class ExplainErrorJobProperty extends OptionalJobProperty<Job<?, ?>> {

    private final boolean autoExplainOnFailure;

    @DataBoundConstructor
    public ExplainErrorJobProperty(boolean autoExplainOnFailure) {
        this.autoExplainOnFailure = autoExplainOnFailure;
    }

    /**
     * Returns whether failed builds of this job are explained automatically.
     */
    public boolean isAutoExplainOnFailure() {
        return autoExplainOnFailure;
    }

    @Extension
    @Symbol("explainErrorJob")
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Override automatic AI error explanation";
        }
    }
}
