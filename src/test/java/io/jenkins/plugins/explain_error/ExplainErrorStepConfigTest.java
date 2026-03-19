package io.jenkins.plugins.explain_error;

import io.jenkins.plugins.explain_error.provider.TestProvider;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ExplainErrorStepConfigTest {

    @Test
    void testExplainErrorStepWithParameters(JenkinsRule jenkins) throws Exception {
        // Create a test pipeline job
        GlobalConfigurationImpl globalConfig = GlobalConfigurationImpl.get();
        globalConfig.setAiProvider(new TestProvider());
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-explain-error-config");

        // Define a simple pipeline that uses the step with parameters
        String pipelineScript = "node {\n" +
                "    echo 'This is a test build'\n" +
                "    echo 'ERROR: Something went wrong'\n" +
                "    echo 'FAILED: Build failed'\n" +
                "    explainError logPattern: 'ERROR|FAILED', maxLines: 50, "
                + "collectDownstreamLogs: true, downstreamJobPattern: 'team/.+'\n" +
                "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the build
        WorkflowRun build = jenkins.buildAndAssertSuccess(job);

        // Verify that the ExplainErrorStep was executed
        // Note: We can't test the actual AI explanation without a real API key
        // but we can verify the step executed without errors
        jenkins.assertLogContains("This is a test build", build);
    }
}
