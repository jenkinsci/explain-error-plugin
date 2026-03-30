package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.PageDecorator;
import hudson.model.Run;
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
     * Helper method used by jelly to checked if we're on a console url.
     */
    public boolean isPluginActive() {
        String uri = Stapler.getCurrentRequest2().getRequestURI();
        return uri.matches(".*/console(Full)?$");
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
