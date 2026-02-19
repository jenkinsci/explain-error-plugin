package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import java.io.InputStream;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Integration tests for {@link PipelineLogExtractor}.
 * <p>
 * Tests the log extraction strategies:
 * <ol>
 *   <li>Strategy 1 — ErrorAction walk: standard uncaught exceptions where the failing step
 *       has both ErrorAction and LogAction (e.g. {@code sh 'exit 1'}).</li>
 *   <li>Strategy 2 — WarningAction walk: step nodes enclosed by a catchError block whose
 *       BlockStartNode carries a WarningAction. Triggers when Jenkins CPS records the
 *       WarningAction on the block's start node (pipeline-variant dependent).</li>
 *   <li>Strategy 3 — Error pattern scan: reads the full console log and returns lines
 *       matching error keywords with surrounding context. Handles the
 *       {@code catchError(buildResult:'SUCCESS') + sh(returnStatus:true) + error()} pattern
 *       used in production pipelines, and any case where errors appear early in large logs.</li>
 * </ol>
 */
@WithJenkins
class PipelineLogExtractorTest {

    @Test
    void testNullFlowExecutionFallsBackToBuildLog(JenkinsRule jenkins) throws Exception {
        // Create a mock WorkflowRun where getExecution() returns null
        WorkflowRun mockRun = mock(WorkflowRun.class);
        when(mockRun.getExecution()).thenReturn(null);
        when(mockRun.getLog(100)).thenReturn(List.of("Build started", "ERROR: Something failed"));
        when(mockRun.getLogInputStream()).thenReturn(InputStream.nullInputStream());
        when(mockRun.getUrl()).thenReturn("job/test/1/");

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 100);

        // Should not throw NullPointerException
        List<String> logLines = assertDoesNotThrow(() -> extractor.getFailedStepLog());

        // Should fall back to build log
        assertNotNull(logLines);
        assertEquals(2, logLines.size());
        assertEquals("ERROR: Something failed", logLines.get(1));

