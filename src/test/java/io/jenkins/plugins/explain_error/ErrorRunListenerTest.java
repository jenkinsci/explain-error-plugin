package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.JobProperty;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.Shell;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.structs.SymbolLookup;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for {@link ErrorRunListener}.
 */
@WithJenkins
class ErrorRunListenerTest {

    private final List<UsageEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void recordUsageEvents() {
        UsageRecorders.setRecorderSupplier(() -> List.of(events::add));
    }

    @AfterEach
    void resetUsageRecorders() {
        UsageRecorders.resetRecorderSupplier();
    }

    @Test
    void autoExplainOnFailureIsDisabledByDefault(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl freshConfig = GlobalConfigurationImpl.get();
        assertFalse(freshConfig.isEnableAutoExplainOnFailure(),
                "Auto-explain on failure must be disabled by default");
    }

    @Test
    void successfulBuildDoesNotAddExplanationAction(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        assertNull(build.getAction(ErrorExplanationAction.class),
                "Successful build should not have an ErrorExplanationAction");
    }

    @Test
    void failedBuildAddsExplanationAction(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        FakeAIProvider provider = new FakeAIProvider();
        provider.setProviderName("AutoExplain-Provider");
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        assertNotNull(awaitExplanation(build),
                "Failed build should have an ErrorExplanationAction");
    }

    @Test
    void failedBuildThatAlreadyHasActionIsSkipped(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        FakeAIProvider provider = new FakeAIProvider();
        provider.setProviderName("AutoExplain-Provider");
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        // Wait for the asynchronous listener to add its action, then verify it
        // added exactly one. If it added a second (not detecting the first),
        // we'd see more than one.
        assertNotNull(awaitExplanation(build));
        assertEquals(1, build.getActions(ErrorExplanationAction.class).size(),
                "Listener must detect existing action and skip");
    }

    @Test
    void disabledAutoExplainDoesNotAddAction(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        FakeAIProvider provider = new FakeAIProvider();
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(false);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        assertNull(build.getAction(ErrorExplanationAction.class),
                "Failed build should NOT have an ErrorExplanationAction when auto-explain is disabled");
    }

