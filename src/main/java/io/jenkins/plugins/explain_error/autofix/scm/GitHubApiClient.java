package io.jenkins.plugins.explain_error.autofix.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;

public class GitHubApiClient implements ScmApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Random RANDOM = new Random();

    private final ScmRepo repo;
    private final HttpClient client;

    public GitHubApiClient(ScmRepo repo) {
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
        JsonNode node = getJson(repoUrl());
        return node.path("default_branch").asText();
    }

    @Override
    public void validateWriteAccess() throws IOException {
        JsonNode node = getJson(repoUrl());
        boolean pushAccess = node.path("permissions").path("push").asBoolean(false);
        if (!pushAccess) {
            throw new IOException("Insufficient permissions: token does not have push access to "
                    + repo.owner() + "/" + repo.repoName());
        }
    }

    @Override
    public void createBranch(String branchName, String fromBranch) throws IOException {
        String sha = getRefSha("heads/" + fromBranch);
        try {
            doCreateRef("refs/heads/" + branchName, sha);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("422")) {
                // Branch already exists – retry with a random suffix
                String suffixed = branchName + "-" + randomHex4();
                doCreateRef("refs/heads/" + suffixed, sha);
            } else {
                throw e;
            }
        }
    }

    @Override
    public String getFileContent(String filePath, String branch) throws IOException {
        String url = repoUrl() + "/contents/" + filePath + "?ref=" + branch;
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub getFileContent failed [" + response.statusCode() + "]: " + response.body());
        }
        JsonNode node = MAPPER.readTree(response.body());
        String encoded = node.path("content").asText().replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(encoded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    @Override
    public void commitFiles(String branchName, String commitMessage, Map<String, String> fileContents)
            throws IOException {

        // 1. Get current commit SHA on branch
        String currentSha = getRefSha("heads/" + branchName);

        // 2. Get tree SHA of current commit
        JsonNode commitNode = getJson(repoUrl() + "/git/commits/" + currentSha);
        String treeSha = commitNode.path("tree").path("sha").asText();

        // 3. Create blobs
        ArrayNode treeEntries = MAPPER.createArrayNode();
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            ObjectNode blobBody = MAPPER.createObjectNode();
            blobBody.put("encoding", "utf-8");
            blobBody.put("content", entry.getValue());
            JsonNode blobResponse = postJson(repoUrl() + "/git/blobs", blobBody);
            String blobSha = blobResponse.path("sha").asText();

            ObjectNode treeEntry = MAPPER.createObjectNode();
            treeEntry.put("path", entry.getKey());
            treeEntry.put("mode", "100644");
            treeEntry.put("type", "blob");
            treeEntry.put("sha", blobSha);
            treeEntries.add(treeEntry);
        }

        // 4. Create tree
        ObjectNode treeBody = MAPPER.createObjectNode();
        treeBody.put("base_tree", treeSha);
        treeBody.set("tree", treeEntries);
        JsonNode newTree = postJson(repoUrl() + "/git/trees", treeBody);
        String newTreeSha = newTree.path("sha").asText();

        // 5. Create commit
        ObjectNode commitBody = MAPPER.createObjectNode();
        commitBody.put("message", commitMessage);
        commitBody.put("tree", newTreeSha);
        commitBody.putArray("parents").add(currentSha);
        JsonNode newCommit = postJson(repoUrl() + "/git/commits", commitBody);
        String newCommitSha = newCommit.path("sha").asText();

        // 6. Update ref
        ObjectNode refBody = MAPPER.createObjectNode();
        refBody.put("sha", newCommitSha);
        patchJson(repoUrl() + "/git/refs/heads/" + branchName, refBody);
    }

    @Override
    public PullRequest createPullRequest(
            String title, String body, String headBranch, String baseBranch, boolean draft) throws IOException {
        ObjectNode prBody = MAPPER.createObjectNode();
        prBody.put("title", title);
        prBody.put("body", body);
        prBody.put("head", headBranch);
        prBody.put("base", baseBranch);
        prBody.put("draft", draft);
        JsonNode response = postJson(repoUrl() + "/pulls", prBody);
        int number = response.path("number").asInt();
        String url = response.path("html_url").asText();
        return new PullRequest(number, url, headBranch, baseBranch);
    }

    @Override
    public void deleteBranch(String branchName) throws IOException {
        HttpRequest request = baseRequest(repoUrl() + "/git/refs/heads/" + branchName)
                .DELETE()
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 204 && response.statusCode() != 422) {
            throw new IOException(
                    "GitHub deleteBranch failed [" + response.statusCode() + "]: " + response.body());
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String repoUrl() {
        return repo.baseUrl() + "/repos/" + repo.owner() + "/" + repo.repoName();
    }

    private String getRefSha(String ref) throws IOException {
        JsonNode node = getJson(repoUrl() + "/git/ref/" + ref);
        return node.path("object").path("sha").asText();
    }

    private void doCreateRef(String refPath, String sha) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("ref", refPath);
        body.put("sha", sha);
        HttpRequest request = baseRequest(repoUrl() + "/git/refs")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 201) {
            throw new IOException(
                    "GitHub createBranch failed [" + response.statusCode() + "]: " + response.body());
        }
    }

    private JsonNode getJson(String url) throws IOException {
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200) {
            throw new IOException("GitHub GET " + url + " failed [" + response.statusCode() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private JsonNode postJson(String url, ObjectNode body) throws IOException {
        HttpRequest request = baseRequest(url)
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "GitHub POST " + url + " failed [" + response.statusCode() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private JsonNode patchJson(String url, ObjectNode body) throws IOException {
        HttpRequest request = baseRequest(url)
                .method("PATCH", jsonBody(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200) {
            throw new IOException(
                    "GitHub PATCH " + url + " failed [" + response.statusCode() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + repo.bearerValue())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
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
