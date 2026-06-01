package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.UnstableBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ConsoleExplainErrorActionTest {

    private ConsoleExplainErrorAction action;
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
        action = new ConsoleExplainErrorAction(build);
    }

    @Test
    void testBasicFunctionality() {
        assertNotNull(action);
        assertEquals(build, action.getRun());
        assertNull(action.getIconFileName()); // Should be null for AJAX functionality
        assertNull(action.getDisplayName()); // Should be null for AJAX functionality
        assertEquals("console-explain-error", action.getUrlName());
    }

    @Test
    void testGetRun() {
        assertEquals(build, action.getRun());
    }

    @Test
    void testExplainConsoleError() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            ErrorExplanationAction action = build.getAction(ErrorExplanationAction.class);
            assertNull(action);
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/explainConsoleError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            client.getPage(request);
            action = build.getAction(ErrorExplanationAction.class);
            assertNotNull(action);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
        }
    }

    @Test
    void testExplainConsoleErrorProviderFailureReturnsJson() throws IOException {
        provider.setThrowError(true);
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/explainConsoleError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);

            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);

            assertEquals("error", responseJson.getString("status"));
            assertEquals("Test", responseJson.getString("providerName"));
            assertTrue(responseJson.getString("message").contains("API request failed: Request failed."));
        }
    }

    @Test
    void testExplainConsoleErrorSecondCall() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/explainConsoleError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            client.getPage(request);
            ErrorExplanationAction action = build.getAction(ErrorExplanationAction.class);
            assertNotNull(action);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
            provider.setAnswerMessage("Second call");
            client.getPage(request);
            assertEquals(1, provider.getCallCount());
            action = build.getAction(ErrorExplanationAction.class);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
        }
    }
    @Test
    void testExplainConsoleErrorSecondCallForceNew() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/explainConsoleError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            client.getPage(request);
            ErrorExplanationAction action = build.getAction(ErrorExplanationAction.class);
            assertNotNull(action);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
            provider.setAnswerMessage("Second call");
            request.setRequestParameters(java.util.Collections.singletonList(
                new org.htmlunit.util.NameValuePair("forceNew", "true")
            ));
            client.getPage(request);
            assertEquals(2, provider.getCallCount());
            action = build.getAction(ErrorExplanationAction.class);
            assertEquals("Summary: Second call\n", action.getExplanation());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testExplainNodeErrorCachesByNodeAndDoesNotCreateBuildExplanation() throws Exception {
        WorkflowJob workflowJob = rule.createProject(WorkflowJob.class, "graph-node-explain");
        workflowJob.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"GRAPH_NODE_ENDPOINT_A\" && exit 1'\n"
                + "    }\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"GRAPH_NODE_ENDPOINT_B\" && exit 1'\n"
                + "    }\n"
                + "}",
                true));
        WorkflowRun workflowRun = rule.assertBuildStatus(hudson.model.Result.FAILURE,
                workflowJob.scheduleBuild2(0));
        String nodeId = findNodeIdWithLog(workflowRun, "GRAPH_NODE_ENDPOINT_B");

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + workflowRun.getUrl()
                    + "console-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            request.setRequestParameters(java.util.List.of(
                    new org.htmlunit.util.NameValuePair("nodeId", nodeId)
            ));
            Page page = client.getPage(request);
            JSONObject responseJson = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("success", responseJson.getString("status"));

            StepErrorExplanationAction stepAction = workflowRun.getAction(StepErrorExplanationAction.class);
            assertNotNull(stepAction);
            assertTrue(stepAction.hasExplanation(nodeId));
            assertNull(workflowRun.getAction(ErrorExplanationAction.class),
                    "Step explanation must not overwrite the build-level explanation action");
            assertEquals(1, provider.getCallCount());
            assertTrue(provider.getLastErrorLogs().contains("GRAPH_NODE_ENDPOINT_B"));
            assertFalse(provider.getLastErrorLogs().contains("GRAPH_NODE_ENDPOINT_A"));

            provider.setAnswerMessage("Second graph call");
            page = client.getPage(request);
            responseJson = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("success", responseJson.getString("status"));
            assertEquals(1, provider.getCallCount(), "Second call should use the node cache");

            request.setRequestParameters(java.util.List.of(
                    new org.htmlunit.util.NameValuePair("nodeId", nodeId),
                    new org.htmlunit.util.NameValuePair("forceNew", "true")
            ));
            client.getPage(request);
            assertEquals(2, provider.getCallCount(), "forceNew should bypass the node cache");
            assertEquals("Summary: Second graph call\n",
                    workflowRun.getAction(StepErrorExplanationAction.class)
                            .getExplanation(nodeId)
                            .getExplanation());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testExplainNodeErrorUsesSelectedParentNodeDescendantLog() throws Exception {
        WorkflowJob workflowJob = rule.createProject(WorkflowJob.class, "graph-parent-node-explain");
        workflowJob.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"GRAPH_PARENT_ENDPOINT_A\" && exit 1'\n"
                + "    }\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"GRAPH_PARENT_ENDPOINT_B\" && exit 1'\n"
                + "    }\n"
                + "}",
                true));
        WorkflowRun workflowRun = rule.assertBuildStatus(Result.FAILURE, workflowJob.scheduleBuild2(0));
        String parentNodeId = findNearestParentNodeIdWithoutLog(workflowRun, "GRAPH_PARENT_ENDPOINT_B");

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + workflowRun.getUrl()
                    + "console-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            request.setRequestParameters(java.util.List.of(
                    new org.htmlunit.util.NameValuePair("nodeId", parentNodeId)
            ));
            Page page = client.getPage(request);
            JSONObject responseJson = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("success", responseJson.getString("status"));

            StepErrorExplanationAction stepAction = workflowRun.getAction(StepErrorExplanationAction.class);
            assertNotNull(stepAction);
            assertTrue(stepAction.hasExplanation(parentNodeId));
            assertEquals(1, provider.getCallCount());
            assertTrue(provider.getLastErrorLogs().contains("GRAPH_PARENT_ENDPOINT_B"));
            assertFalse(provider.getLastErrorLogs().contains("GRAPH_PARENT_ENDPOINT_A"));
        }
    }

    @Test
    void testExplainNodeErrorRequiresNodeId() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl()
                    + "console-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            JSONObject responseJson = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("warning", responseJson.getString("status"));
            assertTrue(responseJson.getString("message").contains("No Pipeline node"));
            assertEquals(0, provider.getCallCount());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testGetNodeExplanationReturnsCached() throws Exception {
        WorkflowJob workflowJob = rule.createProject(WorkflowJob.class, "graph-get-node-explain");
        workflowJob.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {\n"
                + "        sh 'echo \"GET_NODE_CACHE_A\" && exit 1'\n"
                + "    }\n"
                + "}",
                true));
        WorkflowRun workflowRun = rule.assertBuildStatus(Result.FAILURE, workflowJob.scheduleBuild2(0));
        String nodeId = findNodeIdWithLog(workflowRun, "GET_NODE_CACHE_A");

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            // First, no cache should exist.
            URL url = new URL(rule.jenkins.getRootUrl() + workflowRun.getUrl()
                    + "console-explain-error/getNodeExplanation");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            request.setRequestParameters(java.util.List.of(
                    new org.htmlunit.util.NameValuePair("nodeId", nodeId)
            ));
            Page page = client.getPage(request);
            JSONObject json = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("no_cache", json.getString("status"));

            // Generate an explanation.
            URL explainUrl = new URL(rule.jenkins.getRootUrl() + workflowRun.getUrl()
                    + "console-explain-error/explainNodeError");
            WebRequest explainRequest = new WebRequest(explainUrl, HttpMethod.POST);
            explainRequest.setRequestParameters(java.util.List.of(
                    new org.htmlunit.util.NameValuePair("nodeId", nodeId)
            ));
            client.getPage(explainRequest);

            // Now the cache should return the explanation.
            page = client.getPage(request);
            json = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("success", json.getString("status"));
            assertTrue(json.getString("message").contains("Summary:"));
        }
    }

    @Test
    void testGetNodeExplanationMissingNodeId() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl()
                    + "console-explain-error/getNodeExplanation");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            JSONObject json = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("warning", json.getString("status"));
        }
    }

    @Test
    void testCheckBuildStatus() throws Exception {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/checkBuildStatus");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals(0, responseJson.getInt("buildingStatus"));

            // Test build is running
            project.getBuildersList().add(new SleepBuilder(2000));
            build = project.scheduleBuild2(0).waitForStart();
            url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/checkBuildStatus");
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            responseJson = JSONObject.fromObject(content);
            assertEquals(1, responseJson.getInt("buildingStatus"));

            // Test build failed
            project.getBuildersList().clear();
            project.getBuildersList().add(new FailureBuilder());
            build = project.scheduleBuild2(0).get();
            url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/checkBuildStatus");
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            responseJson = JSONObject.fromObject(content);
            assertEquals(2, responseJson.getInt("buildingStatus"));

            project.getBuildersList().clear();
            project.getBuildersList().add(new UnstableBuilder());
            build = project.scheduleBuild2(0).get();
            url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/checkBuildStatus");
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            responseJson = JSONObject.fromObject(content);
            assertEquals(0, responseJson.getInt("buildingStatus"));

            WorkflowJob abortedJob = rule.createProject(WorkflowJob.class, "aborted-status");
            abortedJob.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "    currentBuild.result = 'ABORTED'\n"
                    + "    echo 'aborted status marker'\n"
                    + "}",
                    true));
            WorkflowRun abortedRun = rule.assertBuildStatus(Result.ABORTED, abortedJob.scheduleBuild2(0));
            url = new URL(rule.jenkins.getRootUrl() + abortedRun.getUrl() + "console-explain-error/checkBuildStatus");
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            responseJson = JSONObject.fromObject(content);
            assertEquals(0, responseJson.getInt("buildingStatus"));
        }
    }

    private String findNodeIdWithLog(WorkflowRun run, String marker) throws Exception {
        FlowExecution execution = run.getExecution();
        assertNotNull(execution, "Pipeline execution should be available");
        FlowGraphWalker walker = new FlowGraphWalker(execution);
        for (FlowNode node : walker) {
            LogAction logAction = node.getAction(LogAction.class);
            if (logAction == null) {
                continue;
            }
            StringWriter writer = new StringWriter();
            logAction.getLogText().writeLogTo(0, writer);
            if (writer.toString().contains(marker)) {
                return node.getId();
            }
        }
        throw new AssertionError("No FlowNode log contained marker: " + marker);
    }

    private String findNearestParentNodeIdWithoutLog(WorkflowRun run, String marker) throws Exception {
        FlowExecution execution = run.getExecution();
        assertNotNull(execution, "Pipeline execution should be available");
        FlowNode logNode = execution.getNode(findNodeIdWithLog(run, marker));
        assertNotNull(logNode, "Log node should be available");
        for (FlowNode parent : logNode.getParents()) {
            if (parent.getAction(LogAction.class) == null) {
                return parent.getId();
            }
        }
        throw new AssertionError("No parent FlowNode without log found for marker: " + marker);
    }
}
