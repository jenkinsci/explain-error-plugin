package io.jenkins.plugins.explain_error.autofix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The build's "AI Auto-Fix" page.
 */
@WithJenkins
class AutoFixPageTest {

    private static final long TIMESTAMP = 1_700_000_000_000L;
    private static final String DIFF = "@@ -1,2 +1,2 @@\n flask==3.0.3\n-requests==2.99.0\n+requests==2.32.3";

    private static HtmlPage page(JenkinsRule jenkins, AutoFixAction action) throws Exception {
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
        build.addAction(action);
        build.save();
        return jenkins.createWebClient().getPage(build, "auto-fix");
    }

    @Test
    void aCreatedPullRequestAndItsBranchAreLinks(JenkinsRule jenkins) throws Exception {
        HtmlPage page = page(jenkins, new AutoFixAction(AutoFixStatus.CREATED, "https://github.com/acme/app/pull/7",
                "fix/jenkins-ai-7-pin-requests", "PR created: https://github.com/acme/app/pull/7",
                "fix: pin requests to 2.32.3", TIMESTAMP, "GITHUB"));

        HtmlAnchor pullRequest = page.querySelector(".eep-fix__pr a");
        assertEquals("fix: pin requests to 2.32.3", pullRequest.getTextContent().trim());
        assertEquals("https://github.com/acme/app/pull/7", pullRequest.getHrefAttribute());
        HtmlAnchor branch = page.querySelector(".eep-fix__branch a");
        assertEquals("fix/jenkins-ai-7-pin-requests", branch.getTextContent().trim());
        assertEquals("https://github.com/acme/app/tree/fix/jenkins-ai-7-pin-requests", branch.getHrefAttribute());
        assertFalse(page.asNormalizedText().contains("PR created: https://"),
                "the address must not be repeated as text next to the link");
        assertNull(page.querySelector(".eep-fix"), "the pull request carries the fix; it is not repeated here");
    }

    @Test
    void aBranchOnAnUnknownHostStaysText(JenkinsRule jenkins) throws Exception {
        HtmlPage page = page(jenkins, new AutoFixAction(AutoFixStatus.CREATED, "https://example.com/review/5",
                "fix/x", "m", null, TIMESTAMP, null));

        assertEquals("https://example.com/review/5", page.querySelector(".eep-fix__pr a").getTextContent().trim());
        DomNode branch = page.querySelector(".eep-fix__branch");
        assertTrue(branch.getTextContent().contains("fix/x"));
        assertNull(branch.querySelector("a"));
    }

    @Test
    void aFixThatWasNotAppliedIsShownWithItsDiff(JenkinsRule jenkins) throws Exception {
        AutoFixAction action = new AutoFixAction(AutoFixStatus.FAILED, null, null,
                "SCM credentials not found for ID: github-bot", null, TIMESTAMP, null);
        action.setSuggestion(new FixSuggestion(true, "requests 2.99.0 <b>does not exist</b> on PyPI.", "high",
                "dependency",
                List.of(new FixSuggestion.FileChange("requirements.txt", "modify", DIFF, "Pin a version that exists")),
                "pin requests to 2.32.3"));

        HtmlPage page = page(jenkins, action);

        assertEquals("SCM credentials not found for ID: github-bot",
                page.querySelector(".eep-fix__message").getTextContent().trim());
        DomNode fix = page.querySelector(".eep-fix");
        assertNotNull(fix, "the proposed fix should be on the page");
        assertEquals("Proposed fix: pin requests to 2.32.3",
                fix.querySelector(".eep-fix__heading").getTextContent().trim().replaceAll("\\s+", " "));
        assertTrue(fix.querySelector(".eep-fix__note").getTextContent().contains("No pull request was created"));
        DomNode explanation = fix.querySelector(".eep-fix__explanation");
        assertEquals("requests 2.99.0 <b>does not exist</b> on PyPI.", explanation.getTextContent().trim());
        assertNull(explanation.querySelector("b"), "markup written by the model must stay text");
        assertEquals("Confidence: high · Type: dependency",
                fix.querySelector(".eep-fix__meta").getTextContent().trim().replaceAll("\\s+", " "));
        assertEquals("requirements.txt", fix.querySelector(".eep-fix__file code").getTextContent());
        assertEquals("Pin a version that exists", fix.querySelector(".eep-fix__description").getTextContent().trim());

        List<DomNode> lines = fix.querySelectorAll(".eep-diff .eep-diff__line");
        assertEquals(4, lines.size());
        assertEquals("-requests==2.99.0", fix.querySelector(".eep-diff__del").getTextContent());
        assertEquals("+requests==2.32.3", fix.querySelector(".eep-diff__add").getTextContent());
        assertEquals("@@ -1,2 +1,2 @@", fix.querySelector(".eep-diff__hunk").getTextContent());
        DomNode copy = fix.querySelector(".eep-fix__file button.jenkins-copy-button");
        assertNotNull(copy, "the diff should be one click away from the clipboard");
        assertEquals("--- a/requirements.txt\n+++ b/requirements.txt\n" + DIFF + "\n",
                copy.getAttributes().getNamedItem("text").getNodeValue());
    }

    @Test
    void aFailureWithoutASuggestionShowsTheReasonAlone(JenkinsRule jenkins) throws Exception {
        HtmlPage page = page(jenkins, new AutoFixAction(AutoFixStatus.FAILED, null, null,
                "autoFixCredentialsId is required for auto-fix", null, TIMESTAMP, null));

        assertEquals("autoFixCredentialsId is required for auto-fix",
                page.querySelector(".eep-fix__message").getTextContent().trim());
        assertNull(page.querySelector(".eep-fix"));
        assertNull(page.querySelector(".eep-fix__pr"));
    }

    @Test
    void reasoningWithoutChangesIsShownAsWhatTheAiFound(JenkinsRule jenkins) throws Exception {
        AutoFixAction action = new AutoFixAction(AutoFixStatus.SKIPPED_LOW_CONFIDENCE, null, null,
                "Skipped: AI confidence level was too low to apply automatic fix.", null, TIMESTAMP, null);
        action.setSuggestion(new FixSuggestion(false, "The agent ran out of disk space.", "low", "unknown", List.of()));

        DomNode fix = page(jenkins, action).querySelector(".eep-fix");

        assertEquals("What the AI found", fix.querySelector(".eep-fix__heading").getTextContent().trim());
        assertEquals("The agent ran out of disk space.",
                fix.querySelector(".eep-fix__explanation").getTextContent().trim());
        assertNull(fix.querySelector(".eep-fix__note"));
        assertNull(fix.querySelector(".eep-diff"));
    }
}
