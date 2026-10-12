package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.util.FormValidation;
import hudson.util.LogTaskListener;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

/**
 * The limits that keep a burst of failed builds from becoming a burst of provider calls:
 * how many automatic explanations run at once, how many wait, and how many are made per hour.
 */
@WithJenkins
class AutoExplainLimitsTest {

    private static final Logger LOGGER = Logger.getLogger(AutoExplainLimitsTest.class.getName());

    private JenkinsRule jenkins;
    private GlobalConfigurationImpl config;
    private FakeAIProvider provider;
    private final List<UsageEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        config = GlobalConfigurationImpl.get();
        provider = new FakeAIProvider();
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);
        UsageRecorders.setRecorderSupplier(() -> List.of(events::add));
    }

    @AfterEach
    void tearDown() {
        UsageRecorders.resetRecorderSupplier();
    }

    // -----------------------------------------------------------------------
    // The hourly limit
    // -----------------------------------------------------------------------

    @Test
    void hourlyLimitDefaultsTo30AndIsAtLeastOne() {
        assertEquals(30, config.getAutoExplainMaxPerHour());

        config.setAutoExplainMaxPerHour(0);
        assertEquals(1, config.getAutoExplainMaxPerHour());

        config.setAutoExplainMaxPerHour(120);
        assertEquals(120, config.getAutoExplainMaxPerHour());
    }

    @Test
    void hourlyLimitCountsCallsAndTakesBackTheOnesNotMade() {
        config.setAutoExplainMaxPerHour(2);

        assertTrue(config.tryAcquireAutoExplainQuota());
        assertTrue(config.tryAcquireAutoExplainQuota());
        assertFalse(config.tryAcquireAutoExplainQuota());

        config.releaseAutoExplainQuota();
        assertTrue(config.tryAcquireAutoExplainQuota());
        assertFalse(config.tryAcquireAutoExplainQuota());

        QuotaEnforcer unused = new QuotaEnforcer();
        unused.release();
        assertTrue(unused.tryAcquire(QuotaWindow.HOURLY, 1), "giving back more than was taken must not go negative");
        assertFalse(unused.tryAcquire(QuotaWindow.HOURLY, 1));
    }

    @Test
    void hourlyLimitIsValidatedAndSurvivesAConfigurationRoundTrip() throws Exception {
        assertEquals(FormValidation.Kind.ERROR, config.doCheckAutoExplainMaxPerHour(0).kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckAutoExplainMaxPerHour(30).kind);

        config.setAutoExplainMaxPerHour(45);
        jenkins.configRoundtrip();

        assertTrue(GlobalConfigurationImpl.get().isEnableAutoExplainOnFailure());
        assertEquals(45, GlobalConfigurationImpl.get().getAutoExplainMaxPerHour());
    }

    @Test
    void aBuildOverTheHourlyLimitIsSkippedAndItsConsoleSaysSo() throws Exception {
        config.setAutoExplainMaxPerHour(1);
        assertNotNull(awaitExplanation(failedBuild()));

        FreeStyleBuild second = failedBuild();

        jenkins.assertLogContains("auto-explain was skipped because the limit of 1 automatic explanations per hour"
                + " has been reached", second);
        jenkins.assertLogNotContains("Auto-explain triggered", second);
        assertFalse(PendingExplanations.contains(second), "a skipped build must not look like one being explained");
        assertNull(second.getAction(ErrorExplanationAction.class));
        assertEquals(1, provider.getCallCount());
        UsageEvent rejected = awaitEvent(UsageEvent.Result.QUOTA_REJECTED);
        assertNotNull(rejected);
        assertEquals(UsageEvent.EntryPoint.RUN_LISTENER, rejected.entryPoint());
    }

    @Test
    void inABurstEveryConsoleSaysWhetherItsBuildWillBeExplained() throws Exception {
        // The provider has not answered anything yet when the later builds fail
        BlockingProvider blocking = new BlockingProvider();
        config.setAiProvider(blocking);
        config.setAutoExplainMaxPerHour(2);
        try {
            List<FreeStyleBuild> builds = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                builds.add(failedBuild());
            }

            for (FreeStyleBuild build : builds.subList(0, 2)) {
                jenkins.assertLogContains("Auto-explain triggered", build);
            }
            for (FreeStyleBuild build : builds.subList(2, 4)) {
                jenkins.assertLogContains("auto-explain was skipped because the limit of 2", build);
                jenkins.assertLogNotContains("Auto-explain triggered", build);
                assertFalse(PendingExplanations.contains(build));
            }

            blocking.release.countDown();
            assertNotNull(awaitExplanation(builds.get(0)));
            assertNotNull(awaitExplanation(builds.get(1)));
            assertEquals(2, blocking.getCallCount());
        } finally {
            blocking.release.countDown();
        }
    }

    @Test
    void aCallTheProviderFailedStillCounts() throws Exception {
        config.setAutoExplainMaxPerHour(1);
        provider.setThrowError(true);
        FreeStyleBuild first = failedBuild();
        jenkins.assertLogContains("Auto-explain triggered", first);
        await(() -> !PendingExplanations.contains(first));
        assertNull(first.getAction(ErrorExplanationAction.class));

        FreeStyleBuild second = failedBuild();

        jenkins.assertLogContains("auto-explain was skipped because the limit of 1", second);
    }

    @Test
    void theStepAndTheConsoleButtonAreNotSubjectToTheHourlyLimit() throws Exception {
        config.setEnableAutoExplainOnFailure(false);
        config.setAutoExplainMaxPerHour(1);
        assertTrue(config.tryAcquireAutoExplainQuota());
        assertFalse(config.tryAcquireAutoExplainQuota());
        FreeStyleBuild build = failedBuild();

        assertNotNull(explain(build, UsageEvent.EntryPoint.PIPELINE_STEP));
        assertNotNull(new ErrorExplainer().explainErrorText("ERROR: boom", "", build));

        assertEquals(2, provider.getCallCount());
    }

    // -----------------------------------------------------------------------
    // How many run at once, and how many wait
    // -----------------------------------------------------------------------

    @Test
    void noMoreExplanationsRunAtOnceThanThePoolAllows() throws Exception {
        BlockingProvider blocking = new BlockingProvider();
        config.setAiProvider(blocking);
        ThreadPoolExecutor pool = ErrorRunListener.newExecutor(2, 50);
        ThreadPoolExecutor previous = ErrorRunListener.setExecutor(pool);
        try {
            List<FreeStyleBuild> builds = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                builds.add(failedBuild());
            }

            await(() -> blocking.inFlight.get() == 2 && pool.getQueue().size() == 3);
            assertEquals(2, blocking.inFlight.get(), "two explanations should be with the provider");
            assertEquals(3, pool.getQueue().size(), "the other three should wait");
            for (FreeStyleBuild build : builds) {
                assertTrue(PendingExplanations.contains(build), "a waiting build is still being explained");
            }

            blocking.release.countDown();
            for (FreeStyleBuild build : builds) {
                assertNotNull(awaitExplanation(build));
            }
            assertEquals(2, blocking.maxInFlight.get());
            assertEquals(5, blocking.getCallCount());
        } finally {
            ErrorRunListener.setExecutor(previous);
            blocking.release.countDown();
            pool.shutdown();
        }
    }

    @Test
    void aFullQueueSkipsTheBuildAndItsConsoleSaysSo() throws Exception {
        config.setAutoExplainMaxPerHour(1);
        CountDownLatch release = new CountDownLatch(1);
        // One running and one queued task fill an executor with a single thread and a queue of one
        ThreadPoolExecutor saturated = ErrorRunListener.newExecutor(1, 1);
        saturated.execute(() -> awaitQuietly(release));
        saturated.execute(() -> awaitQuietly(release));
        ThreadPoolExecutor previous = ErrorRunListener.setExecutor(saturated);
        try {
            FreeStyleBuild build = failedBuild();

            jenkins.assertLogContains("auto-explain was skipped because too many failed builds", build);
            jenkins.assertLogNotContains("Auto-explain triggered", build);
            assertFalse(PendingExplanations.contains(build), "a skipped build must not look like one being explained");
            assertNull(build.getAction(ErrorExplanationAction.class));
            assertEquals(0, provider.getCallCount());
            assertEquals(1, events.size());
            assertEquals(UsageEvent.Result.THROTTLED, events.get(0).result());
            assertEquals(UsageEvent.EntryPoint.RUN_LISTENER, events.get(0).entryPoint());
            assertTrue(config.tryAcquireAutoExplainQuota(), "a build that was never explained must not use the limit");
        } finally {
            ErrorRunListener.setExecutor(previous);
            release.countDown();
            saturated.shutdown();
        }
    }

    @Test
    void aBuildExplainedByHandWhileItWaitedIsNotExplainedAgain() throws Exception {
        config.setAutoExplainMaxPerHour(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = ErrorRunListener.newExecutor(1, 5);
        pool.execute(() -> awaitQuietly(release));
        ThreadPoolExecutor previous = ErrorRunListener.setExecutor(pool);
        try {
            FreeStyleBuild build = failedBuild();
            jenkins.assertLogContains("Auto-explain triggered", build);
            assertTrue(PendingExplanations.contains(build));
            ErrorExplanationAction byHand = new ErrorExplanationAction("explained by hand", "", "line 1", "Test");
            build.addAction(byHand);

            release.countDown();
            await(() -> !PendingExplanations.contains(build));

            assertFalse(PendingExplanations.contains(build));
            assertEquals(0, provider.getCallCount());
            assertSame(byHand, build.getAction(ErrorExplanationAction.class));
            assertTrue(config.tryAcquireAutoExplainQuota(), "no provider call was made, so the limit is not used");
        } finally {
            ErrorRunListener.setExecutor(previous);
            release.countDown();
            pool.shutdown();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private FreeStyleBuild failedBuild() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new FailureBuilder());
        return jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
    }

    private static String explain(Run<?, ?> run, UsageEvent.EntryPoint entryPoint) {
        return new ErrorExplainer().explainError(run, new LogTaskListener(LOGGER, Level.FINE), "", 100, null, null,
                false, null, null, null, entryPoint);
    }

    private static ErrorExplanationAction awaitExplanation(Run<?, ?> build) throws InterruptedException {
        await(() -> build.getAction(ErrorExplanationAction.class) != null);
        return build.getAction(ErrorExplanationAction.class);
    }

    private UsageEvent awaitEvent(UsageEvent.Result result) throws InterruptedException {
        await(() -> events.stream().anyMatch(event -> event.result() == result));
        return events.stream().filter(event -> event.result() == result).findFirst().orElse(null);
    }

    /** Waits up to ten seconds; what was waited for is asserted by the caller. */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A provider that holds every request until released, and counts how many it holds at once. */
    private static final class BlockingProvider extends FakeAIProvider {

        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Assistant createAssistant(@CheckForNull Item item, @CheckForNull Authentication authentication,
                                         @CheckForNull Double temperature) {
            Assistant assistant = super.createAssistant(item, authentication, temperature);
            return (errorLogs, language, customContext) -> {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    awaitQuietly(release);
                } finally {
                    inFlight.decrementAndGet();
                }
                return assistant.analyzeLogs(errorLogs, language, customContext);
            };
        }
    }
}
