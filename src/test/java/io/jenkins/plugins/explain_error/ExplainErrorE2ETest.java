package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * End-to-end integration tests that cover all supported plugin features.
 *
 * <p>These tests use {@link TestProvider} (no real AI API calls) and exercise
 * the full stack: pipeline execution → log extraction → provider selection →
 * AI input/output → build action storage.
 *
 * <p>Covered scenarios:
 * <ol>
 *   <li>Declarative {@code post { failure { explainError() } }} — primary real-world usage</li>
 *   <li>{@code logPattern} filters logs before passing to AI</li>
 *   <li>{@code maxLines} caps the log payload sent to AI</li>
 *   <li>Folder-level provider overrides global provider in a real pipeline run</li>
 *   <li>Console "Explain Error" button works on a genuinely failed build</li>
 *   <li>AI provider failure causes a graceful fallback — build stays SUCCESS</li>
 *   <li>{@code collectDownstreamLogs} includes downstream job output in AI input</li>
 * </ol>
 */
@WithJenkins
class ExplainErrorE2ETest {

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Primary usage: declarative pipeline with post { failure { explainError() } }
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies the most common real-world usage: a declarative pipeline that fails during
     * a stage and calls {@code explainError()} in the {@code post { failure {} }} block.
     *
     * <p>Asserts that:
     * <ul>
     *   <li>The AI provider is called exactly once.</li>
     *   <li>An {@link ErrorExplanationAction} is attached to the failed build.</li>
     *   <li>The explanation text matches the mocked provider response.</li>
     *   <li>The "[explain-error] Explanation saved to the build." message appears in the console.</li>
     * </ul>
     */
    @Test
    void declarativePipeline_postFailure_explainErrorGeneratesExplanation(JenkinsRule jenkins) throws Exception {
        TestProvider provider = new TestProvider();
        provider.setAnswerMessage("Declarative pipeline failure detected");
        GlobalConfigurationImpl.get().setAiProvider(provider);
        GlobalConfigurationImpl.get().setEnableExplanation(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "declarative-post-failure");
        job.setDefinition(new CpsFlowDefinition(
                "pipeline {\n"
                + "    agent any\n"
                + "    stages {\n"
                + "        stage('Build') {\n"
                + "            steps {\n"
                + "                error('Simulated build failure')\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "    post {\n"
                + "        failure {\n"
                + "            explainError()\n"
                + "        }\n"
                + "    }\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        assertEquals(1, provider.getCallCount(), "AI provider should be called exactly once");

        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action, "ErrorExplanationAction should be attached to the failed build");
        assertTrue(action.getExplanation().contains("Declarative pipeline failure detected"),
                "Explanation should contain the mocked provider response");

        jenkins.assertLogContains("[explain-error] Explanation saved to the build.", run);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. logPattern: only matching lines reach the AI
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that the {@code logPattern} parameter filters the log before it is sent to the AI.
     *
     * <p>The pipeline echoes both an "INCLUDE_ME" line and an "EXCLUDE_ME" line.
     * After calling {@code explainError(logPattern: 'INCLUDE_ME')}, the AI input must contain
     * "INCLUDE_ME" and must NOT contain "EXCLUDE_ME".
     */
    @Test
    void logPattern_filtersLogsBeforePassingToAI(JenkinsRule jenkins) throws Exception {
        TestProvider provider = new TestProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);
        GlobalConfigurationImpl.get().setEnableExplanation(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "log-pattern-filter");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    echo 'INCLUDE_ME: relevant error line'\n"
                + "    echo 'EXCLUDE_ME: irrelevant noise'\n"
                + "    explainError(logPattern: 'INCLUDE_ME')\n"
                + "}",
                true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        assertEquals(1, provider.getCallCount(), "AI provider should be called exactly once");
        String logsPassedToAI = provider.getLastErrorLogs();
        assertNotNull(logsPassedToAI, "Error logs should have been passed to the AI");
        assertTrue(logsPassedToAI.contains("INCLUDE_ME"),
                "Logs passed to AI should contain lines matching the logPattern");
        assertTrue(!logsPassedToAI.contains("EXCLUDE_ME"),
                "Logs passed to AI should NOT contain lines that don't match the logPattern.\nActual logs:\n" + logsPassedToAI);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. maxLines: log payload is capped before reaching the AI
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that the {@code maxLines} parameter limits the number of log lines sent to the AI.
     *
     * <p>The pipeline echoes 20 distinct lines, then calls {@code explainError(maxLines: 5)}.
     * The AI input must contain at most 5 lines.
     */
    @Test
    void maxLines_capsLogPayloadSentToAI(JenkinsRule jenkins) throws Exception {
        TestProvider provider = new TestProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);
        GlobalConfigurationImpl.get().setEnableExplanation(true);

        StringBuilder script = new StringBuilder("node {\n");
        for (int i = 1; i <= 20; i++) {
            script.append("    echo 'LOG_LINE_").append(i).append("'\n");
        }
        script.append("    explainError(maxLines: 5)\n");
        script.append("}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "max-lines-cap");
        job.setDefinition(new CpsFlowDefinition(script.toString(), true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        assertEquals(1, provider.getCallCount(), "AI provider should be called exactly once");
        String logsPassedToAI = provider.getLastErrorLogs();
        assertNotNull(logsPassedToAI, "Error logs should have been passed to the AI");
        String[] lines = logsPassedToAI.split("\n");
        assertTrue(lines.length <= 5,
                "AI should receive at most 5 lines but received " + lines.length
                + ":\n" + logsPassedToAI);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. Folder-level provider overrides global in a real pipeline run
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that a folder-level AI provider is used instead of the global one
     * when a pipeline job lives inside a configured folder.
     *
     * <p>The global provider returns "GLOBAL_ANSWER", the folder provider returns
     * "FOLDER_ANSWER". After running the pipeline, the explanation must contain
     * "FOLDER_ANSWER" and the global provider must have been called zero times.
     */
    @Test
    void folderProvider_overridesGlobalProvider_inRealPipelineRun(JenkinsRule jenkins) throws Exception {
        // Global provider — should NOT be called
        TestProvider globalProvider = new TestProvider();
        globalProvider.setAnswerMessage("GLOBAL_ANSWER");
        GlobalConfigurationImpl.get().setAiProvider(globalProvider);
        GlobalConfigurationImpl.get().setEnableExplanation(true);

        // Create folder with its own provider
        Folder folder = jenkins.jenkins.createProject(Folder.class, "team-folder");
        TestProvider folderProvider = new TestProvider();
        folderProvider.setAnswerMessage("FOLDER_ANSWER");
        ExplainErrorFolderProperty folderProperty = new ExplainErrorFolderProperty();
        folderProperty.setEnableExplanation(true);
        folderProperty.setAiProvider(folderProvider);
        folder.addProperty(folderProperty);

        // Create pipeline job inside the folder
        WorkflowJob job = folder.createProject(WorkflowJob.class, "pipeline-in-folder");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    explainError()\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        // Folder provider should have been called; global should not
        assertEquals(1, folderProvider.getCallCount(),
                "Folder-level provider should be called exactly once");
        assertEquals(0, globalProvider.getCallCount(),
                "Global provider should NOT be called when a folder-level provider is configured");

        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action, "ErrorExplanationAction should be attached to the build");
        assertTrue(action.getExplanation().contains("FOLDER_ANSWER"),
                "Explanation should come from the folder-level provider");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. Console "Explain Error" button works on a genuinely failed build
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that the core console "Explain Error" button logic works on a genuinely failed
     * pipeline build.
     *
     * <p>The button's HTTP handler ({@code doExplainConsoleError}) ultimately calls
     * {@link ErrorExplainer#explainErrorText}. This test exercises that full code path
     * directly on a {@link WorkflowRun} that failed with {@code error("msg")}, confirming
     * that the explanation is both generated and attached to the failed build.
     *
     * <p>HTTP-level routing (the actual POST → Stapler dispatch) is covered separately by
     * {@link ConsoleExplainErrorActionTest#testExplainConsoleError()} on a successful build.
     */
    @Test
    void consoleButton_onFailedBuild_generatesExplanation(JenkinsRule jenkins) throws Exception {
        TestProvider provider = new TestProvider();
        provider.setAnswerMessage("Console button explanation for failure");
        GlobalConfigurationImpl.get().setAiProvider(provider);
        GlobalConfigurationImpl.get().setEnableExplanation(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "console-button-failed");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    error('Simulated failure for console button test')\n"
                + "}",
                true));
        WorkflowRun failedRun = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        // Verify no action exists before triggering explanation
        assertNull(failedRun.getAction(ErrorExplanationAction.class),
                "No ErrorExplanationAction should exist before the console button is clicked");

        // Exercise the same code path that doExplainConsoleError() calls: extract logs
        // from the failed build, ask the AI, and attach the result to the run.
        ErrorExplainer explainer = new ErrorExplainer();
        ErrorExplanationAction action = explainer.explainErrorText(
                "Simulated failure for console button test",
                failedRun.getUrl() + "console",
                failedRun);

        assertNotNull(action, "ErrorExplanationAction should be returned by explainErrorText");
        assertTrue(action.getExplanation().contains("Console button explanation for failure"),
                "Explanation should contain the mocked provider response");

        // Confirm the action is also stored on the run (as doExplainConsoleError does it)
        ErrorExplanationAction stored = failedRun.getAction(ErrorExplanationAction.class);
        assertNotNull(stored, "ErrorExplanationAction should be attached to the failed build");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. AI provider failure → build continues, error message logged
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that when the AI provider throws an exception, {@code explainError()} does
     * NOT cause the pipeline to fail. The build must remain SUCCESS and the console must
     * contain the AI request failed message.
     *
     * <p>This tests the graceful-fallback behaviour that protects pipeline stability.
     */
    @Test
    void aiProviderFailure_buildContinues_errorMessageLogged(JenkinsRule jenkins) throws Exception {
        TestProvider provider = new TestProvider();
        provider.setThrowError(true);
        GlobalConfigurationImpl.get().setAiProvider(provider);
        GlobalConfigurationImpl.get().setEnableExplanation(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "ai-failure-graceful");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    explainError()\n"
                + "}",
                true));

        // Build should succeed even though the AI provider throws
        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        jenkins.assertLogContains("[explain-error] AI request failed:", run);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. collectDownstreamLogs: downstream job output is included in AI input
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that when {@code collectDownstreamLogs: true} is set, the logs from a
     * triggered downstream job are included in the payload sent to the AI.
     *
     * <p>The downstream job echoes a unique marker string ({@code DOWNSTREAM_UNIQUE_MARKER})
     * and then fails. The upstream job builds the downstream job (without propagating the
     * failure) and then calls {@code explainError(collectDownstreamLogs: true)}.
     * The AI input must contain the downstream marker.
     */
    @Test
    void collectDownstreamLogs_downstreamJobLogsIncludedInAIInput(JenkinsRule jenkins) throws Exception {
        TestProvider provider = new TestProvider();
        GlobalConfigurationImpl.get().setAiProvider(provider);
        GlobalConfigurationImpl.get().setEnableExplanation(true);

        // Create the downstream job that emits a unique marker and fails
        WorkflowJob downstreamJob = jenkins.createProject(WorkflowJob.class, "downstream-job");
        downstreamJob.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    echo 'DOWNSTREAM_UNIQUE_MARKER: something went wrong in downstream'\n"
                + "    error('Downstream job failed')\n"
                + "}",
                true));

        // Create the upstream job that triggers downstream and then calls explainError
        WorkflowJob upstreamJob = jenkins.createProject(WorkflowJob.class, "upstream-job");
        upstreamJob.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    build job: 'downstream-job', propagate: false\n"
                + "    explainError(collectDownstreamLogs: true, downstreamJobPattern: 'downstream-job')\n"
                + "}",
                true));

        jenkins.assertBuildStatus(Result.SUCCESS, upstreamJob.scheduleBuild2(0));

        assertEquals(1, provider.getCallCount(), "AI provider should be called exactly once");
        String logsPassedToAI = provider.getLastErrorLogs();
        assertNotNull(logsPassedToAI, "Error logs should have been passed to the AI");
        assertTrue(logsPassedToAI.contains("DOWNSTREAM_UNIQUE_MARKER"),
                "AI input should include unique log line from downstream job.\nActual logs:\n" + logsPassedToAI);
    }
}
