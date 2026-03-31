package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.explain_error.provider.TestProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import hudson.util.LogTaskListener;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@WithJenkins
class UsageStatisticsManagerTest {

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private UsageStatisticsManager mgr;

    /** Stored in @BeforeEach so integration tests can use it without requesting a second instance. */
    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.j = jenkins;
        mgr = UsageStatisticsManager.get();
        assertNotNull(mgr, "UsageStatisticsManager extension must be registered");
        mgr.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        if (mgr != null) {
            mgr.flushPendingSaveForTesting();
        }
    }

    // ─────────────────────────────────────────────────────────
    // Class 1: recordCall() basic
    // ─────────────────────────────────────────────────────────

    @Test
    void recordCall_incrementsTotalCalls() {
        assertEquals(0L, mgr.getTotalCalls());
        mgr.recordCall("my-job", Instant.now());
        assertEquals(1L, mgr.getTotalCalls());
        mgr.recordCall("my-job", Instant.now());
        assertEquals(2L, mgr.getTotalCalls());
    }

    @Test
    void recordCall_incrementsDailyCounts() {
        Instant now = Instant.now();
        mgr.recordCall("my-job", now);
        mgr.recordCall("my-job", now);

        // calls this month covers today's calls
        assertTrue(mgr.getCallsThisMonth() >= 2);
    }

    @Test
    void recordCall_incrementsPerJobCounts() {
        mgr.recordCall("folder/job-a", Instant.now());
        mgr.recordCall("folder/job-a", Instant.now());
        mgr.recordCall("folder/job-a", Instant.now());

        List<Map.Entry<String, Long>> top = mgr.getTopJobs();
        assertEquals(1, top.size());
        assertEquals("folder/job-a", top.get(0).getKey());
        assertEquals(3L, top.get(0).getValue());
    }

    @Test
    void recordCall_multipleJobs_trackedSeparately() {
        mgr.recordCall("job-a", Instant.now());
        mgr.recordCall("job-a", Instant.now());
        mgr.recordCall("job-b", Instant.now());

        List<Map.Entry<String, Long>> top = mgr.getTopJobs();
        assertEquals(2, top.size());
        // sorted descending: job-a first
        assertEquals("job-a", top.get(0).getKey());
        assertEquals(2L, top.get(0).getValue());
        assertEquals("job-b", top.get(1).getKey());
        assertEquals(1L, top.get(1).getValue());
    }

    @Test
    void recordCall_nullJobFullName_usesUnknownFallback() {
        assertDoesNotThrow(() -> mgr.recordCall(null, Instant.now()));
        assertEquals(1L, mgr.getTotalCalls());
        List<Map.Entry<String, Long>> top = mgr.getTopJobs();
        assertEquals(1, top.size());
        assertEquals("unknown", top.get(0).getKey());
    }

    @Test
    void recordCall_nullTimestamp_usesNowFallback() {
        assertDoesNotThrow(() -> mgr.recordCall("my-job", null));
        assertEquals(1L, mgr.getTotalCalls());
        assertTrue(mgr.getCallsThisMonth() >= 1);
    }

    // ─────────────────────────────────────────────────────────
    // Class 2: pruneOldDays()
    // ─────────────────────────────────────────────────────────

    @Test
    void pruneOldDays_removesEntriesOlderThan31Days() {
        // Insert an old entry directly into the transient map
        String oldKey = DAY_FMT.format(Instant.now().minusSeconds(40L * 86400)); // 40 days ago
        mgr.dailyCounts.put(oldKey, new java.util.concurrent.atomic.AtomicLong(5L));

        mgr.pruneOldDays();

        assertFalse(mgr.dailyCounts.containsKey(oldKey), "Entry older than 31 days should be pruned");
    }

    @Test
    void pruneOldDays_preservesEntriesWithin31Days() {
        String recentKey = DAY_FMT.format(Instant.now().minusSeconds(10L * 86400)); // 10 days ago
        mgr.dailyCounts.put(recentKey, new java.util.concurrent.atomic.AtomicLong(3L));

        mgr.pruneOldDays();

        assertTrue(mgr.dailyCounts.containsKey(recentKey), "Entry within 31 days should be kept");
    }

    @Test
    void pruneOldDays_emptyMap_noException() {
        assertTrue(mgr.dailyCounts.isEmpty());
        assertDoesNotThrow(() -> mgr.pruneOldDays());
    }

    // ─────────────────────────────────────────────────────────
    // Class 3: XStream serialization round-trip
    // ─────────────────────────────────────────────────────────

    @Test
    void roundTrip_totalCallsPersisted() {
        mgr.recordCall("job-a", Instant.now());
        mgr.recordCall("job-b", Instant.now());
        mgr.save(); // snapshot to plain fields + write XML

        // Simulate restart: wipe transient state, reload from disk
        mgr.totalCallsValue = 0L;
        mgr.load();          // reads XML → sets totalCallsValue
        mgr.readResolve();   // rebuilds AtomicLong from totalCallsValue

        assertEquals(2L, mgr.getTotalCalls());
    }

    @Test
    void roundTrip_dailyCountsPersisted() {
        mgr.recordCall("job-a", Instant.now());
        mgr.save();

        mgr.dailyCountsMap = new java.util.HashMap<>();
        mgr.load();
        mgr.readResolve();

        assertTrue(mgr.getCallsThisMonth() >= 1);
    }

    @Test
    void roundTrip_perJobCountsPersisted() {
        mgr.recordCall("folder/my-job", Instant.now());
        mgr.recordCall("folder/my-job", Instant.now());
        mgr.save();

        mgr.perJobCountsMap = new java.util.HashMap<>();
        mgr.load();
        mgr.readResolve();

        List<Map.Entry<String, Long>> top = mgr.getTopJobs();
        assertEquals(1, top.size());
        assertEquals("folder/my-job", top.get(0).getKey());
        assertEquals(2L, top.get(0).getValue());
    }

    @Test
    void roundTrip_concurrentTypesRebuiltAfterLoad() {
        // After readResolve, types must be the concurrent variants
        mgr.recordCall("job", Instant.now());
        mgr.save();
        mgr.load();
        mgr.readResolve();

        // If transient types weren't rebuilt we'd get NPE on the next call
        assertDoesNotThrow(() -> mgr.recordCall("job", Instant.now()));
        assertEquals(2L, mgr.getTotalCalls());
    }

    // ─────────────────────────────────────────────────────────
    // Class 4: Concurrent safety
    // ─────────────────────────────────────────────────────────

    @Test
    void concurrentRecordCalls_noCountingLoss() throws InterruptedException {
        int threads = 10;
        int callsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int threadIdx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        mgr.recordCall("job-" + (threadIdx % 3), Instant.now());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean completed = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(completed, "All threads should complete within 30s");
        assertEquals((long) threads * callsPerThread, mgr.getTotalCalls(),
                "No count should be lost under concurrent access");
    }

    // ─────────────────────────────────────────────────────────
    // Class 5: ErrorExplainer integration
    // ─────────────────────────────────────────────────────────

    @Test
    void explainError_success_callsRecordCall() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);

        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        ErrorExplainer explainer = new ErrorExplainer();
        TaskListener listener = new LogTaskListener(Logger.getLogger("test"), Level.INFO);

        explainer.explainError(build, listener, null, 100);

        assertEquals(1L, mgr.getTotalCalls());
    }

    @Test
    void explainError_disabled_doesNotCallRecordCall() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new TestProvider());
        config.setEnableExplanation(false);

        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        ErrorExplainer explainer = new ErrorExplainer();
        TaskListener listener = new LogTaskListener(Logger.getLogger("test"), Level.INFO);

        explainer.explainError(build, listener, null, 100);

        assertEquals(0L, mgr.getTotalCalls(), "Disabled plugin must not record calls");
    }

    @Test
    void explainError_noProvider_doesNotCallRecordCall() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(null);
        config.setEnableExplanation(true);

        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        ErrorExplainer explainer = new ErrorExplainer();
        TaskListener listener = new LogTaskListener(Logger.getLogger("test"), Level.INFO);

        explainer.explainError(build, listener, null, 100);

        assertEquals(0L, mgr.getTotalCalls(), "No provider must not record calls");
    }

    @Test
    void explainError_failure_callsRecordCall() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        provider.setThrowError(true); // provider throws RuntimeException → ExplanationException
        config.setAiProvider(provider);
        config.setEnableExplanation(true);

        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        ErrorExplainer explainer = new ErrorExplainer();
        TaskListener listener = new LogTaskListener(Logger.getLogger("test"), Level.INFO);

        // Returns null on failure — but should still record the call
        String result = explainer.explainError(build, listener, null, 100);

        assertNull(result, "Failure should return null");
        assertEquals(1L, mgr.getTotalCalls(), "Failed API attempts must still be counted");
    }

    @Test
    void explainErrorText_success_callsRecordCall() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);

        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        ErrorExplainer explainer = new ErrorExplainer();
        explainer.explainErrorText("some error text", "http://example.com", build);

        assertEquals(1L, mgr.getTotalCalls());
    }

    @Test
    void explainErrorText_failure_callsRecordCall() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        provider.setThrowError(true);
        config.setAiProvider(provider);
        config.setEnableExplanation(true);

        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        ErrorExplainer explainer = new ErrorExplainer();

        // explainErrorText throws ExplanationException on provider failure
        assertThrows(Exception.class,
                () -> explainer.explainErrorText("some error text", "http://example.com", build));

        assertEquals(1L, mgr.getTotalCalls(), "Failed explainErrorText calls must still be counted");
    }

    // ─────────────────────────────────────────────────────────
    // Class 6: Safety
    // ─────────────────────────────────────────────────────────

    @Test
    void getTopJobs_returnedListIsTopTen() {
        // Insert 15 jobs with varying counts
        for (int i = 1; i <= 15; i++) {
            for (int j = 0; j < i; j++) {
                mgr.recordCall("job-" + i, Instant.now());
            }
        }

        List<Map.Entry<String, Long>> top = mgr.getTopJobs();
        assertEquals(10, top.size(), "getTopJobs must return at most 10 entries");
        // Verify sorted descending
        for (int i = 0; i < top.size() - 1; i++) {
            assertTrue(top.get(i).getValue() >= top.get(i + 1).getValue(),
                    "Top jobs must be sorted descending by call count");
        }
    }
}
