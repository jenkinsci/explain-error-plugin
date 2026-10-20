package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.nio.file.Files;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A Jenkins whose root URL is not configured, as one set up without the setup wizard often is: explanations
 * are made, returned and saved all the same.
 */
@WithJenkins
class NoRootUrlTest {

    @Test
    void theStepReturnsItsExplanation(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = setUp(jenkins, "step");
        job.setDefinition(new CpsFlowDefinition(
                "try { error 'boom' } catch (e) { echo \"RESULT=${explainError()}\" }", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        jenkins.assertLogContains("RESULT=Summary: Request was successful", run);
        jenkins.assertLogNotContains("IllegalStateException", run);
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
        assertFalse(action.getUrlString().startsWith("null"), action.getUrlString());
    }

    @Test
    void anAutomaticExplanationIsSaved(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = setUp(jenkins, "auto");
        GlobalConfigurationImpl.get().setEnableAutoExplainOnFailure(true);
        job.setDefinition(new CpsFlowDefinition("error 'boom'", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        for (int i = 0; i < 200 && (run.getAction(ErrorExplanationAction.class) == null
                || PendingExplanations.contains(run)); i++) {
            Thread.sleep(100);
        }

        assertNotNull(run.getAction(ErrorExplanationAction.class), "the build was explained");
        assertFalse(PendingExplanations.contains(run));
        assertTrue(Files.readString(run.getRootDir().toPath().resolve("build.xml"))
                .contains(ErrorExplanationAction.class.getSimpleName()), "the explanation is saved with the build");
    }

    private static WorkflowJob setUp(JenkinsRule jenkins, String name) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new FakeAIProvider());
        JenkinsLocationConfiguration.get().setUrl(null);
        assertNull(Jenkins.get().getRootUrl(), "no root URL outside of a request");
        return jenkins.createProject(WorkflowJob.class, name);
    }
}
