package io.jenkins.plugins.explain_error.autofix;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.jenkins.plugins.explain_error.autofix.scm.BitbucketApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.PullRequest;
import io.jenkins.plugins.explain_error.autofix.scm.ScmRepo;
import io.jenkins.plugins.explain_error.autofix.scm.ScmType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class BitbucketApiClientTest {

    private WireMockServer server;
    private BitbucketApiClient client;

    private static final String REPO_PATH = "/2.0/repositories/owner/repo";

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        configureFor("localhost", server.port());

        ScmRepo repo = new ScmRepo(
                ScmType.BITBUCKET,
                "http://localhost:" + server.port() + "/2.0",
                "owner",
                "repo",
                "test-token");
        client = new BitbucketApiClient(repo);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // -----------------------------------------------------------------------
    // getDefaultBranch
    // -----------------------------------------------------------------------

    @Test
    void getDefaultBranch_returnsMainBranchName() throws Exception {
        stubFor(get(urlEqualTo(REPO_PATH))
                .willReturn(okJson("{\"mainbranch\":{\"name\":\"main\"}}")));

        assertEquals("main", client.getDefaultBranch());
    }

    // -----------------------------------------------------------------------
    // validateWriteAccess
    // -----------------------------------------------------------------------

    @Test
    void validateWriteAccess_writePermission_succeeds() {
        stubFor(get(urlEqualTo(REPO_PATH))
                .willReturn(okJson("{\"full_name\":\"owner/repo\"}")));
        stubFor(get(urlPathEqualTo("/2.0/user/permissions/repositories"))
                .willReturn(okJson("{\"values\":[{\"permission\":\"write\",\"repository\":{\"full_name\":\"owner/repo\"}}]}")));

        assertDoesNotThrow(() -> client.validateWriteAccess());
    }

    @Test
    void validateWriteAccess_readOnlyPermission_throwsIOException() {
        stubFor(get(urlEqualTo(REPO_PATH))
                .willReturn(okJson("{\"full_name\":\"owner/repo\"}")));
        stubFor(get(urlPathEqualTo("/2.0/user/permissions/repositories"))
                .willReturn(okJson("{\"values\":[{\"permission\":\"read\",\"repository\":{\"full_name\":\"owner/repo\"}}]}")));

        assertThrows(Exception.class, () -> client.validateWriteAccess());
    }

    @Test
    void validateWriteAccess_repoGet401_throwsIOException() {
        stubFor(get(urlEqualTo(REPO_PATH))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"Unauthorized\"}}")));

        assertThrows(Exception.class, () -> client.validateWriteAccess());
    }

    @Test
    void validateWriteAccess_adminPermission_succeeds() {
        stubFor(get(urlEqualTo(REPO_PATH))
                .willReturn(okJson("{\"full_name\":\"owner/repo\"}")));
        stubFor(get(urlPathEqualTo("/2.0/user/permissions/repositories"))
                .willReturn(okJson("{\"values\":[{\"permission\":\"admin\",\"repository\":{\"full_name\":\"owner/repo\"}}]}")));

        assertDoesNotThrow(() -> client.validateWriteAccess());
    }

    // -----------------------------------------------------------------------
    // createBranch
    // -----------------------------------------------------------------------

    @Test
    void createBranch_success() {
        stubFor(get(urlEqualTo(REPO_PATH + "/refs/branches/main"))
                .willReturn(okJson("{\"name\":\"main\",\"target\":{\"hash\":\"abc123def456\"}}")));
        stubFor(post(urlEqualTo(REPO_PATH + "/refs/branches"))
                .willReturn(aResponse().withStatus(201).withBody("{\"name\":\"fix/test\"}")));

        assertDoesNotThrow(() -> client.createBranch("fix/test", "main"));
    }

    @Test
    void createBranch_postReturns422_throwsIOException() {
        stubFor(get(urlEqualTo(REPO_PATH + "/refs/branches/main"))
                .willReturn(okJson("{\"name\":\"main\",\"target\":{\"hash\":\"abc123def456\"}}")));
        stubFor(post(urlEqualTo(REPO_PATH + "/refs/branches"))
                .willReturn(aResponse().withStatus(422).withBody("{\"error\":{\"message\":\"Branch already exists\"}}")));

        assertThrows(Exception.class, () -> client.createBranch("fix/test", "main"));
    }

    // -----------------------------------------------------------------------
    // getFileContent
    // -----------------------------------------------------------------------

    @Test
    void getFileContent_found_returnsContent() throws Exception {
        stubFor(get(urlEqualTo(REPO_PATH + "/src/main/pom.xml"))
                .willReturn(aResponse().withStatus(200).withBody("content")));

        assertEquals("content", client.getFileContent("pom.xml", "main"));
    }

    @Test
    void getFileContent_notFound_returnsNull() throws Exception {
        stubFor(get(urlEqualTo(REPO_PATH + "/src/main/missing.txt"))
                .willReturn(aResponse().withStatus(404).withBody("{\"error\":{\"message\":\"Not Found\"}}")));

        assertNull(client.getFileContent("missing.txt", "main"));
    }

    // -----------------------------------------------------------------------
    // commitFiles
    // -----------------------------------------------------------------------

    @Test
    void commitFiles_success() {
        stubFor(post(urlEqualTo(REPO_PATH + "/src"))
                .willReturn(aResponse().withStatus(201).withBody("")));

        assertDoesNotThrow(() ->
                client.commitFiles("fix-branch", "fix: apply patch", Map.of("pom.xml", "<project/>")));
    }

    @Test
    void commitFiles_returns400_throwsIOException() {
        stubFor(post(urlEqualTo(REPO_PATH + "/src"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":{\"message\":\"Bad Request\"}}")));

        assertThrows(Exception.class, () ->
                client.commitFiles("fix-branch", "fix: apply patch", Map.of("pom.xml", "<project/>")));
    }

    // -----------------------------------------------------------------------
    // createPullRequest
    // -----------------------------------------------------------------------

    @Test
    void createPullRequest_success_returnsPullRequest() throws Exception {
        stubFor(post(urlEqualTo(REPO_PATH + "/pullrequests"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"id\":7,\"links\":{\"html\":{\"href\":\"https://bitbucket.org/owner/repo/pull-requests/7\"}}}")));

        PullRequest pr = client.createPullRequest("fix: auto-fix", "body text", "fix-branch", "main", false);
        assertEquals(7, pr.number());
        assertEquals("https://bitbucket.org/owner/repo/pull-requests/7", pr.url());
    }

    @Test
    void createPullRequest_returns422_throwsIOException() {
        stubFor(post(urlEqualTo(REPO_PATH + "/pullrequests"))
                .willReturn(aResponse().withStatus(422).withBody("{\"error\":{\"message\":\"Unprocessable Entity\"}}")));

        assertThrows(Exception.class, () ->
                client.createPullRequest("fix: auto-fix", "body text", "fix-branch", "main", false));
    }

    // -----------------------------------------------------------------------
    // deleteBranch
    // -----------------------------------------------------------------------

    @Test
    void deleteBranch_success() {
        stubFor(delete(urlEqualTo(REPO_PATH + "/refs/branches/fix-branch"))
                .willReturn(aResponse().withStatus(204)));

        assertDoesNotThrow(() -> client.deleteBranch("fix-branch"));
    }

    @Test
    void deleteBranch_returns404_throwsIOException() {
        stubFor(delete(urlEqualTo(REPO_PATH + "/refs/branches/fix-branch"))
                .willReturn(aResponse().withStatus(404).withBody("{\"error\":{\"message\":\"Branch not found\"}}")));

        assertThrows(Exception.class, () -> client.deleteBranch("fix-branch"));
    }
}
