package io.jenkins.plugins.explain_error.autofix;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.jenkins.plugins.explain_error.autofix.scm.BitbucketApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.BitbucketServerApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.GitHubApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.GitLabApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.ScmApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.ScmClientFactory;
import io.jenkins.plugins.explain_error.autofix.scm.ScmRepo;
import io.jenkins.plugins.explain_error.autofix.scm.ScmType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Error handling shared by the SCM REST clients: non-success responses, retries and interruption.
 */
class ScmApiClientErrorHandlingTest {

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void gitHubFailuresReportTheHttpStatus() {
        denyEverything();
        GitHubApiClient client = new GitHubApiClient(repo(ScmType.GITHUB, "token"));

        assertFailure("GitHub GET", client::getDefaultBranch);
        assertFailure("GitHub getFileContent failed [403]: denied", () -> client.getFileContent("pom.xml", "main"));
        assertFailure("GitHub deleteBranch failed [403]: denied", () -> client.deleteBranch("fix/x"));
        assertFailure("GitHub POST", () -> client.createPullRequest("title", "body", "fix/x", "main", false));

        // Only a 422 (branch exists) is retried with a suffixed name; other errors are rethrown.
        server.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/main")).atPriority(1)
                .willReturn(okJson("{\"object\":{\"sha\":\"base\"}}")));
        assertFailure("GitHub createBranch failed [403]: denied", () -> client.createBranch("fix/x", "main"));
        server.verify(1, postRequestedFor(urlEqualTo("/repos/owner/repo/git/refs")));

