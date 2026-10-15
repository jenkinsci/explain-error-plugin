package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Action;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.net.URL;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Where "View failure output" under an explanation leads. Pipeline Graph View is installed in these tests.
 */
@WithJenkins
class FailureOutputLinkTest {

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        jenkins.jenkins.setCrumbIssuer(null);
        GlobalConfigurationImpl.get().setAiProvider(new FakeAIProvider());
    }

    @Test
    void aFreestyleBuildLinksToItsConsole() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("freestyle");
        project.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild build = jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));

        String url = linkFor(build);

        assertEquals(jenkins.jenkins.getRootUrl() + build.getUrl() + "console", url);
        assertPageExists(url);
    }

    @Test
    void aPipelineLinksToTheFailingStepOnItsGraphViewPage() throws Exception {
        WorkflowRun run = pipeline("pipeline",
                "node { stage('Load') { sh 'echo \"connection refused\" && exit 1' } }");

        String url = linkFor(run);

        String prefix = jenkins.jenkins.getRootUrl() + run.getUrl() + graphViewPage(run) + "?selected-node=";
        assertTrue(url.startsWith(prefix), "the page the installed Graph View has, whatever it is called: " + url);
        String node = url.substring(prefix.length());
        assertTrue(run.getExecution().getNode(node) != null, "a node of the build: " + node);
        assertPageExists(url);
    }

    @Test
    void aPipelineWhoseFailingStepHasNoOutputLinksToItsConsole() throws Exception {
        // error() leaves no log of its own, so the whole build log is explained
        WorkflowRun run = pipeline("no-step-output", "stage('Load') { echo 'connecting'; error 'boom' }");

        String url = linkFor(run);

        assertEquals(jenkins.jenkins.getRootUrl() + run.getUrl() + "console", url);
        assertPageExists(url);
    }

    @Test
    void theExplanationKeepsThatLink() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("explained");
        project.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild build = jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            WebRequest request = new WebRequest(new URL(jenkins.jenkins.getRootUrl() + build.getUrl()
                    + "console-explain-error/explainConsoleError"), HttpMethod.POST);
            JSONObject answer = JSONObject.fromObject(client.getPage(request).getWebResponse().getContentAsString());
            assertEquals("success", answer.getString("status"));
            assertTrue(answer.getString("url").endsWith(build.getUrl() + "console"), answer.getString("url"));
        }
        assertTrue(build.getAction(ErrorExplanationAction.class).getUrlString().endsWith(build.getUrl() + "console"));
    }

    private WorkflowRun pipeline(String name, String script) throws Exception {
        WorkflowJob job = jenkins.jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
    }

    private static String linkFor(Run<?, ?> run) throws Exception {
        PipelineLogExtractor extractor = new PipelineLogExtractor(run, 100);
        extractor.getFailedStepLog();
        return extractor.getUrl();
    }

    private static String graphViewPage(Run<?, ?> run) {
        for (Action action : run.getAllActions()) {
            if (action.getClass().getName().endsWith(".PipelineConsoleViewAction")) {
                return action.getUrlName();
            }
        }
        throw new AssertionError("Pipeline Graph View adds no page to " + run);
    }

    /** Follows the link the way a browser would, without running the page's scripts. */
    private void assertPageExists(String url) throws Exception {
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(false);
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            Page page = client.getPage(url);
            assertEquals(200, page.getWebResponse().getStatusCode(), url);
        }
    }
}
