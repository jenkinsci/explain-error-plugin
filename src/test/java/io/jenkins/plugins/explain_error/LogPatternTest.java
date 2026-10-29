package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.net.URL;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The log pattern searches the whole log, and applies wherever an explanation is asked for. */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class LogPatternTest {

    private static final String EARLY_ERROR = "sh 'echo \"EARLY_ERROR: missing dependency\";"
            + " for i in $(seq 1 300); do echo \"info $i\"; done; exit 1'";

    private final FakeAIProvider provider = new FakeAIProvider();
    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        GlobalConfigurationImpl.get().setAiProvider(provider);
    }

    @Test
    void aMatchFarFromTheEndOfTheLogIsFound() throws Exception {
        WorkflowRun run = explain("far", "explainError(logPattern: 'ERROR')", EARLY_ERROR);

        jenkins.assertLogContains("RETURNED=true", run);
        String sent = provider.getLastErrorLogs();
        assertTrue(sent.contains("EARLY_ERROR: missing dependency"), sent);
        assertFalse(sent.contains("info 300"), "only matching lines are sent: " + sent);
        assertTrue(sent.contains(PipelineLogExtractor.NODE_HEADER_PREFIX), "the step is still named: " + sent);
    }

    @Test
    void aPatternThatMatchesNothingSendsTheLinesWithoutIt() throws Exception {
        WorkflowRun run = explain("nothing", "explainError(logPattern: 'NO_SUCH_TEXT')", EARLY_ERROR);

        jenkins.assertLogContains("RETURNED=true", run);
        jenkins.assertLogContains("The log pattern matched no line; using the lines without it.", run);
        assertTrue(provider.getLastErrorLogs().contains("info 300"), provider.getLastErrorLogs());
    }

    @Test
    void theGlobalPatternAppliesToAStepThatSetsNone() throws Exception {
        GlobalConfigurationImpl.get().setLogPattern("EARLY_ERROR");

        explain("global", "explainError()", EARLY_ERROR);

        assertTrue(provider.getLastErrorLogs().contains("EARLY_ERROR"), provider.getLastErrorLogs());
        assertFalse(provider.getLastErrorLogs().contains("info 300"), provider.getLastErrorLogs());
    }

    @Test
    void aStepPatternWinsOverTheGlobalOne() throws Exception {
        GlobalConfigurationImpl.get().setLogPattern("EARLY_ERROR");

        explain("step-wins", "explainError(logPattern: 'info 299')", EARLY_ERROR);

        // The step's header names the command, which has the text; the pattern picks log lines
        String sent = provider.getLastErrorLogs();
        String logLines = sent.substring(sent.indexOf("--- LOG CONTENT ---"));
        assertTrue(logLines.contains("info 299"), provider.getLastErrorLogs());
        assertFalse(logLines.contains("EARLY_ERROR"), provider.getLastErrorLogs());
    }

    @Test
    void anInvalidPatternIsLeftOut() throws Exception {
        WorkflowRun run = explain("invalid", "explainError(logPattern: '([')", EARLY_ERROR);

        jenkins.assertLogContains("RETURNED=true", run);
        jenkins.assertLogContains("Log pattern is not a valid regular expression, so it is not used", run);
        assertTrue(provider.getLastErrorLogs().contains("info 300"), provider.getLastErrorLogs());
    }

    @Test
    void downstreamLogsAreNotFiltered() throws Exception {
        WorkflowJob subJob = jenkins.createProject(WorkflowJob.class, "sub");
        subJob.setDefinition(new CpsFlowDefinition("node { sh 'echo \"SUB_JOB_MARKER\" && exit 1' }", true));
        WorkflowJob parent = jenkins.createProject(WorkflowJob.class, "parent");
        parent.setDefinition(new CpsFlowDefinition("build job: 'sub', propagate: false\n"
                + "echo 'PARENT_ERROR'\ncurrentBuild.result = 'FAILURE'", true));
        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, parent.scheduleBuild2(0));

        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 500, true, "sub");
        extractor.setLogPattern(PipelineLogExtractor.compileLogPattern("PARENT_ERROR"));
        String sent = String.join("\n", extractor.getFailedStepLog());

        assertTrue(sent.contains("PARENT_ERROR"), sent);
        assertFalse(sent.contains("Started by user"), "the parent's own lines are filtered: " + sent);
        assertTrue(sent.contains("SUB_JOB_MARKER"), "the downstream build's are not: " + sent);
    }

    @Test
    void theButtonOnTheConsoleUsesTheGlobalSettings() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setLogPattern("WANTED");
        config.setAutoExplainMaxLogLines(500);
        jenkins.jenkins.setCrumbIssuer(null);
        FreeStyleProject project = jenkins.createFreeStyleProject("console");
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(hudson.model.AbstractBuild<?, ?> build, hudson.Launcher launcher,
                                   hudson.model.BuildListener listener) {
                listener.getLogger().println("WANTED: the cause");
                for (int i = 1; i <= 700; i++) {
                    listener.getLogger().println("noise " + i);
                }
                return false;
            }
        });
        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            WebRequest request = new WebRequest(new URL(jenkins.getURL(), build.getUrl()
                    + "console-explain-error/explainConsoleError"), HttpMethod.POST);
            JSONObject response = JSONObject.fromObject(client.getPage(request).getWebResponse()
                    .getContentAsString());
            assertEquals("success", response.getString("status"), response.toString());
        }
        assertTrue(provider.getLastErrorLogs().contains("WANTED: the cause"), provider.getLastErrorLogs());
        assertFalse(provider.getLastErrorLogs().contains("noise 700"), provider.getLastErrorLogs());
        assertEquals(500, ConsoleExplainErrorAction.defaultMaxLines(),
                "the button reads as many lines as the global setting when that is higher");
    }

    private WorkflowRun explain(String name, String step, String failure) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("node { try { " + failure + " } catch (e) { def r = " + step
                + "; echo \"RETURNED=${r != null}\" } }", true));
        return jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }
}
