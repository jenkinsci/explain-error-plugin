package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.PageDecorator;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.Stapler;

/**
 * Page decorator to add "Explain Error" functionality to console output pages.
 */
@Extension
public class ConsolePageDecorator extends PageDecorator {

    public ConsolePageDecorator() {
        super();
    }

    public boolean isExplainErrorEnabled() {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        // Must have explanation enabled. API key required for providers other than OLLAMA.
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
     * Helper method used by jelly to check if we're on a supported build page.
     */
    public boolean isPluginActive() {
        return isConsolePage() || isPipelineGraphViewPage();
    }

    public boolean isConsolePage() {
        String uri = Stapler.getCurrentRequest2().getRequestURI();
        return uri.matches(".*/console(Full)?$");
    }

    public boolean isPipelineGraphViewPage() {
        String uri = Stapler.getCurrentRequest2().getRequestURI();
        return isPipelineGraphViewUri(uri) && Jenkins.get().getPlugin("pipeline-graph-view") != null;
    }

    static boolean isPipelineGraphViewUri(String uri) {
        return uri != null && uri.matches(".*/(stages|pipeline-overview)/?$");
    }

    public String getRunUrl() {
        Ancestor ancestor = Stapler.getCurrentRequest2().findAncestor(Run.class);
        if (ancestor != null && ancestor.getObject() instanceof Run<?, ?> run) {
            return run.getUrl();
        } else {
            return null;
        }
    }

    /**
     * Returns the console-level explanation for pre-populating the Jelly view.
     * Only relevant on console pages; returns null on graph view pages because
     * graph view uses per-node {@link StepErrorExplanationAction} explanations.
     */
    public ErrorExplanationAction getExistingExplanation() {
        if (isPipelineGraphViewPage()) {
            return null;
        }
        Ancestor ancestor = Stapler.getCurrentRequest2().findAncestor(Run.class);
        if (ancestor != null && ancestor.getObject() instanceof Run<?, ?> run) {
            return run.getAction(ErrorExplanationAction.class);
        } else {
            return null;
        }
    }
}
