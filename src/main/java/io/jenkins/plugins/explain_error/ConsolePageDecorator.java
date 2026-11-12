package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.PageDecorator;
import hudson.model.Run;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Page decorator to add "Explain Error" functionality to console output pages.
 */
@Extension
public class ConsolePageDecorator extends PageDecorator {

    public ConsolePageDecorator() {
        super();
    }

    /**
     * Whether the page offers to explain the build: explanation is enabled for it, its provider is
     * configured and the user may build the job, which a new explanation requires. Folder settings count,
     * so a folder with a provider of its own gets the button while the global switch is off, and a folder
     * that turns explanation off does not get it. An explanation the build has is shown to anyone who can
     * read the build.
     */
    public boolean isExplainErrorEnabled() {
        Run<?, ?> run = currentRun();
        if (run != null) {
            return run.hasPermission(Item.BUILD) && new ErrorExplainer().canExplain(run);
        }
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        return config.isEnableExplanation() && config.getAiProvider() != null
                && !config.getAiProvider().isNotValid(null);
    }

    /**
     * Whether the build can be copied for a coding agent: its log can be read and explanation is not turned off
     * for it. No AI provider is needed.
     */
    public boolean isAgentContextEnabled() {
        Run<?, ?> run = currentRun();
        return run != null && run.hasPermission(Item.READ) && new ErrorExplainer().isExplanationEnabled(run);
    }

    /**
     * @return the name of the provider a new explanation of the build would come from, folder or global
     */
    public String getProviderName() {
        Run<?, ?> run = currentRun();
        BaseAIProvider provider = run != null
                ? new ErrorExplainer().providerFor(run)
                : GlobalConfigurationImpl.get().getAiProvider();
        return provider != null ? provider.getProviderName() : null;
    }

    /**
     * Helper method used by jelly to checked if we're on a console url.
     */
    public boolean isPluginActive() {
        String uri = Stapler.getCurrentRequest2().getRequestURI();
        return uri.matches(".*/console(Full)?$");
    }

    public String getRunUrl() {
        Run<?, ?> run = currentRun();
        return run != null ? run.getUrl() : null;
    }

    public ErrorExplanationAction getExistingExplanation() {
        Run<?, ?> run = currentRun();
        return run != null ? run.getAction(ErrorExplanationAction.class) : null;
    }

    /** The build the current page belongs to, if any. */
    @CheckForNull
    private static Run<?, ?> currentRun() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        Ancestor ancestor = req != null ? req.findAncestor(Run.class) : null;
        return ancestor != null && ancestor.getObject() instanceof Run<?, ?> run ? run : null;
    }
}
