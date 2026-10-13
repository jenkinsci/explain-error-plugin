package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Adds the "Explain Error" card and its scripts to the "Stages" page of the Pipeline Graph View
 * plugin. What the card needs to know about the build is the same as on the console page.
 */
@Extension
public class PipelineGraphViewDecorator extends ConsolePageDecorator {

    /**
     * Whether the current request is for the "Stages" page of a build. The page is looked for
     * right under the build, so that a job or folder that happens to be called "stages" does not
     * count.
     */
    @Override
    public boolean isPluginActive() {
        if (Jenkins.get().getPlugin("pipeline-graph-view") == null) {
            return false;
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return false;
        }
        Ancestor ancestor = req.findAncestor(Run.class);
        if (ancestor == null) {
            return false;
        }
        return isStagesPage(ancestor.getRestOfUrl());
    }

    /**
     * @param restOfUrl what follows the build in the request URL, without a leading slash
     */
    static boolean isStagesPage(String restOfUrl) {
        return restOfUrl.equals(PipelineLogExtractor.URL_NAME)
                || restOfUrl.startsWith(PipelineLogExtractor.URL_NAME + "/");
    }
}
