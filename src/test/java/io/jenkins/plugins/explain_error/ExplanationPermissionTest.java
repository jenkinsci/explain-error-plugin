package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Who may ask for a new explanation: one calls the AI provider and replaces the explanation the build has,
 * so it takes Job/Build, while the explanation a build already has is shown to anyone who can read it.
 */
@WithJenkins
class ExplanationPermissionTest {

    private static final String CONSOLE = "console-explain-error/explainConsoleError";
    private static final String GRAPH = "graph-explain-error/explainNodeError";

    private JenkinsRule rule;
    private final FakeAIProvider provider = new FakeAIProvider();
    private FreeStyleBuild build;

    @BeforeEach
    void setUp(JenkinsRule jenkins) throws Exception {
        rule = jenkins;
        rule.jenkins.setCrumbIssuer(null);
        GlobalConfigurationImpl.get().setAiProvider(provider);
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        rule.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("builder"));
        FreeStyleProject project = rule.createFreeStyleProject("failing");
        project.getBuildersList().add(new FailureBuilder());
        build = rule.buildAndAssertStatus(Result.FAILURE, project);
    }

    @Test
    void aReaderCannotAskForANewExplanation() throws Exception {
        Response response = post("reader", build, CONSOLE);

        assertEquals(403, response.status());
        assertEquals("error", response.json().getString("status"));
        assertTrue(response.json().getString("message").contains("Job/Build"), response.json().toString());
        assertEquals(0, provider.getCallCount());
        assertNull(build.getAction(ErrorExplanationAction.class));
    }

    @Test
    void aReaderSeesTheExplanationTheBuildHasButCannotReplaceIt() throws Exception {
        assertEquals("success", post("builder", build, CONSOLE).json().getString("status"));
        provider.setAnswerMessage("Second call");

        Response shown = post("reader", build, CONSOLE);
        assertEquals(200, shown.status());
        assertEquals("success", shown.json().getString("status"));
        assertTrue(shown.json().getString("message").contains("Request was successful"));

        Response replaced = post("reader", build, CONSOLE, new NameValuePair("forceNew", "true"));
        assertEquals(403, replaced.status());
        assertEquals(1, provider.getCallCount());
        assertEquals("Summary: Request was successful\n", build.getAction(ErrorExplanationAction.class)
                .getExplanation());
    }

    @Test
    void aUserWhoMayBuildTheJobCanAskForOne() throws Exception {
        Response response = post("builder", build, CONSOLE);

        assertEquals(200, response.status());
        assertEquals("success", response.json().getString("status"));
        assertEquals(1, provider.getCallCount());
        assertFalse(PendingExplanations.contains(build), "the request no longer counts once it is answered");
    }

    @Test
    void aRequestWhileAnExplanationIsOnItsWayDoesNotCallTheProviderAgain() throws Exception {
        PendingExplanations.add(build);
        try {
            Response response = post("builder", build, CONSOLE, new NameValuePair("forceNew", "true"));

            assertEquals("warning", response.json().getString("status"));
            assertEquals(ConsoleExplainErrorAction.ALREADY_PENDING, response.json().getString("message"));
            assertEquals(0, provider.getCallCount());
            assertTrue(PendingExplanations.contains(build), "the pending explanation is left alone");
        } finally {
            PendingExplanations.remove(build);
        }
    }

    @Test
    void aBuildIsMarkedPendingOnlyOnce() throws Exception {
        assertTrue(PendingExplanations.tryAdd(build));
        try {
            assertFalse(PendingExplanations.tryAdd(build));
        } finally {
            PendingExplanations.remove(build);
        }
        PendingExplanations.add(build, System.currentTimeMillis() - PendingExplanations.MAX_AGE_MILLIS - 1);
        try {
            assertTrue(PendingExplanations.tryAdd(build), "a request that never came back stops counting");
        } finally {
            PendingExplanations.remove(build);
        }
    }

    @Test
    void theNumberOfLogLinesARequestMayAskForIsLimited() {
        assertEquals(ConsoleExplainErrorAction.DEFAULT_MAX_LINES, ConsoleExplainErrorAction.maxLines(null));
        assertEquals(ConsoleExplainErrorAction.DEFAULT_MAX_LINES, ConsoleExplainErrorAction.maxLines("lots"));
        assertEquals(50, ConsoleExplainErrorAction.maxLines("50"));
        assertEquals(1, ConsoleExplainErrorAction.maxLines("0"));
        assertEquals(ConsoleExplainErrorAction.MAX_LINES_LIMIT, ConsoleExplainErrorAction.maxLines("100000000"));
    }

    @Test
    void theStagesPageAppliesTheSameRules() throws Exception {
        WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "pipeline");
        job.setDefinition(new CpsFlowDefinition(
                "stage('A') { echo 'a' }\nstage('B') { echo 'stage B log'; error 'boom' }", true));
        WorkflowRun run = rule.buildAndAssertStatus(Result.FAILURE, job);
        NameValuePair node = new NameValuePair("nodeId", failedStep(run).getId());

        Response refused = post("reader", run, GRAPH, node);
        assertEquals(403, refused.status());
        assertEquals(0, provider.getCallCount());

        PendingExplanations.add(run);
        try {
            assertEquals("warning", post("builder", run, GRAPH, node).json().getString("status"));
            assertEquals(0, provider.getCallCount());
        } finally {
            PendingExplanations.remove(run);
        }

        assertEquals("success", post("builder", run, GRAPH, node).json().getString("status"));
        assertEquals(1, provider.getCallCount());
        assertEquals("success", post("reader", run, GRAPH, node).json().getString("status"),
                "the explanation the build has is shown to a reader");
        assertEquals(403, post("reader", run, GRAPH, node, new NameValuePair("forceNew", "true")).status());
        assertEquals(1, provider.getCallCount());
    }

    @Test
    void theConsolePageOffersTheButtonOnlyToUsersWhoMayUseIt() throws Exception {
        Element readerCard = card("reader");
        assertEquals("false", readerCard.attr("data-plugin-enabled"),
                "a reader gets no Explain Error button for a build without an explanation");
        assertEquals("true", readerCard.attr("data-agent-context"),
                "but can copy the failure for a coding agent, which calls no provider");
        assertEquals("true", card("builder").attr("data-plugin-enabled"));

        post("builder", build, CONSOLE);

        Element card = card("reader");
        assertNotNull(card, "a reader is shown the explanation the build has");
        assertEquals("false", card.attr("data-plugin-enabled"), "but is not offered a new one");
        assertEquals("true", card.attr("data-has-explanation"));
    }

    private Element card(String user) throws Exception {
        try (JenkinsRule.WebClient client = rule.createWebClient().login(user)) {
            String page = client.goTo(build.getUrl() + "console").getWebResponse().getContentAsString();
            return Jsoup.parse(page).getElementById("explain-error-container");
        }
    }

    private Response post(String user, Run<?, ?> run, String endpoint, NameValuePair... parameters)
            throws Exception {
        try (JenkinsRule.WebClient client = rule.createWebClient().login(user)) {
            client.setThrowExceptionOnFailingStatusCode(false);
            WebRequest request = new WebRequest(new URL(rule.getURL(), run.getUrl() + endpoint), HttpMethod.POST);
            request.setRequestParameters(new ArrayList<>(List.of(parameters)));
            WebResponse response = client.getPage(request).getWebResponse();
            return new Response(response.getStatusCode(), JSONObject.fromObject(response.getContentAsString()));
        }
    }

    private static FlowNode failedStep(WorkflowRun run) {
        for (FlowNode node : new FlowGraphWalker(run.getExecution())) {
            if (node instanceof StepAtomNode && node.getError() != null) {
                return node;
            }
        }
        throw new AssertionError("no failed step in " + run);
    }

    private record Response(int status, JSONObject json) {
    }
}
