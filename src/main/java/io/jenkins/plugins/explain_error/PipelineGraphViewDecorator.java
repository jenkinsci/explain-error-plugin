package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.PageDecorator;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.Stapler;

/**
 * Page decorator to add "Explain Error" functionality to Pipeline Graph View pages.
 * Injects JavaScript that monitors node selection and provides an
 * "Explain Error" button for failed nodes.
 */
@Extension
public class PipelineGraphViewDecorator extends PageDecorator {

    public PipelineGraphViewDecorator() {
        super();
    }

    /**
     * Returns {@code true} when the explain-error plugin is configured
     * and enabled.
     */
    public boolean isExplainErrorEnabled() {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        if (!config.isEnableExplanation()) {
            return false;
        }

        if (config.getAiProvider() == null) {
            return false;
        }

        return !config.getAiProvider().isNotValid(null);
    }

    public String getProviderName() {
        if (GlobalConfigurationImpl.get().getAiProvider() == null) {
            return null;
        }
        return GlobalConfigurationImpl.get().getAiProvider().getProviderName();
    }

    /**
     * Checks whether the current request is on a Pipeline Graph View page.
     * Only active when the {@code pipeline-graph-view} plugin is installed
     * and the URL matches the graph view pattern.
     */
    public boolean isPluginActive() {
        if (Jenkins.get().getPlugin("pipeline-graph-view") == null) {
            return false;
        }
        String uri = Stapler.getCurrentRequest2().getRequestURI();
        return uri.matches(".*/stages(\\?.*)?$");
    }

    public String getRunUrl() {
        Ancestor ancestor = Stapler.getCurrentRequest2().findAncestor(Run.class);
        if (ancestor != null && ancestor.getObject() instanceof Run<?, ?> run) {
            return run.getUrl();
        } else {
            return null;
        }
    }

    public ErrorExplanationAction getExistingExplanation() {
        Ancestor ancestor = Stapler.getCurrentRequest2().findAncestor(Run.class);
        if (ancestor != null && ancestor.getObject() instanceof Run<?, ?> run) {
            return run.getAction(ErrorExplanationAction.class);
        } else {
            return null;
        }
    }
}
