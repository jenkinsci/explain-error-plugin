package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import java.util.List;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
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

    private static WorkflowRun build(JenkinsRule jenkins, String name, String script) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
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
