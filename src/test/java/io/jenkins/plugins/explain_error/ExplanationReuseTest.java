package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import net.sf.json.JSONObject;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;

/**
 * An automatic explanation taken over from an earlier build of the job that failed the same way.
 */
@WithJenkins
class ExplanationReuseTest {

    private static final String DISK_FULL = "error: no space left on device, wanted 512 blocks";

    private JenkinsRule jenkins;
    private GlobalConfigurationImpl config;
    private FakeAIProvider provider;
    private ThreadPoolExecutor pool;
    private ThreadPoolExecutor previousPool;
    private final List<UsageEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        config = GlobalConfigurationImpl.get();
        provider = new FakeAIProvider();
        provider.setAnswer(new JenkinsLogAnalysis("The agent's disk is full.", List.of("Free some space."),
                List.of("Watch disk usage."), DISK_FULL, "Disk full on the agent"));
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);
        UsageRecorders.setRecorderSupplier(() -> List.of(events::add));
        // One at a time, on a pool of the test's own, so that "the background work is done" can be asked
        pool = ErrorRunListener.newExecutor(1, 20);
        previousPool = ErrorRunListener.setExecutor(pool);
    }

    @AfterEach
    void tearDown() {
        ErrorRunListener.setExecutor(previousPool);
        pool.shutdown();
        UsageRecorders.resetRecorderSupplier();
        System.clearProperty(ErrorExplainer.REUSE_LOOKBACK_PROPERTY);
    }

    // -----------------------------------------------------------------------
    // When an explanation is reused
    // -----------------------------------------------------------------------

    @Test
    void anIdenticalFailureReusesTheExplanation() throws Exception {
        FreeStyleProject project = failingProject(DISK_FULL);

        FreeStyleBuild first = failAndSettle(project);
        FreeStyleBuild second = failAndSettle(project);

        ErrorExplanationAction original = first.getAction(ErrorExplanationAction.class);
        ErrorExplanationAction reused = second.getAction(ErrorExplanationAction.class);
        assertNotNull(original);
        assertNotNull(reused, "the second build should have an explanation too");
        assertEquals(1, provider.getCallCount(), "an identical failure must not call the provider again");
        assertEquals(0, original.getReusedFromBuild());
        assertEquals(first.getNumber(), reused.getReusedFromBuild());
        assertEquals(original.getExplanation(), reused.getExplanation());
        assertEquals("Disk full on the agent", reused.getTitle());
        assertEquals(original.getTimestamp(), reused.getTimestamp(), "it is as old as the explanation it copies");
        assertEquals(original.getFailureFingerprint(), reused.getFailureFingerprint());
        UsageEvent hit = events.stream()
                .filter(e -> e.result() == UsageEvent.Result.CACHE_HIT).findFirst().orElse(null);
        assertNotNull(hit, "the reuse should be recorded");
        assertEquals(UsageEvent.EntryPoint.RUN_LISTENER, hit.entryPoint());
        assertNotNull(jenkins.jenkins.getItemByFullName(project.getFullName(), FreeStyleProject.class)
                .getBuildByNumber(second.getNumber()).getAction(ErrorExplanationAction.class));
    }

    @Test
    void aChainOfIdenticalFailuresNamesTheBuildThatWasExplained() throws Exception {
        FreeStyleProject project = failingProject(DISK_FULL);

        FreeStyleBuild first = failAndSettle(project);
        failAndSettle(project);
        FreeStyleBuild third = failAndSettle(project);

        assertEquals(1, provider.getCallCount());
        assertEquals(first.getNumber(), third.getAction(ErrorExplanationAction.class).getReusedFromBuild());
    }

    @Test
    void aDifferentFailureIsExplainedAgain() throws Exception {
        FreeStyleProject project = failingProject(DISK_FULL);
        failAndSettle(project);

        project.getBuildersList().replace(new FailingStep("error: out of memory"));
        FreeStyleBuild second = failAndSettle(project);

        assertEquals(2, provider.getCallCount());
        assertEquals(0, second.getAction(ErrorExplanationAction.class).getReusedFromBuild());
    }

    @Test
    void aFailureThatDiffersOnlyInAVersionIsExplainedAgain() throws Exception {
        FreeStyleProject project = failingProject("ERROR: No matching distribution found for requests==2.99.0");
        failAndSettle(project);

        project.getBuildersList().replace(
                new FailingStep("ERROR: No matching distribution found for requests==2.98.0"));
        FreeStyleBuild second = failAndSettle(project);

        assertEquals(2, provider.getCallCount(), "the old explanation names a version this build did not ask for");
        assertEquals(0, second.getAction(ErrorExplanationAction.class).getReusedFromBuild());
    }

    @Test
    void aSuccessfulBuildInBetweenDoesNotBreakTheMatch() throws Exception {
        FreeStyleProject project = failingProject(DISK_FULL);
        FreeStyleBuild first = failAndSettle(project);
        project.getBuildersList().clear();
        jenkins.buildAndAssertSuccess(project);

        project.getBuildersList().add(new FailingStep(DISK_FULL));
        FreeStyleBuild third = failAndSettle(project);

        assertEquals(1, provider.getCallCount());
        assertEquals(first.getNumber(), third.getAction(ErrorExplanationAction.class).getReusedFromBuild());
    }

    // -----------------------------------------------------------------------
    // What a reused explanation costs
    // -----------------------------------------------------------------------

    @Test
    void aReusedExplanationGivesItsPlaceInTheHourlyLimitBack() throws Exception {
        config.setAutoExplainMaxPerHour(2);
        FreeStyleProject project = failingProject(DISK_FULL);
        failAndSettle(project);
        failAndSettle(project);

        project.getBuildersList().replace(new FailingStep("error: out of memory"));
        FreeStyleBuild third = failAndSettle(project);

        jenkins.assertLogContains("Auto-explain triggered", third);
        assertNotNull(third.getAction(ErrorExplanationAction.class),
                "two provider calls were made, so the limit of two must not be spent by the reuse");
        assertEquals(2, provider.getCallCount());
    }

    @Test
    void aReusedExplanationDoesNotUseTheRequestQuota() throws Exception {
        config.setEnableQuota(true);
        config.setQuotaWindow(QuotaWindow.HOURLY);
        config.setMaxProviderCallsPerWindow(1);
        FreeStyleProject project = failingProject(DISK_FULL);
        failAndSettle(project);

        FreeStyleBuild second = failAndSettle(project);
        project.getBuildersList().replace(new FailingStep("error: out of memory"));
        FreeStyleBuild third = failAndSettle(project);

        assertNotNull(second.getAction(ErrorExplanationAction.class), "reuse needs no quota");
        assertNull(third.getAction(ErrorExplanationAction.class), "the quota of one call is spent");
        assertEquals(1, provider.getCallCount());
        assertTrue(events.stream().anyMatch(e -> e.result() == UsageEvent.Result.QUOTA_REJECTED));
    }

    // -----------------------------------------------------------------------
    // When it is not
    // -----------------------------------------------------------------------

    @Test
    void theStepAlwaysAsksTheProvider() throws Exception {
        config.setEnableAutoExplainOnFailure(false);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "step-no-reuse");
        job.setDefinition(new CpsFlowDefinition("node { explainError() }", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        WorkflowRun second = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        assertEquals(2, provider.getCallCount());
        assertEquals(0, second.getAction(ErrorExplanationAction.class).getReusedFromBuild());
    }

    @Test
    void anExplanationAskedForByHandIsNotPassedOnAndReplacesAReusedOne() throws Exception {
        config.setEnableAutoExplainOnFailure(false);
        FreeStyleProject project = failingProject(DISK_FULL);
        FreeStyleBuild first = failAndSettle(project);
        new ErrorExplainer().explainErrorText(DISK_FULL, "", first);
        assertNull(first.getAction(ErrorExplanationAction.class).getFailureFingerprint());

        config.setEnableAutoExplainOnFailure(true);
        FreeStyleBuild second = failAndSettle(project);
        FreeStyleBuild third = failAndSettle(project);

        assertEquals(0, second.getAction(ErrorExplanationAction.class).getReusedFromBuild(),
                "an explanation without a fingerprint cannot be matched");
        assertEquals(second.getNumber(), third.getAction(ErrorExplanationAction.class).getReusedFromBuild());
        assertEquals(2, provider.getCallCount());

        // "Re-explain" on the console page
        new ErrorExplainer().explainErrorText(DISK_FULL, "", third);
        assertEquals(0, third.getAction(ErrorExplanationAction.class).getReusedFromBuild());
        assertEquals(3, provider.getCallCount());
    }

    @Test
    void reuseCanBeSwitchedOff() throws Exception {
        System.setProperty(ErrorExplainer.REUSE_LOOKBACK_PROPERTY, "0");
        FreeStyleProject project = failingProject(DISK_FULL);
        failAndSettle(project);

        FreeStyleBuild second = failAndSettle(project);

        assertEquals(2, provider.getCallCount());
        assertEquals(0, second.getAction(ErrorExplanationAction.class).getReusedFromBuild());
    }

    @Test
    void aRequestMadeDifferentlyIsNotAMatch() throws Exception {
        FreeStyleProject project = failingProject(DISK_FULL);
        FreeStyleBuild first = failAndSettle(project);

        config.setLanguage("中文");
        FreeStyleBuild second = failAndSettle(project);

        assertEquals(2, provider.getCallCount(), "the earlier explanation is in another language");
        assertNotEquals(first.getAction(ErrorExplanationAction.class).getFailureFingerprint(),
                second.getAction(ErrorExplanationAction.class).getFailureFingerprint());
    }

    // -----------------------------------------------------------------------
    // What the reader is told
    // -----------------------------------------------------------------------

    @Test
    void thePagesSayWhichBuildAReusedExplanationCameFrom() throws Exception {
        FreeStyleProject project = failingProject(DISK_FULL);
        FreeStyleBuild first = failAndSettle(project);
        FreeStyleBuild second = failAndSettle(project);
        String note = "Reused from build #" + first.getNumber() + ", which failed the same way";

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage explanation = client.getPage(second, "error-explanation");
            HtmlAnchor link = explanation.querySelector(".eep-reused a");
            assertNotNull(link, "the explanation page should name and link the source build");
            assertEquals(note, link.getTextContent().trim());
            assertTrue(link.getHrefAttribute().endsWith(first.getUrl() + "error-explanation/"),
                    link.getHrefAttribute());

            DomNode summary = client.getPage(second).querySelector(".eep-build-summary__reused");
            assertNotNull(summary, "the build page should say so too");
            assertEquals(note, summary.getTextContent().trim());

            DomNode card = client.getPage(second, "console").querySelector("#explain-error-content .eep-reused");
            assertNotNull(card, "and so should the console card");

            assertNull(client.getPage(first, "error-explanation").querySelector(".eep-reused"));

            first.delete();
            HtmlPage orphaned = client.getPage(second, "error-explanation");
            assertEquals(note, orphaned.querySelector(".eep-reused").getTextContent().trim());
            assertNull(orphaned.querySelector(".eep-reused a"), "a build that is gone cannot be linked");
        }
    }

    @Test
    void theNoteIsInTheLanguageOfTheExplanation() throws Exception {
        config.setLanguage("中文");
        FreeStyleProject project = failingProject(DISK_FULL);
        FreeStyleBuild first = failAndSettle(project);
        FreeStyleBuild second = failAndSettle(project);

        DomNode note = jenkins.createWebClient().getPage(second, "error-explanation").querySelector(".eep-reused");

        assertEquals("复用自构建 #" + first.getNumber() + "（失败原因相同）", note.getTextContent().trim());
        assertEquals("Reprise du build #7, qui a échoué de la même façon",
                ExplanationLabels.forLanguage("French").reusedFrom(7));
    }

    @Test
    void aReusedExplanationKeepsItsSourceInTheBuildRecordAndTheRestApi() throws Exception {
        ErrorExplanationAction source = new ErrorExplanationAction(
                "Disk is full", "https://example.com/1", "log 1", "Ollama", "llama3.2", 7);
        source.setStructuredData(new JenkinsLogAnalysis("Disk is full", List.of("Free space"),
                List.of("Watch it"), "ENOSPC", "Disk full"));
        source.setLanguage("日本語");
        source.setFailureFingerprint("abc");

        ErrorExplanationAction reused = source.reuseFor(41, "https://example.com/2", "log 2");

        assertEquals("Disk is full", reused.getExplanation());
        assertEquals("https://example.com/2", reused.getUrlString());
        assertEquals("log 2", reused.getOriginalErrorLogs());
        assertEquals("Ollama", reused.getProviderName());
        assertEquals("llama3.2", reused.getProviderModel());
        assertEquals(7, reused.getInputLogLineCount());
        assertEquals("Disk is full", reused.getErrorSummary());
        assertEquals(List.of("Free space"), reused.getResolutionSteps());
        assertEquals(List.of("Watch it"), reused.getBestPractices());
        assertEquals("ENOSPC", reused.getErrorSignature());
        assertEquals("Disk full", reused.getTitle());
        assertEquals("修正方法", reused.getLabels().getHowToFix());
        assertEquals(source.getTimestamp(), reused.getTimestamp());
        assertEquals("abc", reused.getFailureFingerprint());
        assertEquals(41, reused.getReusedFromBuild());
        assertEquals(0, source.getReusedFromBuild());
        assertEquals(41, reused.reuseFor(42, "", "log 3").getReusedFromBuild(), "the source stays the same");
        assertNull(reused.getReusedFromBuildUrl(), "no build to link while the action is not attached to one");

        ErrorExplanationAction restored =
                (ErrorExplanationAction) Run.XSTREAM2.fromXML(Run.XSTREAM2.toXML(reused));
        assertEquals("abc", restored.getFailureFingerprint());
        assertEquals(41, restored.getReusedFromBuild());

        StringWriter writer = new StringWriter();
        Model<ErrorExplanationAction> model = new ModelBuilder().get(ErrorExplanationAction.class);
        model.writeTo(reused, Flavor.JSON.createDataWriter(reused, writer));
        assertEquals(41, JSONObject.fromObject(writer.toString()).getInt("reusedFromBuild"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private FreeStyleProject failingProject(String message) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new FailingStep(message));
        return project;
    }

    /** Fails a build and waits until the listener's background work for it is over. */
    private FreeStyleBuild failAndSettle(FreeStyleProject project) throws Exception {
        FreeStyleBuild build = jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(() -> !PendingExplanations.contains(build)
                && pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
        assertFalse(PendingExplanations.contains(build), "the automatic explanation should be over");
        return build;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 150 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
    }

    /** Fails the build the same way on every platform: one line in the log, then failure. */
    private static final class FailingStep extends TestBuilder {

        private final String message;

        private FailingStep(String message) {
            this.message = message;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            listener.getLogger().println(message);
            return false;
        }
    }
}
