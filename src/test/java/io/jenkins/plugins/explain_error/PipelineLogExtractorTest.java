package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import hudson.console.AnnotatedLargeText;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

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
        PipelineLogExtractor.ExtractionResult result = assertDoesNotThrow(extractor::extractFailedStepLog);
        List<String> logLines = result.logLines();

        // Should fall back to build log
        assertNotNull(logLines);
        assertEquals(2, logLines.size());
        assertEquals("ERROR: Something failed", logLines.get(1));

        assertTrue(result.fallbackToBuildLog(), "Expected fallback metadata to be set");
        assertFalse(result.foundFailingNode(),
                "Mock run without flow execution should not report a failing node");

        // URL should be set (either console or stages depending on plugin availability)
        String url = result.url();
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
     * catchError + sh(returnStatus:true) + error() — FlowGraph traversal.
     * <p>
     * The error() step has ErrorAction but no LogAction. The sh step has LogAction but no
     * ErrorAction. {@code findImmediateParentWithLog} checks the immediate parent of the
     * error() node and returns it when it is the sole parent and carries a LogAction — the sh step.
     * Expected: extracted log contains the sh step output from inside the catchError block.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void catchError_returnStatusPattern_immediateParentShLogExtracted(JenkinsRule jenkins) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-catcherror-sibling");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        def exitCode = sh(returnStatus: true, script: '"
                + "echo \"CATCHERROR_SIBLING_MARKER\" && exit 1')\n"
                + "        if (exitCode != 0) { error('Command failed') }\n"
                + "    }\n"
                + "}",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 200);
        List<String> lines = extractor.getFailedStepLog();

        String log = String.join("\n", lines);
        assertTrue(log.contains("CATCHERROR_SIBLING_MARKER"),
                "FlowGraph traversal should find the sh output inside the catchError block.\nActual log:\n" + log);
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

    // -------------------------------------------------------------------------
    // isAbortedByFailFast unit tests
    // -------------------------------------------------------------------------

    /**
     * isAbortedByFailFast — non-ABORTED result returns false immediately.
     * A FAILURE build should never be considered a fail-fast abort regardless of
     * any InterruptedBuildAction that might be attached.
     */
    @Test
    void isAbortedByFailFast_nonAbortedResult_returnsFalse(JenkinsRule jenkins) {
        @SuppressWarnings("unchecked")
        Run<?, ?> mockRun = mock(Run.class);
        when(mockRun.getResult()).thenReturn(Result.FAILURE);

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 100);
        assertFalse(extractor.isAbortedByFailFast(mockRun),
                "A FAILURE build must not be treated as a fail-fast abort");
    }

    /**
     * isAbortedByFailFast — ABORTED build with a cause whose description contains
     * "fail fast" (case-insensitive) returns true.
     */
    @Test
    void isAbortedByFailFast_abortedWithFailFastCause_returnsTrue(JenkinsRule jenkins) {
        @SuppressWarnings("unchecked")
        Run<?, ?> mockRun = mock(Run.class);
        when(mockRun.getResult()).thenReturn(Result.ABORTED);

        CauseOfInterruption failFastCause = mock(CauseOfInterruption.class);
        when(failFastCause.getShortDescription()).thenReturn("Fail Fast: sibling branch failed");

        InterruptedBuildAction action = new InterruptedBuildAction(List.of(failFastCause));
        when(mockRun.getActions(InterruptedBuildAction.class)).thenReturn(List.of(action));

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 100);
        assertTrue(extractor.isAbortedByFailFast(mockRun),
                "An ABORTED build with a 'fail fast' cause description must return true");
    }

    /**
     * isAbortedByFailFast — ABORTED build whose InterruptedBuildAction cause description
     * does NOT contain "fail fast" returns false (e.g. a manual user abort).
     */
    @Test
    void isAbortedByFailFast_abortedWithNonFailFastCause_returnsFalse(JenkinsRule jenkins) {
        @SuppressWarnings("unchecked")
        Run<?, ?> mockRun = mock(Run.class);
        when(mockRun.getResult()).thenReturn(Result.ABORTED);

        CauseOfInterruption userCause = mock(CauseOfInterruption.class);
        when(userCause.getShortDescription()).thenReturn("Aborted by user admin");

        InterruptedBuildAction action = new InterruptedBuildAction(List.of(userCause));
        when(mockRun.getActions(InterruptedBuildAction.class)).thenReturn(List.of(action));

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 100);
        assertFalse(extractor.isAbortedByFailFast(mockRun),
                "A user-aborted build must not be treated as a fail-fast abort");
    }

    // -------------------------------------------------------------------------
    // Downstream sub-job integration tests
    // -------------------------------------------------------------------------

    /**
     * Downstream collection is opt-in: when the parent triggers a failing sub-job but
     * downstream collection is not explicitly enabled, the sub-job's log must not appear.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void downstream_defaultOff_subJobFailureNotIncluded(JenkinsRule jenkins) throws Exception {
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub-job-default-off");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"DEFAULT_OFF_MARKER\" && exit 1' }", true));

        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "parent-default-off");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'sub-job-default-off', propagate: false\n"
                + "currentBuild.result = 'FAILURE'",
                true));

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500);
        List<String> lines = extractor.getFailedStepLog();
        String log = String.join("\n", lines);

        assertFalse(log.contains("DEFAULT_OFF_MARKER"),
                "Downstream logs must be excluded unless explicitly enabled.\nActual log:\n" + log);
        assertFalse(log.contains("### Downstream Job: sub-job-default-off"),
                "No downstream section should be present when downstream collection is disabled.\nActual log:\n" + log);
    }

    /**
     * Downstream sub-job FAILURE: when a parent pipeline triggers a sub-job via the
     * {@code build} step and that sub-job fails, the parent's extracted log must contain
     * a downstream section with {@code Result: FAILURE} and the sub-job's error output.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void downstream_subJobFailure_logIncludedWithFailureHeader(JenkinsRule jenkins) throws Exception {
        // Create the sub-job that will fail
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub-job-failure");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"SUB_JOB_ERROR_MARKER\" && exit 1' }", true));

        // Create the parent pipeline that triggers the sub-job
        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "parent-triggers-failing-sub");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'sub-job-failure', propagate: false\n"
                + "currentBuild.result = 'FAILURE'",
                true));

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500, true, "sub-job-failure");
        PipelineLogExtractor.ExtractionResult result = extractor.extractFailedStepLog();
        List<String> lines = result.logLines();
        String log = String.join("\n", lines);

        assertTrue(log.contains("### Downstream Job: sub-job-failure"),
                "Log must contain a downstream section header.\nActual log:\n" + log);
        assertTrue(log.contains("Result: FAILURE"),
                "Downstream section must be labelled Result: FAILURE.\nActual log:\n" + log);
        assertTrue(log.contains("SUB_JOB_ERROR_MARKER"),
                "Downstream section must include the sub-job's error output.\nActual log:\n" + log);
        assertTrue(result.downstreamCollectionEnabled(), "Downstream collection should be enabled");
        assertEquals(1, result.downstreamMatchedCount(), "Expected one downstream build to be matched");
        assertEquals(0, result.downstreamReusedExplanationCount(),
                "Expected raw log extraction instead of explanation reuse");
    }

    /**
     * Downstream sub-job SUCCESS: when the triggered sub-job succeeds, no downstream
     * section should be appended to the parent's extracted log.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void downstream_subJobSuccess_logNotIncluded(JenkinsRule jenkins) throws Exception {
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub-job-success");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { echo 'SUB_JOB_SUCCESS_MARKER' }", true));

        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "parent-triggers-passing-sub");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'sub-job-success'\n"
                + "sh 'echo \"PARENT_FAILURE\" && exit 1'",
                true));

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500, true, "sub-job-success");
        List<String> lines = extractor.getFailedStepLog();
        String log = String.join("\n", lines);

        assertFalse(log.contains("### Downstream Job: sub-job-success"),
                "Successful sub-job must not produce a downstream section.\nActual log:\n" + log);
    }

    // -------------------------------------------------------------------------
    // collectDownstreamLogs unit tests (depth guard, deduplication)
    // -------------------------------------------------------------------------

    /**
     * MAX_DOWNSTREAM_DEPTH guard: when a PipelineLogExtractor is constructed at depth 5
     * (the maximum), {@code collectDownstreamLogs} must return immediately without
     * appending anything to the accumulated list.
     */
    @Test
    void collectDownstreamLogs_atMaxDepth_appendsNothing(JenkinsRule jenkins) throws Exception {
        // Use a real (successful) FreeStyleBuild so Jenkins.get() is available
        FreeStyleProject project = jenkins.createFreeStyleProject("depth-guard-project");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Construct at depth = MAX (5) to verify the guard in collectDownstreamLogs
        PipelineLogExtractor extractor = new PipelineLogExtractor(build, 200, 5);

        List<String> accumulated = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        extractor.collectDownstreamLogs(accumulated, visited);

        assertTrue(accumulated.isEmpty(),
                "collectDownstreamLogs at MAX_DOWNSTREAM_DEPTH must not append any lines");
    }

    /**
     * visitedRunIds deduplication: if a downstream run ID is already in the visited set,
     * {@code collectDownstreamLogs} must not append its log a second time.
     * <p>
     * Verified by pre-populating visitedRunIds with the sub-job's ID before the parent
     * run's extraction, then asserting the downstream section does not appear.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void collectDownstreamLogs_alreadyVisitedRunId_notAppendedTwice(JenkinsRule jenkins) throws Exception {
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub-dedup");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"DEDUP_MARKER\" && exit 1' }", true));

        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "parent-dedup");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'sub-dedup', propagate: false\n"
                + "currentBuild.result = 'FAILURE'",
                true));

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        // Find the sub-job run that was triggered
        WorkflowRun subRun = subJob.getLastBuild();
        assertNotNull(subRun, "Sub-job must have been triggered");

        // Pre-populate visitedRunIds with the sub-job's ID so it is treated as already seen
        PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500, true, "sub-dedup");
        // Call collectDownstreamLogs with a visited set that already contains both the parent
        // and the sub-job → no downstream section should be added for the sub-job.
        List<String> accumulated = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(parentRun.getParent().getFullName() + "#" + parentRun.getNumber());
        visited.add(subRun.getParent().getFullName() + "#" + subRun.getNumber());

        extractor.collectDownstreamLogs(accumulated, visited);

        String log = String.join("\n", accumulated);
        assertFalse(log.contains("DEDUP_MARKER"),
                "Already-visited sub-job must not be appended again.\nActual log:\n" + log);
    }

    /**
     * Capacity guard: when {@code accumulated} has already reached {@code maxLines},
     * {@code collectDownstreamLogs} must return immediately instead of scanning jobs/builds.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void collectDownstreamLogs_atCapacity_skipsDownstreamScan(JenkinsRule jenkins) throws Exception {
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub-capacity-guard");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"CAPACITY_GUARD_MARKER\" && exit 1' }", true));

        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "parent-capacity-guard");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'sub-capacity-guard', propagate: false\n"
                + "currentBuild.result = 'FAILURE'",
                true));

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 1, true, "sub-capacity-guard");
        List<String> accumulated = new ArrayList<>();
        accumulated.add("already full");
        Set<String> visited = new HashSet<>();
        visited.add(parentRun.getParent().getFullName() + "#" + parentRun.getNumber());

        extractor.collectDownstreamLogs(accumulated, visited);

        assertEquals(List.of("already full"), accumulated,
                "No downstream content should be appended after maxLines is already reached");
    }

    // -------------------------------------------------------------------------
    // Fast-path: reuse ErrorExplanationAction from sub-job
    // -------------------------------------------------------------------------

    /**
     * Fast path — sub-job has ErrorExplanationAction: when the downstream run already
     * carries an {@link ErrorExplanationAction} (i.e. it called {@code explainError()}),
     * the parent's extracted log must contain the pre-computed explanation text wrapped in
     * the "[AI explanation from sub-job]" marker, and must NOT contain raw log lines from
     * the sub-job (no redundant log extraction).
     * <p>
     * Strategy: run the parent pipeline first (which triggers the sub-job), then attach an
     * {@link ErrorExplanationAction} to the sub-job run that was actually triggered, and
     * finally re-run {@link PipelineLogExtractor} on the parent run to verify the fast path.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void downstream_subJobHasExplanationAction_explanationReusedInsteadOfRawLog(JenkinsRule jenkins) throws Exception {
        // Sub-job: fails but does NOT call explainError() yet
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub-with-explanation");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"RAW_SUB_LOG_SHOULD_NOT_APPEAR\" && exit 1' }", true));

        // Parent pipeline: triggers the sub-job
        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "parent-reuses-explanation");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'sub-with-explanation', propagate: false\n"
                + "currentBuild.result = 'FAILURE'",
                true));

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        // Find the sub-job run that was triggered by the parent
        WorkflowRun subRun = subJob.getLastBuild();
        assertNotNull(subRun, "Sub-job must have been triggered");

        // Simulate the sub-job having called explainError() by attaching an ErrorExplanationAction
        subRun.addOrReplaceAction(new ErrorExplanationAction(
                "SUB_JOB_AI_EXPLANATION: null pointer in Foo.bar()",
                "http://localhost/job/sub-with-explanation/1/console",
                "raw logs",
                "Test"));
        subRun.save();

        // Now extract logs from the parent — the fast path should kick in
        PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500, true, "sub-with-explanation");
        PipelineLogExtractor.ExtractionResult result = extractor.extractFailedStepLog();
        List<String> lines = result.logLines();
        String log = String.join("\n", lines);

        // The pre-computed explanation must appear
        assertTrue(log.contains("[AI explanation from sub-job]"),
                "Log must contain the fast-path marker.\nActual log:\n" + log);
        assertTrue(log.contains("SUB_JOB_AI_EXPLANATION"),
                "Log must contain the sub-job's explanation text.\nActual log:\n" + log);
        // Raw log lines from the sub-job's sh step must NOT be extracted again
        assertFalse(log.contains("RAW_SUB_LOG_SHOULD_NOT_APPEAR"),
                "Raw sub-job log lines must not appear when explanation is reused.\nActual log:\n" + log);
        assertEquals(1, result.downstreamMatchedCount(), "Expected one downstream build to be matched");
        assertEquals(1, result.downstreamReusedExplanationCount(),
                "Expected the downstream explanation to be reused");
    }

    /**
     * Slow path — sub-job has no ErrorExplanationAction: when the downstream run has no
     * {@link ErrorExplanationAction}, the parent falls back to raw log extraction and the
     * sub-job's error output appears directly (no "[AI explanation from sub-job]" marker).
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void downstream_subJobHasNoExplanationAction_rawLogExtractedAsFallback(JenkinsRule jenkins) throws Exception {
        // Sub-job: fails but does NOT call explainError() — no ErrorExplanationAction
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub-no-explanation");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"RAW_FALLBACK_MARKER\" && exit 1' }", true));

        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "parent-fallback-to-raw");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'sub-no-explanation', propagate: false\n"
                + "currentBuild.result = 'FAILURE'",
                true));

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500, true, "sub-no-explanation");
        List<String> lines = extractor.getFailedStepLog();
        String log = String.join("\n", lines);

        // Raw log must be present
        assertTrue(log.contains("RAW_FALLBACK_MARKER"),
                "Slow path must extract raw sub-job log.\nActual log:\n" + log);
        // Fast-path marker must NOT appear
        assertFalse(log.contains("[AI explanation from sub-job]"),
                "Fast-path marker must not appear when no explanation exists.\nActual log:\n" + log);
    }

    /**
     * Downstream regex filter: when downstream collection is enabled but the job name
     * does not match the configured pattern, the sub-job log must be skipped.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void downstream_patternMismatch_subJobFailureNotIncluded(JenkinsRule jenkins) throws Exception {
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub-job-pattern-miss");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"PATTERN_MISS_MARKER\" && exit 1' }", true));

        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "parent-pattern-miss");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'sub-job-pattern-miss', propagate: false\n"
                + "currentBuild.result = 'FAILURE'",
                true));

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500, true, "other-job-.*");
        List<String> lines = extractor.getFailedStepLog();
        String log = String.join("\n", lines);

        assertFalse(log.contains("PATTERN_MISS_MARKER"),
                "Sub-job logs must be skipped when the job name does not match the regex.\nActual log:\n" + log);
        assertFalse(log.contains("### Downstream Job: sub-job-pattern-miss"),
                "No downstream section should be present for non-matching jobs.\nActual log:\n" + log);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void downstream_invisibleBuildStepJob_replacedWithHiddenPlaceholder(JenkinsRule jenkins) throws Exception {
        Authentication viewer = configureReadAccess(jenkins, "viewer-build-step");

        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "hidden-build-step-sub");
        subJob.setDefinition(new CpsFlowDefinition(
                "node { sh 'echo \"HIDDEN_BUILD_STEP_MARKER\" && exit 1' }", true));

        WorkflowJob parentJob = jenkins.createProject(WorkflowJob.class, "visible-build-step-parent");
        parentJob.setDefinition(new CpsFlowDefinition(
                "build job: 'hidden-build-step-sub', propagate: false\n"
                + "currentBuild.result = 'FAILURE'",
                true));

        grantItemRead(jenkins, "viewer-build-step", parentJob);

        WorkflowRun parentRun = jenkins.assertBuildStatus(Result.FAILURE, parentJob.scheduleBuild2(0));

        String log;
        try (ACLContext ignored = ACL.as2(viewer)) {
            PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500, viewer, true,
                    "hidden-build-step-sub");
            PipelineLogExtractor.ExtractionResult result = extractor.extractFailedStepLog();
            log = String.join("\n", result.logLines());
            assertEquals(1, result.downstreamPermissionSkippedCount(),
                    "Expected one downstream build to be skipped due to permissions");
        }

        assertTrue(log.contains("### Downstream Job: [hidden] ###"),
                "Unreadable downstream jobs should be represented by a hidden placeholder.\nActual log:\n" + log);
        assertTrue(log.contains("Downstream failure details omitted due to permissions."),
                "Hidden placeholder should explain why downstream details are missing.\nActual log:\n" + log);
        assertFalse(log.contains("HIDDEN_BUILD_STEP_MARKER"),
                "Hidden downstream logs must not be exposed.\nActual log:\n" + log);
    }

    @Test
    void downstream_invisibleUpstreamCauseJob_skippedByVisibilityFilter(JenkinsRule jenkins) throws Exception {
        Authentication viewer = configureReadAccess(jenkins, "viewer-upstream-cause");

        FreeStyleProject parentJob = jenkins.createFreeStyleProject("visible-upstream-parent");
        grantItemRead(jenkins, "viewer-upstream-cause", parentJob);
        FreeStyleBuild parentRun = jenkins.buildAndAssertSuccess(parentJob);

        FreeStyleProject hiddenSubJob = jenkins.createFreeStyleProject("hidden-upstream-sub");
        hiddenSubJob.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild hiddenSubRun = jenkins.assertBuildStatus(Result.FAILURE,
                hiddenSubJob.scheduleBuild2(0, new Cause.UpstreamCause(parentRun)));
        assertNotNull(hiddenSubRun, "Hidden downstream build should be created");

        String log;
        try (ACLContext ignored = ACL.as2(viewer)) {
            PipelineLogExtractor extractor = new PipelineLogExtractor(parentRun, 500, viewer, true,
                    "hidden-upstream-sub");
            log = String.join("\n", extractor.getFailedStepLog());
        }

        assertFalse(log.contains("### Downstream Job:"),
                "UpstreamCause fallback should skip unreadable downstream jobs entirely.\nActual log:\n" + log);
    }

    private Authentication configureReadAccess(JenkinsRule jenkins, String username) {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        MockAuthorizationStrategy strategy = new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to(username);
        jenkins.jenkins.setAuthorizationStrategy(strategy);
        return User.getById(username, true).impersonate2();
    }

    private void grantItemRead(JenkinsRule jenkins, String username, Item item) {
        MockAuthorizationStrategy strategy = (MockAuthorizationStrategy) jenkins.jenkins.getAuthorizationStrategy();
        strategy.grant(Item.READ).onItems(item).to(username);
        jenkins.jenkins.setAuthorizationStrategy(strategy);
    }
}
