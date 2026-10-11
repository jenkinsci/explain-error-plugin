package io.jenkins.plugins.explain_error.autofix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What {@link AutoFixAction} keeps of the AI's suggestion, and the links it works out.
 */
class AutoFixSuggestionTest {

    private static final long TIMESTAMP = 1_700_000_000_000L;
    private static final String DIFF = "@@ -1,2 +1,2 @@\n flask==3.0.3\n-requests==2.99.0\n+requests==2.32.3";

    private static AutoFixAction failed() {
        return new AutoFixAction(AutoFixStatus.FAILED, null, null, "boom", null, TIMESTAMP, null);
    }

    private static AutoFixAction created(String prUrl, String branchName) {
        return new AutoFixAction(AutoFixStatus.CREATED, prUrl, branchName, "m", "fix: x", TIMESTAMP, "GITHUB");
    }

    @Test
    void keepsTheSuggestionAndItsChanges() {
        AutoFixAction action = failed();
        action.setSuggestion(new FixSuggestion(true, " requests 2.99.0 does not exist. ", "high", "dependency",
                List.of(new FixSuggestion.FileChange("requirements.txt", "modify", DIFF, "Pin a real version")),
                "pin requests to 2.32.3"));

        assertTrue(action.hasSuggestion());
        assertTrue(action.hasProposedChanges());
        assertEquals("pin requests to 2.32.3", action.getSuggestedTitle());
        assertEquals("requests 2.99.0 does not exist.", action.getSuggestedExplanation());
        assertEquals("high", action.getSuggestedConfidence());
        assertEquals("dependency", action.getSuggestedFixType());
        AutoFixAction.ProposedChange change = action.getProposedChanges().get(0);
        assertEquals("requirements.txt", change.getFilePath());
        assertEquals("modify", change.getAction());
        assertEquals("Pin a real version", change.getDescription());
        assertEquals(DIFF, change.getUnifiedDiff());
    }

    @Test
    void anActionWithoutASuggestionHasNothingToShow() {
        AutoFixAction action = failed();
        assertFalse(action.hasSuggestion());
        assertFalse(action.hasProposedChanges());
        assertTrue(action.getProposedChanges().isEmpty());

        action.setSuggestion(null);
        assertFalse(action.hasSuggestion());

        action.setSuggestion(new FixSuggestion(false, " ", null, null, null));
        assertFalse(action.hasSuggestion());
        assertNull(action.getSuggestedExplanation());
    }

    @Test
    void reasoningWithoutChangesIsStillASuggestion() {
        AutoFixAction action = failed();
        action.setSuggestion(new FixSuggestion(false, "Nothing in the repository causes this.", "low", "unknown",
                Arrays.asList((FixSuggestion.FileChange) null,
                        new FixSuggestion.FileChange(null, "modify", DIFF, "no path"))));

        assertTrue(action.hasSuggestion());
        assertFalse(action.hasProposedChanges(), "changes without a file are dropped");
    }

    @Test
    void patchGetsTheFileLinesTheModelLeftOut() {
        AutoFixAction action = failed();
        action.setSuggestion(new FixSuggestion(true, "e", "high", "dependency", List.of(
                new FixSuggestion.FileChange("requirements.txt", "modify", DIFF, null),
                new FixSuggestion.FileChange("ci/retry.txt", "create", "@@ -0,0 +1 @@\n+3\n", null),
                new FixSuggestion.FileChange("pom.xml", "modify",
                        "--- a/pom.xml\n+++ b/pom.xml\n@@ -1 +1 @@\n-a\n+b", null),
                new FixSuggestion.FileChange("empty.txt", "modify", null, null))));
        List<AutoFixAction.ProposedChange> changes = action.getProposedChanges();

        assertEquals("--- a/requirements.txt\n+++ b/requirements.txt\n" + DIFF + "\n", changes.get(0).getPatch());
        assertEquals("--- /dev/null\n+++ b/ci/retry.txt\n@@ -0,0 +1 @@\n+3\n", changes.get(1).getPatch());
        assertEquals("--- a/pom.xml\n+++ b/pom.xml\n@@ -1 +1 @@\n-a\n+b\n", changes.get(2).getPatch());
        assertEquals("", changes.get(3).getPatch());
        assertEquals("", changes.get(3).getUnifiedDiff());
    }

    @Test
    void diffLinesSayWhatTheyAre() {
        AutoFixAction action = failed();
        action.setSuggestion(new FixSuggestion(true, "e", "high", "dependency", List.of(new FixSuggestion.FileChange(
                "pom.xml", "modify",
                "--- a/pom.xml\n+++ b/pom.xml\n@@ -1,3 +1,3 @@\n <project>\n-  <v>1</v>\n+  <v>2</v>\n", null))));

        List<AutoFixAction.DiffLine> lines = action.getProposedChanges().get(0).getDiffLines();

        assertEquals(List.of("file", "file", "hunk", "context", "del", "add"),
                lines.stream().map(AutoFixAction.DiffLine::getKind).toList());
        assertEquals("-  <v>1</v>", lines.get(4).getText());
    }

    @Test
    void branchLinkFollowsTheHostOfThePullRequest() {
        assertEquals("https://github.com/acme/app/tree/fix/jenkins-ai-7-pin-requests",
                created("https://github.com/acme/app/pull/7", "fix/jenkins-ai-7-pin-requests").getBranchUrl());
        assertEquals("https://ghe.example.com/acme/app/tree/fix/x",
                created("https://ghe.example.com/acme/app/pull/12/", "fix/x").getBranchUrl());
        assertEquals("https://gitlab.com/group/sub/app/-/tree/fix/x",
                created("https://gitlab.com/group/sub/app/-/merge_requests/3", "fix/x").getBranchUrl());
        assertEquals("https://bitbucket.org/acme/app/branch/fix/x",
                created("https://bitbucket.org/acme/app/pull-requests/5", "fix/x").getBranchUrl());
    }

    @Test
    void noBranchLinkWithoutARecognisedPullRequest() {
        assertNull(created("https://example.com/review/5", "fix/x").getBranchUrl());
        assertNull(created("https://bitbucket.example.com/projects/ACME/repos/app/pull-requests/5", "fix/x")
                .getBranchUrl(), "Bitbucket Server has no /branch/ page");
        assertNull(created(null, "fix/x").getBranchUrl());
        assertNull(created("https://github.com/acme/app/pull/7", null).getBranchUrl());
        assertNull(created("https://github.com/acme/app/pull/7", " ").getBranchUrl());
    }
}
