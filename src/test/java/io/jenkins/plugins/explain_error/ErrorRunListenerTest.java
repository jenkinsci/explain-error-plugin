package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
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

    /**
     * The explanation is produced on a background thread, so poll for the action
     * to appear instead of asserting on it immediately.
     */
    private static ErrorExplanationAction awaitExplanation(FreeStyleBuild build) throws InterruptedException {
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
    private static void assertStaysUnexplained(FreeStyleBuild build) throws InterruptedException {
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
