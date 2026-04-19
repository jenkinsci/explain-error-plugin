package io.jenkins.plugins.explain_error.autofix;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.jenkins.plugins.explain_error.autofix.scm.BitbucketServerApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.PullRequest;
import io.jenkins.plugins.explain_error.autofix.scm.ScmRepo;
import io.jenkins.plugins.explain_error.autofix.scm.ScmType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class BitbucketServerApiClientTest {

    private WireMockServer server;
    private BitbucketServerApiClient client;

    /** /rest/api/1.0/projects/PROJ/repos/my-repo */
    private static final String REPO_PATH = "/rest/api/1.0/projects/PROJ/repos/my-repo";

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        configureFor("localhost", server.port());

        ScmRepo repo = new ScmRepo(
                ScmType.BITBUCKET_SERVER,
                "http://localhost:" + server.port() + "/rest/api/1.0",
                "PROJ",
                "my-repo",
                "test-token");
        client = new BitbucketServerApiClient(repo);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // -----------------------------------------------------------------------
    // getDefaultBranch
    // -----------------------------------------------------------------------

    @Test
    void getDefaultBranch_returnsDisplayId() throws Exception {
        stubFor(get(urlEqualTo(REPO_PATH + "/branches/default"))
                .willReturn(okJson("{\"displayId\":\"main\",\"id\":\"refs/heads/main\"}")));

        assertEquals("main", client.getDefaultBranch());
    }

    @Test
    void getDefaultBranch_notFound_throwsIOException() {
        stubFor(get(urlEqualTo(REPO_PATH + "/branches/default"))
                .willReturn(aResponse().withStatus(404).withBody("{\"errors\":[]}")));

        assertThrows(Exception.class, () -> client.getDefaultBranch());
    }

    // -----------------------------------------------------------------------
    // validateWriteAccess
    // -----------------------------------------------------------------------

    @Test
    void validateWriteAccess_returns200_succeeds() {
        stubFor(get(urlEqualTo(REPO_PATH))
                .willReturn(okJson("{\"slug\":\"my-repo\",\"project\":{\"key\":\"PROJ\"}}")));
        stubFor(get(urlEqualTo(REPO_PATH + "/permissions/users?limit=1"))
                .willReturn(okJson("{\"values\":[]}")));

        assertDoesNotThrow(() -> client.validateWriteAccess());
    }

    @Test
    void validateWriteAccess_repoGet401_throwsIOException() {
        stubFor(get(urlEqualTo(REPO_PATH))
                .willReturn(aResponse().withStatus(401).withBody("{\"errors\":[]}")));

        assertThrows(Exception.class, () -> client.validateWriteAccess());
    }

    @Test
    void validateWriteAccess_repoGet403_throwsIOException() {
        stubFor(get(urlEqualTo(REPO_PATH))
                .willReturn(aResponse().withStatus(403).withBody("{\"errors\":[]}")));

        assertThrows(Exception.class, () -> client.validateWriteAccess());
    }

    // -----------------------------------------------------------------------
    // createBranch
    // -----------------------------------------------------------------------

    @Test
    void createBranch_success() {
        stubFor(post(urlEqualTo(REPO_PATH + "/branches"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"displayId\":\"fix/test\",\"id\":\"refs/heads/fix/test\"}")));

        assertDoesNotThrow(() -> client.createBranch("fix/test", "main"));
    }

    @Test
    void createBranch_conflict_throwsIOException() {
        stubFor(post(urlEqualTo(REPO_PATH + "/branches"))
                .willReturn(aResponse().withStatus(409).withBody("{\"errors\":[{\"message\":\"Branch already exists\"}]}")));

        assertThrows(Exception.class, () -> client.createBranch("fix/test", "main"));
    }

    // -----------------------------------------------------------------------
    // getFileContent
    // -----------------------------------------------------------------------

    @Test
    void getFileContent_found_returnsContent() throws Exception {
        stubFor(get(urlPathEqualTo(REPO_PATH + "/raw/pom.xml"))
                .willReturn(aResponse().withStatus(200).withBody("<project/>")));

        assertEquals("<project/>", client.getFileContent("pom.xml", "main"));
    }

    @Test
    void getFileContent_notFound_returnsNull() throws Exception {
        stubFor(get(urlPathEqualTo(REPO_PATH + "/raw/missing.txt"))
                .willReturn(aResponse().withStatus(404).withBody("{\"errors\":[]}")));

        assertNull(client.getFileContent("missing.txt", "main"));
    }

    // -----------------------------------------------------------------------
    // commitFiles
    // -----------------------------------------------------------------------

    @Test
    void commitFiles_singleFile_success() throws Exception {
        // First: get branch HEAD commit
        stubFor(get(urlPathEqualTo(REPO_PATH + "/commits"))
                .willReturn(okJson("{\"values\":[{\"id\":\"abc123\"}]}")));
        // Then: PUT the file
        stubFor(put(urlEqualTo(REPO_PATH + "/browse/pom.xml"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"def456\"}")));

        assertDoesNotThrow(() ->
                client.commitFiles("fix-branch", "fix: apply patch", Map.of("pom.xml", "<project/>")));
    }

    @Test
    void commitFiles_multipleFiles_chainsCommitIds() throws Exception {
        stubFor(get(urlPathEqualTo(REPO_PATH + "/commits"))
                .willReturn(okJson("{\"values\":[{\"id\":\"sha0\"}]}")));
        stubFor(put(urlEqualTo(REPO_PATH + "/browse/file1.txt"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"sha1\"}")));
        stubFor(put(urlEqualTo(REPO_PATH + "/browse/file2.txt"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"sha2\"}")));

        // Use LinkedHashMap to control iteration order
        Map<String, String> files = new LinkedHashMap<>();
        files.put("file1.txt", "content1");
        files.put("file2.txt", "content2");

        assertDoesNotThrow(() -> client.commitFiles("fix-branch", "fix: apply patch", files));

        // Both files must have been committed
        verify(putRequestedFor(urlEqualTo(REPO_PATH + "/browse/file1.txt")));
        verify(putRequestedFor(urlEqualTo(REPO_PATH + "/browse/file2.txt")));
    }

    @Test
    void commitFiles_putFails_throwsIOException() {
        stubFor(get(urlPathEqualTo(REPO_PATH + "/commits"))
                .willReturn(okJson("{\"values\":[{\"id\":\"abc123\"}]}")));
        stubFor(put(urlEqualTo(REPO_PATH + "/browse/pom.xml"))
                .willReturn(aResponse().withStatus(400).withBody("{\"errors\":[{\"message\":\"Bad request\"}]}")));

        assertThrows(Exception.class, () ->
                client.commitFiles("fix-branch", "fix: apply patch", Map.of("pom.xml", "<project/>")));
    }

    // -----------------------------------------------------------------------
    // createPullRequest
    // -----------------------------------------------------------------------

    @Test
    void createPullRequest_success_returnsPullRequest() throws Exception {
        String responseBody = "{\"id\":42,\"links\":{\"self\":[{\"href\":"
                + "\"https://bitbucket.company.com/projects/PROJ/repos/my-repo/pull-requests/42\"}]}}";
        stubFor(post(urlEqualTo(REPO_PATH + "/pull-requests"))
                .willReturn(aResponse().withStatus(201).withBody(responseBody)));

        PullRequest pr = client.createPullRequest("fix: auto-fix", "description", "fix-branch", "main", false);
        assertEquals(42, pr.number());
        assertEquals("https://bitbucket.company.com/projects/PROJ/repos/my-repo/pull-requests/42", pr.url());
        assertEquals("fix-branch", pr.headBranch());
        assertEquals("main", pr.baseBranch());
    }

    @Test
    void createPullRequest_draftFlagIgnored_stillCreatesRegularPr() throws Exception {
        String responseBody = "{\"id\":5,\"links\":{\"self\":[{\"href\":"
                + "\"https://bitbucket.company.com/projects/PROJ/repos/my-repo/pull-requests/5\"}]}}";
        stubFor(post(urlEqualTo(REPO_PATH + "/pull-requests"))
                .willReturn(aResponse().withStatus(201).withBody(responseBody)));

        // draft=true should be ignored (Bitbucket Server has no draft PR support)
        PullRequest pr = client.createPullRequest("fix", "desc", "fix-branch", "main", true);
        assertEquals(5, pr.number());
    }

    @Test
    void createPullRequest_returns409_throwsIOException() {
        stubFor(post(urlEqualTo(REPO_PATH + "/pull-requests"))
                .willReturn(aResponse().withStatus(409)
                        .withBody("{\"errors\":[{\"message\":\"PR already exists\"}]}")));

        assertThrows(Exception.class, () ->
                client.createPullRequest("fix", "desc", "fix-branch", "main", false));
    }

    // -----------------------------------------------------------------------
    // deleteBranch
    // -----------------------------------------------------------------------

    @Test
    void deleteBranch_success() {
        stubFor(delete(urlEqualTo(REPO_PATH + "/branches"))
                .willReturn(aResponse().withStatus(204)));

        assertDoesNotThrow(() -> client.deleteBranch("fix-branch"));
    }

    @Test
    void deleteBranch_returns404_throwsIOException() {
        stubFor(delete(urlEqualTo(REPO_PATH + "/branches"))
                .willReturn(aResponse().withStatus(404).withBody("{\"errors\":[]}")));

        assertThrows(Exception.class, () -> client.deleteBranch("fix-branch"));
    }

    // -----------------------------------------------------------------------
    // Retry on 429 / 5xx
    // -----------------------------------------------------------------------

    @Test
    void getDefaultBranch_retryOn429_succeeds() throws Exception {
        stubFor(get(urlEqualTo(REPO_PATH + "/branches/default"))
                .inScenario("Retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("Retry1"));
        stubFor(get(urlEqualTo(REPO_PATH + "/branches/default"))
                .inScenario("Retry")
                .whenScenarioStateIs("Retry1")
                .willReturn(okJson("{\"displayId\":\"main\"}")));

        assertEquals("main", client.getDefaultBranch());
    }

    @Test
    void getDefaultBranch_retryOn503_succeeds() throws Exception {
        stubFor(get(urlEqualTo(REPO_PATH + "/branches/default"))
                .inScenario("Retry503")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable"))
                .willSetStateTo("Ok"));
        stubFor(get(urlEqualTo(REPO_PATH + "/branches/default"))
                .inScenario("Retry503")
                .whenScenarioStateIs("Ok")
                .willReturn(okJson("{\"displayId\":\"develop\"}")));

        assertEquals("develop", client.getDefaultBranch());
    }
}
