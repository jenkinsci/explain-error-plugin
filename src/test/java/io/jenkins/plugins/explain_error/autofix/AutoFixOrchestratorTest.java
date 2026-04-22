package io.jenkins.plugins.explain_error.autofix;

import hudson.model.Job;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.scm.NullSCM;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoFixOrchestratorTest {

    private AutoFixOrchestrator orchestrator;

    // Shared mocks for attemptAutoFix paths
    private Run<?, ?> run;
    private BaseAIProvider aiProvider;
    private FixAssistant fixAssistant;
    private TaskListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orchestrator = new AutoFixOrchestrator();

        run = mock(Run.class);
        aiProvider = mock(BaseAIProvider.class);
        fixAssistant = mock(FixAssistant.class);
        listener = mock(TaskListener.class);

        PrintStream printStream = mock(PrintStream.class);
        when(listener.getLogger()).thenReturn(printStream);
        when(aiProvider.createFixAssistant(nullable(Item.class), nullable(Authentication.class)))
                .thenReturn(fixAssistant);
    }

    // -----------------------------------------------------------------------
    // parseFixSuggestion — happy paths
    // -----------------------------------------------------------------------

    @Test
    void parseFixSuggestion_fixableTrue() throws IOException {
        String json = """
                {
                  "fixable": true,
                  "explanation": "Missing dependency",
                  "confidence": "high",
                  "fixType": "dependency",
                  "changes": []
                }
                """;
        FixSuggestion suggestion = orchestrator.parseFixSuggestion(json);
        assertTrue(suggestion.fixable());
        assertEquals("high", suggestion.confidence());
        assertEquals("dependency", suggestion.fixType());
    }

    @Test
    void parseFixSuggestion_fixableFalse() throws IOException {
        String json = "{\"fixable\": false, \"explanation\": \"Unknown error\", \"confidence\": \"low\", \"fixType\": \"unknown\", \"changes\": []}";
        FixSuggestion suggestion = orchestrator.parseFixSuggestion(json);
        assertFalse(suggestion.fixable());
        assertEquals("low", suggestion.confidence());
    }

    @Test
    void parseFixSuggestion_withPreamble_extractsJsonBlock() throws IOException {
        // AI sometimes prefixes the JSON with prose; the parser should strip it
        String raw = "Sure! Here is the fix:\n{\"fixable\": false, \"confidence\": \"low\", \"fixType\": \"unknown\", \"changes\": []}";
        FixSuggestion suggestion = orchestrator.parseFixSuggestion(raw);
        assertFalse(suggestion.fixable());
    }

    @Test
    void parseFixSuggestion_emptyResponse_throws() {
        assertThrows(IOException.class, () -> orchestrator.parseFixSuggestion(""));
    }

    @Test
    void parseFixSuggestion_blankResponse_throws() {
        assertThrows(IOException.class, () -> orchestrator.parseFixSuggestion("   "));
    }

    @Test
    void parseFixSuggestion_noJsonObject_throws() {
        assertThrows(IOException.class, () -> orchestrator.parseFixSuggestion("no braces here"));
    }

    // -----------------------------------------------------------------------
    // extractNewContent
    // -----------------------------------------------------------------------

    @Test
    void extractNewContent_returnsAddedLines() {
        String diff = "--- a/NewFile.java\n+++ b/NewFile.java\n@@ -0,0 +1,3 @@\n+line one\n+line two\n+line three\n";
        String result = orchestrator.extractNewContent(diff);
        assertTrue(result.contains("line one"));
        assertTrue(result.contains("line two"));
        assertTrue(result.contains("line three"));
    }

    @Test
    void extractNewContent_ignoresContextAndRemovalLines() {
        String diff = "--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n context\n-removed\n+added\n";
        String result = orchestrator.extractNewContent(diff);
        assertTrue(result.contains("added"));
        assertFalse(result.contains("removed"));
        assertFalse(result.contains("context"));
    }

    @Test
    void extractNewContent_nullDiff_returnsEmpty() {
        assertEquals("", orchestrator.extractNewContent(null));
    }

    @Test
    void extractNewContent_blankDiff_returnsEmpty() {
        assertEquals("", orchestrator.extractNewContent("   "));
    }

    // -----------------------------------------------------------------------
    // buildPrBody
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void buildPrBody_substitutesAllPlaceholders() {
        Job<?, ?> job = mock(Job.class);
        when(job.getFullName()).thenReturn("my-org/my-repo");
        when(run.getParent()).thenReturn((Job) job);
        when(run.getNumber()).thenReturn(42);

        FixSuggestion suggestion = new FixSuggestion(
                true,
                "Dependency version mismatch",
                "high",
                "dependency",
                List.of(new FixSuggestion.FileChange("pom.xml", "modify", "--- a/pom.xml\n+++ b/pom.xml\n@@ -1,1 +1,1 @@\n-old\n+new\n", "Update version")));

        String template = "job={jobName} build=#{buildNumber} conf={confidence} type={fixType}\n{explanation}\n{changesSummary}";
        String body = orchestrator.buildPrBody(run, suggestion, template);

        assertTrue(body.contains("my-org/my-repo"), "jobName placeholder must be substituted");
        assertTrue(body.contains("42"), "buildNumber placeholder must be substituted");
        assertTrue(body.contains("high"), "confidence placeholder must be substituted");
        assertTrue(body.contains("dependency"), "fixType placeholder must be substituted");
        assertTrue(body.contains("Dependency version mismatch"), "explanation placeholder must be substituted");
        assertTrue(body.contains("pom.xml"), "changesSummary must mention file path");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPrBody_aiContentContainingPlaceholder_isNotDoubleSubstituted() {
        // Regression: Issue 4 — multi-pass .replace() could corrupt AI output
        // if the explanation itself contained a {placeholder} token.
        Job<?, ?> job = mock(Job.class);
        when(job.getFullName()).thenReturn("proj");
        when(run.getParent()).thenReturn((Job) job);
        when(run.getNumber()).thenReturn(7);

        // explanation contains "{fixType}" — should appear literally in the output
        FixSuggestion suggestion = new FixSuggestion(
                true, "Use {fixType} carefully", "high", "dependency", null);

        String body = orchestrator.buildPrBody(run, suggestion,
                "explanation={explanation} type={fixType}");

        assertTrue(body.contains("Use {fixType} carefully"),
                "AI output containing {fixType} must not be substituted a second time");
        assertTrue(body.contains("type=dependency"),
                "The actual {fixType} placeholder in the template must still be substituted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPrBody_noChanges_showsFallback() {
        Job<?, ?> job = mock(Job.class);
        when(job.getFullName()).thenReturn("proj");
        when(run.getParent()).thenReturn((Job) job);
        when(run.getNumber()).thenReturn(1);

        FixSuggestion suggestion = new FixSuggestion(false, null, null, null, null);
        String body = orchestrator.buildPrBody(run, suggestion, "{changesSummary}");

        assertTrue(body.contains("No file changes"), "No changes should show fallback text");
    }

    // -----------------------------------------------------------------------
    // Path 1a — fixable=false → SKIPPED_LOW_CONFIDENCE (AI says not fixable)
    // -----------------------------------------------------------------------

    @Test
    void attemptAutoFix_notFixable_returnsSkippedLowConfidence() {
        String aiJson = "{\"fixable\": false, \"explanation\": \"Unknown\", \"confidence\": \"low\", \"fixType\": \"unknown\", \"changes\": []}";
        when(fixAssistant.suggestFix(anyString())).thenReturn(aiJson);

        AutoFixResult result = orchestrator.attemptAutoFix(
                run, "some error logs", aiProvider,
                "creds-id", null, null, null, null, null,
                Collections.emptyList(), false, 30, listener, null);

        assertEquals(AutoFixStatus.SKIPPED_LOW_CONFIDENCE, result.getStatus());
    }

    // -----------------------------------------------------------------------
    // Path 1b — fixable=true but confidence=low → SKIPPED_LOW_CONFIDENCE
    // -----------------------------------------------------------------------

    @Test
    void attemptAutoFix_fixableButLowConfidence_returnsSkippedLowConfidence() {
        String aiJson = "{\"fixable\": true, \"explanation\": \"Uncertain\", \"confidence\": \"low\", \"fixType\": \"unknown\", \"changes\": []}";
        when(fixAssistant.suggestFix(anyString())).thenReturn(aiJson);

        AutoFixResult result = orchestrator.attemptAutoFix(
                run, "error logs", aiProvider,
                "creds-id", null, null, null, null, null,
                Collections.emptyList(), false, 30, listener, null);

        assertEquals(AutoFixStatus.SKIPPED_LOW_CONFIDENCE, result.getStatus());
    }

    // -----------------------------------------------------------------------
    // Path 1c — fixable=true, confidence=high, empty changes list
    //           Guard added: empty changes → SKIPPED_LOW_CONFIDENCE before any
    //           SCM or branch operations are attempted.
    // -----------------------------------------------------------------------

    @Test
    void attemptAutoFix_emptyChanges_returnsSkipped() {
        String aiJson = "{\"fixable\": true, \"explanation\": \"Fix available\", \"confidence\": \"high\", \"fixType\": \"config\", \"changes\": []}";
        when(fixAssistant.suggestFix(anyString())).thenReturn(aiJson);

        AutoFixResult result = orchestrator.attemptAutoFix(
                run, "error logs", aiProvider,
                "creds-id", null, null, null, null, null,
                Collections.emptyList(), false, 30, listener, null);

        assertEquals(AutoFixStatus.SKIPPED_LOW_CONFIDENCE, result.getStatus(),
                "Empty changes list must be treated as skipped (no changes to commit)");
    }

    // -----------------------------------------------------------------------
    // Path 8 — allowedPaths excludes the target file → SKIPPED_PATH_NOT_ALLOWED
    // -----------------------------------------------------------------------

    @Test
    void attemptAutoFix_pathNotInAllowedList_returnsSkippedPathNotAllowed() {
        // allowed: only pom.xml; AI suggests changing src/Main.java
        String aiJson = """
                {
                  "fixable": true,
                  "explanation": "Code fix",
                  "confidence": "high",
                  "fixType": "code",
                  "changes": [
                    {
                      "filePath": "src/Main.java",
                      "action": "modify",
                      "unifiedDiff": "--- a/src/Main.java\\n+++ b/src/Main.java\\n@@ -1,1 +1,1 @@\\n-old\\n+new\\n",
                      "description": "Fix the bug"
                    }
                  ]
                }
                """;
        when(fixAssistant.suggestFix(anyString())).thenReturn(aiJson);

        AutoFixResult result = orchestrator.attemptAutoFix(
                run, "error logs", aiProvider,
                "creds-id", null, null, null, null, null,
                List.of("pom.xml"), false, 30, listener, null);

        assertEquals(AutoFixStatus.SKIPPED_PATH_NOT_ALLOWED, result.getStatus());
        assertTrue(result.getMessage().contains("src/Main.java"),
                "Message must name the rejected file path");
    }

    // -----------------------------------------------------------------------
    // Path 8 variant — allowed glob matches → path guard passes
    // (The test verifies SKIPPED_PATH_NOT_ALLOWED is NOT returned when the
    //  glob matches; the run proceeds to SCM extraction which fails with FAILED.)
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void attemptAutoFix_pathMatchesAllowedGlob_doesNotReturnPathNotAllowed() {
        String aiJson = """
                {
                  "fixable": true,
                  "explanation": "Config fix",
                  "confidence": "high",
                  "fixType": "config",
                  "changes": [
                    {
                      "filePath": "pom.xml",
                      "action": "modify",
                      "unifiedDiff": "--- a/pom.xml\\n+++ b/pom.xml\\n@@ -1,1 +1,1 @@\\n-old\\n+new\\n",
                      "description": "Update version"
                    }
                  ]
                }
                """;
        when(fixAssistant.suggestFix(anyString())).thenReturn(aiJson);

        Job<?, ?> job = mock(Job.class);
        when(run.getParent()).thenReturn((Job) job);

        AutoFixResult result = orchestrator.attemptAutoFix(
                run, "error logs", aiProvider,
                "creds-id", null, null, null, null, null,
                List.of("pom.xml"), false, 30, listener, null);

        assertNotEquals(AutoFixStatus.SKIPPED_PATH_NOT_ALLOWED, result.getStatus(),
                "pom.xml matches the allowed glob and must not be rejected");
    }

    // -----------------------------------------------------------------------
    // Early validation — blank credentialsId fails before AI call
    // -----------------------------------------------------------------------

    @Test
    void attemptAutoFix_blankCredentialsId_returnsFailedBeforeAiCall() {
        AutoFixResult result = orchestrator.attemptAutoFix(
                run, "error logs", aiProvider,
                "", null, null, null, null, null,
                Collections.emptyList(), false, 30, listener, null);

        assertEquals(AutoFixStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("autoFixCredentialsId"),
                "Error message must mention the missing field");
        // AI was never called — blank creds detected before AI request
        verify(fixAssistant, never()).suggestFix(anyString());
    }

    // -----------------------------------------------------------------------
    // extractRemoteUrl — explicit remoteUrl bypasses SCM reflection
    // -----------------------------------------------------------------------

    @Test
    void attemptAutoFix_explicitRemoteUrl_bypassesScmExtraction() {
        // fixable=true with a valid pom.xml change; provide an explicit remoteUrl
        // so the code never needs to call run.getParent() for SCM extraction.
        // The test verifies that with no AbstractProject parent, we still get past
        // path guards (SCM extraction is the next step and would fail without remoteUrl).
        String aiJson = """
                {
                  "fixable": true,
                  "explanation": "Config fix",
                  "confidence": "high",
                  "fixType": "config",
                  "changes": [
                    {
                      "filePath": "pom.xml",
                      "action": "modify",
                      "unifiedDiff": "--- a/pom.xml\\n+++ b/pom.xml\\n@@ -1,1 +1,1 @@\\n-old\\n+new\\n",
                      "description": "Update version"
                    }
                  ]
                }
                """;
        when(fixAssistant.suggestFix(anyString())).thenReturn(aiJson);

        // No parent mock — run.getParent() would fail if called for SCM extraction
        // (CredentialsProvider.findCredentialById will return null → FAILED, not SKIPPED)
        AutoFixResult result = orchestrator.attemptAutoFix(
                run, "error logs", aiProvider,
                "creds-id", "https://github.com/org/repo", null, null, null, null,
                List.of("pom.xml"), false, 30, listener, null);

        // Must not be SKIPPED_PATH_NOT_ALLOWED — explicit URL bypassed SCM extraction
        assertNotEquals(AutoFixStatus.SKIPPED_PATH_NOT_ALLOWED, result.getStatus());
        // The result will be FAILED (no credentials in test context) — not a path error
        assertNotEquals(AutoFixStatus.SKIPPED_LOW_CONFIDENCE, result.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractRemoteUrl_workflowJobWithScms_returnsFirstGitRemote() {
        WorkflowJob job = mock(WorkflowJob.class);
        when(run.getParent()).thenReturn((Job) job);
        Collection<? extends SCM> scms = List.of(new FakeGitScm("https://github.com/acme/pipeline-repo.git"));
        doReturn(scms).when(job).getSCMs();

        String remoteUrl = orchestrator.extractRemoteUrl(run);

        assertEquals("https://github.com/acme/pipeline-repo.git", remoteUrl);
    }

    @Test
    void resolveCloudBitbucketBaseUrl_cloudHost_normalizesToCloudApi() {
        assertEquals("https://api.bitbucket.org/2.0",
                orchestrator.resolveCloudBitbucketBaseUrl("https://bitbucket.org"));
        assertEquals("https://api.bitbucket.org/2.0",
                orchestrator.resolveCloudBitbucketBaseUrl("https://api.bitbucket.org"));
    }

    @Test
    void resolveCloudBitbucketBaseUrl_selfHostedUrl_normalizesToCloudApi() {
        // Self-hosted instances configured with scmTypeOverride='bitbucketserver' use a
        // separate code path; the Cloud resolver always returns the Cloud API URL.
        assertEquals("https://api.bitbucket.org/2.0",
                orchestrator.resolveCloudBitbucketBaseUrl("https://bitbucket.internal.example"));
    }

    private static final class FakeGitScm extends NullSCM {

        private final List<FakeRemoteConfig> repositories;

        private FakeGitScm(String remoteUrl) {
            this.repositories = List.of(new FakeRemoteConfig(remoteUrl));
        }

        public List<FakeRemoteConfig> getRepositories() {
            return repositories;
        }
    }

    private static final class FakeRemoteConfig {

        private final String remoteUrl;

        private FakeRemoteConfig(String remoteUrl) {
            this.remoteUrl = remoteUrl;
        }

        public List<String> getURIs() {
            return List.of(remoteUrl);
        }
    }
}
