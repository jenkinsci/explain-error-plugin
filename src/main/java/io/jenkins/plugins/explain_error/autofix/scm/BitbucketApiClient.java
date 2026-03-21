package io.jenkins.plugins.explain_error.autofix.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.StringJoiner;
import java.util.UUID;

public class BitbucketApiClient implements ScmApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScmRepo repo;
    private final HttpClient client;

    public BitbucketApiClient(ScmRepo repo) {
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
        return node.path("mainbranch").path("name").asText();
    }

    @Override
    public void validateWriteAccess() throws IOException {
        // Verify at least read access first
        HttpRequest readRequest = baseRequest(repoUrl()).GET().build();
        HttpResponse<String> readResponse = sendWithRetry(readRequest, 3);
        if (readResponse.statusCode() != 200) {
            throw new IOException(
                    "Insufficient permissions: cannot access repository "
                            + repo.owner() + "/" + repo.repoName()
                            + " [" + readResponse.statusCode() + "]");
        }

        // Check write/admin permission via the user repository permissions endpoint
        String fullName = repo.owner() + "/" + repo.repoName();
        String permUrl = repo.baseUrl() + "/user/permissions/repositories?q=repository.full_name%3D%22"
                + URLEncoder.encode(fullName, StandardCharsets.UTF_8) + "%22";
        JsonNode permNode = getJson(permUrl);
        JsonNode values = permNode.path("values");
        if (values.isArray()) {
            for (JsonNode entry : values) {
                String permission = entry.path("permission").asText();
                if ("write".equals(permission) || "admin".equals(permission)) {
                    return;
                }
            }
        }
        throw new IOException(
                "Insufficient permissions: token does not have write access to "
                        + repo.owner() + "/" + repo.repoName());
    }

    @Override
    public void createBranch(String branchName, String fromBranch) throws IOException {
        // 1. Get the HEAD hash of fromBranch
        JsonNode branchNode = getJson(repoUrl() + "/refs/branches/" + fromBranch);
        String hash = branchNode.path("target").path("hash").asText();

        // 2. Create new branch
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", branchName);
        body.putObject("target").put("hash", hash);

        HttpRequest request = baseRequest(repoUrl() + "/refs/branches")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "Bitbucket createBranch failed [" + response.statusCode() + "]: " + response.body());
        }
    }

    @Override
    public String getFileContent(String filePath, String branch) throws IOException {
        String url = repoUrl() + "/src/" + branch + "/" + filePath;
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Bitbucket getFileContent failed [" + response.statusCode() + "]: " + response.body());
        }
        return response.body();
    }

    @Override
    public void commitFiles(String branchName, String commitMessage, Map<String, String> fileContents)
            throws IOException {
        // Bitbucket Cloud accepts a multipart/form-data POST to /src
        String boundary = "----BitbucketBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipartBody = buildMultipartBody(boundary, branchName, commitMessage, fileContents);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(repoUrl() + "/src"))
                .header("Authorization", "Bearer " + repo.token())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();

        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "Bitbucket commitFiles failed [" + response.statusCode() + "]: " + response.body());
        }
    }

    @Override
    public PullRequest createPullRequest(
            String title, String body, String headBranch, String baseBranch, boolean draft) throws IOException {
        // Bitbucket Cloud does not support draft PRs — the draft param is ignored
        ObjectNode prBody = MAPPER.createObjectNode();
        prBody.put("title", title);
        prBody.put("description", body);
        prBody.putObject("source").putObject("branch").put("name", headBranch);
        prBody.putObject("destination").putObject("branch").put("name", baseBranch);

        HttpRequest request = baseRequest(repoUrl() + "/pullrequests")
                .POST(jsonBody(prBody))
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException(
                    "Bitbucket createPullRequest failed [" + response.statusCode() + "]: " + response.body());
        }
        JsonNode node = MAPPER.readTree(response.body());
        int number = node.path("id").asInt();
        String url = node.path("links").path("html").path("href").asText();
        return new PullRequest(number, url, headBranch, baseBranch);
    }

    @Override
    public void deleteBranch(String branchName) throws IOException {
        String encodedName = URLEncoder.encode(branchName, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest(repoUrl() + "/refs/branches/" + encodedName)
                .DELETE()
                .build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 204) {
            throw new IOException(
                    "Bitbucket deleteBranch failed [" + response.statusCode() + "]: " + response.body());
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String repoUrl() {
        return repo.baseUrl() + "/repositories/" + repo.owner() + "/" + repo.repoName();
    }

    private JsonNode getJson(String url) throws IOException {
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Bitbucket GET " + url + " failed [" + response.statusCode() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + repo.token())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
    }

    private static HttpRequest.BodyPublisher jsonBody(ObjectNode body) {
        return HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Builds a multipart/form-data body for the Bitbucket /src commit endpoint.
     * Fields: message, branch, and one field per file.
     */
    private static byte[] buildMultipartBody(
            String boundary,
            String branchName,
            String commitMessage,
            Map<String, String> fileContents) {

        String crlf = "\r\n";
        String dashes = "--";
        StringBuilder sb = new StringBuilder();

        // message field
        sb.append(dashes).append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"message\"").append(crlf);
        sb.append(crlf);
        sb.append(commitMessage).append(crlf);

        // branch field
        sb.append(dashes).append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"branch\"").append(crlf);
        sb.append(crlf);
        sb.append(branchName).append(crlf);

        // one field per file
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            sb.append(dashes).append(boundary).append(crlf);
            sb.append("Content-Disposition: form-data; name=\"")
                    .append(entry.getKey())
                    .append("\"")
                    .append(crlf);
            sb.append(crlf);
            sb.append(entry.getValue()).append(crlf);
        }

        // closing boundary
        sb.append(dashes).append(boundary).append(dashes).append(crlf);

        return sb.toString().getBytes(StandardCharsets.UTF_8);
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
}
