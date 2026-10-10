package io.jenkins.plugins.explain_error.autofix;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.scm.NullSCM;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.explain_error.autofix.scm.ScmRepo;
import io.jenkins.plugins.explain_error.autofix.scm.ScmType;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Drives {@link AutoFixOrchestrator} end to end against a WireMock GitHub Enterprise API.
 * The SCM token lookup is stubbed so no Jenkins instance or real credentials are needed.
 */
class AutoFixOrchestratorFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REPO_API = "/api/v3/repos/acme/app";
    private static final String BRANCH_PATTERN = "fix/jenkins-ai-42-\\d+";
    private static final String POM = "<project>\n  <version>1.0</version>\n</project>\n";
    /** Generous enough that the fix branch is always created before the auto-fix times out. */
    private static final int TIMEOUT_SECONDS = 10;
    private static final int SLOW_RESPONSE_MILLIS = 30_000;
    private static final String POM_DIFF = "--- a/pom.xml\n+++ b/pom.xml\n@@ -1,3 +1,3 @@\n <project>\n"
            + "-  <version>1.0</version>\n+  <version>1.1</version>\n </project>";

    private WireMockServer server;
    private Run<?, ?> run;
    private BaseAIProvider aiProvider;
    private FixAssistant fixAssistant;
    private ByteArrayOutputStream log;
    private StreamTaskListener listener;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();

        run = mock(Run.class);
        Job<?, ?> job = mock(Job.class);
        when(job.getFullName()).thenReturn("team/app");
        doReturn(job).when(run).getParent();
        when(run.getNumber()).thenReturn(42);

        aiProvider = mock(BaseAIProvider.class);
        fixAssistant = mock(FixAssistant.class);
        when(aiProvider.createFixAssistant(nullable(Item.class), nullable(Authentication.class)))
                .thenReturn(fixAssistant);

        log = new ByteArrayOutputStream();
        listener = new StreamTaskListener(log, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // -----------------------------------------------------------------------
    // Happy paths
    // -----------------------------------------------------------------------

    @Test
    void modifyFix_createsBranchCommitsPatchedFileAndOpensDraftPr() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        stubRepository(true);
        stubBranchCreation();
        stubFileContent("pom.xml", POM);
        stubCommitAndPullRequest();

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.CREATED, result.getStatus(), log());
        assertEquals("https://github.example.com/acme/app/pull/7", result.getPrUrl());
        assertTrue(result.getBranchName().matches(BRANCH_PATTERN), result.getBranchName());
        assertEquals("Pull request created successfully: https://github.example.com/acme/app/pull/7",
                result.getMessage());

        server.verify(postRequestedFor(urlEqualTo(REPO_API + "/git/blobs"))
                .withHeader("Authorization", equalTo("Bearer scm-token"))
                .withRequestBody(matchingJsonPath("$.content",
                        equalTo("<project>\n  <version>1.1</version>\n</project>\n"))));
        server.verify(postRequestedFor(urlEqualTo(REPO_API + "/git/commits"))
                .withRequestBody(matchingJsonPath("$.message", equalTo("fix: AI auto-fix for build #42"))));
        server.verify(postRequestedFor(urlEqualTo(REPO_API + "/pulls"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("fix: AI auto-fix for team/app #42")))
                .withRequestBody(matchingJsonPath("$.draft", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.base", equalTo("main")))
                .withRequestBody(matchingJsonPath("$.body", containing("## AI Auto-Fix for team/app #42")))
                .withRequestBody(matchingJsonPath("$.body", containing("- **pom.xml** (modify): Bump version"))));
        server.verify(0, deleteRequestedFor(urlPathMatching(REPO_API + "/git/refs/heads/.*")));

        verify(run).addOrReplaceAction(argThat((Action action) -> action instanceof AutoFixAction fix
                && fix.hasCreatedPr()
                && "https://github.example.com/acme/app/pull/7".equals(fix.getPrUrl())
                && "GITHUB".equals(fix.getScmType())
                && "fix: AI auto-fix for team/app #42".equals(fix.getPrTitle())));
        verify(run).save();
        assertTrue(log().contains("[AutoFix] SCM type: GITHUB, owner: acme, repo: app"), log());
        assertTrue(log().contains("[AutoFix] Pull request created: https://github.example.com/acme/app/pull/7"));
    }

    @Test
    void createFix_usesAddedLinesAsNewFileContentAndCustomTemplate() throws Exception {
        String createDiff = "--- /dev/null\n+++ b/config/app.yml\n@@ -0,0 +1,2 @@\n+retries: 3\n+timeout: 30";
        aiSuggests(fileChange("config/app.yml", "create", createDiff, null));
        stubRepository(true);
        stubBranchCreation();
        server.stubFor(get(urlEqualTo(REPO_API + "/contents/config/app.yml?ref=main"))
                .willReturn(aResponse().withStatus(404)));
        stubCommitAndPullRequest();
        // A failing save must not turn a created PR into a failure.
        doThrow(new IOException("disk full")).when(run).save();

        AutoFixResult result = attempt(withToken("scm-token"), List.of("*.yml"), 30,
                "Fix for {jobName} #{buildNumber}: {explanation} [{unknown}]", false);

        assertEquals(AutoFixStatus.CREATED, result.getStatus(), log());
        server.verify(postRequestedFor(urlEqualTo(REPO_API + "/git/blobs"))
                .withRequestBody(matchingJsonPath("$.content", equalTo("retries: 3\ntimeout: 30"))));
        server.verify(postRequestedFor(urlEqualTo(REPO_API + "/git/trees"))
                .withRequestBody(matchingJsonPath("$.tree[0].path", equalTo("config/app.yml"))));
        server.verify(postRequestedFor(urlEqualTo(REPO_API + "/pulls"))
                .withRequestBody(matchingJsonPath("$.draft", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.body",
                        equalTo("Fix for team/app #42: Bump the version [{unknown}]"))));
    }

    // -----------------------------------------------------------------------
    // Failures after the fix branch exists roll the branch back
    // -----------------------------------------------------------------------

    @Test
    void modifyingMissingFile_failsAndDeletesBranch() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        stubRepository(true);
        stubBranchCreation();
        server.stubFor(get(urlEqualTo(REPO_API + "/contents/pom.xml?ref=main"))
                .willReturn(aResponse().withStatus(404)));
        stubBranchDeletion(204);

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertEquals("Cannot modify non-existent file: pom.xml", result.getMessage());
        server.verify(deleteRequestedFor(urlPathMatching(REPO_API + "/git/refs/heads/" + BRANCH_PATTERN)));
        verify(run, never()).addOrReplaceAction(any(Action.class));
    }

    @Test
    void diffThatDoesNotApply_failsAndDeletesBranch() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        stubRepository(true);
        stubBranchCreation();
        stubFileContent("pom.xml", "<project>\n  <version>9.9</version>\n</project>\n");
        stubBranchDeletion(204);

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().startsWith("Failed to apply diff to pom.xml: "), result.getMessage());
        server.verify(deleteRequestedFor(urlPathMatching(REPO_API + "/git/refs/heads/" + BRANCH_PATTERN)));
    }

    @Test
    void commitFailure_failsAndDeletesBranch() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        stubRepository(true);
        stubBranchCreation();
        stubFileContent("pom.xml", POM);
        stubBranchHead();
        server.stubFor(post(urlEqualTo(REPO_API + "/git/blobs"))
                .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"bad blob\"}")));
        stubBranchDeletion(204);

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().startsWith("Failed to commit files: "), result.getMessage());
        server.verify(deleteRequestedFor(urlPathMatching(REPO_API + "/git/refs/heads/" + BRANCH_PATTERN)));
    }

    @Test
    void pullRequestFailure_failsAndBranchDeletionErrorsAreOnlyLogged() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        stubRepository(true);
        stubBranchCreation();
        stubFileContent("pom.xml", POM);
        stubCommit();
        server.stubFor(post(urlEqualTo(REPO_API + "/pulls"))
                .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"A pull request already exists\"}")));
        stubBranchDeletion(403);

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().startsWith("Failed to create pull request: "), result.getMessage());
        assertTrue(log().contains("[AutoFix] Rolling back branch: fix/jenkins-ai-42-"), log());
        verify(run, never()).save();
    }

    // -----------------------------------------------------------------------
    // Failures before any branch is created
    // -----------------------------------------------------------------------

    @Test
    void missingCredentials_failsWithoutCallingScm() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));

        AutoFixResult result = attempt(withToken(null), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertEquals("SCM credentials not found for ID: scm-token", result.getMessage());
        assertEquals(0, server.getAllServeEvents().size(), "no SCM request may be sent without credentials");
    }

    @Test
    void tokenWithoutPushAccess_failsWithUnexpectedError() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        stubRepository(false);

        AutoFixResult result = attempt(withToken("read-only"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().startsWith("Auto-fix encountered an unexpected error: Insufficient permissions"),
                result.getMessage());
        assertTrue(log().contains("[AutoFix] Error: Insufficient permissions"), log());
    }

    @Test
    void invalidDiff_failsBeforeAnyScmCall() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", "just some text", "Bump version"));

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertEquals("Invalid diff for pom.xml: No @@ hunk headers found in diff", result.getMessage());
        assertEquals(0, server.getAllServeEvents().size());
    }

    @Test
    void unsafeOrDisallowedPaths_areSkipped() throws Exception {
        for (String path : List.of("/etc/passwd", "../outside.txt", "docs/../../outside.txt", " ")) {
            aiSuggests(fileChange(path, "modify", POM_DIFF, "x"));
            AutoFixResult result = attempt(withToken("scm-token"), Collections.emptyList(), 30, null);
            assertEquals(AutoFixStatus.SKIPPED_PATH_NOT_ALLOWED, result.getStatus(), path);
        }

        // An invalid glob is ignored (logged) rather than matching everything.
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "x"));
        AutoFixResult invalidGlob = attempt(withToken("scm-token"), List.of("pom[.xml"), 30, null);
        assertEquals(AutoFixStatus.SKIPPED_PATH_NOT_ALLOWED, invalidGlob.getStatus());
        assertEquals(0, server.getAllServeEvents().size());
    }

    @Test
    void nullAllowedPathList_allowsAnyRelativePath() throws Exception {
        aiSuggests(fileChange("src/main/../pom.xml", "modify", "no hunk", "x"));

        AutoFixResult result = attempt(withToken("scm-token"), null, 30, null);

        // The path is accepted and processing continues to diff validation.
        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().startsWith("Invalid diff for src/main/../pom.xml"), result.getMessage());
    }

    @Test
    void nullCredentialsId_failsBeforeAiCall() {
        AutoFixResult result = withToken("scm-token").attemptAutoFix(run, "logs", aiProvider, null,
                "https://github.example.com/acme/app.git", "github", gheUrl(), null, null,
                List.of("pom.xml"), false, 30, listener, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        verify(fixAssistant, never()).suggestFix(anyString());
    }

    // -----------------------------------------------------------------------
    // Timeout, interruption and unexpected errors
    // -----------------------------------------------------------------------

    @Test
    void timeoutAfterBranchCreation_deletesTheBranch() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        stubRepository(true);
        stubBranchCreation();
        // The branch is created quickly; reading the file then outlasts the overall timeout.
        server.stubFor(get(urlEqualTo(REPO_API + "/contents/pom.xml?ref=main"))
                .willReturn(okJson(contentJson(POM)).withFixedDelay(SLOW_RESPONSE_MILLIS)));
        stubBranchDeletion(204);

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), TIMEOUT_SECONDS, null);

        assertEquals(AutoFixStatus.TIMED_OUT, result.getStatus());
        assertTrue(log().contains("[AutoFix] Timed out after " + TIMEOUT_SECONDS + " seconds."), log());
        server.verify(deleteRequestedFor(urlPathMatching(REPO_API + "/git/refs/heads/" + BRANCH_PATTERN)));
    }

    @Test
    void timeoutBeforeBranchCreation_doesNotTouchScm() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(fixAssistant.suggestFix(anyString())).thenAnswer(invocation -> {
            release.await(30, TimeUnit.SECONDS);
            return suggestion(fileChange("pom.xml", "modify", POM_DIFF, "x"));
        });

        try {
            AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 1, null);

            assertEquals(AutoFixStatus.TIMED_OUT, result.getStatus());
            assertEquals("Timed out while attempting to apply automatic fix.", result.getMessage());
            assertEquals(0, server.getAllServeEvents().size(), "no branch exists yet, so nothing is cleaned up");
        } finally {
            release.countDown();
        }
    }

    @Test
    void timeoutCleanupFailure_isOnlyLogged() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        stubRepository(true);
        stubBranchCreation();
        server.stubFor(get(urlEqualTo(REPO_API + "/contents/pom.xml?ref=main"))
                .willReturn(okJson(contentJson(POM)).withFixedDelay(SLOW_RESPONSE_MILLIS)));
        stubBranchDeletion(403);

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), TIMEOUT_SECONDS, null);

        assertEquals(AutoFixStatus.TIMED_OUT, result.getStatus());
    }

    @Test
    void interruptedWhileWaiting_returnsFailedAndKeepsInterruptFlag() {
        CountDownLatch release = new CountDownLatch(1);
        when(fixAssistant.suggestFix(anyString())).thenAnswer(invocation -> {
            release.await(30, TimeUnit.SECONDS);
            return "{\"fixable\":false}";
        });

        try {
            Thread.currentThread().interrupt();
            AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

            assertEquals(AutoFixStatus.FAILED, result.getStatus());
            assertEquals("Auto-fix interrupted.", result.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test
    void errorThrownByProvider_isReportedAsExecutionFailure() {
        when(fixAssistant.suggestFix(anyString())).thenThrow(new AssertionError("provider exploded"));

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertEquals("Auto-fix execution failed: provider exploded", result.getMessage());
    }

    @Test
    void malformedAiResponse_isReportedAsUnexpectedError() {
        when(fixAssistant.suggestFix(anyString())).thenReturn("} no json here {");

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("No JSON object found in AI response"), result.getMessage());
    }

    @Test
    void nullAiResponse_isReportedAsUnexpectedError() {
        when(fixAssistant.suggestFix(anyString())).thenReturn(null);

        AutoFixResult result = attempt(withToken("scm-token"), List.of("pom.xml"), 30, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("AI returned empty response"), result.getMessage());
    }

    // -----------------------------------------------------------------------
    // Remote URL extraction from the job configuration
    // -----------------------------------------------------------------------

    @Test
    void extractRemoteUrl_freestyleProject() {
        AbstractProject<?, ?> project = mock(AbstractProject.class);
        doReturn(new FakeGitScm(List.of("git@github.com:acme/app.git"))).when(project).getScm();
        doReturn(project).when(run).getParent();

        assertEquals("git@github.com:acme/app.git", new AutoFixOrchestrator().extractRemoteUrl(run));
    }

    @Test
    void extractRemoteUrl_freestyleProjectWithoutScm() {
        AbstractProject<?, ?> project = mock(AbstractProject.class);
        doReturn(project).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertEquals("No SCM configured on this job", e.getMessage());
    }

    @Test
    void extractRemoteUrl_pipelineFromScmDefinition() {
        WorkflowJob job = mock(WorkflowJob.class);
        CpsScmFlowDefinition definition = mock(CpsScmFlowDefinition.class);
        doReturn(new FakeGitScm(List.of("https://gitlab.com/acme/app.git"))).when(definition).getScm();
        doReturn(definition).when(job).getDefinition();
        doReturn(job).when(run).getParent();

        assertEquals("https://gitlab.com/acme/app.git", new AutoFixOrchestrator().extractRemoteUrl(run));
    }

    @Test
    void extractRemoteUrl_scmDefinitionWithoutScm() {
        WorkflowJob job = mock(WorkflowJob.class);
        doReturn(mock(CpsScmFlowDefinition.class)).when(job).getDefinition();
        doReturn(job).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().endsWith("does not support SCM URL extraction"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_inlinePipelineWithoutACheckout() {
        WorkflowJob job = mock(WorkflowJob.class);
        doReturn(mock(CpsFlowDefinition.class)).when(job).getDefinition();
        doReturn(job).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().endsWith("does not support SCM URL extraction"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_inlinePipelineFirstBuildUsesItsOwnCheckout() {
        // No earlier build, so the job has no SCM to report yet; the script has none either
        WorkflowJob job = mock(WorkflowJob.class);
        doReturn(mock(CpsFlowDefinition.class)).when(job).getDefinition();
        WorkflowRun build = mock(WorkflowRun.class);
        doReturn(job).when(build).getParent();
        doReturn(List.of(new FakeGitScm(List.of("https://github.com/acme/app.git")))).when(build).getSCMs();

        assertEquals("https://github.com/acme/app.git", new AutoFixOrchestrator().extractRemoteUrl(build));
    }

    @Test
    void extractRemoteUrl_buildCheckoutWinsOverAnEarlierBuilds() {
        WorkflowJob job = mock(WorkflowJob.class);
        doReturn(List.of(new FakeGitScm(List.of("https://github.com/acme/moved-away.git")))).when(job).getSCMs();
        WorkflowRun build = mock(WorkflowRun.class);
        doReturn(job).when(build).getParent();
        doReturn(List.of(new FakeGitScm(List.of("https://github.com/acme/app.git")))).when(build).getSCMs();

        assertEquals("https://github.com/acme/app.git", new AutoFixOrchestrator().extractRemoteUrl(build));
    }

    @Test
    void extractRemoteUrl_buildWithoutACheckoutFallsBackToTheJob() {
        WorkflowJob job = mock(WorkflowJob.class);
        doReturn(List.of(new FakeGitScm(List.of("https://github.com/acme/app.git")))).when(job).getSCMs();
        WorkflowRun build = mock(WorkflowRun.class);
        doReturn(job).when(build).getParent();

        assertEquals("https://github.com/acme/app.git", new AutoFixOrchestrator().extractRemoteUrl(build));
    }

    @Test
    void extractRemoteUrl_buildCheckoutLookupFailureIsReported() {
        WorkflowRun build = mock(WorkflowRun.class);
        doReturn(mock(WorkflowJob.class)).when(build).getParent();
        when(build.getSCMs()).thenThrow(new IllegalStateException("checkouts broken"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(build));
        assertTrue(e.getMessage().startsWith("Failed to inspect SCMs via getSCMs"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_pipelineWithoutDefinition() {
        WorkflowJob job = mock(WorkflowJob.class);
        doReturn(job).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().endsWith("does not support SCM URL extraction"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_definitionLookupFailureIsReported() {
        WorkflowJob job = mock(WorkflowJob.class);
        when(job.getDefinition()).thenThrow(new IllegalStateException("definition broken"));
        doReturn(job).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().startsWith("Failed to inspect SCM definition for job type"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_scmsLookupFailureIsReported() {
        WorkflowJob job = mock(WorkflowJob.class);
        when(job.getSCMs()).thenThrow(new IllegalStateException("scms broken"));
        doReturn(job).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().startsWith("Failed to inspect SCMs via getSCMs"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_jobTypeWithoutScmSupport() {
        Job<?, ?> job = mock(Job.class);
        doReturn(job).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().endsWith("does not support SCM URL extraction"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_ignoresEntriesThatAreNotScms() {
        WorkflowJob job = mock(WorkflowJob.class);
        doReturn(List.of("not an SCM")).when(job).getSCMs();
        doReturn(job).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().endsWith("does not support SCM URL extraction"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_rejectsUnsupportedScmTypes() {
        AbstractProject<?, ?> project = mock(AbstractProject.class);
        doReturn(new NullSCM()).when(project).getScm();
        doReturn(project).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().startsWith("Unsupported SCM type: hudson.scm.NullSCM"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_requiresAConfiguredRemote() {
        AbstractProject<?, ?> project = mock(AbstractProject.class);
        doReturn(new FakeGitScm(List.of())).when(project).getScm();
        doReturn(project).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().startsWith("No Git remote URLs configured"), e.getMessage());

        doReturn(new EmptyGitScm()).when(project).getScm();
        e = assertThrows(IllegalStateException.class, () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().startsWith("No Git remote URLs configured"), e.getMessage());
    }

    @Test
    void extractRemoteUrl_reportsBrokenScmImplementations() {
        AbstractProject<?, ?> project = mock(AbstractProject.class);
        doReturn(new BrokenGitScm()).when(project).getScm();
        doReturn(project).when(run).getParent();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AutoFixOrchestrator().extractRemoteUrl(run));
        assertTrue(e.getMessage().startsWith("Failed to extract remote URL from GitSCM"), e.getMessage());
    }

    @Test
    void remoteUrlFromJob_isUsedWhenNoExplicitUrlIsGiven() throws Exception {
        aiSuggests(fileChange("pom.xml", "modify", POM_DIFF, "Bump version"));
        WorkflowJob job = mock(WorkflowJob.class);
        when(job.getFullName()).thenReturn("team/app");
        doReturn(List.of(new FakeGitScm(List.of("git@github.com:acme/app.git")))).when(job).getSCMs();
        doReturn(job).when(run).getParent();
        stubRepository(false);

        AutoFixResult result = withToken("scm-token").attemptAutoFix(run, "logs", aiProvider, "scm-token",
                " ", null, gheUrl(), null, null, List.of("pom.xml"), false, 30, listener, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(log().contains("[AutoFix] SCM remote: git@github.com:acme/app.git"), log());
        assertTrue(log().contains("[AutoFix] SCM type: GITHUB, owner: acme, repo: app"), log());
    }

    // -----------------------------------------------------------------------
    // SCM repository resolution (no network access)
    // -----------------------------------------------------------------------

    @Test
    void buildScmRepo_appliesTypeOverrides() throws Exception {
        String remote = "https://git.internal.example/acme/app.git";

        assertRepo(buildScmRepo(remote, "GitHub ", null, null, null), ScmType.GITHUB, "https://api.github.com");
        assertRepo(buildScmRepo(remote, "github", "https://ghe.example ", null, null),
                ScmType.GITHUB, "https://ghe.example/api/v3");
        assertRepo(buildScmRepo(remote, "gitlab", null, null, null), ScmType.GITLAB, "https://gitlab.com/api/v4");
        assertRepo(buildScmRepo(remote, "gitlab", null, "https://gitlab.internal.example", null),
                ScmType.GITLAB, "https://gitlab.internal.example/api/v4");
        assertRepo(buildScmRepo(remote, "bitbucket", null, null, null),
                ScmType.BITBUCKET, "https://api.bitbucket.org/2.0");
        assertRepo(buildScmRepo(remote, "bitbucketserver", null, null, "https://bitbucket.internal.example"),
                ScmType.BITBUCKET_SERVER, "https://bitbucket.internal.example/rest/api/1.0");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> buildScmRepo(remote, "bitbucketserver", null, null, " "));
        assertEquals("autoFixBitbucketUrl must be set when scmTypeOverride is 'bitbucketserver'", e.getMessage());
    }

    @Test
    void buildScmRepo_detectsTypeFromUrlAndAppliesEnterpriseUrls() throws Exception {
        // An unknown override falls back to host-based detection.
        assertRepo(buildScmRepo("https://github.com/acme/app.git", "svn", null, null, null),
                ScmType.GITHUB, "https://api.github.com");
        assertRepo(buildScmRepo("https://github.com/acme/app.git", " ", "https://ghe.example", null, null),
                ScmType.GITHUB, "https://ghe.example/api/v3");
        assertRepo(buildScmRepo("https://gitlab.com/acme/app.git", null, null, "https://gitlab.example", null),
                ScmType.GITLAB, "https://gitlab.example/api/v4");
        assertRepo(buildScmRepo("https://gitlab.com/acme/app.git", null, "https://ghe.example", null, null),
                ScmType.GITLAB, "https://gitlab.com/api/v4");
        assertRepo(buildScmRepo("https://bitbucket.org/acme/app.git", null, null, null,
                "https://api.bitbucket.org/2.0"), ScmType.BITBUCKET, "https://api.bitbucket.org/2.0");
        assertRepo(buildScmRepo("https://bitbucket.example/scm/PROJ/app.git", null, null, null,
                "https://bitbucket-api.example"),
                ScmType.BITBUCKET_SERVER, "https://bitbucket-api.example/rest/api/1.0");
        assertRepo(buildScmRepo("https://bitbucket.example/scm/PROJ/app.git", null, null, null, null),
                ScmType.BITBUCKET_SERVER, "https://bitbucket.example/rest/api/1.0");
    }

    @Test
    void resolveCloudBitbucketBaseUrl_validatesAndKeepsExplicitApiUrls() {
        AutoFixOrchestrator orchestrator = new AutoFixOrchestrator();

        assertEquals("https://api.bitbucket.org/2.0", orchestrator.resolveCloudBitbucketBaseUrl(null));
        assertEquals("https://api.bitbucket.org/2.0", orchestrator.resolveCloudBitbucketBaseUrl(" "));
        assertEquals("https://proxy.example/bitbucket/2.0",
                orchestrator.resolveCloudBitbucketBaseUrl("https://proxy.example/bitbucket/2.0"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> orchestrator.resolveCloudBitbucketBaseUrl("not-a-url"));
        assertTrue(e.getMessage().startsWith("Bitbucket URL is not a valid absolute URL"), e.getMessage());
    }

    @Test
    void extractNewContent_ignoresLinesOutsideHunks() {
        String diff = "+not part of a hunk\n--- /dev/null\n+++ b/a.txt\n@@ -0,0 +1 @@\n+kept";

        assertEquals("kept", new AutoFixOrchestrator().extractNewContent(diff));
    }

    @Test
    void buildPrBody_handlesMissingDescriptionsAndUnknownPlaceholders() {
        FixSuggestion suggestion = new FixSuggestion(true, null, null, null,
                List.of(new FixSuggestion.FileChange("pom.xml", "modify", POM_DIFF, null)));

        String body = new AutoFixOrchestrator().buildPrBody(run, suggestion,
                "{explanation}|{confidence}|{fixType}|{changesSummary}|{other}");

        assertEquals("No explanation provided.|unknown|unknown|- **pom.xml** (modify):|{other}", body);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private AutoFixResult attempt(AutoFixOrchestrator orchestrator, List<String> allowedPaths, int timeoutSeconds,
                                  String prTemplate) {
        return attempt(orchestrator, allowedPaths, timeoutSeconds, prTemplate, true);
    }

    private AutoFixResult attempt(AutoFixOrchestrator orchestrator, List<String> allowedPaths, int timeoutSeconds,
                                  String prTemplate, boolean draftPr) {
        return orchestrator.attemptAutoFix(run, "ERROR: build failed", aiProvider, "scm-token",
                "https://github.example.com/acme/app.git", "github", gheUrl(), null, null,
                allowedPaths, draftPr, timeoutSeconds, listener, prTemplate);
    }

    private String gheUrl() {
        return "http://localhost:" + server.port();
    }

    private String log() {
        return log.toString(StandardCharsets.UTF_8);
    }

    private static AutoFixOrchestrator withToken(String token) {
        return new AutoFixOrchestrator() {
            @Override
            String resolveScmToken(String credentialsId, Run<?, ?> run) {
                return token;
            }
        };
    }

    private void aiSuggests(FixSuggestion.FileChange change) throws IOException {
        when(fixAssistant.suggestFix(anyString())).thenReturn("Here you go:\n" + suggestion(change));
    }

    private static String suggestion(FixSuggestion.FileChange change) throws IOException {
        return MAPPER.writeValueAsString(
                new FixSuggestion(true, "Bump the version", "high", "dependency", List.of(change)));
    }

    private static FixSuggestion.FileChange fileChange(String path, String action, String diff, String description) {
        return new FixSuggestion.FileChange(path, action, diff, description);
    }

    private void stubRepository(boolean pushAccess) {
        server.stubFor(get(urlEqualTo(REPO_API)).willReturn(okJson(
                "{\"default_branch\":\"main\",\"permissions\":{\"push\":" + pushAccess + "}}")));
    }

    private void stubBranchCreation() {
        server.stubFor(get(urlEqualTo(REPO_API + "/git/ref/heads/main"))
                .willReturn(okJson("{\"object\":{\"sha\":\"base-sha\"}}")));
        server.stubFor(post(urlEqualTo(REPO_API + "/git/refs"))
                .willReturn(aResponse().withStatus(201).withBody("{}")));
    }

    private void stubFileContent(String path, String content) {
        server.stubFor(get(urlEqualTo(REPO_API + "/contents/" + path + "?ref=main"))
                .willReturn(okJson(contentJson(content))));
    }

    private void stubBranchHead() {
        server.stubFor(get(urlPathMatching(REPO_API + "/git/ref/heads/" + BRANCH_PATTERN))
                .willReturn(okJson("{\"object\":{\"sha\":\"branch-sha\"}}")));
        server.stubFor(get(urlEqualTo(REPO_API + "/git/commits/branch-sha"))
                .willReturn(okJson("{\"tree\":{\"sha\":\"tree-sha\"}}")));
    }

    private void stubCommit() {
        stubBranchHead();
        server.stubFor(post(urlEqualTo(REPO_API + "/git/blobs"))
                .willReturn(aResponse().withStatus(201).withBody("{\"sha\":\"blob-sha\"}")));
        server.stubFor(post(urlEqualTo(REPO_API + "/git/trees"))
                .willReturn(aResponse().withStatus(201).withBody("{\"sha\":\"new-tree-sha\"}")));
        server.stubFor(post(urlEqualTo(REPO_API + "/git/commits"))
                .willReturn(aResponse().withStatus(201).withBody("{\"sha\":\"new-commit-sha\"}")));
        server.stubFor(patch(urlPathMatching(REPO_API + "/git/refs/heads/" + BRANCH_PATTERN))
                .willReturn(okJson("{}")));
    }

    private void stubCommitAndPullRequest() {
        stubCommit();
        server.stubFor(post(urlEqualTo(REPO_API + "/pulls")).willReturn(aResponse().withStatus(201).withBody(
                "{\"number\":7,\"html_url\":\"https://github.example.com/acme/app/pull/7\"}")));
    }

    private void stubBranchDeletion(int status) {
        server.stubFor(delete(urlPathMatching(REPO_API + "/git/refs/heads/" + BRANCH_PATTERN))
                .willReturn(aResponse().withStatus(status)));
    }

    private static String contentJson(String content) {
        String encoded = Base64.getMimeEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        return MAPPER.createObjectNode().put("content", encoded).toString();
    }

    private static ScmRepo buildScmRepo(String remoteUrl, String scmTypeOverride, String githubEnterpriseUrl,
                                        String gitlabUrl, String bitbucketUrl) throws Exception {
        Method method = AutoFixOrchestrator.class.getDeclaredMethod("buildScmRepo", String.class, String.class,
                String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        try {
            return (ScmRepo) method.invoke(new AutoFixOrchestrator(), remoteUrl, "token", scmTypeOverride,
                    githubEnterpriseUrl, gitlabUrl, bitbucketUrl);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static void assertRepo(ScmRepo repo, ScmType type, String baseUrl) {
        assertNotNull(repo);
        assertEquals(type, repo.scmType());
        assertEquals(baseUrl, repo.baseUrl());
    }

    private static final class FakeGitScm extends NullSCM {

        private final List<FakeRemoteConfig> repositories;

        private FakeGitScm(List<String> remoteUrls) {
            this.repositories = remoteUrls.isEmpty() ? List.of() : List.of(new FakeRemoteConfig(remoteUrls));
        }

        public List<FakeRemoteConfig> getRepositories() {
            return repositories;
        }
    }

    private static final class EmptyGitScm extends NullSCM {

        public List<FakeRemoteConfig> getRepositories() {
            return List.of(new FakeRemoteConfig(List.of()));
        }
    }

    private static final class BrokenGitScm extends NullSCM {

        public List<FakeRemoteConfig> getRepositories() {
            throw new IllegalStateException("repositories unavailable");
        }
    }

    private static final class FakeRemoteConfig {

        private final List<String> remoteUrls;

        private FakeRemoteConfig(List<String> remoteUrls) {
            this.remoteUrls = remoteUrls;
        }

        public List<String> getURIs() {
            return remoteUrls;
        }
    }
}
