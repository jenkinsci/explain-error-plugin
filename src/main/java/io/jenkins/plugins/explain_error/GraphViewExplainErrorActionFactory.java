package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;

/**
 * TransientActionFactory to dynamically inject
 * {@link GraphViewExplainErrorAction} into all runs when the
 * {@code pipeline-graph-view} plugin is installed.
 */
@Extension
public class GraphViewExplainErrorActionFactory extends TransientActionFactory<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(
            GraphViewExplainErrorActionFactory.class.getName());

    @Override
    @SuppressWarnings("unchecked")
    public Class<Run<?, ?>> type() {
        return (Class<Run<?, ?>>) (Class<?>) Run.class;
    }

    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull Run<?, ?> run) {
        try {
            // Only inject when pipeline-graph-view plugin is installed
            if (Jenkins.get().getPlugin("pipeline-graph-view") == null) {
                return Collections.emptyList();
            }
            GraphViewExplainErrorAction action = new GraphViewExplainErrorAction(run);
            return Collections.singletonList(action);
        } catch (Exception e) {
            LOGGER.severe("Failed to create GraphViewExplainErrorAction for run: "
                    + run.getFullDisplayName() + ". Error: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
