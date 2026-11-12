package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Where the console page's button goes, and how the link back to the failing output looks.
 */
@WithJenkins
class ConsoleToolbarTest {

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setEnableExplanation(true);
        config.setAiProvider(new FakeAIProvider());
    }

    private FreeStyleBuild failedBuild() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new FailureBuilder());
        return jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
    }

    private static ErrorExplanationAction explanation() {
        ErrorExplanationAction action = new ErrorExplanationAction(
                "plain text", "console#L12", "line 1\nline 2", "DeepSeek", "deepseek-v4-flash", 2);
        action.setStructuredData(new JenkinsLogAnalysis(
                "The lock file is out of sync.", List.of("Run `npm install`."), null, "npm error code EUSAGE",
                "Lock file is out of sync"));
        return action;
    }

    @Test
    void theButtonJoinsTheToolbarWhateverLanguageItsButtonsAreIn() throws Exception {
        FreeStyleBuild build = failedBuild();

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(build, "console");
            client.waitForBackgroundJavaScript(10_000);
            DomNode download = page.querySelector("a[href='consoleText'][download]");
            assertNotNull(download, "the console page should have its download link");
            DomNode button = page.querySelector(".explain-error-btn");
            assertNotNull(button);
            assertEquals(download.getParentNode(), button.getParentNode());

            // The same toolbar as a translated Jenkins renders it, and the button placed again
            page.executeJavaScript("document.querySelectorAll('.explain-error-btn, .explain-error-agent-btn,"
                    + " .explain-error-preview').forEach(function (e) { e.remove(); });"
                    + "document.querySelectorAll('.jenkins-app-bar__controls a, .jenkins-app-bar__controls button')"
                    + ".forEach(function (e) { e.textContent = 'ダウンロード'; });"
                    + "addExplainErrorButton();");

            DomNode placedAgain = page.querySelector(".explain-error-btn");
            assertNotNull(placedAgain);
            assertEquals(download.getParentNode(), placedAgain.getParentNode(),
                    "the button belongs with the console page's own buttons, not above the log");
        }
    }

    @Test
    void theLinkToTheFailingOutputIsAnOrdinaryButton() throws Exception {
        FreeStyleBuild build = failedBuild();
        build.addAction(explanation());
        build.save();

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            for (String view : List.of("console", "error-explanation")) {
                HtmlPage page = client.getPage(build, view);
                DomElement link = page.getElementById("explain-error-url");
                assertNotNull(link, view);
                assertEquals("View failure output", link.getTextContent().trim(), view);
                assertTrue(link.getAttribute("class").contains("jenkins-button"), view);
                assertFalse(link.getAttribute("class").contains("destructive"),
                        "a link that only navigates must not look like a destructive action (" + view + ")");
            }
        }
    }

    @Test
    void theExplanationPageShowsItsTimeWithTheTimeZone() throws Exception {
        FreeStyleBuild build = failedBuild();
        ErrorExplanationAction action = explanation();
        build.addAction(action);
        build.save();
        String expected = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new Date(action.getTimestamp()));

        assertEquals(expected, action.getFormattedTimestamp());
        HtmlPage page = jenkins.createWebClient().getPage(build, "error-explanation");
        assertTrue(page.asNormalizedText().contains("Generated on: " + expected), page.asNormalizedText());
    }
}
