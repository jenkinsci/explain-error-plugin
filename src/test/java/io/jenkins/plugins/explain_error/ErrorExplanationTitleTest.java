package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleBuild;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.io.StringWriter;
import net.sf.json.JSONObject;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;

/**
 * The title of an explanation: stored on the build, exported, and used as the heading.
 */
@WithJenkins
class ErrorExplanationTitleTest {

    private ErrorExplanationAction action;

    @BeforeEach
    void setUp() {
        action = new ErrorExplanationAction("plain text", "", "ERROR: Build failed", "Ollama");
    }

    @Test
    void titleIsStoredTidiedAndHeadsTheExplanation() {
        action.setStructuredData(new JenkinsLogAnalysis(
                "summary", null, null, null, " \"Lock file is out of sync.\" "));

        assertEquals("Lock file is out of sync", action.getTitle());
        assertEquals("Lock file is out of sync (Ollama)", action.getHeading());
    }

    @Test
    void headingFallsBackToTheActionNameWithoutATitle() {
        action.setStructuredData(new JenkinsLogAnalysis("summary", null, null, null));

        assertNull(action.getTitle());
        assertEquals("AI Error Explanation (Ollama)", action.getHeading());
    }

    @Test
    void titleIsExposedViaRestApi() throws Exception {
        action.setStructuredData(new JenkinsLogAnalysis("summary", null, null, null, "Lock file is out of sync"));

        StringWriter writer = new StringWriter();
        Model<ErrorExplanationAction> model = new ModelBuilder().get(ErrorExplanationAction.class);
        model.writeTo(action, Flavor.JSON.createDataWriter(action, writer));

        assertEquals("Lock file is out of sync", JSONObject.fromObject(writer.toString()).getString("title"));
    }

    @Test
    void pageAndConsoleCardAreHeadedByTheTitle(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new FakeAIProvider());
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
        action.setStructuredData(new JenkinsLogAnalysis("summary", null, null, null, "Lock file is out of sync"));
        build.addAction(action);
        build.save();
        JenkinsRule.WebClient client = jenkins.createWebClient();

        HtmlPage page = client.getPage(build, "error-explanation");
        assertEquals("Lock file is out of sync (Ollama)", page.querySelector("#main-panel h1").getTextContent().trim());

        HtmlPage console = client.getPage(build, "console");
        assertEquals("Lock file is out of sync (Ollama)",
                console.querySelector("#explain-error-container .jenkins-card__title").getTextContent().trim());
    }

    @Test
    void pageKeepsItsHeadingWithoutATitle(JenkinsRule jenkins) throws Exception {
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
        build.addAction(action);
        build.save();

        HtmlPage page = jenkins.createWebClient().getPage(build, "error-explanation");

        assertEquals("AI Error Explanation (Ollama)", page.querySelector("#main-panel h1").getTextContent().trim());
    }
}