    @Test
    void unstableBuildIsNotAutoExplained(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        FakeAIProvider provider = new FakeAIProvider();
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new org.jvnet.hudson.test.UnstableBuilder());
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.UNSTABLE, project.scheduleBuild2(0).get());

        // Auto-explain is restricted to result == FAILURE; UNSTABLE must be ignored.
        assertStaysUnexplained(build);
    }

    @Test
    void exceptionInListenerDoesNotBreakBuild(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        FakeAIProvider failingProvider = new FakeAIProvider();
        failingProvider.setThrowError(true);
        config.setAiProvider(failingProvider);
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        assertNotNull(build);
        assertEquals(Result.FAILURE, build.getResult());
        // No action because the provider threw; the listener catches internally
        assertNull(build.getAction(ErrorExplanationAction.class),
                "No action should be present when provider throws");
    }

    @Test
    void autoExplainUsesConfiguredProvider(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        FakeAIProvider provider = new FakeAIProvider();
        provider.setProviderName("Custom-Provider");
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        ErrorExplanationAction action = awaitExplanation(build);
        assertNotNull(action);
        assertTrue(action.hasValidExplanation(), "Explanation should be valid");
        assertEquals("Custom-Provider", action.getProviderName());
    }

    @Test
    void repeatedIdenticalFailureReusesTheExplanation(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = enableAutoExplain();
        FreeStyleProject project = failingProject(jenkins, "error: disk full");

        FreeStyleBuild first = buildAndAssertFailure(jenkins, project);
        ErrorExplanationAction original = awaitExplanation(first);
        assertNotNull(original);
        FreeStyleBuild second = buildAndAssertFailure(jenkins, project);
        ErrorExplanationAction reused = awaitExplanation(second);

        assertNotNull(reused);
        assertEquals(1, provider.getCallCount(), "An identical failure must not call the provider again");
        assertEquals(0, original.getReusedFromBuild());
        assertEquals(first.getNumber(), reused.getReusedFromBuild());
        assertEquals(original.getExplanation(), reused.getExplanation());
        assertNotNull(awaitEvent(UsageEvent.Result.CACHE_HIT));

        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {
            HtmlPage page = webClient.getPage(second, "error-explanation/");
            String note = page.getElementById("explain-error-reused").getTextContent().replaceAll("\\s+", " ");
            assertTrue(note.contains("failed the same way as build #" + first.getNumber()), note);
            HtmlAnchor link = page.getAnchorByText("build #" + first.getNumber());
            assertTrue(link.getHrefAttribute().endsWith(first.getUrl() + "error-explanation/"),
                    link.getHrefAttribute());
        }
    }

    @Test
    void differentFailureIsExplainedAgain(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = enableAutoExplain();
        FreeStyleProject project = failingProject(jenkins, "error: disk full");
        assertNotNull(awaitExplanation(buildAndAssertFailure(jenkins, project)));

        project.getBuildersList().replace(new Shell("echo 'error: out of memory'; exit 1"));
        ErrorExplanationAction second = awaitExplanation(buildAndAssertFailure(jenkins, project));

        assertNotNull(second);
        assertEquals(2, provider.getCallCount());
        assertEquals(0, second.getReusedFromBuild());
    }

    @Test
    void hourlyLimitStopsFurtherProviderCalls(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = enableAutoExplain();
        GlobalConfigurationImpl.get().setAutoExplainMaxPerHour(1);
        FreeStyleProject project = failingProject(jenkins, "error: disk full");
        assertNotNull(awaitExplanation(buildAndAssertFailure(jenkins, project)));

        project.getBuildersList().replace(new Shell("echo 'error: out of memory'; exit 1"));
        FreeStyleBuild second = buildAndAssertFailure(jenkins, project);
        UsageEvent rejected = awaitEvent(UsageEvent.Result.QUOTA_REJECTED);

        assertNotNull(rejected, "The second automatic explanation must be rejected by the hourly limit");
        assertEquals(UsageEvent.EntryPoint.RUN_LISTENER, rejected.entryPoint());
        assertNull(second.getAction(ErrorExplanationAction.class));
        assertEquals(1, provider.getCallCount());
    }

    @Test
    void fullQueueSkipsAutoExplainAndSaysSo(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = enableAutoExplain();
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        // One running and one queued task fill an executor with a single thread and a queue of one
        ThreadPoolExecutor saturated = ErrorRunListener.newExecutor(1, 1);
        saturated.execute(blocker);
        saturated.execute(blocker);
        ThreadPoolExecutor previous = ErrorRunListener.setExecutor(saturated);
        try {
            FreeStyleBuild build = buildAndAssertFailure(jenkins, failingProject(jenkins, "error: disk full"));

            jenkins.assertLogContains("auto-explain was skipped because too many failed builds", build);
            jenkins.assertLogNotContains("Auto-explain triggered", build);
            assertNull(build.getAction(ErrorExplanationAction.class));
            assertEquals(0, provider.getCallCount());
            assertEquals(UsageEvent.Result.THROTTLED, events.get(0).result());
        } finally {
            ErrorRunListener.setExecutor(previous);
            release.countDown();
            saturated.shutdown();
        }
    }

    @Test
    void jobCanOptOutWhileAutoExplainIsEnabled(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = enableAutoExplain();
        FreeStyleProject project = failingProject(jenkins, "error: flaky test");
        project.addProperty(new ExplainErrorJobProperty(false));

        FreeStyleBuild build = buildAndAssertFailure(jenkins, project);

        jenkins.assertLogNotContains("Auto-explain triggered", build);
        assertStaysUnexplained(build);
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void jobCanOptInWhileAutoExplainIsDisabled(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = enableAutoExplain();
        GlobalConfigurationImpl.get().setEnableAutoExplainOnFailure(false);
        FreeStyleProject project = failingProject(jenkins, "error: disk full");
        project.addProperty(new ExplainErrorJobProperty(true));

        assertNotNull(awaitExplanation(buildAndAssertFailure(jenkins, project)));
        assertEquals(1, provider.getCallCount());
    }

    @Test
    void pipelineJobCanOptOut(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = enableAutoExplain();
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "opt-out-pipeline");
        job.addProperty(new ExplainErrorJobProperty(false));
        job.setDefinition(new CpsFlowDefinition("error('boom')", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        jenkins.assertLogNotContains("Auto-explain triggered", run);
        assertStaysUnexplained(run);
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void jobPropertyIsAvailableToPipelineAsExplainErrorJob(JenkinsRule jenkins) throws Exception {
        // properties([explainErrorJob(autoExplainOnFailure: false)]) resolves the symbol and binds it like this
        Descriptor<?> descriptor = SymbolLookup.get().findDescriptor(JobProperty.class, "explainErrorJob");
        assertNotNull(descriptor);
        assertEquals(ExplainErrorJobProperty.class, descriptor.clazz);

        ExplainErrorJobProperty property = DescribableModel.of(ExplainErrorJobProperty.class)
                .instantiate(Map.of("autoExplainOnFailure", false));

        assertFalse(property.isAutoExplainOnFailure());
    }

    @Test
    void jobPropertySurvivesConfigurationRoundTrip(JenkinsRule jenkins) throws Exception {
        FreeStyleProject optedOut = jenkins.createFreeStyleProject();
        optedOut.addProperty(new ExplainErrorJobProperty(false));
        FreeStyleProject optedIn = jenkins.createFreeStyleProject();
        optedIn.addProperty(new ExplainErrorJobProperty(true));
        FreeStyleProject inheriting = jenkins.createFreeStyleProject();

        jenkins.configRoundtrip(optedOut);
        jenkins.configRoundtrip(optedIn);
        jenkins.configRoundtrip(inheriting);

        assertFalse(optedOut.getProperty(ExplainErrorJobProperty.class).isAutoExplainOnFailure());
        assertTrue(optedIn.getProperty(ExplainErrorJobProperty.class).isAutoExplainOnFailure());
        assertNull(inheriting.getProperty(ExplainErrorJobProperty.class));
    }

    @Test
    void autoExplainStaysQuietWhenExplanationIsDisabled(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = enableAutoExplain();
        GlobalConfigurationImpl.get().setEnableExplanation(false);

        FreeStyleBuild build = buildAndAssertFailure(jenkins, failingProject(jenkins, "error: disk full"));

        jenkins.assertLogNotContains("Auto-explain triggered", build);
        assertStaysUnexplained(build);
        assertEquals(0, provider.getCallCount());
    }

    private static FakeAIProvider enableAutoExplain() {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        FakeAIProvider provider = new FakeAIProvider();
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);
        return provider;
    }

    private static FreeStyleProject failingProject(JenkinsRule jenkins, String message) throws IOException {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new Shell("echo '" + message + "'; exit 1"));
        return project;
    }

    private static FreeStyleBuild buildAndAssertFailure(JenkinsRule jenkins, FreeStyleProject project)
            throws Exception {
        return jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0).get());
    }

    private UsageEvent awaitEvent(UsageEvent.Result result) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (UsageEvent event : events) {
                if (event.result() == result) {
                    return event;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    /**
     * The explanation is produced on a background thread, so poll for the action
     * to appear instead of asserting on it immediately.
     */
    private static ErrorExplanationAction awaitExplanation(Run<?, ?> build) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            ErrorExplanationAction action = build.getAction(ErrorExplanationAction.class);
            if (action != null) {
                return action;
            }
            Thread.sleep(100);
        }
        return build.getAction(ErrorExplanationAction.class);
    }

    /**
     * Asserts that no explanation action ever appears. Polls for a short window
     * so a regression that wrongly dispatched the async explanation would be
     * caught rather than racing past an immediate assertion.
     */
    private static void assertStaysUnexplained(Run<?, ?> build) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            assertNull(build.getAction(ErrorExplanationAction.class),
                    "Build should NOT be auto-explained");
            Thread.sleep(50);
        }
    }

    @Test
    void failedBuildIsNotExplainedTwiceOrWithAnInvalidProvider(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setEnableExplanation(true);
        config.setEnableAutoExplainOnFailure(true);
        config.setAiProvider(new FakeAIProvider());

        FreeStyleProject explainedDuringBuild = jenkins.createFreeStyleProject("explained-during-build");
        explainedDuringBuild.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                build.addAction(new ErrorExplanationAction("Explained by the pipeline", null, "logs", "Test"));
                return false;
            }
        });
        FreeStyleBuild explained = jenkins.buildAndAssertStatus(Result.FAILURE, explainedDuringBuild);
        assertEquals(1, explained.getActions(ErrorExplanationAction.class).size());
        assertEquals("Explained by the pipeline", explained.getAction(ErrorExplanationAction.class).getExplanation());
        jenkins.assertLogNotContains("Auto-explain triggered", explained);

        config.setAiProvider(new OpenAIProvider(null, "gpt-test", null));
        FreeStyleProject failing = jenkins.createFreeStyleProject("invalid-provider");
        failing.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild notExplained = jenkins.buildAndAssertStatus(Result.FAILURE, failing);
        jenkins.assertLogContains(
                "[explain-error] Build failed, but the AI provider configuration is invalid.", notExplained);
        assertNull(notExplained.getAction(ErrorExplanationAction.class));
    }
}
