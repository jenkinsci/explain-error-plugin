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
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;

public class GitLabApiClient implements ScmApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Random RANDOM = new Random();

    private final ScmRepo repo;
    private final HttpClient client;

    // Cached project ID (resolved lazily)
    private volatile String cachedProjectId;

    public GitLabApiClient(ScmRepo repo) {
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
        JsonNode node = getJson(projectUrl());
        return node.path("default_branch").asText();
    }

    @Override
    public void validateWriteAccess() throws IOException {
        JsonNode node = getJson(projectUrl() + "?simple=true");
        JsonNode perms = node.path("permissions");
        int projectLevel =
                perms.path("project_access").path("access_level").asInt(0);
        int groupLevel =
                perms.path("group_access").path("access_level").asInt(0);
        if (projectLevel < 30 && groupLevel < 30) {
            throw new IOException(
                    "Insufficient permissions: token does not have developer (write) access to "
                            + repo.owner() + "/" + repo.repoName());
        }
    }

    @Override
    public void createBranch(String branchName, String fromBranch) throws IOException {
        try {
            doCreateBranch(branchName, fromBranch);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("400")) {
                // Branch may already exist – retry with a random suffix
                String suffixed = branchName + "-" + randomHex4();
                doCreateBranch(suffixed, fromBranch);
            } else {
                throw e;
            }
        }
    }

    @Override
    public String getFileContent(String filePath, String branch) throws IOException {
        String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
        String url = projectIdUrl() + "/repository/files/" + encodedPath + "?ref=" + branch;
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                    "GitLab getFileContent failed [" + response.statusCode() + "]: " + response.body());
        }
        JsonNode node = MAPPER.readTree(response.body());
        String encoded = node.path("content").asText().replaceAll("\\s", "");
        byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    @Override
    public void commitFiles(String branchName, String commitMessage, Map<String, String> fileContents)
            throws IOException {
        ArrayNode actions = MAPPER.createArrayNode();
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String existingContent = getFileContent(entry.getKey(), branchName);
            String actionType = existingContent != null ? "update" : "create";

            ObjectNode action = MAPPER.createObjectNode();
            action.put("action", actionType);
            action.put("file_path", entry.getKey());
            action.put("content", entry.getValue());
            actions.add(action);
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("branch", branchName);
        body.put("commit_message", commitMessage);
        body.set("actions", actions);

        HttpRequest request = baseRequest(projectIdUrl() + "/repository/commits")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "GitLab commitFiles failed [" + response.statusCode() + "]: " + response.body());
        }
    }

    @Override
    public PullRequest createPullRequest(
            String title, String body, String headBranch, String baseBranch, boolean draft) throws IOException {
        ObjectNode mrBody = MAPPER.createObjectNode();
        mrBody.put("source_branch", headBranch);
        mrBody.put("target_branch", baseBranch);
        mrBody.put("title", title);
        mrBody.put("description", body);
        mrBody.put("draft", draft);

        HttpRequest request = baseRequest(projectIdUrl() + "/merge_requests")
                .POST(jsonBody(mrBody))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "GitLab createMergeRequest failed [" + response.statusCode() + "]: " + response.body());
        }
        JsonNode node = MAPPER.readTree(response.body());
        int number = node.path("iid").asInt();
        String url = node.path("web_url").asText();
        return new PullRequest(number, url, headBranch, baseBranch);
    }

    @Override
    public void deleteBranch(String branchName) throws IOException {
        String encodedName = URLEncoder.encode(branchName, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest(projectIdUrl() + "/repository/branches/" + encodedName)
                .DELETE()
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw new IOException(
                    "GitLab deleteBranch failed [" + response.statusCode() + "]: " + response.body());
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** URL-encoded "owner/repoName" path for the GitLab projects API. */
    private String urlEncodedProjectPath() {
        String path = repo.owner() + "/" + repo.repoName();
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    /** Base project URL using the namespace path (for initial lookup). */
    private String projectUrl() {
        return repo.baseUrl() + "/projects/" + urlEncodedProjectPath();
    }

    /** Base project URL using the numeric project ID (for all mutating calls). */
    private String projectIdUrl() throws IOException {
        return repo.baseUrl() + "/projects/" + getProjectId();
    }

    /**
     * Fetches (and caches) the numeric GitLab project ID.
     */
    private String getProjectId() throws IOException {
        if (cachedProjectId != null) {
            return cachedProjectId;
        }
        JsonNode node = getJson(projectUrl());
        cachedProjectId = node.path("id").asText();
        return cachedProjectId;
    }

    private void doCreateBranch(String branchName, String fromBranch) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("branch", branchName);
        body.put("ref", fromBranch);

        HttpRequest request = baseRequest(projectIdUrl() + "/repository/branches")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "GitLab createBranch failed [" + response.statusCode() + "]: " + response.body());
        }
    }

    private JsonNode getJson(String url) throws IOException {
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200) {
            throw new IOException("GitLab GET " + url + " failed [" + response.statusCode() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + repo.bearerValue())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
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

    private static String randomHex4() {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
