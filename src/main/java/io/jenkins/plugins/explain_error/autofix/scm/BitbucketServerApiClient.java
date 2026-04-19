package io.jenkins.plugins.explain_error.autofix.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SCM API client for Bitbucket Server (Data Center) REST API 1.0.
 *
 * <p>Authenticates with an HTTP access token (Bearer token) which is available
 * since Bitbucket Server 5.5. For older instances, basic-auth credentials
 * are also accepted when the token is in {@code username:password} format.
 *
 * <p>Base URL format: {@code https://bitbucket.company.com/rest/api/1.0}
 * <br>Repository path: {@code /projects/{PROJECT}/repos/{repo}}
 */
public class BitbucketServerApiClient implements ScmApiClient {

    private static final Logger LOGGER = Logger.getLogger(BitbucketServerApiClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScmRepo repo;
    private final HttpClient client;

    public BitbucketServerApiClient(ScmRepo repo) {
        this.repo = repo;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -----------------------------------------------------------------------
    // ScmApiClient implementation
    // -----------------------------------------------------------------------

    @Override
    public String getDefaultBranch() throws IOException {
        JsonNode node = getJson(repoUrl() + "/branches/default");
        return node.path("displayId").asText();
    }

    @Override
    public void validateWriteAccess() throws IOException {
        HttpRequest request = baseRequest(repoUrl()).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException(
                    "Bitbucket Server: token does not have access to "
                            + repo.owner() + "/" + repo.repoName()
                            + " [HTTP " + response.statusCode() + "]");
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Bitbucket Server: cannot reach repository "
                            + repo.owner() + "/" + repo.repoName()
                            + " [HTTP " + response.statusCode() + "]: " + response.body());
        }

        // Confirm write permission via the repository permissions endpoint
        String permUrl = repoUrl() + "/permissions/users?limit=1";
        HttpRequest permRequest = baseRequest(permUrl).GET().build();
        HttpResponse<String> permResponse = sendWithRetry(permRequest, 3);
        // 403 means the token does not have REPO_ADMIN — that is acceptable.
        // 200 means we have at least admin access. Either way, we do not fail here;
        // only a 401 (no credentials at all) indicates we cannot write.
        if (permResponse.statusCode() == 401) {
            throw new IOException(
                    "Bitbucket Server: token is not authenticated. "
                            + "Provide an HTTP access token with REPO_WRITE permission.");
        }
    }

    @Override
    public void createBranch(String branchName, String fromBranch) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", branchName);
        body.put("startPoint", "refs/heads/" + fromBranch);

        HttpRequest request = baseRequest(repoUrl() + "/branches")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "Bitbucket Server createBranch failed [" + response.statusCode() + "]: "
                            + response.body());
        }
    }

    @Override
    public String getFileContent(String filePath, String branch) throws IOException {
        String encodedPath = encodePathSegments(filePath);
        String url = repoUrl() + "/raw/" + encodedPath
                + "?at=" + URLEncoder.encode("refs/heads/" + branch, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Bitbucket Server getFileContent failed [" + response.statusCode() + "]: "
                            + response.body());
        }
        return response.body();
    }

    /**
     * Commits multiple files to a branch on Bitbucket Server.
     *
     * <p>Bitbucket Server does not have an atomic multi-file commit REST API, so files
     * are committed sequentially. Each commit uses the previous commit's ID as
     * {@code sourceCommitId} to preserve history and detect concurrent edits.
     */
    @Override
    public void commitFiles(String branchName, String commitMessage, Map<String, String> fileContents)
            throws IOException {
        // Seed with the current branch HEAD so the first file commit has a valid parent.
        String currentCommitId = getBranchHead(branchName);

        int index = 0;
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String filePath = entry.getKey();
            String newContent = entry.getValue();

            // Include file index in commit message only when multiple files are changed.
            String msg = fileContents.size() > 1
                    ? commitMessage + " [" + (index + 1) + "/" + fileContents.size() + "]"
                    : commitMessage;

            currentCommitId = commitSingleFile(branchName, filePath, newContent, msg, currentCommitId);
            LOGGER.fine("Committed " + filePath + " → " + currentCommitId);
            index++;
        }
    }

    @Override
    public PullRequest createPullRequest(
            String title, String body, String headBranch, String baseBranch, boolean draft)
            throws IOException {
        // Bitbucket Server does not support draft PRs natively — the param is ignored.
        ObjectNode prBody = MAPPER.createObjectNode();
        prBody.put("title", title);
        prBody.put("description", body);

        ObjectNode fromRef = prBody.putObject("fromRef");
        fromRef.put("id", "refs/heads/" + headBranch);
        ObjectNode fromRepo = fromRef.putObject("repository");
        fromRepo.put("slug", repo.repoName());
        fromRepo.putObject("project").put("key", repo.owner());

        ObjectNode toRef = prBody.putObject("toRef");
        toRef.put("id", "refs/heads/" + baseBranch);
        ObjectNode toRepo = toRef.putObject("repository");
        toRepo.put("slug", repo.repoName());
        toRepo.putObject("project").put("key", repo.owner());

        ArrayNode reviewers = prBody.putArray("reviewers");
        reviewers.addObject(); // empty reviewers array is required by the API

        HttpRequest request = baseRequest(repoUrl() + "/pull-requests")
                .POST(jsonBody(prBody))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "Bitbucket Server createPullRequest failed [" + response.statusCode() + "]: "
                            + response.body());
        }
        JsonNode node = MAPPER.readTree(response.body());
        int number = node.path("id").asInt();
        String url = node.path("links").path("self").path(0).path("href").asText();
        return new PullRequest(number, url, headBranch, baseBranch);
    }

    @Override
    public void deleteBranch(String branchName) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", "refs/heads/" + branchName);
        body.put("dryRun", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(repoUrl() + "/branches"))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .method("DELETE", jsonBody(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw new IOException(
                    "Bitbucket Server deleteBranch failed [" + response.statusCode() + "]: "
                            + response.body());
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String repoUrl() {
        return repo.baseUrl() + "/projects/" + repo.owner() + "/repos/" + repo.repoName();
    }

    /**
     * Returns the latest commit ID (SHA) on the given branch.
     */
    private String getBranchHead(String branch) throws IOException {
        String url = repoUrl() + "/commits?limit=1&until="
                + URLEncoder.encode("refs/heads/" + branch, StandardCharsets.UTF_8);
        JsonNode node = getJson(url);
        JsonNode values = node.path("values");
        if (!values.isArray() || values.isEmpty()) {
            throw new IOException(
                    "Bitbucket Server: no commits found on branch '" + branch + "'");
        }
        return values.get(0).path("id").asText();
    }

    /**
     * Commits a single file using the Bitbucket Server {@code browse} endpoint.
     *
     * @param branchName     target branch
     * @param filePath       file path relative to repo root (e.g. {@code src/main/pom.xml})
     * @param content        new complete file content
     * @param commitMessage  commit message
     * @param sourceCommitId the commit ID that this change is based on (for conflict detection);
     *                       pass {@code null} for brand-new files
     * @return the new commit ID after the file is committed
     */
    private String commitSingleFile(String branchName, String filePath, String content,
                                     String commitMessage, String sourceCommitId) throws IOException {
        String encodedPath = encodePathSegments(filePath);
        String boundary = "----BitbucketServerBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipartBody = buildBrowseMultipart(
                boundary, branchName, content, commitMessage, sourceCommitId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(repoUrl() + "/browse/" + encodedPath))
                .header("Authorization", authHeader())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();

        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "Bitbucket Server commitFile failed for '" + filePath
                            + "' [" + response.statusCode() + "]: " + response.body());
        }

        JsonNode node = MAPPER.readTree(response.body());
        String newId = node.path("id").asText();
        if (newId.isEmpty()) {
            throw new IOException(
                    "Bitbucket Server commitFile: response missing commit ID for '" + filePath + "'");
        }
        return newId;
    }

    /**
     * Builds the multipart/form-data body for the Bitbucket Server {@code /browse/{path}} PUT endpoint.
     */
    private static byte[] buildBrowseMultipart(String boundary, String branch, String content,
                                                String message, String sourceCommitId) {
        String crlf = "\r\n";
        String dashes = "--";
        StringBuilder sb = new StringBuilder();

        addPart(sb, boundary, crlf, dashes, "content", content);
        addPart(sb, boundary, crlf, dashes, "message", message);
        addPart(sb, boundary, crlf, dashes, "branch", branch);

        if (sourceCommitId != null && !sourceCommitId.isBlank()) {
            addPart(sb, boundary, crlf, dashes, "sourceCommitId", sourceCommitId);
        }

        sb.append(dashes).append(boundary).append(dashes).append(crlf);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void addPart(StringBuilder sb, String boundary, String crlf, String dashes,
                                  String name, String value) {
        sb.append(dashes).append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"").append(crlf);
        sb.append(crlf);
        sb.append(value).append(crlf);
    }

    private JsonNode getJson(String url) throws IOException {
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Bitbucket Server GET " + url
                            + " failed [" + response.statusCode() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
    }

    /**
     * Builds the {@code Authorization} header value.
     *
     * <p>Supports two token formats:
     * <ul>
     *   <li>Plain token (Bitbucket Server HTTP access token, version 5.5+): sent as {@code Bearer {token}}</li>
     *   <li>{@code username:password} Basic Auth for older instances</li>
     * </ul>
     */
    private String authHeader() {
        String token = repo.token();
        if (token.contains(":")) {
            // username:password → Basic Auth
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString(token.getBytes(StandardCharsets.UTF_8));
            return "Basic " + encoded;
        }
        return "Bearer " + token;
    }

    /**
     * Encodes each path segment individually so that slashes between segments are preserved
     * while special characters within segments are percent-encoded.
     */
    private static String encodePathSegments(String filePath) {
        String[] segments = filePath.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static HttpRequest.BodyPublisher jsonBody(ObjectNode body) {
        return HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request, int maxRetries) throws IOException {
        int attempt = 0;
        long delayMs = 1000;
        while (true) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if ((status == 429 || status >= 500) && attempt < maxRetries) {
                    attempt++;
                    LOGGER.fine("Bitbucket Server HTTP " + status + ", retrying (attempt " + attempt + ")");
                    sleep(delayMs);
                    delayMs *= 2;
                    continue;
                }
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("HTTP request interrupted", e);
            }
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during retry backoff", e);
        }
    }
}
