package io.jenkins.plugins.explain_error.autofix;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.jenkins.plugins.explain_error.autofix.scm.GitHubApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.PullRequest;
import io.jenkins.plugins.explain_error.autofix.scm.ScmRepo;
import io.jenkins.plugins.explain_error.autofix.scm.ScmType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubApiClientTest {

    private WireMockServer server;
    private GitHubApiClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        configureFor("localhost", server.port());
        ScmRepo repo = new ScmRepo(
                ScmType.GITHUB,
                "http://localhost:" + server.port(),
                "owner",
                "repo",
                "test-token");
        client = new GitHubApiClient(repo);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // -----------------------------------------------------------------------
    // getDefaultBranch
    // -----------------------------------------------------------------------

    @Test
    void getDefaultBranch_success() throws Exception {
        stubFor(get(urlEqualTo("/repos/owner/repo"))
                .willReturn(okJson("{\"default_branch\": \"main\", \"permissions\": {\"push\": true}}")));

        assertEquals("main", client.getDefaultBranch());
    }

    // -----------------------------------------------------------------------
    // validateWriteAccess
    // -----------------------------------------------------------------------

    @Test
    void validateWriteAccess_sufficient() {
        stubFor(get(urlEqualTo("/repos/owner/repo"))
                .willReturn(okJson("{\"default_branch\": \"main\", \"permissions\": {\"push\": true}}")));

        assertDoesNotThrow(() -> client.validateWriteAccess());
    }

    @Test
    void validateWriteAccess_insufficient() {
        stubFor(get(urlEqualTo("/repos/owner/repo"))
                .willReturn(okJson("{\"default_branch\": \"main\", \"permissions\": {\"push\": false}}")));

        assertThrows(Exception.class, () -> client.validateWriteAccess());
    }

    // -----------------------------------------------------------------------
    // createBranch
    // -----------------------------------------------------------------------

    @Test
    void createBranch_success() throws Exception {
        stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/main"))
                .willReturn(okJson("{\"object\": {\"sha\": \"abc123\"}}")));
        stubFor(post(urlEqualTo("/repos/owner/repo/git/refs"))
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        assertDoesNotThrow(() -> client.createBranch("fix/test", "main"));
    }

    @Test
    void createBranch_collision_retries() throws Exception {
        stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/main"))
                .willReturn(okJson("{\"object\": {\"sha\": \"abc123\"}}")));

        // First POST returns 422 (collision); second POST succeeds
        stubFor(post(urlEqualTo("/repos/owner/repo/git/refs"))
                .inScenario("collision")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(422).withBody("{\"message\": \"Reference already exists\"}"))
                .willSetStateTo("retry"));
        stubFor(post(urlEqualTo("/repos/owner/repo/git/refs"))
                .inScenario("collision")
                .whenScenarioStateIs("retry")
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        assertDoesNotThrow(() -> client.createBranch("fix/test", "main"));
    }

    // -----------------------------------------------------------------------
    // commitFiles (atomic via Trees API)
    // -----------------------------------------------------------------------

    @Test
    void commitFiles_atomic_success() throws Exception {
        stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/fix-branch"))
                .willReturn(okJson("{\"object\": {\"sha\": \"commit123\"}}")));
        stubFor(get(urlEqualTo("/repos/owner/repo/git/commits/commit123"))
                .willReturn(okJson("{\"tree\": {\"sha\": \"tree123\"}}")));
        stubFor(post(urlEqualTo("/repos/owner/repo/git/blobs"))
                .willReturn(aResponse().withStatus(201).withBody("{\"sha\": \"blob123\"}")));
        stubFor(post(urlEqualTo("/repos/owner/repo/git/trees"))
                .willReturn(aResponse().withStatus(201).withBody("{\"sha\": \"newtree123\"}")));
        stubFor(post(urlEqualTo("/repos/owner/repo/git/commits"))
                .willReturn(aResponse().withStatus(201).withBody("{\"sha\": \"newcommit123\"}")));
        stubFor(patch(urlEqualTo("/repos/owner/repo/git/refs/heads/fix-branch"))
                .willReturn(okJson("{}")));

        assertDoesNotThrow(() -> client.commitFiles("fix-branch", "fix: test commit", Map.of("pom.xml", "<project/>")));
    }

    // -----------------------------------------------------------------------
    // Rate-limit retry
    // -----------------------------------------------------------------------

    @Test
    void rateLimitRetry() throws Exception {
        // First call returns 429, second call succeeds
        stubFor(get(urlEqualTo("/repos/owner/repo"))
                .inScenario("rateLimit")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("retry1"));
        stubFor(get(urlEqualTo("/repos/owner/repo"))
                .inScenario("rateLimit")
                .whenScenarioStateIs("retry1")
                .willReturn(okJson("{\"default_branch\": \"main\", \"permissions\": {\"push\": true}}")));

        assertEquals("main", client.getDefaultBranch());
    }

    // -----------------------------------------------------------------------
    // deleteBranch
    // -----------------------------------------------------------------------

    @Test
    void deleteBranch_success() throws Exception {
        stubFor(delete(urlEqualTo("/repos/owner/repo/git/refs/heads/fix-branch"))
                .willReturn(aResponse().withStatus(204)));

        assertDoesNotThrow(() -> client.deleteBranch("fix-branch"));
    }

    @Test
    void deleteBranch_alreadyGone_noThrow() throws Exception {
        // 422 is treated as "already deleted / not found" by the client
        stubFor(delete(urlEqualTo("/repos/owner/repo/git/refs/heads/fix-branch"))
                .willReturn(aResponse().withStatus(422).withBody("{\"message\": \"Reference does not exist\"}")));

        assertDoesNotThrow(() -> client.deleteBranch("fix-branch"));
    }

    // -----------------------------------------------------------------------
    // createPullRequest
    // -----------------------------------------------------------------------

    @Test
    void createPullRequest_success() throws Exception {
        stubFor(post(urlEqualTo("/repos/owner/repo/pulls"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"number\": 42, \"html_url\": \"https://github.com/owner/repo/pull/42\"}")));

        PullRequest pr = client.createPullRequest("fix: test", "body", "fix-branch", "main", false);
        assertEquals(42, pr.number());
        assertEquals("https://github.com/owner/repo/pull/42", pr.url());
    }

    @Test
    void createPullRequest_draft() throws Exception {
        stubFor(post(urlEqualTo("/repos/owner/repo/pulls"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"number\": 7, \"html_url\": \"https://github.com/owner/repo/pull/7\"}")));

        PullRequest pr = client.createPullRequest("draft: test", "body", "fix-branch", "main", true);
        assertEquals(7, pr.number());
    }

    // -----------------------------------------------------------------------
    // getFileContent
    // -----------------------------------------------------------------------

    @Test
    void getFileContent_found() throws Exception {
        // Base64 encoding of "hello world\n"
        String encoded = java.util.Base64.getEncoder().encodeToString("hello world\n".getBytes());
        stubFor(get(urlPathEqualTo("/repos/owner/repo/contents/pom.xml"))
                .willReturn(okJson("{\"content\": \"" + encoded + "\"}")));

        String content = client.getFileContent("pom.xml", "main");
        assertEquals("hello world\n", content);
    }

    @Test
    void getFileContent_notFound_returnsNull() throws Exception {
        stubFor(get(urlPathEqualTo("/repos/owner/repo/contents/missing.txt"))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\": \"Not Found\"}")));

        assertNull(client.getFileContent("missing.txt", "main"));
    }
}
