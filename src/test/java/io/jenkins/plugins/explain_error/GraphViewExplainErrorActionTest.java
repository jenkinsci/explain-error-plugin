package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
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
    private final FakeAIProvider provider = new FakeAIProvider();
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

    @Test
    void testIsFailedNodeMultiStagePipeline() throws Exception {
        WorkflowRun pipelineRun = buildMinimalMultiStagePipeline("multi-stage");
        GraphViewExplainErrorAction pipelineAction = new GraphViewExplainErrorAction(pipelineRun);

        FlowNode stageA = findStageNode(pipelineRun, "A");
        FlowNode stageB = findStageNode(pipelineRun, "B");
        FlowNode failedStep = findStepNode(pipelineRun, true);
        FlowNode successfulStep = findStepNode(pipelineRun, false);

        assertTrue(pipelineAction.isFailedNode(stageB.getId()),
                "failed stage must be reported as failed");
        assertTrue(pipelineAction.isFailedNode(failedStep.getId()),
                "failing step must be reported as failed");
        assertFalse(pipelineAction.isFailedNode(stageA.getId()),
                "successful stage that ran before the failure must not be reported as failed");
        assertFalse(pipelineAction.isFailedNode(successfulStep.getId()),
                "successful step that ran before the failure must not be reported as failed");
    }

    @Test
    void testExtractNodeLogFromStageBlockNode() throws Exception {
        WorkflowRun pipelineRun = buildMultiStagePipeline("multi-stage-log");
        PipelineLogExtractor extractor = new PipelineLogExtractor(pipelineRun, 200);

        List<String> failedStageLog = extractor.extractNodeLog(findStageNode(pipelineRun, "B").getId());
        assertFalse(failedStageLog.isEmpty(),
                "failed stage must yield the enclosed failing step's log");
        assertTrue(failedStageLog.stream().anyMatch(
                        line -> line.contains("boom") || line.contains("stage B log")),
                "stage log must contain output from inside the failed stage, got: " + failedStageLog);
        assertFalse(failedStageLog.stream().anyMatch(line -> line.contains("hello from A")),
                "stage log must not leak output from a preceding stage");

        List<String> successfulStageLog = extractor.extractNodeLog(findStageNode(pipelineRun, "A").getId());
        assertTrue(successfulStageLog.isEmpty(),
                "successful stage has no failing content and must not fall back to a predecessor's log");
    }

    @Test
    void testExplainNodeErrorOnStageNode() throws Exception {
        WorkflowRun pipelineRun = buildMultiStagePipeline("multi-stage-explain");
        String stageBId = findStageNode(pipelineRun, "B").getId();

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + pipelineRun.getUrl() + "graph-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            request.setRequestParameters(java.util.Collections.singletonList(
                new org.htmlunit.util.NameValuePair("nodeId", stageBId)
            ));
            Page page = client.getPage(request);
            JSONObject responseJson = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("success", responseJson.getString("status"));
            String sentLogs = provider.getLastErrorLogs();
            assertTrue(sentLogs.contains("boom") || sentLogs.contains("stage B log"),
                    "provider must receive log output from inside the failed stage, got: " + sentLogs);
            assertFalse(sentLogs.contains("hello from A"),
                    "provider must not receive output leaked from a preceding stage");
        }
    }

    @Test
    void testExplainNodeErrorCachedResponseIncludesUrl() throws Exception {
        WorkflowRun pipelineRun = buildMultiStagePipeline("multi-stage-cache-url");
        String failedStepId = findStepNode(pipelineRun, true).getId();

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + pipelineRun.getUrl() + "graph-explain-error/explainNodeError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            request.setRequestParameters(java.util.Collections.singletonList(
                new org.htmlunit.util.NameValuePair("nodeId", failedStepId)
            ));

            // First call generates and stores the explanation
            client.getPage(request);
            ErrorExplanationAction explanationAction =
                    pipelineRun.getAction(ErrorExplanationAction.class);
            assertNotNull(explanationAction);
            assertNotNull(explanationAction.getUrlString());

            // Second call is a cache hit and must still carry the stored URL
            Page page = client.getPage(request);
            JSONObject responseJson = JSONObject.fromObject(page.getWebResponse().getContentAsString());
            assertEquals("success", responseJson.getString("status"));
            assertTrue(responseJson.getString("message").contains("previously generated"));
            assertEquals(explanationAction.getUrlString(), responseJson.getString("url"),
                    "cached response must carry the stored explanation URL");
        }
    }

    @Test
    void testExplainNodeErrorCacheIsScopedToSelectedNode() throws Exception {
        // Two parallel branches, each independently failing with distinct log content.
        WorkflowRun pipelineRun = buildPipeline("multi-failed-node",
                "node {\n"
                + "  parallel(\n"
                + "    a: { echo 'log from branch A'; error 'boom A' },\n"
                + "    b: { echo 'log from branch B'; error 'boom B' }\n"
                + "  )\n"
                + "}");

        String nodeAId = null;
        String nodeBId = null;
        for (FlowNode node : new FlowGraphWalker(pipelineRun.getExecution())) {
            if (node.getError() == null) {
                continue;
            }
            List<String> parentLog = new PipelineLogExtractor(pipelineRun, 200)
                    .extractNodeLog(node.getId());
            String joined = String.join("\n", parentLog);
            if (joined.contains("branch A")) {
                nodeAId = node.getId();
            } else if (joined.contains("branch B")) {
                nodeBId = node.getId();
            }
        }
        assertNotNull(nodeAId, "expected to find branch A's failing node");
        assertNotNull(nodeBId, "expected to find branch B's failing node");

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl()
                    + pipelineRun.getUrl() + "graph-explain-error/explainNodeError");

            // Explain branch A first.
            provider.setAnswerMessage("Explanation for branch A");
            WebRequest requestA = new WebRequest(url, HttpMethod.POST);
            requestA.setRequestParameters(java.util.Collections.singletonList(
                    new org.htmlunit.util.NameValuePair("nodeId", nodeAId)));
            Page pageA = client.getPage(requestA);
            JSONObject jsonA = JSONObject.fromObject(pageA.getWebResponse().getContentAsString());
            assertTrue(jsonA.getString("message").contains("Explanation for branch A"));

            // Now select branch B's failing node and ask for an explanation (no forceNew,
            // simulating the user simply clicking "Explain Error" on the newly selected node).
            provider.setAnswerMessage("Explanation for branch B");
            WebRequest requestB = new WebRequest(url, HttpMethod.POST);
            requestB.setRequestParameters(java.util.Collections.singletonList(
                    new org.htmlunit.util.NameValuePair("nodeId", nodeBId)));
            Page pageB = client.getPage(requestB);
            JSONObject jsonB = JSONObject.fromObject(pageB.getWebResponse().getContentAsString());

            assertTrue(jsonB.getString("message").contains("Explanation for branch B"),
                    "explaining a different failed node must not return the previous node's "
                    + "cached explanation, got: " + jsonB.getString("message"));
        }
    }

    /**
     * Two-stage pipeline where the second stage fails. Stage B contains an
     * {@code echo} before the {@code error} step so that log extraction has
     * in-stage output to fall back to when the {@code error} step node itself
     * carries no log.
     */
    private WorkflowRun buildMultiStagePipeline(String name) throws Exception {
        return buildPipeline(name,
                "stage('A') { echo 'hello from A' }\n"
                + "stage('B') { echo 'stage B log'; error 'boom' }");
    }

    /**
     * Minimal two-stage pipeline: the only successful step is stage A's
     * {@code echo}, so {@link #findStepNode} can unambiguously locate a
     * successful step that ran before the failure.
     */
    private WorkflowRun buildMinimalMultiStagePipeline(String name) throws Exception {
        return buildPipeline(name,
                "stage('A') { echo 'hello from A' }\n"
                + "stage('B') { error 'boom' }");
    }

    private WorkflowRun buildPipeline(String name, String script) throws Exception {
        WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun pipelineRun = job.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.FAILURE, pipelineRun);
        return pipelineRun;
    }

    private static FlowNode findStageNode(WorkflowRun pipelineRun, String stageName) {
        for (FlowNode node : new FlowGraphWalker(pipelineRun.getExecution())) {
            if (node.getAction(LabelAction.class) != null && stageName.equals(node.getDisplayName())) {
                return node;
            }
        }
        throw new AssertionError("Stage node not found: " + stageName);
    }

    private static FlowNode findStepNode(WorkflowRun pipelineRun, boolean failed) {
        for (FlowNode node : new FlowGraphWalker(pipelineRun.getExecution())) {
            if (node instanceof StepAtomNode && (node.getError() != null) == failed) {
                return node;
            }
        }
        throw new AssertionError("Step node not found (failed=" + failed + ")");
    }
}
