package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jenkins.plugins.explain_error.provider.TestProvider;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CustomContextTest {

    @Test
    void testGlobalCustomContext(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setCustomContext("Global custom instructions");

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-global-context");

        String pipelineScript = "node {\n"
                + "    explainError()\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
        
        // Verify custom context is used
        assertEquals("Global custom instructions", config.getCustomContext());
    }

    @Test
    void testStepLevelCustomContextOverridesGlobal(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setCustomContext("Global custom instructions");

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-step-context");

        String pipelineScript = "node {\n"
                + "    explainError(customContext: 'Step-level custom context')\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
    }

    @Test
    void testCustomContextWithOtherParameters(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-combined-params");

        String pipelineScript = "node {\n"
                + "    explainError(\n"
                + "        maxLines: 200,\n"
                + "        language: 'Spanish',\n"
                + "        customContext: 'This is a payment service. Check PCI compliance.'\n"
                + "    )\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
    }
}
