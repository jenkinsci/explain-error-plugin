package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.LogTaskListener;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.springframework.security.core.Authentication;

/**
 * {@link RunListener} that automatically triggers AI error explanation
 * when a build fails and the global "auto explain on failure" toggle is enabled.
 *
 * Builds that already carry an {@link ErrorExplanationAction} are skipped to
 * avoid duplicate explanations when the pipeline already calls
 * {@code explainError()} explicitly.
 *
 * A single summary line ("[explain-error] Build failed. Auto-explain triggered...")
 * is written to the build console synchronously in {@link #onCompleted} while
 * the stream is guaranteed open. The actual AI provider call runs on a dedicated
 * background thread with a {@link LogTaskListener}, so the verbose setup messages
 * from {@link ErrorExplainer#explainError} go to the plugin log rather than
 * cluttering the build console. All exceptions are safely caught and logged so
 * they never interrupt the normal build lifecycle.
 */
@Extension
public class ErrorRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(ErrorRunListener.class.getName());

    /**
     * Dedicated daemon thread pool for auto-explain work. {@code onCompleted} runs
     * on the build's own thread, so making the (potentially slow) AI provider call
     * inline would hold the executor until the provider responds. Offloading keeps
     * the build completion flow non-blocking. Daemon threads do not prevent JVM
     * shutdown and avoid polluting {@code ForkJoinPool.commonPool()}.
     */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "explain-error-run-listener");
        t.setDaemon(true);
        return t;
    });

    @SuppressWarnings("rawtypes")
    public ErrorRunListener() {
        super((Class) Run.class);
    }

    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        if (!config.isEnableAutoExplainOnFailure()) {
            return;
        }

        // Only explain hard failures. UNSTABLE, ABORTED, NOT_BUILT and SUCCESS
        // are intentionally left untouched.
        if (run.getResult() != Result.FAILURE) {
            return;
        }

        // Skip if this build was already explained (e.g. via pipeline step)
        if (run.getAction(ErrorExplanationAction.class) != null) {
            LOGGER.fine("[" + fullName(run) + "] Skipping auto-explain: build already has an ErrorExplanationAction.");
            return;
        }

        // Verify the provider is valid before printing an optimistic message.
        // The pipeline step (explainError()) may have already run and failed silently
        // due to misconfiguration, so we resolve the provider here and check validity.
        // If the provider is null or invalid, we warn the user and skip — there is no
        // point saying "AI explanation will appear" when it cannot.
        ErrorExplainer explainer = new ErrorExplainer();
        BaseAIProvider provider = explainer.getResolvedProvider(run);
        if (provider == null) {
            listener.getLogger().println("[explain-error] Build failed, but no AI provider is configured."
                    + " No explanation will be generated.");
            return;
        }
        if (provider.isNotValid(new LogTaskListener(LOGGER, Level.FINE), run.getParent(), null)) {
            listener.getLogger().println("[explain-error] Build failed, but the AI provider configuration is invalid."
                    + " No explanation will be generated.");
            return;
        }

        // Write a single summary line while the stream is guaranteed open.
        // The background thread writes no further console output; verbose
        // setup messages from ErrorExplainer go to the plugin log instead.
        listener.getLogger().println("[explain-error] Build failed. Auto-explain triggered"
                + " \u2014 AI explanation will appear on the build page shortly.");

        // Capture the security context on the build thread; the background thread
        // starts with no authentication of its own.
        Authentication authentication = Jenkins.getAuthentication2();
        EXECUTOR.submit(() -> explainAsync(run, authentication));
    }

    /**
     * Performs the AI explanation off the build thread. Uses a
     * {@link LogTaskListener} instead of the build's original
     * {@link TaskListener} so the verbose diagnostic messages from
     * {@link ErrorExplainer#explainError} (provider resolution, log extraction,
     * etc.) are written to the plugin log rather than the build console.
     * A single summary line was already written synchronously in
     * {@link #onCompleted} where the stream was guaranteed open.
     */
    private void explainAsync(Run<?, ?> run, Authentication authentication) {
        try (ACLContext ignored = ACL.as2(authentication)) {
            LOGGER.fine("[" + fullName(run) + "] Build failed; automatically requesting AI explanation.");
            ErrorExplainer explainer = new ErrorExplainer();
            int maxLogLines = GlobalConfigurationImpl.get().getAutoExplainMaxLogLines();
            explainer.explainError(run, new LogTaskListener(LOGGER, Level.FINE),
                    "", maxLogLines, null, null, false,
                    null, authentication, null, UsageEvent.EntryPoint.RUN_LISTENER);
            // The build has already been finalized and saved by the time this
            // background task runs, so persist any freshly added action ourselves.
            run.save();
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
