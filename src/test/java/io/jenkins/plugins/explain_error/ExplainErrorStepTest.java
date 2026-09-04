package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
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
        config.setAiProvider(new FakeAIProvider());

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
        jenkins.assertLogContains("AI Error Explanation", run);
    }

    @Test
    void testExplainErrorStepReturnValue(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new FakeAIProvider());

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
        config.setAiProvider(new FakeAIProvider());

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
        FakeAIProvider provider = new FakeAIProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-language");
        job.setDefinition(new CpsFlowDefinition(
                "node { explainError(language: 'Chinese') }", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        assertEquals("Chinese", provider.getLastLanguage(),
                "language parameter should be forwarded to the AI provider");
    }

    // -------------------------------------------------------------------------
    // autoFix=true — null-guard and skip-path tests
    // -------------------------------------------------------------------------

    @Test
    void testAutoFix_disabledByDefault_noAutoFixSideEffects(JenkinsRule jenkins) throws Exception {
        // When autoFix is not set (default false), the auto-fix block is never entered
        // and no credentialsId is required
        FakeAIProvider provider = new FakeAIProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-autofix-disabled");
        job.setDefinition(new CpsFlowDefinition(
                "node { explainError() }", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogNotContains("[AutoFix]", run);
    }

    @Test
    void testAutoFix_blankCredentials_logsSkipAndContinues(JenkinsRule jenkins) throws Exception {
        // autoFix=true but no credentialsId → auto-fix fails early, step still returns explanation
        FakeAIProvider provider = new FakeAIProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-autofix-no-creds");
        job.setDefinition(new CpsFlowDefinition(
                "node { explainError(autoFix: true, autoFixRemoteUrl: 'https://github.com/org/repo') }", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        // The step must still return an explanation despite auto-fix failing
        jenkins.assertLogContains("[AutoFix]", run);
        jenkins.assertLogContains("autoFixCredentialsId", run);
    }

    // -------------------------------------------------------------------------
    // returnStructured parameter
    // -------------------------------------------------------------------------

    @Test
    void testReturnStructuredReturnsMappingWithErrorSummary(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setAnswerMessage("Disk is full");
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-return-structured-summary");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  def r = explainError(returnStructured: true)\n"
                + "  echo \"errorSummary: ${r.errorSummary}\"\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("errorSummary: Disk is full", run);
    }

    @Test
    void testReturnStructuredMapContainsAllExpectedKeys(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new FakeAIProvider());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-return-structured-keys");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  def r = explainError(returnStructured: true)\n"
                + "  assert r.containsKey('errorSummary') : 'missing errorSummary'\n"
                + "  assert r.containsKey('resolutionSteps') : 'missing resolutionSteps'\n"
                + "  assert r.containsKey('bestPractices') : 'missing bestPractices'\n"
                + "  assert r.containsKey('errorSignature') : 'missing errorSignature'\n"
                + "  echo 'all keys present'\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("all keys present", run);
    }

    @Test
    void testReturnStructuredFalseReturnsString(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new FakeAIProvider());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-return-structured-false");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  def r = explainError(returnStructured: false)\n"
                + "  assert r instanceof String : 'expected String when returnStructured=false'\n"
                + "  echo 'is string'\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("is string", run);
    }

    @Test
    void testReturnStructuredDefaultIsFalse(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new FakeAIProvider());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-return-structured-default");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  def r = explainError()\n"
                + "  assert r instanceof String : 'default must be String'\n"
                + "  echo 'default is string'\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("default is string", run);
    }

    @Test
    void testActionStructuredDataPopulatedWhenAnalysisAvailable(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setAnswerMessage("Compilation error in Foo.java");
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-action-structured");
        job.setDefinition(new CpsFlowDefinition("node { explainError() }", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
        assertNotNull(action.getErrorSummary(), "errorSummary must be set on the action");
        assertEquals("Compilation error in Foo.java", action.getErrorSummary());
    }

}