        server.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/fix/x")).atPriority(1)
                .willReturn(okJson("{\"object\":{\"sha\":\"head\"}}")));
        server.stubFor(get(urlEqualTo("/repos/owner/repo/git/commits/head")).atPriority(1)
                .willReturn(okJson("{\"tree\":{\"sha\":\"tree\"}}")));
        server.stubFor(post(urlPathMatching("/repos/owner/repo/git/(blobs|trees|commits)")).atPriority(1)
                .willReturn(aResponse().withStatus(201).withBody("{\"sha\":\"new\"}")));
        assertFailure("GitHub PATCH", () -> client.commitFiles("fix/x", "message", Map.of("pom.xml", "content")));
    }

    @Test
    void gitLabFailuresReportTheHttpStatus() {
        denyEverything();
        GitLabApiClient client = new GitLabApiClient(repo(ScmType.GITLAB, "token"));

        assertFailure("GitLab GET", client::getDefaultBranch);

        server.stubFor(get(urlEqualTo("/projects/owner%2Frepo")).atPriority(1).willReturn(okJson("{\"id\":42}")));
        assertFailure("GitLab getFileContent failed [403]: denied", () -> client.getFileContent("pom.xml", "main"));
        // Only a 400 (branch exists) is retried with a suffixed name; other errors are rethrown.
        assertFailure("GitLab createBranch failed [403]: denied", () -> client.createBranch("fix/x", "main"));
        server.verify(1, postRequestedFor(urlEqualTo("/projects/42/repository/branches")));

        server.stubFor(get(urlPathMatching("/projects/42/repository/files/.*")).atPriority(1)
                .willReturn(aResponse().withStatus(404)));
        assertFailure("GitLab commitFiles failed [403]: denied",
                () -> client.commitFiles("fix/x", "message", Map.of("pom.xml", "content")));
    }

    @Test
    void bitbucketCloudFailuresReportTheHttpStatusAndRateLimitsAreRetried() throws Exception {
        denyEverything();
        BitbucketApiClient client = new BitbucketApiClient(repo(ScmType.BITBUCKET, "token"));

        assertFailure("Bitbucket GET", client::getDefaultBranch);
        assertFailure("Bitbucket getFileContent failed [403]: denied", () -> client.getFileContent("pom.xml", "main"));

        server.resetRequests();
        server.stubFor(get(urlEqualTo("/repositories/owner/repo")).atPriority(1)
                .inScenario("rate limit").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429)).willSetStateTo("recovered"));
        server.stubFor(get(urlEqualTo("/repositories/owner/repo")).atPriority(1)
                .inScenario("rate limit").whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"mainbranch\":{\"name\":\"develop\"}}")));

        assertEquals("develop", client.getDefaultBranch());
        server.verify(2, getRequestedFor(urlEqualTo("/repositories/owner/repo")));
    }

    @Test
    void bitbucketServerFailuresAreReported() {
        BitbucketServerApiClient client = new BitbucketServerApiClient(repo(ScmType.BITBUCKET_SERVER, "token"));
        String repoPath = "/projects/owner/repos/repo";

        server.stubFor(get(urlEqualTo(repoPath)).willReturn(aResponse().withStatus(404).withBody("missing")));
        assertFailure("Bitbucket Server: cannot reach repository owner/repo [HTTP 404]: missing",
                client::validateWriteAccess);

        server.stubFor(get(urlEqualTo(repoPath)).willReturn(okJson("{}")));
        server.stubFor(get(urlEqualTo(repoPath + "/permissions/users?limit=1"))
                .willReturn(aResponse().withStatus(401)));
        assertFailure("Bitbucket Server: token is not authenticated", client::validateWriteAccess);

        server.stubFor(get(urlPathMatching(repoPath + "/raw/.*"))
                .willReturn(aResponse().withStatus(403).withBody("denied")));
        assertFailure("Bitbucket Server getFileContent failed [403]: denied",
                () -> client.getFileContent("src/main/pom.xml", "main"));
        server.verify(getRequestedFor(urlPathEqualTo(repoPath + "/raw/src/main/pom.xml")));

        server.stubFor(get(urlPathEqualTo(repoPath + "/commits")).willReturn(okJson("{\"values\":[]}")));
        assertFailure("Bitbucket Server: no commits found on branch 'fix/x'",
                () -> client.commitFiles("fix/x", "message", Map.of("pom.xml", "content")));

        server.stubFor(get(urlPathEqualTo(repoPath + "/commits"))
                .willReturn(okJson("{\"values\":[{\"id\":\"head\"}]}")));
        server.stubFor(put(urlPathMatching(repoPath + "/browse/.*")).willReturn(okJson("{}")));
        assertFailure("Bitbucket Server commitFile: response missing commit ID for 'pom.xml'",
                () -> client.commitFiles("fix/x", "message", Map.of("pom.xml", "content")));
    }

    @Test
    void bitbucketServerUsesBasicAuthenticationForUserPasswordTokens() throws Exception {
        BitbucketServerApiClient client =
                new BitbucketServerApiClient(repo(ScmType.BITBUCKET_SERVER, "jenkins:app-password"));
        server.stubFor(delete(urlEqualTo("/projects/owner/repos/repo/branches"))
                .willReturn(aResponse().withStatus(204)));

        client.deleteBranch("fix/x");

        server.verify(deleteRequestedFor(urlEqualTo("/projects/owner/repos/repo/branches"))
                .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder()
                        .encodeToString("jenkins:app-password".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void interruptingAPendingRequestFailsFastAndKeepsTheInterruptFlag() throws Exception {
        server.stubFor(any(anyUrl()).willReturn(okJson("{}").withFixedDelay(20_000)));

        for (ScmApiClient client : allClients()) {
            IOException e = interruptWhen(Thread.State.WAITING, defaultBranchOrAccessCheck(client));
            assertEquals("HTTP request interrupted", e.getMessage(), client.getClass().getSimpleName());
        }
    }

    @Test
    void interruptingTheRetryBackOffFailsFastAndKeepsTheInterruptFlag() throws Exception {
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(429)));

        for (ScmApiClient client : allClients()) {
            IOException e = interruptWhen(Thread.State.TIMED_WAITING, defaultBranchOrAccessCheck(client));
            assertEquals("Interrupted during retry backoff", e.getMessage(), client.getClass().getSimpleName());
        }
    }

    private List<ScmApiClient> allClients() {
        return List.of(
                ScmClientFactory.create(repo(ScmType.GITHUB, "token")),
                ScmClientFactory.create(repo(ScmType.GITLAB, "token")),
                ScmClientFactory.create(repo(ScmType.BITBUCKET, "token")),
                ScmClientFactory.create(repo(ScmType.BITBUCKET_SERVER, "token")));
    }

    private static Executable defaultBranchOrAccessCheck(ScmApiClient client) {
        // Bitbucket Server resolves the default branch from a different endpoint; its access check is a plain GET.
        return client instanceof BitbucketServerApiClient ? client::validateWriteAccess : client::getDefaultBranch;
    }

    /**
     * Runs {@code call} on a worker thread, interrupts it once it reaches {@code state} (WAITING inside the
     * HTTP call, TIMED_WAITING inside the retry back-off) and returns the resulting exception.
     */
    private static IOException interruptWhen(Thread.State state, Executable call) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptFlagKept = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                call.execute();
            } catch (Throwable t) {
                failure.set(t);
            }
            interruptFlagKept.set(Thread.currentThread().isInterrupted());
        }, "scm-client-interrupt-test");
        worker.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (worker.getState() != state) {
            if (!worker.isAlive() || System.nanoTime() > deadline) {
                fail("worker did not reach " + state + ", failure: " + failure.get());
            }
            Thread.sleep(5);
        }
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(30));

        assertTrue(interruptFlagKept.get(), "the interrupt flag must be restored");
        return assertInstanceOf(IOException.class, failure.get());
    }

    private void denyEverything() {
        server.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(403).withBody("denied")));
    }

    private ScmRepo repo(ScmType type, String token) {
        return new ScmRepo(type, "http://localhost:" + server.port(), "owner", "repo", token);
    }

    private static void assertFailure(String expectedMessagePart, Executable call) {
        IOException e = assertThrows(IOException.class, call);
        assertTrue(e.getMessage().contains(expectedMessagePart), e.getMessage());
    }
}
