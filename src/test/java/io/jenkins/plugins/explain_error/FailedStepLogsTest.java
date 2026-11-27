package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import hudson.model.Result;
import java.nio.file.NoSuchFileException;
import java.util.List;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Several steps failing in one build: each gets its share of the lines that are sent. */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class FailedStepLogsTest {

    private static final String NOISE = "for i in $(seq 1 150); do echo \"%s noise $i\"; done; ";

    @Test
    void everyFailedParallelBranchIsSent(JenkinsRule jenkins) throws Exception {
        WorkflowRun run = build(jenkins, "parallel", "node { parallel(\n"
                + " lint: { sh '" + NOISE.formatted("lint") + "echo LINT_ERROR; exit 1' },\n"
                + " unit: { sh '" + NOISE.formatted("unit") + "echo UNIT_ERROR; exit 1' },\n"
                + " docs: { sh '" + NOISE.formatted("docs") + "echo DOCS_ERROR; exit 1' }) }");

        PipelineLogExtractor.ExtractionResult result = new PipelineLogExtractor(run, 100).extractFailedStepLog();

        String sent = String.join("\n", result.logLines());
        assertTrue(sent.contains("LINT_ERROR") && sent.contains("UNIT_ERROR") && sent.contains("DOCS_ERROR"), sent);
        assertEquals(3, result.failedStepCount());
        assertTrue(contentLines(result.logLines()) <= 100, "the lines are shared out, not added up: " + sent);
    }

    @Test
    void aBranchStoppedByFailFastComesLastAndSaysSo(JenkinsRule jenkins) throws Exception {
        WorkflowRun run = build(jenkins, "fail-fast", "node { parallel(failFast: true,\n"
                + " slow: { sh 'for i in $(seq 1 300); do echo \"slow test $i\"; done; sleep 60' },\n"
                + " lint: { sh 'sleep 5; echo REAL_CAUSE: lint failed; exit 1' }) }");

        List<String> lines = new PipelineLogExtractor(run, 100).getFailedStepLog();

        String sent = String.join("\n", lines);
        int realCause = sent.indexOf("REAL_CAUSE");
        int stopped = sent.indexOf(PipelineLogExtractor.STOPPED_BY_FAIL_FAST);
        assertTrue(realCause >= 0, sent);
        assertTrue(stopped > realCause, "the stopped branch comes after the one that failed: " + sent);
    }

    @Test
    void theFirstStepsGetTheirShareWhenTooManyFail(JenkinsRule jenkins) throws Exception {
        StringBuilder branches = new StringBuilder("node { parallel(\n");
        for (int i = 1; i <= 12; i++) {
            branches.append(" b").append(i).append(": { sh '").append(NOISE.formatted("b" + i))
                    .append("echo ERROR_").append(i).append("; exit 1' },\n");
        }
        branches.setLength(branches.length() - 2);
        WorkflowRun run = build(jenkins, "many", branches.append(") }").toString());

        PipelineLogExtractor.ExtractionResult result = new PipelineLogExtractor(run, 100).extractFailedStepLog();

        assertEquals(100 / PipelineLogExtractor.MIN_LINES_PER_STEP, result.failedStepCount());
        assertTrue(contentLines(result.logLines()) <= 100);
    }

    @Test
    void aStageExplainsEveryBranchOfItThatFailed(JenkinsRule jenkins) throws Exception {
        WorkflowRun run = build(jenkins, "stage", "node { stage('Checks') { parallel(\n"
                + " lint: { sh 'echo LINT_ERROR; exit 1' },\n"
                + " unit: { sh 'echo UNIT_ERROR; exit 1' }) } }");

        String sent = String.join("\n", new PipelineLogExtractor(run, 100).extractNodeLog(stage(run, "Checks")));

        assertTrue(sent.contains("LINT_ERROR") && sent.contains("UNIT_ERROR"), sent);
    }

    @Test
    void coloursAreTakenOutOfTheLogSent(JenkinsRule jenkins) throws Exception {
        WorkflowRun run = build(jenkins, "colours",
                "node { sh 'printf \"\\\\033[31mRED_ERROR\\\\033[0m\\\\n\"; exit 1' }");

        String sent = String.join("\n", new PipelineLogExtractor(run, 100).getFailedStepLog());

        assertTrue(sent.contains("RED_ERROR"), sent);
        assertFalse(sent.contains("\u001B"), "no escape character is sent: " + sent);
    }

    @Test
    void theErrorOfAStepWithoutALogIsSent(JenkinsRule jenkins) throws Exception {
        // As with withCredentials and an unknown credentials ID: the step fails before it writes anything, and
        // Jenkins writes its error to the console only once the build is over, after post has run
        WorkflowRun run = build(jenkins, "no-log", "node {\n"
                + " stage('Build') { sh 'echo built' }\n"
                + " stage('Publish') { unstash 'does-not-exist' } }");

        PipelineLogExtractor.ExtractionResult result = new PipelineLogExtractor(run, 100).extractFailedStepLog();

        String sent = String.join("\n", result.logLines());
        assertTrue(sent.contains(PipelineLogExtractor.ERROR_PREFIX) && sent.contains("does-not-exist"), sent);
        assertFalse(result.fallbackToBuildLog(), "the failed step is named, not the end of the console: " + sent);
        assertEquals(1, result.failedStepCount());
    }

    @Test
    void theErrorIsSentBeforeJenkinsWritesItToTheConsole(JenkinsRule jenkins) throws Exception {
        // As explainError() in post { failure }: the build is still running, and its console does not have the
        // error yet
        WorkflowRun run = build(jenkins, "post", "node {\n"
                + " try { sh 'echo built'; unstash 'does-not-exist' }\n"
                + " finally {\n"
                + "  def sent = new io.jenkins.plugins.explain_error.PipelineLogExtractor(currentBuild.rawBuild, 100)"
                + ".getFailedStepLog().join('\\n')\n"
                + "  echo(sent.contains('does-not-exist') ? 'CAUSE_SENT' : 'CAUSE_MISSING:\\n' + sent)\n"
                + " } }", false);

        jenkins.assertLogContains("CAUSE_SENT", run);
    }

    @Test
    void theErrorOfAStepWithALogIsSentWithIt(JenkinsRule jenkins) throws Exception {
        WorkflowRun run = build(jenkins, "with-log", "node { sh 'echo SH_OUTPUT; exit 3' }");

        String sent = String.join("\n", new PipelineLogExtractor(run, 100).getFailedStepLog());

        assertTrue(sent.contains("SH_OUTPUT"), sent);
        assertTrue(sent.contains(PipelineLogExtractor.ERROR_PREFIX + "script returned exit code 3"), sent);
    }

    @Test
    void aStageWhoseStepFailedWithoutALogExplainsTheError(JenkinsRule jenkins) throws Exception {
        WorkflowRun run = build(jenkins, "stage-no-log", "node { stage('Publish') { unstash 'does-not-exist' } }");

        String sent = String.join("\n", new PipelineLogExtractor(run, 100).extractNodeLog(stage(run, "Publish")));

        assertTrue(sent.contains(PipelineLogExtractor.ERROR_PREFIX) && sent.contains("does-not-exist"), sent);
    }

    @Test
    void theErrorIsOneShortLineAndOnlyWhenItSaysWhy(JenkinsRule jenkins) {
        assertEquals("Could not find credentials entry with ID 'x'", PipelineLogExtractor.errorMessage(
                new ErrorAction(new AbortException("Could not find credentials entry with ID 'x'"))));
        assertEquals("java.nio.file.NoSuchFileException: a b", PipelineLogExtractor.errorMessage(
                new ErrorAction(new NoSuchFileException("a\n   b"))));
        String longMessage = PipelineLogExtractor.errorMessage(new ErrorAction(new AbortException("x".repeat(5000))));
        assertEquals(PipelineLogExtractor.MAX_ERROR_LENGTH + 3, longMessage.length());
        assertNull(PipelineLogExtractor.errorMessage(new ErrorAction(new FlowInterruptedException(Result.ABORTED))));
        assertNull(PipelineLogExtractor.errorMessage(new ErrorAction(new AbortException(" "))));
        assertNull(PipelineLogExtractor.errorMessage(null));
    }

    private static WorkflowRun build(JenkinsRule jenkins, String name, String script) throws Exception {
        return build(jenkins, name, script, true);
    }

    private static WorkflowRun build(JenkinsRule jenkins, String name, String script, boolean sandbox)
            throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, sandbox));
        return jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
    }

    private static String stage(WorkflowRun run, String name) {
        for (FlowNode node : new FlowGraphWalker(run.getExecution())) {
            if (node.getAction(LabelAction.class) != null && name.equals(node.getDisplayName())) {
                return node.getId();
            }
        }
        throw new AssertionError("no stage " + name);
    }

    /** Lines of the steps' own output, leaving out the header and footer around each. */
    private static int contentLines(List<String> lines) {
        int count = 0;
        boolean inContent = false;
        for (String line : lines) {
            if (line.equals("--- LOG CONTENT ---")) {
                inContent = true;
            } else if (line.startsWith(PipelineLogExtractor.NODE_FOOTER_PREFIX)) {
                inContent = false;
            } else if (inContent) {
                count++;
            }
        }
        assertNotNull(lines);
        return count;
    }
}
