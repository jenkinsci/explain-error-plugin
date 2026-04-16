package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ExplainErrorStepTest {

    @Test
    void testExplainErrorStepInvalidConfig(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new OpenAIProvider(null, "test-model", null));

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-explain-error");

        // Define a simple pipeline that calls explainError directly
        String pipelineScript = "node {\n"
                + "    explainError()\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job - it should succeed but log the API key error
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));

        // Check that the explain error step was called and logged the expected error
        jenkins.assertLogContains("[explain-error] Starting explanation", run);
        jenkins.assertLogContains("[explain-error] Using provider OpenAI", run);
        jenkins.assertLogContains("No Api key configured for OpenAI.", run);
        jenkins.assertLogContains("[explain-error] Provider configuration is invalid.", run);
    }

    @Test
    void testExplainErrorStep(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new TestProvider());

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-explain-error");

        // Define a simple pipeline that calls explainError directly
        String pipelineScript = "node {\n"
                + "    explainError()\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job - it should succeed but log the API key error
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
        jenkins.assertLogContains("[explain-error] Starting explanation", run);
        jenkins.assertLogContains("[explain-error] Using provider Test, model test-model.", run);
        jenkins.assertLogContains("[explain-error] AI request completed successfully.", run);
        jenkins.assertLogContains("[explain-error] Explanation saved to the build.", run);
    }

    @Test
    void testExplainErrorStepReturnValue(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new TestProvider());

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-explain-error-return");

        // Define a pipeline that captures the return value
        String pipelineScript = "node {\n"
                + "    def explanation = explainError()\n"
                + "    echo \"Got explanation: ${explanation}\"\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job and verify the explanation was returned
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("Got explanation:", run);
        
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
    }

    @Test
    void testExplainErrorStepDisabled(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setEnableExplanation(false);
        config.setAiProvider(new TestProvider());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-explain-error-disabled");
        job.setDefinition(new CpsFlowDefinition("node {\n"
                + "    explainError()\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));

        jenkins.assertLogContains("[explain-error] Starting explanation", run);
        jenkins.assertLogContains("[explain-error] Explanation is disabled by configuration.", run);
    }

    @Test
    void testExplainErrorStepPassesLanguageToAI(JenkinsRule jenkins) throws Exception {
        TestProvider provider = new TestProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-language");
        job.setDefinition(new CpsFlowDefinition(
                "node { explainError(language: 'Chinese') }", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        assertEquals("Chinese", provider.getLastLanguage(),
                "language parameter should be forwarded to the AI provider");
    }

}
