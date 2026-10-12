package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.JobProperty;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.structs.SymbolLookup;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A job overriding the global "Automatically explain failed builds" setting.
 */
@WithJenkins
class AutoExplainJobOverrideTest {

    private JenkinsRule jenkins;
    private GlobalConfigurationImpl config;
    private FakeAIProvider provider;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        config = GlobalConfigurationImpl.get();
        provider = new FakeAIProvider();
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);
    }

    private FreeStyleProject failingProject() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new FailureBuilder());
        return project;
    }

    private FreeStyleBuild buildAndFail(FreeStyleProject project) throws Exception {
        return jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
    }

    @Test
    void aJobCanOptOutWhileAutomaticExplanationIsOn() throws Exception {
        FreeStyleProject project = failingProject();
        project.addProperty(new ExplainErrorJobProperty(false));

        FreeStyleBuild build = buildAndFail(project);

        jenkins.assertLogNotContains("[explain-error]", build);
        assertStaysUnexplained(build);
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void aJobCanOptInWhileAutomaticExplanationIsOff() throws Exception {
        config.setEnableAutoExplainOnFailure(false);
        FreeStyleProject optedIn = failingProject();
        optedIn.addProperty(new ExplainErrorJobProperty(true));
        FreeStyleProject following = failingProject();

        FreeStyleBuild explained = buildAndFail(optedIn);
        FreeStyleBuild untouched = buildAndFail(following);

        jenkins.assertLogContains("Auto-explain triggered", explained);
        assertNotNull(awaitExplanation(explained));
        assertStaysUnexplained(untouched);
        assertEquals(1, provider.getCallCount());
    }

    @Test
    void aJobWithoutTheOverrideFollowsTheGlobalSetting() throws Exception {
        assertNotNull(awaitExplanation(buildAndFail(failingProject())));

        config.setEnableAutoExplainOnFailure(false);
        assertStaysUnexplained(buildAndFail(failingProject()));

        assertEquals(1, provider.getCallCount());
    }

    @Test
    void aPipelineJobCanOptOut() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "opt-out-pipeline");
        job.addProperty(new ExplainErrorJobProperty(false));
        job.setDefinition(new CpsFlowDefinition("error('boom')", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        jenkins.assertLogNotContains("[explain-error]", run);
        assertStaysUnexplained(run);
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void theOverrideIsAvailableToPipelineAsExplainErrorJob() throws Exception {
        // properties([explainErrorJob(autoExplainOnFailure: false)]) resolves the symbol and binds it like this
        Descriptor<?> descriptor = SymbolLookup.get().findDescriptor(JobProperty.class, "explainErrorJob");
        assertNotNull(descriptor);
        assertEquals(ExplainErrorJobProperty.class, descriptor.clazz);

        ExplainErrorJobProperty property = DescribableModel.of(ExplainErrorJobProperty.class)
                .instantiate(Map.of("autoExplainOnFailure", false));

        assertFalse(property.isAutoExplainOnFailure());
    }

    @Test
    void theOverrideSurvivesAConfigurationRoundTrip() throws Exception {
        FreeStyleProject optedOut = jenkins.createFreeStyleProject();
        optedOut.addProperty(new ExplainErrorJobProperty(false));
        FreeStyleProject optedIn = jenkins.createFreeStyleProject();
        optedIn.addProperty(new ExplainErrorJobProperty(true));
        FreeStyleProject following = jenkins.createFreeStyleProject();

        jenkins.configRoundtrip(optedOut);
        jenkins.configRoundtrip(optedIn);
        jenkins.configRoundtrip(following);

        assertFalse(optedOut.getProperty(ExplainErrorJobProperty.class).isAutoExplainOnFailure());
        assertTrue(optedIn.getProperty(ExplainErrorJobProperty.class).isAutoExplainOnFailure());
        assertNull(following.getProperty(ExplainErrorJobProperty.class));
    }

    @Test
    void optingInDoesNotGetPastExplanationBeingSwitchedOff() throws Exception {
        config.setEnableAutoExplainOnFailure(false);
        config.setEnableExplanation(false);
        FreeStyleProject project = failingProject();
        project.addProperty(new ExplainErrorJobProperty(true));

        FreeStyleBuild build = buildAndFail(project);

        jenkins.assertLogNotContains("[explain-error]", build);
        assertStaysUnexplained(build);
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void theGlobalSettingStaysQuietToo_whenExplanationIsSwitchedOff() throws Exception {
        config.setEnableExplanation(false);

        FreeStyleBuild build = buildAndFail(failingProject());

        jenkins.assertLogNotContains("[explain-error]", build);
        assertFalse(PendingExplanations.contains(build));
        assertStaysUnexplained(build);
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void aJobThatOptedInIsStillSubjectToTheHourlyLimit() throws Exception {
        config.setEnableAutoExplainOnFailure(false);
        config.setAutoExplainMaxPerHour(1);
        FreeStyleProject project = failingProject();
        project.addProperty(new ExplainErrorJobProperty(true));
        assertNotNull(awaitExplanation(buildAndFail(project)));

        FreeStyleBuild second = buildAndFail(project);

        jenkins.assertLogContains("auto-explain was skipped because the limit of 1", second);
        assertEquals(1, provider.getCallCount());
    }

    @Test
    void theGlobalLimitsAreKeptWhileTheGlobalToggleIsOff() throws Exception {
        // Jobs that opt in use them, and the form does not show them while the toggle is unchecked
        config.setEnableAutoExplainOnFailure(false);
        config.setAutoExplainMaxPerHour(45);
        config.setAutoExplainMaxLogLines(250);

        jenkins.configRoundtrip();

        assertFalse(GlobalConfigurationImpl.get().isEnableAutoExplainOnFailure());
        assertEquals(45, GlobalConfigurationImpl.get().getAutoExplainMaxPerHour());
        assertEquals(250, GlobalConfigurationImpl.get().getAutoExplainMaxLogLines());
    }

    @Test
    void optingOutLeavesTheExplainErrorButtonAlone() throws Exception {
        FreeStyleProject project = failingProject();
        project.addProperty(new ExplainErrorJobProperty(false));
        FreeStyleBuild build = buildAndFail(project);

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(build, "console");
            client.waitForBackgroundJavaScript(10_000);

            DomNode button = page.querySelector(".explain-error-btn");
            assertNotNull(button, "a job that opted out can still be explained by hand");
            assertEquals("Explain Error", button.getTextContent());
        }
    }

    @Test
    void theJobFormOffersTheOverrideWithBothChoices() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ExplainErrorJobProperty(true));

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            String form = client.getPage(project, "configure").asNormalizedText();

            assertTrue(form.contains("Override automatic AI error explanation"), form);
            assertTrue(form.contains("Explain automatically"), form);
            assertTrue(form.contains("Do not explain automatically"), form);
        }
    }

    private static ErrorExplanationAction awaitExplanation(Run<?, ?> build) throws InterruptedException {
        await(() -> build.getAction(ErrorExplanationAction.class) != null);
        return build.getAction(ErrorExplanationAction.class);
    }

    /** Gives a wrongly started explanation time to show up before saying there is none. */
    private static void assertStaysUnexplained(Run<?, ?> build) throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            assertNull(build.getAction(ErrorExplanationAction.class), "the build must not be explained");
            Thread.sleep(100);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
    }
}
