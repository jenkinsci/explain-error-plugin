package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import java.util.List;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The conclusion of an explanation on the build's own page.
 */
@WithJenkins
class ExplanationBuildSummaryTest {

    @Test
    void buildPageShowsTheConclusionAndLinksToTheRest(JenkinsRule jenkins) throws Exception {
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
        ErrorExplanationAction action = new ErrorExplanationAction(
                "plain text", "", "line 1\nline 2", "DeepSeek", "deepseek-v4-flash", 2);
        action.setStructuredData(new JenkinsLogAnalysis(
                "`npm ci` failed: <b>package-lock.json</b> is out of sync.", List.of("Run `npm install`."), null,
                "npm error code EUSAGE", "Lock file is out of sync"));
        build.addAction(action);
        build.save();

        HtmlPage page = jenkins.createWebClient().getPage(build);

        DomNode summary = page.querySelector(".eep-build-summary");
        assertNotNull(summary, "the build page should carry the explanation summary");
        HtmlAnchor heading = summary.querySelector("a.eep-build-summary__heading");
        assertEquals("Lock file is out of sync (DeepSeek)", heading.getTextContent().trim());
        assertTrue(heading.getHrefAttribute().endsWith(build.getUrl() + "error-explanation/"),
                heading.getHrefAttribute());
        DomNode text = summary.querySelector(".eep-build-summary__text");
        assertEquals("npm ci failed: <b>package-lock.json</b> is out of sync.", text.getTextContent().trim());
        assertEquals("npm ci", text.querySelector("code").getTextContent());
        assertNull(text.querySelector("b"), "markup written by the model must stay text");
        HtmlAnchor more = summary.querySelector("a.eep-build-summary__more");
        assertEquals("View full explanation", more.getTextContent().trim());

        HtmlPage explanation = more.click();
        assertTrue(explanation.getUrl().getPath().endsWith("/error-explanation/"), explanation.getUrl().toString());
    }

    @Test
    void anExplanationWithoutFieldsGetsTheHeadingAndTheLinkOnly(JenkinsRule jenkins) throws Exception {
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
        build.addAction(new ErrorExplanationAction("free text from the model", "", "line 1", "Ollama"));
        build.save();

        DomNode summary = jenkins.createWebClient().getPage(build).querySelector(".eep-build-summary");

        assertNotNull(summary);
        assertEquals("AI Error Explanation (Ollama)",
                summary.querySelector("a.eep-build-summary__heading").getTextContent().trim());
        assertNull(summary.querySelector(".eep-build-summary__text"));
        assertNotNull(summary.querySelector("a.eep-build-summary__more"));
    }

    @Test
    void aBuildWithoutAnExplanationShowsNothing(JenkinsRule jenkins) throws Exception {
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());

        assertNull(jenkins.createWebClient().getPage(build).querySelector(".eep-build-summary"));
    }

    @Test
    void aBlankExplanationShowsNothing(JenkinsRule jenkins) throws Exception {
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
        build.addAction(new ErrorExplanationAction("  ", "", "line 1", "Ollama"));
        build.save();

        assertNull(jenkins.createWebClient().getPage(build).querySelector(".eep-build-summary"));
    }

    @Test
    void aShortSummaryIsShownWhole() {
        assertEquals("The lock file is out of sync.",
                ErrorExplanationAction.preview(" The lock file\n is out of sync. ", 320));
        assertEquals("", ErrorExplanationAction.preview(null, 320));
    }

    @Test
    void aLongSummaryIsCutAtTheEndOfASentence() {
        String text = "The lock file is out of sync. The dependency was added by hand. "
                + "More detail follows. ".repeat(30);

        assertEquals("The lock file is out of sync. The dependency was added by hand.",
                ErrorExplanationAction.preview(text, 70));
        assertEquals("锁文件与 package.json 不同步。依赖是手工添加的。",
                ErrorExplanationAction.preview("锁文件与 package.json 不同步。依赖是手工添加的。后面还有很多很多很多很多细节。", 32));
    }

    @Test
    void aLongSentenceIsCutAtAWordWithAnEllipsis() {
        String text = "The build failed because the lock file and the manifest disagree about a dependency "
                + "that was added";

        assertEquals("The build failed because the lock file and the\u2026", ErrorExplanationAction.preview(text, 50));
        assertEquals("abcdefghij\u2026", ErrorExplanationAction.preview("abcdefghijklmnopqrstuvwxyz", 10));
    }
}
