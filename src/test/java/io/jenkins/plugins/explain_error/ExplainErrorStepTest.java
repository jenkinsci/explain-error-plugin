package io.jenkins.plugins.explain_error;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ExplainErrorStepTest {

    @Test
    void testExplainErrorStep(JenkinsRule jenkins) throws Exception {
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
        jenkins.assertLogContains("ERROR: API key is not configured", run);
    }

    @Test
    void testGlobalConfiguration(JenkinsRule jenkins) throws Exception {
        // Test that global configuration can be accessed
        GlobalConfigurationImpl config =
                jenkins.getInstance().getDescriptorByType(GlobalConfigurationImpl.class);

        // With no auto-population, values should be null initially
        assert config.getProvider() == AIProvider.OPENAI;
        assert config.getApiUrl() == null; // No auto-population
        assert config.getModel() == null; // No auto-population
        assert config.isEnableExplanation() == true;
    }
}
