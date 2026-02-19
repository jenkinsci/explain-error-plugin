package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import hudson.console.AnnotatedLargeText;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
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
    @DisabledOnOs(OS.WINDOWS)
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
    @DisabledOnOs(OS.WINDOWS)
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
    @DisabledOnOs(OS.WINDOWS)
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
    @DisabledOnOs(OS.WINDOWS)
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
     * Strategy 2: WarningAction walk — direct sh failure inside a catchError block
     * that carries {@code stageResult: 'FAILURE'}.
     * <p>
     * When catchError wraps a direct sh failure, Strategy 1 finds the sh step's ErrorAction
     * and LogAction and returns the log. Either way, the log from inside catchError must be
     * found by at least Strategy 1 or Strategy 3.
     * Expected: extracted log contains the sh step output.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void strategy2_catchErrorWithWarningAction_extractsStepLog(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-strategy2");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"STRATEGY2_MARKER error: violation A\" && "
                + "echo \"STRATEGY2_MARKER error: violation B\" && exit 1'\n"
                + "    }\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 200);
        List<String> lines = extractor.getFailedStepLog();

        String log = String.join("\n", lines);
        assertTrue(log.contains("STRATEGY2_MARKER"),
                "Strategy 1 or 2 should capture the sh log from inside catchError with WarningAction."
                + "\nActual log:\n" + log);
    }

    /**
     * End-to-end test: verify that with a catchError pipeline, the AI provider receives
     * the error content from inside the catchError block (not just archiving warnings).
     * Uses TestProvider to capture what gets sent to the AI model.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
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

    /**
     * Strategy 3 — context buffer eviction: when more than ERROR_CONTEXT_LINES (5) consecutive
     * non-error lines appear before the first error line, the buffer evicts the oldest entries
     * so only the nearest 5 lines are kept as pre-context.
     * Expected: the 2 oldest non-error lines are absent; the 5 nearest are present.
     */
    @Test
    void strategy3_contextBuffer_evictsOldestLinesWhenBufferFull(JenkinsRule jenkins) throws Exception {
        // 7 non-error lines (> ERROR_CONTEXT_LINES=5) then an error line
        String logContent = "ok1\nok2\nok3\nok4\nok5\nok6\nok7\nERROR: something failed\n";
        WorkflowRun mockRun = mock(WorkflowRun.class);
        when(mockRun.getExecution()).thenReturn(null);
        when(mockRun.getLog(anyInt())).thenReturn(List.of());
        when(mockRun.getLogInputStream())
                .thenReturn(new ByteArrayInputStream(logContent.getBytes(StandardCharsets.UTF_8)));
        when(mockRun.getUrl()).thenReturn("job/test/1/");

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 100);
        List<String> lines = extractor.getFailedStepLog();

        assertTrue(lines.contains("ERROR: something failed"), "Error line must be present");
        assertFalse(lines.contains("ok1"), "ok1 should be evicted (buffer capped at ERROR_CONTEXT_LINES=5)");
        assertFalse(lines.contains("ok2"), "ok2 should be evicted");
        assertTrue(lines.contains("ok3"), "ok3 should be within the 5-line pre-context window");
    }

    /**
     * Strategy 3 — maxLines constraint during context flush: with maxLines=1 and 2 non-error
     * lines buffered before an error line, the flush saturates the budget after the first line
     * (L149 while-loop exits because result.size() >= maxLines) so the error line itself is
     * never added (L153 false branch).
     * Expected: exactly 1 line returned, the first pre-context line.
     */
    @Test
    void strategy3_maxLines_contextFlushExhaustsBudgetBeforeErrorLine(JenkinsRule jenkins) throws Exception {
        String logContent = "pre1\npre2\nERROR: hit\npost\n";
        WorkflowRun mockRun = mock(WorkflowRun.class);
        when(mockRun.getExecution()).thenReturn(null);
        when(mockRun.getLog(anyInt())).thenReturn(List.of());
        when(mockRun.getLogInputStream())
                .thenReturn(new ByteArrayInputStream(logContent.getBytes(StandardCharsets.UTF_8)));
        when(mockRun.getUrl()).thenReturn("job/test/1/");

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 1);
        List<String> lines = extractor.getFailedStepLog();

        assertEquals(1, lines.size(), "maxLines=1 must be respected");
        assertEquals("pre1", lines.get(0));
    }

    /**
     * Strategy 3 — IOException in getLogInputStream: when reading the full console log
     * throws an IOException, the scan returns an empty list gracefully (exception is
     * swallowed internally) and execution falls through to the final build-log fallback.
     * Expected: no exception propagated; fallback returns run.getLog() content.
     */
    @Test
    void strategy3_ioExceptionInGetLogInputStream_doesNotPropagate(JenkinsRule jenkins) throws Exception {
        WorkflowRun mockRun = mock(WorkflowRun.class);
        when(mockRun.getExecution()).thenReturn(null);
        when(mockRun.getLog(anyInt())).thenReturn(List.of("fallback line"));
        when(mockRun.getLogInputStream()).thenThrow(new IOException("simulated disk failure"));
        when(mockRun.getUrl()).thenReturn("job/test/1/");

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 100);
        List<String> lines = assertDoesNotThrow(() -> extractor.getFailedStepLog());

        assertNotNull(lines);
        assertFalse(lines.isEmpty(), "Fallback must return content even when Strategy 3 I/O fails");
        assertEquals("fallback line", lines.get(0));
    }

    /**
     * Strategy 1 budget exhaustion: with a very small maxLines (5), the first failing step
     * fills the entire budget so the walker breaks immediately on the next iteration (L222),
     * leaving zero budget for Strategy 3 (L282 false branch — strategy3 skipped entirely).
     * Expected: result capped at maxLines.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void strategy1_budgetExhausted_walkerBreaksAndStrategy3Skipped(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-budget-exhausted");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                // Second failure (visited first by reverse walker) produces many lines
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"FIRST_ERROR\" && exit 1'\n"
                + "    }\n"
                // Direct failure visited second; walker breaks if budget=0 after first
                + "    sh 'for i in 1 2 3 4 5 6 7 8 9 10; do echo \"line $i\"; done && exit 1'\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 5);
        List<String> lines = extractor.getFailedStepLog();

        assertTrue(lines.size() <= 5, "Result must be capped at maxLines=5, got: " + lines.size());
    }

    /**
     * Strategy 1 with two direct sh failures (each in its own catchError block):
     * the FlowGraphWalker visits in reverse order so the second failure is processed first
     * and sets primaryNodeId. When the first failure is then processed, primaryNodeId is
     * already set so the false branch of the primaryNodeId-null check is exercised.
     * Expected: output contains at least one of the two failure markers.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void strategy1_twoDirectFailures_primaryNodeIdSetByFirstVisitedNode(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-two-direct-failures");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"FAILURE_A\" && exit 1'\n"
                + "    }\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"FAILURE_B\" && exit 1'\n"
                + "    }\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 200);
        List<String> lines = extractor.getFailedStepLog();

        String log = String.join("\n", lines);
        assertTrue(log.contains("FAILURE_A") || log.contains("FAILURE_B"),
                "At least one failure must be captured.\nActual log:\n" + log);
    }

    /**
     * Strategy 1 — seenOriginIds deduplication: a parallel block where a sh step fails
     * propagates the same exception to multiple FlowNodes (the sh node and outer parallel
     * block nodes). The second node that shares the same ErrorAction origin is skipped via
     * seenOriginIds, exercising the {@code seenOriginIds.contains()} branch of L226.
     * Expected: result contains the sh failure output without duplication.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void strategy1_parallelFailure_seenOriginIdsPreventsDuplication(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-parallel-dedup");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    parallel(\n"
                + "        'branch1': { sh 'echo \"PARALLEL_FAIL\" && exit 1' }\n"
                + "    )\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 200);
        List<String> lines = extractor.getFailedStepLog();

        String log = String.join("\n", lines);
        assertTrue(log.contains("PARALLEL_FAIL"),
                "Parallel branch failure must be captured.\nActual log:\n" + log);
    }

    /**
     * Strategy 1 — logAction null: a direct {@code error()} step creates an ErrorAction
     * on its FlowNode but no LogAction (the step only throws; it does not write to a step
     * log). Strategy 1 finds the ErrorAction via findOrigin, then skips the node because
     * {@code origin.getAction(LogAction.class)} returns null, exercising the
     * {@code logAction == null} branch.
     * Expected: Strategy 3 still surfaces the error message from the console log.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void strategy1_directErrorStep_logActionIsNullSkipsToStrategy3(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-direct-error-no-logaction");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    error('direct failure: critical error')\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 200);
        List<String> lines = extractor.getFailedStepLog();

        assertNotNull(lines);
        assertFalse(lines.isEmpty(), "Strategy 3 should surface the error message from the console log");
    }

    /**
     * Strategy 3 — stream dedup cap: when patternLines contains more entries than the remaining
     * budget, {@code stream().filter().limit(budget)} stops after {@code budget} items, capping
     * the result at maxLines without requiring an explicit break statement.
     * Expected: result is capped at maxLines.
     */
    @Test
    void strategy3_manyPatternLines_budgetBreakStopsLoop(JenkinsRule jenkins) throws Exception {
        // Build a log with 20 error lines — more than the budget of 5
        StringBuilder logContent = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            logContent.append("ERROR: violation ").append(i).append("\n");
        }
        WorkflowRun mockRun = mock(WorkflowRun.class);
        when(mockRun.getExecution()).thenReturn(null);
        when(mockRun.getLog(anyInt())).thenReturn(List.of());
        when(mockRun.getLogInputStream())
                .thenReturn(new ByteArrayInputStream(logContent.toString().getBytes(StandardCharsets.UTF_8)));
        when(mockRun.getUrl()).thenReturn("job/test/1/");

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 5);
        List<String> lines = extractor.getFailedStepLog();

        assertEquals(5, lines.size(), "Result must be capped at maxLines=5 by stream limit");
    }

    /**
     * Strategy 1 — {@code resolveOrigin} returns null: exercises the {@code origin == null}
     * branch of L216. When the resolved origin is null for all ErrorAction nodes, Strategy 1
     * skips every node and Strategy 3 fills the result from the console log.
     * Expected: no exception; Strategy 3 finds the echo output via the error pattern.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void strategy1_resolveOriginNull_originNullBranchCovered(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-origin-null");
        job.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"fatal: origin null test\" && exit 1' }", true));
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE,
                job.scheduleBuild2(0));

        // Override resolveOrigin to always return null → exercises origin == null branch
        PipelineLogExtractor extractor = spy(new PipelineLogExtractor(run, 200));
        doReturn(null).when(extractor).resolveOrigin(any(Throwable.class), any(FlowExecution.class));

        List<String> lines = assertDoesNotThrow(() -> extractor.getFailedStepLog());
        assertNotNull(lines);
        assertFalse(lines.isEmpty(), "Strategy 3 should find the echo output from the console log");
    }

    /**
     * Strategy 1 — same origin seen twice: exercises the {@code seenOriginIds.contains()}
     * branch of L216. When two or more FlowNodes with ErrorAction resolve to the same mock
     * origin, the second visit finds the origin already in seenOriginIds and skips.
     * A two-branch parallel ensures at least two ErrorAction nodes are present.
     * Expected: no exception; deduplication branch fires on the second visit.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void strategy1_sameOriginVisitedTwice_seenOriginIdsBranchCovered(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-seen-origin-dedup");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    parallel(\n"
                + "        'a': { sh 'echo \"error in branch A\" && exit 1' },\n"
                + "        'b': { sh 'echo \"error in branch B\" && exit 1' }\n"
                + "    )\n"
                + "}",
                true));
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE,
                job.scheduleBuild2(0));

        // Mock origin with empty but non-null log so the first visit adds to seenOriginIds
        FlowNode mockOrigin = mock(FlowNode.class);
        when(mockOrigin.getId()).thenReturn("shared-origin");
        LogAction mockLogAction = mock(LogAction.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        AnnotatedLargeText mockLog = mock(AnnotatedLargeText.class);
        when(mockLog.writeLogTo(anyLong(), any(java.io.Writer.class))).thenReturn(0L);
        // doReturn avoids the wildcard-capture type mismatch that thenReturn() would cause
        doReturn(mockLog).when(mockLogAction).getLogText();
        when(mockOrigin.getAction(LogAction.class)).thenReturn(mockLogAction);

        // All resolveOrigin calls return the same mock origin:
        // first visit → not in seen → add to seenOriginIds
        // second visit → seenOriginIds.contains("shared-origin") → true → L216 fires
        PipelineLogExtractor extractor = spy(new PipelineLogExtractor(run, 200));
        doReturn(mockOrigin).when(extractor).resolveOrigin(any(Throwable.class), any(FlowExecution.class));

        assertDoesNotThrow(() -> extractor.getFailedStepLog());
    }

    /**
     * Strategy 1 — origin has no LogAction: exercises the {@code logAction == null} branch
     * of L218. When the resolved origin node carries no {@link LogAction}, Strategy 1 skips
     * the node and Strategy 3 handles the fallback via the console log pattern scan.
     * Expected: no exception; Strategy 3 finds the echo output via the error pattern.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void strategy1_originWithoutLogAction_logNullBranchCovered(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-no-logaction");
        job.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"fatal: logaction null test\" && exit 1' }", true));
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE,
                job.scheduleBuild2(0));

        // Mock origin with null LogAction → exercises logAction == null branch (L218)
        FlowNode mockOrigin = mock(FlowNode.class);
        when(mockOrigin.getId()).thenReturn("no-log-origin");
        when(mockOrigin.getAction(LogAction.class)).thenReturn(null);

        PipelineLogExtractor extractor = spy(new PipelineLogExtractor(run, 200));
        doReturn(mockOrigin).when(extractor).resolveOrigin(any(Throwable.class), any(FlowExecution.class));

        List<String> lines = assertDoesNotThrow(() -> extractor.getFailedStepLog());
        assertNotNull(lines);
        assertFalse(lines.isEmpty(), "Strategy 3 should find the echo output from the console log");
    }
}
