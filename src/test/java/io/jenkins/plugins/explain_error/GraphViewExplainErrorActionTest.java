package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import java.io.IOException;
import java.net.URL;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GraphViewExplainErrorActionTest {

    private GraphViewExplainErrorAction action;
    private FreeStyleBuild build;
    private JenkinsRule rule;
    private final TestProvider provider = new TestProvider();
    FreeStyleProject project;

    @BeforeEach
    void setUp(JenkinsRule jenkins) throws Exception {
        this.rule = jenkins;
        rule.jenkins.setCrumbIssuer(null);
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(provider);
        project = jenkins.createFreeStyleProject("test");
        build = jenkins.buildAndAssertSuccess(project);
        action = new GraphViewExplainErrorAction(build);
    }

    @Test
    void testBasicFunctionality() {
        assertNotNull(action);
        assertEquals(build, action.getRun());
        assertNull(action.getIconFileName());
        assertNull(action.getDisplayName());
        assertEquals("graph-explain-error", action.getUrlName());
    }

    @Test
    void testCreateCachedResponse() {
        String originalExplanation = "This is the original AI explanation.";
        String cachedResponse = action.createCachedResponse(originalExplanation);

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains(originalExplanation));
        assertTrue(cachedResponse.contains("previously generated explanation"));
        assertTrue(cachedResponse.contains("Generate New"));
    }

    @Test
    void testIsFailedNodeNullNodeId() {
        assertFalse(action.isFailedNode(null));
    }

    @Test
    void testIsFailedNodeBlankNodeId() {
        assertFalse(action.isFailedNode(""));
        assertFalse(action.isFailedNode("   "));
    }

    @Test
    void testIsFailedNodeNonWorkflowRun() {
        assertFalse(action.isFailedNode("123"));
    }

    @Test
    void testCheckBuildStatus() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + build.getUrl() + "graph-explain-error/checkBuildStatus");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals(0, responseJson.getInt("buildingStatus"));
        }
    }

    @Test
    void testCheckBuildStatusRunning() throws Exception {
        project.getBuildersList().add(new SleepBuilder(2000));
        FreeStyleBuild runningBuild = project.scheduleBuild2(0).waitForStart();
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + runningBuild.getUrl() + "graph-explain-error/checkBuildStatus");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals(1, responseJson.getInt("buildingStatus"));
        }
    }

    @Test
    void testCheckBuildStatusFailed() throws Exception {
        project.getBuildersList().clear();
        project.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild failedBuild = project.scheduleBuild2(0).get();
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + failedBuild.getUrl() + "graph-explain-error/checkBuildStatus");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals(2, responseJson.getInt("buildingStatus"));
        }
    }

    @Test
    void testExplainNodeErrorNoNodeId() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + build.getUrl() + "graph-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals("error", responseJson.getString("status"));
            assertTrue(responseJson.getString("message").contains("No node selected"));
        }
    }

    @Test
    void testExplainNodeErrorNonExistentNode() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + build.getUrl() + "graph-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            request.setRequestParameters(java.util.Collections.singletonList(
                new org.htmlunit.util.NameValuePair("nodeId", "99999")
            ));
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals("error", responseJson.getString("status"));
            assertTrue(responseJson.getString("message").contains("No log output found"));
        }
    }

    @Test
    void testExplainNodeErrorSuccess() throws Exception {
        // Use a Pipeline (WorkflowRun) to get real flow graph nodes
        org.jenkinsci.plugins.workflow.job.WorkflowJob job =
                rule.jenkins.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob.class, "pipeline-test");
        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(
            "node { sh 'echo hello' }", true));
        org.jenkinsci.plugins.workflow.job.WorkflowRun pipelineRun =
                rule.buildAndAssertSuccess(job);

        GraphViewExplainErrorAction pipelineAction = new GraphViewExplainErrorAction(pipelineRun);

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + pipelineRun.getUrl() + "graph-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            // Node ID "3" is typically the shell step in this simple pipeline
            request.setRequestParameters(java.util.Collections.singletonList(
                new org.htmlunit.util.NameValuePair("nodeId", "3")
            ));
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals("success", responseJson.getString("status"));
            assertTrue(responseJson.getString("message").contains("Request was successful"));
        }
    }

    @Test
    void testExplainNodeErrorCacheHit() throws Exception {
        org.jenkinsci.plugins.workflow.job.WorkflowJob job =
                rule.jenkins.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob.class, "pipeline-cache");
        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(
            "node { sh 'echo hello' }", true));
        org.jenkinsci.plugins.workflow.job.WorkflowRun pipelineRun =
                rule.buildAndAssertSuccess(job);

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + pipelineRun.getUrl() + "graph-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            request.setRequestParameters(java.util.Collections.singletonList(
                new org.htmlunit.util.NameValuePair("nodeId", "3")
            ));

            // First call — generates explanation
            client.getPage(request);
            ErrorExplanationAction explanationAction =
                    pipelineRun.getAction(ErrorExplanationAction.class);
            assertNotNull(explanationAction);
            assertEquals("Summary: Request was successful\n", explanationAction.getExplanation());

            // Second call — should hit cache
            provider.setAnswerMessage("Second call");
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals("success", responseJson.getString("status"));
            // Should still be the original message (cached)
            assertTrue(responseJson.getString("message").contains("Request was successful"));
            // Should not have made a second provider call
            assertEquals(1, provider.getCallCount());
        }
    }

    @Test
    void testExplainNodeErrorForceNew() throws Exception {
        org.jenkinsci.plugins.workflow.job.WorkflowJob job =
                rule.jenkins.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob.class, "pipeline-force");
        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(
            "node { sh 'echo hello' }", true));
        org.jenkinsci.plugins.workflow.job.WorkflowRun pipelineRun =
                rule.buildAndAssertSuccess(job);

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + pipelineRun.getUrl() + "graph-explain-error/explainNodeError");

            // First call
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            request.setRequestParameters(java.util.Collections.singletonList(
                new org.htmlunit.util.NameValuePair("nodeId", "3")
            ));
            client.getPage(request);

            // Second call with forceNew
            provider.setAnswerMessage("Second call");
            request.setRequestParameters(java.util.Arrays.asList(
                new org.htmlunit.util.NameValuePair("nodeId", "3"),
                new org.htmlunit.util.NameValuePair("forceNew", "true")
            ));
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals("success", responseJson.getString("status"));
            assertTrue(responseJson.getString("message").contains("Second call"));
            assertEquals(2, provider.getCallCount());
        }
    }
}