        // URL should be set (either console or stages depending on plugin availability)
        String url = extractor.getUrl();
        assertNotNull(url, "URL should not be null after getFailedStepLog()");
        assertTrue(url.contains("job/test/1/"), "URL should reference the build");
    }

    @Test
    void testNonPipelineBuildFallsBackToBuildLog(JenkinsRule jenkins) throws Exception {
        // FreeStyleBuild is not a WorkflowRun, so it should skip the pipeline path entirely
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        PipelineLogExtractor extractor = new PipelineLogExtractor(build, 100);
        List<String> logLines = extractor.getFailedStepLog();

        assertNotNull(logLines);
        assertFalse(logLines.isEmpty());

        String url = extractor.getUrl();
        assertNotNull(url);
        assertTrue(url.contains(build.getUrl()), "URL should reference the build");
    }

    /**
     * Strategy 1: Standard failure without catchError.
     * When a step fails and the exception propagates uncaught, the FlowGraph walk
     * finds the ErrorAction node and returns its log directly.
     * Expected: extracted log contains the error output from the failing step.
     */
    @Test
    @EnabledOnOs(OS.UNIX)
    void strategy1_standardFailure_extractsErrorStepLog(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-strategy1");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    sh 'echo \"STANDARD_ERROR_OUTPUT\" && exit 1'\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 200);
        List<String> lines = extractor.getFailedStepLog();

        String log = String.join("\n", lines);
        assertTrue(log.contains("STANDARD_ERROR_OUTPUT"),
                "Strategy 1 should extract the sh step log containing the error output.\nActual log:\n" + log);
    }

    /**
     * catchError wrapping sh(returnStatus:true) + error() — a common pattern in production pipelines.
     * <p>
     * sh captures exit code without throwing (no ErrorAction on sh node),
     * then error() throws with just a message (ErrorAction but NO LogAction on error step).
     * Strategy 1 finds the error() ErrorAction but has no log to return.
     * <p>
     * When catchError uses {@code buildResult: 'SUCCESS'}, the BlockStartNode does NOT carry a
     * WarningAction in Jenkins' CPS execution, so Strategy 2 (WarningAction walk) does not trigger.
     * Strategy 3 (error pattern scan of the full console log) finds the sh output lines via the
     * matching keyword patterns and returns them with surrounding context.
     * Expected: extracted log contains the sh step output from inside the catchError block.
     */
    @Test
    @EnabledOnOs(OS.UNIX)
    void strategy3_catchErrorWithReturnStatusPattern_extractsErrorLines(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-catcherror-returnstatus");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {\n"
                + "        def exitCode = sh(returnStatus: true, script: '"
                + "echo \"static analysis failed: 3 violations found\" && "
                + "echo \"ANALYSIS_FAILURE_MARKER\" && "
                + "exit 1')\n"
                + "        if (exitCode != 0) { error(\"Static analysis found violations\") }\n"
                + "    }\n"
                + "    currentBuild.result = 'FAILURE'\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 200);
        List<String> lines = extractor.getFailedStepLog();

        String log = String.join("\n", lines);
        assertTrue(log.contains("static analysis failed") || log.contains("ANALYSIS_FAILURE_MARKER"),
                "Strategy 3 should find the sh step output from inside catchError.\nActual log:\n" + log);
    }

    /**
     * Strategy 3: Error pattern scan for large logs where errors appear early.
     * A pipeline runs many steps that succeed, with an error-like message early in the log.
     * The build ultimately fails via error(). The last-N-lines fallback would miss the
     * early error message — Strategy 3 (error pattern scan) should find it.
     * Expected: extracted log contains the early error-like line.
     */
    @Test
    @EnabledOnOs(OS.UNIX)
    void strategy3_earlyErrorInLargeLog_extractsEarlyErrorLines(JenkinsRule jenkins) throws Exception {
        StringBuilder script = new StringBuilder();
        script.append("node {\n");
        // Early error-like output (sh succeeds with exit 0, but output matches error pattern)
        script.append("    sh 'echo \"critical error detected: 5 issues found\"'\n");
        // Many successful steps to push the error to the beginning of the log
        for (int i = 0; i < 50; i++) {
            script.append("    sh 'echo \"Step ").append(i).append(" completed successfully\"'\n");
        }
        // Final failure - this creates an ErrorAction, but its log is minimal
        script.append("    error('Build failed due to quality issues')\n");
        script.append("}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-large-log");
        job.setDefinition(new CpsFlowDefinition(script.toString(), true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        // Use a small maxLines to force Strategy 3 (last 10 lines won't include the early error)
        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 10);
        List<String> lines = extractor.getFailedStepLog();

        String log = String.join("\n", lines);
        // Strategy 1 finds error() step but its log just contains the error message
        // Strategy 3 scans full log and finds "critical error detected" even though it's early
        assertTrue(log.contains("critical error detected") || log.contains("issues found"),
                "Strategy 3 should find the early error-pattern line even in a large log.\nActual log:\n" + log);
    }

    /**
     * Multi-error: both a direct sh failure (Strategy 1) and a
     * catchError+sh(returnStatus:true)+error() failure (Strategy 3) occur in the same build.
     * <p>
     * Strategy 1 captures the direct sh failure log (ErrorAction + LogAction on the sh step).
     * Strategy 3 supplements with the catchError sh output from the full console log
     * (the error() step has ErrorAction but no LogAction, so Strategy 1 skips it).
     * Expected: the combined result contains output from both failing steps.
     */
    @Test
    @EnabledOnOs(OS.UNIX)
    void multiError_catchErrorAndDirectFailure_capturesBothErrors(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-multi-error");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                // catchError + sh(returnStatus:true) + error() — no LogAction on error()
                + "    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {\n"
                + "        def exitCode = sh(returnStatus: true, script: '"
                + "echo \"static analysis failed: 3 violations found\" && exit 1')\n"
                + "        if (exitCode != 0) { error('Static analysis found violations') }\n"
                + "    }\n"
                // Direct sh failure — has both ErrorAction and LogAction
                + "    sh 'echo \"DIRECT_FAILURE_MARKER\" && exit 1'\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 200);
        List<String> lines = extractor.getFailedStepLog();

        String log = String.join("\n", lines);
        assertTrue(log.contains("DIRECT_FAILURE_MARKER"),
                "Strategy 1 should capture the direct sh failure.\nActual log:\n" + log);
        assertTrue(log.contains("static analysis failed") || log.contains("violations"),
                "Strategy 3 should supplement with the catchError sh output.\nActual log:\n" + log);
    }

    /**
     * End-to-end test: verify that with a catchError pipeline, the AI provider receives
     * the error content from inside the catchError block (not just archiving warnings).
     * Uses TestProvider to capture what gets sent to the AI model.
     */
    @Test
    @EnabledOnOs(OS.UNIX)
    void endToEnd_catchErrorWithExplainError_aiReceivesInnerError(JenkinsRule jenkins) throws Exception {
        TestProvider testProvider = new TestProvider();
        GlobalConfigurationImpl.get().setAiProvider(testProvider);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-e2e-catcherror");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"RUBOCOP_OFFENSE_C_78_METRICS\" && exit 1'\n"
                + "    }\n"
                + "    explainError()\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        // Verify the AI provider was called and received the error from inside catchError
        assertTrue(testProvider.getCallCount() > 0, "AI provider should have been called");
        String sentLogs = testProvider.getLastErrorLogs();
        assertNotNull(sentLogs, "AI provider should have received log content");
        assertTrue(sentLogs.contains("RUBOCOP_OFFENSE_C_78_METRICS"),
                "AI provider should receive the error from inside catchError, not generic fallback.\n"
                + "Sent logs:\n" + sentLogs);
    }
}
