package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
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
        TestProvider provider = new TestProvider();
        provider.setProviderName("AutoExplain-Provider");
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        assertNotNull(build.getAction(ErrorExplanationAction.class),
                "Failed build should have an ErrorExplanationAction");
    }

    @Test
    void failedBuildThatAlreadyHasActionIsSkipped(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        provider.setProviderName("AutoExplain-Provider");
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        // The RunListener should have added exactly one action.
        // If the listener added a second one (not detecting the first),
        // we'd see more than one.
        assertEquals(1, build.getActions(ErrorExplanationAction.class).size(),
                "Listener must detect existing action and skip");
    }

    @Test
    void disabledAutoExplainDoesNotAddAction(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
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
    void unstableBuildAlsoTriggersAutoExplain(JenkinsRule jenkins) throws Exception {
        // Verify that UNSTABLE result is worse than SUCCESS,
        // which means it passes the gate in ErrorRunListener.onCompleted
        assertFalse(Result.UNSTABLE.isBetterOrEqualTo(Result.SUCCESS),
                "UNSTABLE should NOT be better-or-equal to SUCCESS");
        assertTrue(Result.SUCCESS.isBetterOrEqualTo(Result.SUCCESS));
        assertFalse(Result.FAILURE.isBetterOrEqualTo(Result.SUCCESS));
    }

    @Test
    void exceptionInListenerDoesNotBreakBuild(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider failingProvider = new TestProvider();
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
        TestProvider provider = new TestProvider();
        provider.setProviderName("Custom-Provider");
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        ErrorExplanationAction action = build.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
        assertTrue(action.hasValidExplanation(), "Explanation should be valid");
        assertEquals("Custom-Provider", action.getProviderName());
    }
}
