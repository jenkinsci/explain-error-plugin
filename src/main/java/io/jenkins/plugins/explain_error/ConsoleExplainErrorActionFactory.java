package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import jenkins.model.TransientActionFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

/**
 * TransientActionFactory to dynamically inject ConsoleExplainErrorAction into all runs.
 * This approach works for both new and existing runs, unlike RunListener which only
 * works for runs started after the plugin was installed.
 */
@Extension
public class ConsoleExplainErrorActionFactory extends TransientActionFactory<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(ConsoleExplainErrorActionFactory.class.getName());

    @Override
    @SuppressWarnings("unchecked")
    public Class<Run<?, ?>> type() {
        return (Class<Run<?, ?>>) (Class<?>) Run.class;
    }

    @Nonnull
    @Override
    public Collection<? extends Action> createFor(@Nonnull Run<?, ?> run) {
        try {
            // Create and return the ConsoleExplainErrorAction for this run
            ConsoleExplainErrorAction action = new ConsoleExplainErrorAction(run);
            return Collections.singletonList(action);
        } catch (Exception e) {
            String jobContext = JobContextUtil.createJobContext(run);
            LOGGER.severe(jobContext + " Failed to create ConsoleExplainErrorAction. Error: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
