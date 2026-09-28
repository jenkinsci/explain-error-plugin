package io.jenkins.plugins.explain_error;

import com.google.common.annotations.VisibleForTesting;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.springframework.security.core.Authentication;

/**
 * {@link RunListener} that automatically triggers AI error explanation
 * when a build fails and the global "auto explain on failure" toggle is enabled,
 * or when the job opts in through {@link ExplainErrorJobProperty}.
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

    /** Maximum number of automatic explanations requested from the AI provider at the same time. */
    static final int MAX_CONCURRENT_REQUESTS = Math.max(1, SystemProperties.getInteger(
            ErrorRunListener.class.getName() + ".maxConcurrentRequests", 2));

    /** Maximum number of failed builds waiting for an automatic explanation; further failures are skipped. */
    static final int MAX_QUEUED_REQUESTS = Math.max(1, SystemProperties.getInteger(
            ErrorRunListener.class.getName() + ".maxQueuedRequests", 50));

    /**
     * Dedicated daemon thread pool for auto-explain work. {@code onCompleted} runs
     * on the build's own thread, so making the (potentially slow) AI provider call
     * inline would hold the executor until the provider responds. The pool and its
     * queue are bounded, so a burst of failures (for example during an outage)
     * cannot start an unbounded number of threads and provider calls. Daemon threads
     * do not prevent JVM shutdown and avoid polluting {@code ForkJoinPool.commonPool()}.
     */
    private static volatile ThreadPoolExecutor executor = newExecutor(MAX_CONCURRENT_REQUESTS, MAX_QUEUED_REQUESTS);

    @SuppressWarnings("rawtypes")
    public ErrorRunListener() {
        super((Class) Run.class);
    }

    @VisibleForTesting
    static ThreadPoolExecutor newExecutor(int maxConcurrentRequests, int maxQueuedRequests) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(maxConcurrentRequests, maxConcurrentRequests,
                60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(maxQueuedRequests), r -> {
                    Thread t = new Thread(r, "explain-error-run-listener");
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Replaces the executor that runs automatic explanations.
     *
     * @return the executor that was replaced
     */
    @VisibleForTesting
    static ThreadPoolExecutor setExecutor(ThreadPoolExecutor replacement) {
        ThreadPoolExecutor previous = executor;
        executor = replacement;
        return previous;
    }

    /**
     * Returns whether failed builds of the job of {@code run} are explained automatically:
     * the job-level {@link ExplainErrorJobProperty} wins over the global setting.
     */
    static boolean isAutoExplainEnabled(Run<?, ?> run, GlobalConfigurationImpl config) {
        ExplainErrorJobProperty property = run.getParent().getProperty(ExplainErrorJobProperty.class);
        if (property != null) {
            return property.isAutoExplainOnFailure();
        }
        return config.isEnableAutoExplainOnFailure();
    }

    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        // Only explain hard failures. UNSTABLE, ABORTED, NOT_BUILT and SUCCESS
        // are intentionally left untouched.
        if (run.getResult() != Result.FAILURE) {
            return;
        }

        if (!isAutoExplainEnabled(run, GlobalConfigurationImpl.get())) {
            return;
        }

        // Skip if this build was already explained (e.g. via pipeline step)
        if (run.getAction(ErrorExplanationAction.class) != null) {
            LOGGER.fine("[" + fullName(run) + "] Skipping auto-explain: build already has an ErrorExplanationAction.");
            return;
        }

        // Explanation disabled globally or for the folder: nothing to do
        ErrorExplainer explainer = new ErrorExplainer();
        if (!explainer.isExplanationEnabled(run)) {
            return;
        }

        // Verify the provider is valid before printing an optimistic message.
        // The pipeline step (explainError()) may have already run and failed silently
        // due to misconfiguration, so we resolve the provider here and check validity.
        // If the provider is null or invalid, we warn the user and skip — there is no
        // point saying "AI explanation will appear" when it cannot.
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

        // Capture the security context on the build thread; the background thread
        // starts with no authentication of its own.
        Authentication authentication = Jenkins.getAuthentication2();
        try {
            executor.execute(() -> explainAsync(run, authentication));
        } catch (RejectedExecutionException e) {
            listener.getLogger().println("[explain-error] Build failed, but auto-explain was skipped because too many"
                    + " failed builds are already waiting for an explanation.");
            LOGGER.info("[" + fullName(run) + "] Auto-explain skipped: the queue of pending explanations is full.");
            UsageRecorders.get().record(new UsageEvent(System.currentTimeMillis(),
                    UsageEvent.EntryPoint.RUN_LISTENER, UsageEvent.Result.THROTTLED,
                    provider.getProviderName(), provider.getModel(), 0L, 0, false));
            return;
        }

        // Write a single summary line while the stream is guaranteed open.
        // The background thread writes no further console output; verbose
        // setup messages from ErrorExplainer go to the plugin log instead.
        listener.getLogger().println("[explain-error] Build failed. Auto-explain triggered"
                + " — AI explanation will appear on the build page shortly.");
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
            awaitLogCompletion(run);
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

    /**
     * {@code onCompleted} fires before the build log is closed, so its last lines may still be
     * written when the background task starts. Waiting briefly keeps the extracted log, and so
     * the failure fingerprint used to reuse explanations, the same for identical failures.
     */
    private static void awaitLogCompletion(Run<?, ?> run) {
        try {
            for (int i = 0; i < 50 && run.isLogUpdated(); i++) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String fullName(Run<?, ?> run) {
        return run.getParent().getFullName() + " #" + run.getNumber();
    }
}
