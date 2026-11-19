package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
                + "  assert r.containsKey('title') : 'missing title'\n"
                + "  echo 'all keys present'\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("all keys present", run);
    }

    @Test
    void testReturnStructuredCarriesTheTitle(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setAnswer(new JenkinsLogAnalysis("Disk is full", null, null, null, "Agent ran out of disk space."));
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-return-structured-title");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  def r = explainError(returnStructured: true)\n"
                + "  echo \"title: ${r.title}\"\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("title: Agent ran out of disk space", run);
        jenkins.assertLogNotContains("title: Agent ran out of disk space.", run);
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

    @Test
    void testReturnStructuredExposesListsAndRenderedExplanation(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setAnswer(new JenkinsLogAnalysis("Disk is full",
                new ArrayList<>(List.of("Free disk space", "Rotate logs")), null, "No space left on device"));
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-return-structured-lists");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  def r = explainError(returnStructured: true)\n"
                + "  echo \"steps: ${r.resolutionSteps.join(' | ')}\"\n"
                + "  echo \"practices: ${r.bestPractices.size()}\"\n"
                + "  echo \"signature: ${r.errorSignature}\"\n"
                + "  echo \"rendered: ${r.explanation.startsWith('Summary: Disk is full')}\"\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("steps: Free disk space | Rotate logs", run);
        jenkins.assertLogContains("practices: 0", run);
        jenkins.assertLogContains("signature: No space left on device", run);
        jenkins.assertLogContains("rendered: true", run);
    }

    @Test
    void testReturnStructuredReturnsNullWhenExplanationFails(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setThrowError(true);
        GlobalConfigurationImpl.get().setAiProvider(provider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-return-structured-failure");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  def r = explainError(returnStructured: true)\n"
                + "  echo \"result is null: ${r == null}\"\n"
                + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("result is null: true", run);
        assertNull(run.getAction(ErrorExplanationAction.class));
    }


    @Test
    void settersNormalizeMissingValuesToDefaults() {
        ExplainErrorStep step = new ExplainErrorStep();
        String defaultAllowedPaths = step.getAutoFixAllowedPaths();

        step.setLogPattern(null);
        step.setMaxLines(-1);
        step.setLanguage(null);
        step.setCustomContext(null);
        step.setDownstreamJobPattern(null);
        step.setWorkspaceContextPaths(null);
        step.setWorkspaceContextMaxBytes(0);
        step.setAutoFixCredentialsId(null);
        step.setAutoFixRemoteUrl(null);
        step.setAutoFixScmType(null);
        step.setAutoFixGithubEnterpriseUrl(null);
        step.setAutoFixGitlabUrl(null);
        step.setAutoFixBitbucketUrl(null);
        step.setAutoFixAllowedPaths(null);
        step.setAutoFixTimeoutSeconds(0);
        step.setAutoFixPrTemplate(null);

        assertEquals("", step.getLogPattern());
        assertEquals(100, step.getMaxLines());
        assertEquals("", step.getLanguage());
        assertEquals("", step.getCustomContext());
        assertEquals("", step.getDownstreamJobPattern());
        assertEquals(WorkspaceContextCollector.DEFAULT_PATHS, step.getWorkspaceContextPaths());
        assertEquals(WorkspaceContextCollector.DEFAULT_MAX_BYTES, step.getWorkspaceContextMaxBytes());
        assertEquals("", step.getAutoFixCredentialsId());
        assertEquals("", step.getAutoFixRemoteUrl());
        assertEquals("", step.getAutoFixScmType());
        assertEquals("", step.getAutoFixGithubEnterpriseUrl());
        assertEquals("", step.getAutoFixGitlabUrl());
        assertEquals("", step.getAutoFixBitbucketUrl());
        assertEquals(defaultAllowedPaths, step.getAutoFixAllowedPaths());
        assertEquals(new ExplainErrorStep().getAutoFixTimeoutSeconds(), step.getAutoFixTimeoutSeconds(),
                "0 falls back to the same default as a step that does not set it");
        assertEquals("", step.getAutoFixPrTemplate());
        assertFalse(step.isAdaptiveWorkspaceContext());
    }

    @Test
    void settersKeepConfiguredValues() {
        ExplainErrorStep step = new ExplainErrorStep();
        step.setTemperature(0.4);
        step.setCollectDownstreamLogs(true);
        step.setIncludeWorkspaceContext(true);
        step.setWorkspaceContextPaths("pom.xml");
        step.setWorkspaceContextMaxBytes(512);
        step.setAdaptiveWorkspaceContext(true);
        step.setAutoFix(true);
        step.setAutoFixCredentialsId("scm-token");
        step.setAutoFixRemoteUrl("https://github.com/acme/app.git");
        step.setAutoFixScmType("github");
        step.setAutoFixGithubEnterpriseUrl("https://ghe.example");
        step.setAutoFixGitlabUrl("https://gitlab.example");
        step.setAutoFixBitbucketUrl("https://bitbucket.example");
        step.setAutoFixAllowedPaths("pom.xml");
        step.setAutoFixDraftPr(true);
        step.setAutoFixTimeoutSeconds(30);
        step.setAutoFixPrTemplate("{explanation}");
        step.setReturnStructured(true);

        assertEquals(0.4, step.getTemperature());
        assertTrue(step.isCollectDownstreamLogs());
        assertTrue(step.isIncludeWorkspaceContext());
        assertEquals("pom.xml", step.getWorkspaceContextPaths());
        assertEquals(512, step.getWorkspaceContextMaxBytes());
        assertTrue(step.isAutoFix());
        assertEquals("scm-token", step.getAutoFixCredentialsId());
        assertEquals("https://github.com/acme/app.git", step.getAutoFixRemoteUrl());
        assertEquals("github", step.getAutoFixScmType());
        assertEquals("https://ghe.example", step.getAutoFixGithubEnterpriseUrl());
        assertEquals("https://gitlab.example", step.getAutoFixGitlabUrl());
        assertEquals("https://bitbucket.example", step.getAutoFixBitbucketUrl());
        assertEquals("pom.xml", step.getAutoFixAllowedPaths());
        assertTrue(step.isAutoFixDraftPr());
        assertEquals(30, step.getAutoFixTimeoutSeconds());
        assertEquals("{explanation}", step.getAutoFixPrTemplate());
        assertTrue(step.isReturnStructured());
        assertTrue(step.isAdaptiveWorkspaceContext());

        ExplainErrorStep.DescriptorImpl descriptor = new ExplainErrorStep.DescriptorImpl();
        assertEquals("explainError", descriptor.getFunctionName());
        assertEquals("Explain Error with AI", descriptor.getDisplayName());
        assertEquals(Set.of(Run.class, TaskListener.class), descriptor.getRequiredContext());
    }

    @Test
    void adaptiveWorkspaceContextIsOffByDefault(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "adaptive-off");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    writeFile file: 'src/main/java/com/example/App.java', text: 'class App {}'
                    try {
                        sh '''set +x
                echo "\tat com.example.App.main(App.java:7)"
                exit 1'''
                    } catch (e) {
                        explainError(includeWorkspaceContext: true, workspaceContextPaths: 'pom.xml')
                    }
                }
                """, true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        assertFalse(provider.getLastCustomContext().contains("src/main/java/com/example/App.java"),
                provider.getLastCustomContext());
        assertEquals(0, provider.getSelectionCallCount());
    }

    @Test
    void adaptiveWorkspaceContextReadsFilesNamedByTheLogWithoutASelectionCall(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "adaptive-anchor");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    writeFile file: 'src/main/java/com/example/App.java',
                            text: 'class App { void fail() {} }'
                    try {
                        sh '''set +x
                echo "\tat com.example.App.fail(App.java:7)"
                echo "BUILD FAILED"
                exit 1'''
                    } catch (e) {
                        explainError(includeWorkspaceContext: true, adaptiveWorkspaceContext: true)
                    }
                }
                """, true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        String context = provider.getLastCustomContext();
        assertTrue(context.contains("### src/main/java/com/example/App.java"), context);
        assertTrue(context.contains("class App { void fail() {} }"), context);
        assertEquals(0, provider.getSelectionCallCount());
        jenkins.assertLogContains("workspace file(s) named by the failure log", run);
    }

    @Test
    void adaptiveWorkspaceContextCallsTheSelectorOnceWhenTheLogNamesNoFile(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setSelectedWorkspaceFiles(List.of("config/app.properties"));
        GlobalConfigurationImpl.get().setAiProvider(provider);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "adaptive-selector");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    writeFile file: 'config/app.properties', text: 'retries=3'
                    try {
                        sh 'echo "BUILD FAILED"; exit 1'
                    } catch (e) {
                        explainError(includeWorkspaceContext: true, adaptiveWorkspaceContext: true)
                    }
                }
                """, true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        String context = provider.getLastCustomContext();
        assertTrue(context.contains("### config/app.properties"), context);
        assertTrue(context.contains("retries=3"), context);
        assertEquals(1, provider.getSelectionCallCount());
        jenkins.assertLogContains("selected by the AI provider", run);
    }

    @Test
    void adaptiveWorkspaceContextIgnoresUnsafeAndMissingSuggestedPaths(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setSelectedWorkspaceFiles(List.of("../outside.java", "/etc/passwd", ".env",
                "target/generated.java", "missing.java", "config/app.properties"));
        GlobalConfigurationImpl.get().setAiProvider(provider);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "adaptive-unsafe");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    writeFile file: 'config/app.properties', text: 'retries=3'
                    writeFile file: '.env', text: 'TOKEN=secret'
                    try {
                        sh 'echo "BUILD FAILED"; exit 1'
                    } catch (e) {
                        explainError(includeWorkspaceContext: true, adaptiveWorkspaceContext: true)
                    }
                }
                """, true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        String context = provider.getLastCustomContext();
        assertTrue(context.contains("### config/app.properties"), context);
        assertFalse(context.contains("TOKEN=secret"), context);
        assertFalse(context.contains("missing.java"), context);
        assertEquals(1, provider.getSelectionCallCount());
    }

    @Test
    void adaptiveWorkspaceContextFallsBackWhenSelectionFails(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setThrowOnSelection(true);
        GlobalConfigurationImpl.get().setAiProvider(provider);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "adaptive-failure");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    try {
                        sh 'echo "BUILD FAILED"; exit 1'
                    } catch (e) {
                        explainError(includeWorkspaceContext: true, adaptiveWorkspaceContext: true)
                    }
                }
                """, true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        assertNotNull(run.getAction(ErrorExplanationAction.class), "the explanation must still be saved");
        assertEquals(1, provider.getCallCount());
        jenkins.assertLogContains("Workspace file selection failed", run);
    }

    @Test
    void adaptiveWorkspaceContextWithoutAWorkspaceIsSkipped(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "adaptive-no-workspace");
        job.setDefinition(new CpsFlowDefinition(
                "explainError(includeWorkspaceContext: true, adaptiveWorkspaceContext: true)", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        jenkins.assertLogContains("Adaptive workspace context skipped: no workspace is available.", run);
        assertNotNull(run.getAction(ErrorExplanationAction.class));
        assertEquals(0, provider.getSelectionCallCount());
    }

    @Test
    void adaptiveWorkspaceContextDoesNotCallTheSelectorWhenQuotaRejects(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setEnableQuota(true);
        config.setMaxProviderCallsPerWindow(0);
        FakeAIProvider provider = new FakeAIProvider();
        config.setAiProvider(provider);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "adaptive-quota");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    try {
                        sh 'echo "BUILD FAILED"; exit 1'
                    } catch (e) {
                        explainError(includeWorkspaceContext: true, adaptiveWorkspaceContext: true)
                    }
                }
                """, true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        assertEquals(0, provider.getCallCount());
        assertEquals(0, provider.getSelectionCallCount());
    }

    @Test
    void workspaceContextIsCollectedOnlyWhenAWorkspaceIsAvailable(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-workspace-context");
        job.setDefinition(new CpsFlowDefinition("""
                explainError(includeWorkspaceContext: true)
                node {
                    writeFile file: 'notes.txt', text: 'not collected'
                    explainError(includeWorkspaceContext: true, workspaceContextPaths: 'missing.txt')
                    writeFile file: 'pom.xml', text: '<project>workspace-context</project>'
                    explainError(includeWorkspaceContext: true, workspaceContextPaths: 'pom.xml', autoFix: true,
                            customContext: 'Focus on the Maven build')
                }
                """, true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        jenkins.assertLogContains("[explain-error] Workspace context skipped: no workspace is available.", run);
        jenkins.assertLogContains("[explain-error] Workspace context is empty.", run);
        jenkins.assertLogContains("[explain-error] Workspace context collected.", run);
        assertTrue(provider.getLastCustomContext().contains("<project>workspace-context</project>"),
                "the workspace context is sent as additional context: " + provider.getLastCustomContext());
        assertFalse(provider.getLastCustomContext().contains("not collected"));
        assertTrue(provider.getLastCustomContext().contains("Focus on the Maven build"),
                "the step's custom context is kept in front of the workspace context");
        // Auto-fix receives the logs together with the workspace context; it stops at the missing credentials.
        jenkins.assertLogContains("autoFixCredentialsId is required", run);

        GlobalConfigurationImpl.get().setEnableExplanation(false);
        job.setDefinition(new CpsFlowDefinition(
                "explainError(autoFix: true, autoFixCredentialsId: 'scm-token')", true));
        WorkflowRun disabled = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("[AutoFix] Skipped: no error logs available", disabled);
    }
}
