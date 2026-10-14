package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
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
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A folder turning explanation off for its jobs, or on with a provider of its own.
 */
@WithJenkins
class FolderExplanationSwitchTest {

    private JenkinsRule jenkins;
    private GlobalConfigurationImpl config;
    private FakeAIProvider globalProvider;
    private int jobs;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        jenkins.jenkins.setCrumbIssuer(null);
        config = GlobalConfigurationImpl.get();
        globalProvider = named("Global Provider");
        config.setAiProvider(globalProvider);
        config.setEnableExplanation(true);
    }

    @Test
    void aFolderCanTurnExplanationOffWhileItIsOnGlobally() throws Exception {
        FreeStyleBuild build = failingBuild(folder("quiet", turnedOff()));

        assertFalse(enabled(build));
        ExplanationException refused = assertThrows(ExplanationException.class,
                () -> new ErrorExplainer().explainErrorText("boom", "", build));
        assertEquals("AI error explanation is disabled.", refused.getMessage());
        assertEquals(0, globalProvider.getCallCount());
    }

    @Test
    void theConsolePageOffersNothingInThatFolder() throws Exception {
        FreeStyleBuild build = failingBuild(folder("quiet", turnedOff()));

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage console = client.getPage(build, "console");
            assertNull(console.getElementById("explain-error-container"), "no card and no button");

            JSONObject answer = post(client, build, "console-explain-error/explainConsoleError", List.of());
            assertEquals("warning", answer.getString("status"));
            assertEquals(ErrorExplainer.TURNED_OFF, answer.getString("message"));
        }
        assertEquals(0, globalProvider.getCallCount());
    }

    @Test
    void theStepDoesNothingInThatFolder() throws Exception {
        Folder folder = folder("quiet", turnedOff());
        WorkflowJob job = folder.createProject(WorkflowJob.class, "pipeline");
        job.setDefinition(new CpsFlowDefinition(
                "node { try { error 'boom' } catch (e) { explainError(); throw e } }", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        jenkins.assertLogContains("Explanation is disabled by configuration.", run);
        assertNull(run.getAction(ErrorExplanationAction.class));
        assertEquals(0, globalProvider.getCallCount());
    }

    @Test
    void automaticExplanationLeavesThatFolderAlone() throws Exception {
        config.setEnableAutoExplainOnFailure(true);

        FreeStyleBuild build = failingBuild(folder("quiet", turnedOff()));

        for (int i = 0; i < 10; i++) {
            assertNull(build.getAction(ErrorExplanationAction.class), "the build must not be explained");
            Thread.sleep(100);
        }
        jenkins.assertLogNotContains("[explain-error]", build);
        assertEquals(0, globalProvider.getCallCount());
    }

    @Test
    void aFolderWithItsOwnProviderGetsTheButtonWhileExplanationIsOffGlobally() throws Exception {
        config.setEnableExplanation(false);
        FakeAIProvider folderProvider = named("Folder Provider");
        FreeStyleBuild build = failingBuild(folder("own", ownProvider(folderProvider)));

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage console = client.getPage(build, "console");
            DomElement card = console.getElementById("explain-error-container");
            assertNotNull(card, "the folder's provider makes the button available");
            assertEquals("true", card.getAttribute("data-plugin-enabled"));
            assertEquals("Folder Provider", card.getAttribute("data-provider-name"));

            JSONObject answer = post(client, build, "console-explain-error/explainConsoleError", List.of());
            assertEquals("success", answer.getString("status"));
            assertEquals("Folder Provider", answer.getString("providerName"));
        }
        assertEquals(1, folderProvider.getCallCount());
        assertEquals(0, globalProvider.getCallCount());
    }

    @Test
    void theGraphViewEndpointFollowsTheFolderToo() throws Exception {
        FakeAIProvider folderProvider = named("Folder Provider");
        WorkflowRun own = failingPipeline(folder("own", ownProvider(folderProvider)));
        WorkflowRun quiet = failingPipeline(folder("quiet", turnedOff()));

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            config.setEnableExplanation(false);
            JSONObject explained = post(client, own, "graph-explain-error/explainNodeError",
                    List.of(new NameValuePair("nodeId", failedStepId(own))));
            assertEquals("success", explained.getString("status"));
            assertEquals("Folder Provider", explained.getString("providerName"));

            config.setEnableExplanation(true);
            JSONObject refused = post(client, quiet, "graph-explain-error/explainNodeError",
                    List.of(new NameValuePair("nodeId", failedStepId(quiet))));
            assertEquals("warning", refused.getString("status"));
            assertEquals(ErrorExplainer.TURNED_OFF, refused.getString("message"));
        }
        assertEquals(1, folderProvider.getCallCount());
        assertEquals(0, globalProvider.getCallCount());
    }

    @Test
    void theNearestFolderDecides() throws Exception {
        Folder off = folder("off", turnedOff());
        Folder onAgain = subfolder(off, "on-again", ownProvider(named("Team Provider")));
        Folder team = folder("team", ownProvider(named("Team Provider")));
        Folder offBelow = subfolder(team, "off-below", turnedOff());

        assertFalse(enabled(failingBuild(off)));
        assertTrue(enabled(failingBuild(onAgain)), "a provider of its own turns it on again below");
        assertTrue(enabled(failingBuild(team)));
        assertFalse(enabled(failingBuild(offBelow)), "and a subfolder can turn it off below one");
    }

    @Test
    void foldersSavedBeforeKeepFollowingTheGlobalSwitch() throws Exception {
        // What the folder form stored for a section left unticked, before the section was dropped instead
        ExplainErrorFolderProperty unused = new ExplainErrorFolderProperty();
        unused.setEnableExplanation(false);
        FreeStyleBuild build = failingBuild(folder("unused", unused));

        assertTrue(enabled(build));
        config.setEnableExplanation(false);
        assertFalse(enabled(build));
    }

    @Test
    void anUnusedSectionPassesTheParentsProviderOn() throws Exception {
        FakeAIProvider parentProvider = named("Parent Provider");
        Folder parent = folder("parent", ownProvider(parentProvider));
        // An older form saved a provider nobody chose into sections left unticked
        ExplainErrorFolderProperty unused = new ExplainErrorFolderProperty();
        unused.setEnableExplanation(false);
        unused.setAiProvider(named("Leftover Provider"));
        Folder child = subfolder(parent, "child", unused);

        assertSame(parentProvider, ExplainErrorFolderProperty.findFolderProvider(child));
        assertTrue(enabled(failingBuild(child)));
    }

    @Test
    void turningExplanationOffOnTheFolderFormIsSaved() throws Exception {
        Folder folder = jenkins.jenkins.createProject(Folder.class, "form");

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(folder, "configure");
            String html = page.getWebResponse().getContentAsString();
            assertTrue(html.contains("Set AI error explanation for this folder"));
            assertTrue(html.contains("Turn off AI error explanation for jobs in this folder"));

            HtmlForm form = page.getFormByName("config");
            form.<HtmlCheckBoxInput>getInputByName("_.enableExplanation").setChecked(true);
            form.<HtmlCheckBoxInput>getInputByName("_.disableExplanation").setChecked(true);
            jenkins.submit(form);
        }

        ExplainErrorFolderProperty saved = folder.getProperties().get(ExplainErrorFolderProperty.class);
        assertNotNull(saved, "a folder that only turns explanation off is stored");
        assertTrue(saved.isDisableExplanation());

        jenkins.configRoundtrip(folder);
        assertTrue(folder.getProperties().get(ExplainErrorFolderProperty.class).isDisableExplanation());
    }

    private Folder folder(String name, ExplainErrorFolderProperty property) throws IOException {
        Folder folder = jenkins.jenkins.createProject(Folder.class, name);
        folder.addProperty(property);
        return folder;
    }

    private static Folder subfolder(Folder parent, String name, ExplainErrorFolderProperty property)
            throws IOException {
        Folder folder = parent.createProject(Folder.class, name);
        folder.addProperty(property);
        return folder;
    }

    private static ExplainErrorFolderProperty turnedOff() {
        ExplainErrorFolderProperty property = new ExplainErrorFolderProperty();
        property.setDisableExplanation(true);
        return property;
    }

    private static ExplainErrorFolderProperty ownProvider(BaseAIProvider provider) {
        ExplainErrorFolderProperty property = new ExplainErrorFolderProperty();
        property.setAiProvider(provider);
        return property;
    }

    private static FakeAIProvider named(String name) {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setProviderName(name);
        return provider;
    }

    private FreeStyleBuild failingBuild(Folder folder) throws Exception {
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "job-" + jobs++);
        project.getBuildersList().add(new FailureBuilder());
        return jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
    }

    private WorkflowRun failingPipeline(Folder folder) throws Exception {
        WorkflowJob job = folder.createProject(WorkflowJob.class, "pipeline-" + jobs++);
        job.setDefinition(new CpsFlowDefinition("stage('B') { echo 'stage B log'; error 'boom' }", true));
        return jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
    }

    private static String failedStepId(WorkflowRun run) {
        for (FlowNode node : new FlowGraphWalker(run.getExecution())) {
            if (node instanceof StepAtomNode && node.getError() != null) {
                return node.getId();
            }
        }
        throw new AssertionError("no failed step in " + run);
    }

    private static boolean enabled(Run<?, ?> run) {
        return new ErrorExplainer().isExplanationEnabled(run);
    }

    private JSONObject post(JenkinsRule.WebClient client, Run<?, ?> run, String path, List<NameValuePair> parameters)
            throws IOException {
        WebRequest request = new WebRequest(new URL(jenkins.jenkins.getRootUrl() + run.getUrl() + path),
                HttpMethod.POST);
        request.setRequestParameters(parameters);
        return JSONObject.fromObject(client.getPage(request).getWebResponse().getContentAsString());
    }
}
