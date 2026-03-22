package io.jenkins.plugins.explain_error.autofix;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.jenkins.plugins.explain_error.autofix.scm.GitLabApiClient;
import io.jenkins.plugins.explain_error.autofix.scm.PullRequest;
import io.jenkins.plugins.explain_error.autofix.scm.ScmRepo;
import io.jenkins.plugins.explain_error.autofix.scm.ScmType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class GitLabApiClientTest {

    private WireMockServer server;
    private GitLabApiClient client;

    // The URL-encoded project path "owner/repo" → "owner%2Frepo"
    private static final String ENCODED_PATH = "owner%2Frepo";
    // Numeric project ID returned by the project-lookup stub
    private static final String PROJECT_ID = "999";

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        configureFor("localhost", server.port());

        ScmRepo repo = new ScmRepo(
                ScmType.GITLAB,
                "http://localhost:" + server.port() + "/api/v4",
                "owner",
                "repo",
                "test-token");
        client = new GitLabApiClient(repo);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // -----------------------------------------------------------------------
    // Shared helper: stub the project-path lookup that returns the numeric ID.
    // Many operations call projectIdUrl() which first fetches the project by
    // namespace path, caches the ID, then uses /projects/:id for mutations.
    // -----------------------------------------------------------------------

    private void stubProjectLookup() {
        stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PATH))
                .willReturn(okJson(
                        "{\"id\": " + PROJECT_ID + ", \"default_branch\": \"main\"}")));
    }

    // -----------------------------------------------------------------------
    // getDefaultBranch
    // -----------------------------------------------------------------------

    @Test
    void getDefaultBranch_success() throws Exception {
        stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PATH))
                .willReturn(okJson(
                        "{\"id\": " + PROJECT_ID + ", \"default_branch\": \"develop\"}")));

        assertEquals("develop", client.getDefaultBranch());
    }

    // -----------------------------------------------------------------------
    // validateWriteAccess
    // -----------------------------------------------------------------------

    @Test
    void validateWriteAccess_developerAccess_succeeds() {
        // project_access.access_level >= 30 (Developer)
        stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PATH + "?simple=true"))
                .willReturn(okJson(
                        "{\"id\": " + PROJECT_ID + ", \"permissions\": {\"project_access\": {\"access_level\": 30}, \"group_access\": {\"access_level\": 0}}}")));

        assertDoesNotThrow(() -> client.validateWriteAccess());
    }

    @Test
    void validateWriteAccess_reporterAccess_throws() {
        // project_access.access_level < 30 (Reporter) and group_access also low
        stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PATH + "?simple=true"))
                .willReturn(okJson(
                        "{\"id\": " + PROJECT_ID + ", \"permissions\": {\"project_access\": {\"access_level\": 20}, \"group_access\": {\"access_level\": 0}}}")));

        assertThrows(Exception.class, () -> client.validateWriteAccess());
    }

    @Test
    void validateWriteAccess_groupDeveloperAccess_succeeds() {
        // project_access is low but group_access.access_level >= 30 is sufficient
        stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PATH + "?simple=true"))
                .willReturn(okJson(
                        "{\"id\": " + PROJECT_ID + ", \"permissions\": {\"project_access\": {\"access_level\": 0}, \"group_access\": {\"access_level\": 40}}}")));

        assertDoesNotThrow(() -> client.validateWriteAccess());
    }

    // -----------------------------------------------------------------------
    // createBranch
    // -----------------------------------------------------------------------

    @Test
    void createBranch_success() throws Exception {
        stubProjectLookup();
        stubFor(post(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/branches"))
                .willReturn(aResponse().withStatus(201).withBody("{\"name\": \"fix/test\"}")));

        assertDoesNotThrow(() -> client.createBranch("fix/test", "main"));
    }

    @Test
    void createBranch_collision_retriesWithSuffix() throws Exception {
        stubProjectLookup();

        // First POST returns 400 (branch exists); second succeeds on a suffixed name
        stubFor(post(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/branches"))
                .inScenario("branchCollision")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(400).withBody("{\"message\": \"Branch already exists\"}"))
                .willSetStateTo("retry"));
        stubFor(post(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/branches"))
                .inScenario("branchCollision")
                .whenScenarioStateIs("retry")
                .willReturn(aResponse().withStatus(201).withBody("{\"name\": \"fix/test-ab12\"}")));

        assertDoesNotThrow(() -> client.createBranch("fix/test", "main"));
    }

    // -----------------------------------------------------------------------
    // commitFiles (atomic via GitLab Commits API)
    // -----------------------------------------------------------------------

    @Test
    void commitFiles_atomic_success() throws Exception {
        stubProjectLookup();

        // getFileContent is called per file to decide "update" vs "create" action
        String encoded = Base64.getEncoder().encodeToString("old content\n".getBytes());
        stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/files/pom.xml"))
                .willReturn(okJson("{\"content\": \"" + encoded + "\"}")));

        // POST to the commits endpoint
        stubFor(post(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/commits"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"id\": \"abc123def456\", \"short_id\": \"abc123\"}")));

        assertDoesNotThrow(() ->
                client.commitFiles("fix-branch", "fix: apply patch", Map.of("pom.xml", "<project/>")));
    }

    @Test
    void commitFiles_newFile_usesCreateAction() throws Exception {
        stubProjectLookup();

        // File does not exist on the branch → getFileContent returns 404
        stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/files/new-file.txt"))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\": \"404 File Not Found\"}")));

        stubFor(post(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/commits"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"id\": \"deadbeef\", \"short_id\": \"deadbeef\"}")));

        assertDoesNotThrow(() ->
                client.commitFiles("fix-branch", "feat: add new file", Map.of("new-file.txt", "hello\n")));
    }

    // -----------------------------------------------------------------------
    // createPullRequest (Merge Request)
    // -----------------------------------------------------------------------

    @Test
    void createPullRequest_success() throws Exception {
        stubProjectLookup();

        stubFor(post(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"iid\": 7, \"web_url\": \"https://gitlab.com/owner/repo/-/merge_requests/7\"}")));

        PullRequest pr = client.createPullRequest("fix: auto-fix", "body text", "fix-branch", "main", false);
        assertEquals(7, pr.number());
        assertEquals("https://gitlab.com/owner/repo/-/merge_requests/7", pr.url());
    }

    @Test
    void createPullRequest_draft() throws Exception {
        stubProjectLookup();

        stubFor(post(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"iid\": 12, \"web_url\": \"https://gitlab.com/owner/repo/-/merge_requests/12\"}")));

        PullRequest pr = client.createPullRequest("Draft: fix", "body", "fix-branch", "main", true);
        assertEquals(12, pr.number());
    }

    @Test
    void createPullRequest_serverError_throws() {
        stubProjectLookup();

        stubFor(post(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests"))
                .willReturn(aResponse().withStatus(500).withBody("{\"message\": \"Internal Server Error\"}")));

        assertThrows(Exception.class, () ->
                client.createPullRequest("fix: test", "body", "fix-branch", "main", false));
    }

    // -----------------------------------------------------------------------
    // deleteBranch
    // -----------------------------------------------------------------------

    @Test
    void deleteBranch_success() throws Exception {
        stubProjectLookup();
        stubFor(delete(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/branches/fix-branch"))
                .willReturn(aResponse().withStatus(204)));

        assertDoesNotThrow(() -> client.deleteBranch("fix-branch"));
    }

    @Test
    void deleteBranch_alreadyGone_throws() {
        stubProjectLookup();
        // GitLabApiClient treats non-200/204 as an error (unlike GitHub client's 422 leniency)
        stubFor(delete(urlEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/branches/fix-branch"))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\": \"404 Branch Not Found\"}")));

        assertThrows(Exception.class, () -> client.deleteBranch("fix-branch"));
    }

    // -----------------------------------------------------------------------
    // getFileContent
    // -----------------------------------------------------------------------

    @Test
    void getFileContent_found() throws Exception {
        stubProjectLookup();

        String encoded = Base64.getEncoder().encodeToString("hello gitlab\n".getBytes());
        stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/files/pom.xml"))
                .willReturn(okJson("{\"content\": \"" + encoded + "\"}")));

        String content = client.getFileContent("pom.xml", "main");
        assertEquals("hello gitlab\n", content);
    }

    @Test
    void getFileContent_notFound_returnsNull() throws Exception {
        stubProjectLookup();

        stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/files/missing.txt"))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\": \"404 File Not Found\"}")));

        assertNull(client.getFileContent("missing.txt", "main"));
    }
}
