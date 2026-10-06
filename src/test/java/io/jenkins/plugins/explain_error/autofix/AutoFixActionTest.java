package io.jenkins.plugins.explain_error.autofix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import hudson.model.Run;
import io.jenkins.plugins.explain_error.autofix.scm.BitbucketApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.BitbucketServerApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.GitHubApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.GitLabApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.ScmClientFactory;
import io.jenkins.plugins.explain_error.autofix.scm.ScmRepo;
import io.jenkins.plugins.explain_error.autofix.scm.ScmType;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the auto-fix value objects: {@link AutoFixAction}, {@link AutoFixResult}
 * and {@link ScmClientFactory}.
 */
class AutoFixActionTest {

    private static final long TIMESTAMP = 1_767_225_600_000L;

    @Test
    void createdActionExposesPullRequestDetails() {
        AutoFixAction action = new AutoFixAction(AutoFixStatus.CREATED, "https://github.com/acme/app/pull/7",
                "fix/jenkins-ai-42-1", "PR created", "fix: AI auto-fix for app #42", TIMESTAMP, "GITHUB");

        assertEquals(AutoFixStatus.CREATED, action.getStatus());
        assertEquals("https://github.com/acme/app/pull/7", action.getPrUrl());
        assertEquals("fix/jenkins-ai-42-1", action.getBranchName());
        assertEquals("PR created", action.getMessage());
        assertEquals("fix: AI auto-fix for app #42", action.getPrTitle());
        assertEquals(TIMESTAMP, action.getTimestamp());
        assertEquals("GITHUB", action.getScmType());
        assertTrue(action.hasCreatedPr());
        assertEquals("PR Created", action.getStatusDisplayName());
    }

    @Test
    void actionIsListedInTheBuildSidebar() {
        AutoFixAction action = new AutoFixAction(AutoFixStatus.FAILED, null, null, "boom", null, TIMESTAMP, null);

        assertEquals("symbol-git-pull-request-outline plugin-ionicons-api", action.getIconFileName());
        assertEquals("AI Auto-Fix", action.getDisplayName());
        assertEquals("auto-fix", action.getUrlName());
        assertFalse(action.hasCreatedPr());
        assertNull(action.getPrUrl());
        assertNull(action.getBranchName());
        assertNull(action.getPrTitle());
        assertNull(action.getScmType());
    }

    @Test
    void statusDisplayNamesAreHumanReadable() {
        Map<AutoFixStatus, String> expected = Map.of(
                AutoFixStatus.CREATED, "PR Created",
                AutoFixStatus.FAILED, "Failed",
                AutoFixStatus.NOT_APPLICABLE, "Not Applicable",
                AutoFixStatus.SKIPPED_LOW_CONFIDENCE, "Skipped (Low Confidence)",
                AutoFixStatus.SKIPPED_PATH_NOT_ALLOWED, "Skipped (Path Not Allowed)",
                AutoFixStatus.TIMED_OUT, "Timed Out");
        assertEquals(AutoFixStatus.values().length, expected.size(), "every status needs a display name");

        expected.forEach((status, label) -> assertEquals(label,
                new AutoFixAction(status, null, null, "m", null, TIMESTAMP, null).getStatusDisplayName()));
        assertEquals("Unknown", new AutoFixAction(null, null, null, "m", null, TIMESTAMP, null).getStatusDisplayName());
    }

    @Test
    void runIsAttachedAndRestoredAfterLoading() {
        AutoFixAction action = new AutoFixAction(AutoFixStatus.CREATED, null, null, "m", null, TIMESTAMP, null);
        Run<?, ?> run = mock(Run.class);
        Run<?, ?> reloaded = mock(Run.class);

        assertNull(action.getRun());
        action.onAttached(run);
        assertSame(run, action.getRun());
        action.onLoad(reloaded);
        assertSame(reloaded, action.getRun());
        assertSame(action, action.readResolve());
    }

    @Test
    void timestampIsFormattedWithTimeZone() {
        AutoFixAction action = new AutoFixAction(AutoFixStatus.CREATED, null, null, "m", null, TIMESTAMP, null);

        assertEquals(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new Date(TIMESTAMP)),
                action.getFormattedTimestamp());
    }

    @Test
    void resultFactoriesSetStatusAndMessage() {
        AutoFixResult created = AutoFixResult.created("https://example/pr/1", "fix/branch");
        assertEquals(AutoFixStatus.CREATED, created.getStatus());
        assertEquals("https://example/pr/1", created.getPrUrl());
        assertEquals("fix/branch", created.getBranchName());
        assertEquals("Pull request created successfully: https://example/pr/1", created.getMessage());

        AutoFixResult failed = AutoFixResult.failed("boom");
        assertEquals(AutoFixStatus.FAILED, failed.getStatus());
        assertEquals("boom", failed.getMessage());
        assertNull(failed.getPrUrl());
        assertNull(failed.getBranchName());

        AutoFixResult notApplicable = AutoFixResult.notApplicable("no SCM");
        assertEquals(AutoFixStatus.NOT_APPLICABLE, notApplicable.getStatus());
        assertEquals("no SCM", notApplicable.getMessage());

        assertEquals(AutoFixStatus.SKIPPED_LOW_CONFIDENCE, AutoFixResult.skippedLowConfidence().getStatus());
        AutoFixResult pathNotAllowed = AutoFixResult.skippedPathNotAllowed("secrets.env");
        assertEquals(AutoFixStatus.SKIPPED_PATH_NOT_ALLOWED, pathNotAllowed.getStatus());
        assertTrue(pathNotAllowed.getMessage().endsWith("secrets.env"));
        assertEquals(AutoFixStatus.TIMED_OUT, AutoFixResult.timedOut().getStatus());
    }

    @Test
    void resultToStringListsAllFields() {
        assertEquals("AutoFixResult{status=CREATED, prUrl='https://example/pr/1', branchName='fix/branch', "
                        + "message='Pull request created successfully: https://example/pr/1'}",
                AutoFixResult.created("https://example/pr/1", "fix/branch").toString());
    }

    @Test
    void scmClientFactoryCreatesTheMatchingClient() {
        assertInstanceOf(GitHubApiClient.class, ScmClientFactory.create(repo(ScmType.GITHUB)));
        assertInstanceOf(GitLabApiClient.class, ScmClientFactory.create(repo(ScmType.GITLAB)));
        assertInstanceOf(BitbucketApiClient.class, ScmClientFactory.create(repo(ScmType.BITBUCKET)));
        assertInstanceOf(BitbucketServerApiClient.class, ScmClientFactory.create(repo(ScmType.BITBUCKET_SERVER)));
    }

    private static ScmRepo repo(ScmType type) {
        return new ScmRepo(type, "http://localhost:1", "owner", "repo", "token");
    }
}
