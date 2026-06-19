package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.util.LogTaskListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * {@link RunListener} that automatically triggers AI error explanation
 * when a build fails and the global "auto explain on failure" toggle is enabled.
 *
 * Builds that already carry an {@link ErrorExplanationAction} are skipped to
 * avoid duplicate explanations when the pipeline already calls
 * {@code explainError()} explicitly.
 *
 * All exceptions are safely caught and logged so they never interrupt the
 * normal build completion flow.
 */
@Extension
public class ErrorRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(ErrorRunListener.class.getName());

    @SuppressWarnings("rawtypes")
    public ErrorRunListener() {
        super((Class) Run.class);
    }

    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        try {
            GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

            if (!config.isEnableAutoExplainOnFailure()) {
                return;
            }

            // Only explain builds that actually failed (UNSTABLE included)
            Result result = run.getResult();
            if (result == null || result.isBetterOrEqualTo(Result.SUCCESS)) {
                return;
            }

            // Skip if this build was already explained (e.g. via pipeline step)
            if (run.getAction(ErrorExplanationAction.class) != null) {
                LOGGER.fine("[" + fullName(run) + "] Skipping auto-explain: build already has an ErrorExplanationAction.");
                return;
            }

            // Use a LogTaskListener in case the original listener is no longer
            // suitable for long-running tasks; we also piggyback on the original
            // listener for best-effort console output
            TaskListener logListener = listener != null ? listener : new LogTaskListener(LOGGER, Level.INFO);

            logListener.getLogger().println("[explain-error] Build failed — automatically requesting AI explanation.");

            ErrorExplainer explainer = new ErrorExplainer();
            explainer.explainError(run, logListener, "", 100, null, null, false,
                    null, Jenkins.getAuthentication2(), null, UsageEvent.EntryPoint.RUN_LISTENER);

        } catch (Exception e) {
            // Safety net: never let a failure in the listener surface to the
            // build completion pipeline. Log and move on.
            LOGGER.log(Level.WARNING, "[" + fullName(run) + "] Auto-explain on failure failed unexpectedly.", e);
        }
    }

    private static String fullName(Run<?, ?> run) {
        return run.getParent().getFullName() + " #" + run.getNumber();
    }
}
