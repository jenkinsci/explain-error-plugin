package io.jenkins.plugins.explain_error.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Generic provider for OpenAI-style chat completion APIs secured by Okta OAuth client credentials.
 */
public class CustomOktaAIProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(CustomOktaAIProvider.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String DEFAULT_MODEL = "gpt-5-nano";
    public static final String DEFAULT_ACCESS_TOKEN_HEADER = "Authorization";
    public static final String DEFAULT_ACCESS_TOKEN_PREFIX = "";
    public static final int DEFAULT_TIMEOUT_SECONDS = 180;

    private String tokenUrl;
    private String apiVersion;
    private String clientId;
    private Secret clientSecret;
    private String scope;
    private String accessTokenHeader;
    private String accessTokenPrefix;
    private Secret appKey;
    private String userId;
    private Integer timeoutSeconds;

    @DataBoundConstructor
    public CustomOktaAIProvider(String url, String tokenUrl, String model, String clientId, Secret clientSecret) {
        super(Util.fixEmptyAndTrim(url), Util.fixEmptyAndTrim(model));
        this.tokenUrl = Util.fixEmptyAndTrim(tokenUrl);
        this.clientId = Util.fixEmptyAndTrim(clientId);
        this.clientSecret = clientSecret;
        this.accessTokenHeader = DEFAULT_ACCESS_TOKEN_HEADER;
        this.accessTokenPrefix = DEFAULT_ACCESS_TOKEN_PREFIX;
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    @DataBoundSetter
    public void setApiVersion(String apiVersion) {
        this.apiVersion = Util.fixEmptyAndTrim(apiVersion);
    }

    public String getClientId() {
        return clientId;
    }

    public Secret getClientSecret() {
        return clientSecret;
    }

    public String getScope() {
        return scope;
    }

    @DataBoundSetter
    public void setScope(String scope) {
        this.scope = Util.fixEmptyAndTrim(scope);
    }

    public String getAccessTokenHeader() {
        return accessTokenHeader;
    }

    @DataBoundSetter
    public void setAccessTokenHeader(String accessTokenHeader) {
        this.accessTokenHeader = Util.fixEmptyAndTrim(accessTokenHeader);
    }

    public String getAccessTokenPrefix() {
        return accessTokenPrefix;
    }

    @DataBoundSetter
    public void setAccessTokenPrefix(String accessTokenPrefix) {
        this.accessTokenPrefix = Util.fixEmptyAndTrim(accessTokenPrefix);
    }

    public Secret getAppKey() {
        return appKey;
    }

    @DataBoundSetter
    public void setAppKey(Secret appKey) {
        this.appKey = appKey;
    }

    public String getUserId() {
        return userId;
    }

    @DataBoundSetter
    public void setUserId(String userId) {
        this.userId = Util.fixEmptyAndTrim(userId);
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @DataBoundSetter
    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Assistant createAssistant() {
        return (errorLogs, language, customContext) -> {
            try {
                return analyzeWithOkta(errorLogs, language, customContext);
            } catch (ExplanationException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        };
    }

    @Override
    public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant() {
        return errorLogs -> {
            try {
                return requestFixSuggestion(errorLogs);
            } catch (ExplanationException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        };
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        String urlValue = Util.fixEmptyAndTrim(getUrl());
        String tokenUrlValue = Util.fixEmptyAndTrim(getTokenUrl());
        String modelValue = Util.fixEmptyAndTrim(getModel());
        String clientIdValue = Util.fixEmptyAndTrim(getClientId());
        String clientSecretValue = Util.fixEmptyAndTrim(Secret.toString(getClientSecret()));

        if (listener != null) {
            if (urlValue == null) {
                listener.getLogger().println("No API URL configured for Custom Okta AI provider.");
            } else if (tokenUrlValue == null) {
                listener.getLogger().println("No token URL configured for Custom Okta AI provider.");
            } else if (modelValue == null) {
                listener.getLogger().println("No model configured for Custom Okta AI provider.");
            } else if (clientIdValue == null) {
                listener.getLogger().println("No client ID configured for Custom Okta AI provider.");
            } else if (clientSecretValue == null) {
                listener.getLogger().println("No client secret configured for Custom Okta AI provider.");
            }
        }

        return urlValue == null || tokenUrlValue == null || modelValue == null
                || clientIdValue == null || clientSecretValue == null;
    }

    private JenkinsLogAnalysis analyzeWithOkta(String errorLogs, String language, String customContext)
            throws ExplanationException {
        HttpClient client = newJenkinsHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .build();

        try {
            String accessToken = fetchAccessToken(client);
            return requestAnalysis(client, accessToken, errorLogs, language, customContext);
        } catch (IOException e) {
            throw new ExplanationException("error", "Failed to communicate with Custom Okta AI provider", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExplanationException("error", "Interrupted while communicating with Custom Okta AI provider", e);
        }
    }

    private String requestFixSuggestion(String errorLogs) throws ExplanationException {
        HttpClient client = newJenkinsHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .build();

        try {
            String accessToken = fetchAccessToken(client);
            return requestRawContent(client, accessToken, buildFixRequestBody(errorLogs));
        } catch (IOException e) {
            throw new ExplanationException("error", "Failed to communicate with Custom Okta AI provider", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExplanationException("error", "Interrupted while communicating with Custom Okta AI provider", e);
        }
    }

    private String fetchAccessToken(HttpClient client) throws IOException, InterruptedException, ExplanationException {
        StringBuilder body = new StringBuilder("grant_type=client_credentials");
        String configuredScope = Util.fixEmptyAndTrim(getScope());
        if (configuredScope != null) {
            body.append("&scope=")
                    .append(URLEncoder.encode(configuredScope, StandardCharsets.UTF_8));
        }

        String credentials = getClientId() + ':' + Secret.toString(getClientSecret());
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(getTokenUrl()))
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basicAuth)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new ExplanationException("error", "Token request failed with status "
                    + response.statusCode() + ": " + abbreviate(response.body()));
        }

        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        String accessToken = Util.fixEmptyAndTrim(json.path("access_token").asText(null));
        if (accessToken == null) {
            throw new ExplanationException("error", "Token response did not contain an access_token.");
        }

        return accessToken;
    }

    private JenkinsLogAnalysis requestAnalysis(HttpClient client, String accessToken, String errorLogs,
                                               String language, String customContext)
            throws IOException, InterruptedException, ExplanationException {
        String content = requestRawContent(client, accessToken, buildChatRequestBody(errorLogs, language, customContext));
        return parseAnalysis(content);
    }

    private String requestRawContent(HttpClient client, String accessToken, String requestBody)
            throws IOException, InterruptedException, ExplanationException {
        HttpRequest request = HttpRequest.newBuilder(buildChatUri())
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header(resolveAccessTokenHeader(), buildAccessTokenHeaderValue(accessToken))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Sending Custom Okta AI request to " + request.uri());
        }

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new ExplanationException("error", "Chat completion request failed with status "
                    + response.statusCode() + ": " + abbreviate(response.body()));
        }

        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        return extractAssistantContent(json);
    }

    private URI buildChatUri() {
        String resolvedUrl = getUrl();
        String encodedModel = URLEncoder.encode(getModel(), StandardCharsets.UTF_8);

        if (resolvedUrl.contains("{model}")) {
            resolvedUrl = resolvedUrl.replace("{model}", encodedModel);
        } else if (!resolvedUrl.contains("/chat/completions")) {
            if (!resolvedUrl.endsWith("/")) {
                resolvedUrl += "/";
            }
            resolvedUrl += encodedModel + "/chat/completions";
        }

        String configuredApiVersion = Util.fixEmptyAndTrim(getApiVersion());
        if (configuredApiVersion != null && !resolvedUrl.contains("api-version=")) {
            resolvedUrl += resolvedUrl.contains("?") ? "&" : "?";
            resolvedUrl += "api-version=" + URLEncoder.encode(configuredApiVersion, StandardCharsets.UTF_8);
        }

        return URI.create(resolvedUrl);
    }

    private String buildChatRequestBody(String errorLogs, String language, String customContext) throws IOException {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("model", getModel());
        payload.put("temperature", 0.3);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", buildSystemPrompt());
        messages.addObject()
                .put("role", "user")
                .put("content", buildUserPrompt(errorLogs, language, customContext)
                        + "\n\nReturn ONLY valid JSON with these keys: errorSummary, resolutionSteps, bestPractices, errorSignature.");

        String userMetadata = buildUserMetadata();
        if (userMetadata != null) {
            payload.put("user", userMetadata);
        } else {
            LOGGER.fine("Custom Okta AI: No App Key or User ID configured; "
                    + "'user' metadata field will be omitted from the request. "
                    + "This may cause a 400 error if the API requires an App Key.");
        }

        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    private String buildFixRequestBody(String errorLogs) throws IOException {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("model", getModel());
        payload.put("temperature", 0.3);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", """
                        You are an expert Jenkins CI/CD engineer. You analyze build failure logs and generate structured fix suggestions.

                        You MUST respond ONLY with valid JSON matching this exact schema (no other text before or after):
                        {
                          "fixable": <boolean>,
                          "explanation": "<one paragraph explaining the root cause>",
                          "confidence": "<high|medium|low>",
                          "fixType": "<dependency|config|code|unknown>",
                          "changes": [
                            {
                              "filePath": "<path relative to repo root, e.g. pom.xml>",
                              "action": "<modify|create>",
                              "unifiedDiff": "<standard unified diff, properly escaped for JSON>",
                              "description": "<one sentence explaining this change>"
                            }
                          ]
                        }

                        Rules:
                        - Only set fixable=true when confidence is "high" or "medium"
                        - Only suggest changes to source/config files. NEVER modify: target/, build/, dist/, node_modules/, .gradle/, lock files (package-lock.json, yarn.lock, Pipfile.lock), secrets (.env*, credentials*)
                        - For unifiedDiff: use standard unified diff format with @@ -line,count +line,count @@ headers
                        - filePath must be relative to repo root (no leading /, no ../ traversal)
                        - If you cannot determine a fix with at least medium confidence, set fixable=false and return an empty changes array
                        - Supported file types: pom.xml, build.gradle, build.gradle.kts, package.json, requirements.txt, go.mod, Gemfile, Jenkinsfile, Dockerfile, *.yaml, *.yml, *.json (config), *.properties, *.xml (config), *.java, *.py, *.js, *.ts (small targeted fixes only)
                        """);
        messages.addObject()
                .put("role", "user")
                .put("content", "Jenkins build failed. Analyze and suggest a fix.\n\nError logs:\n" + errorLogs);

        String userMetadata = buildUserMetadata();
        if (userMetadata != null) {
            payload.put("user", userMetadata);
        }

        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    private String buildUserMetadata() throws IOException {
        String configuredAppKey = Util.fixEmptyAndTrim(Secret.toString(getAppKey()));
        String configuredUserId = Util.fixEmptyAndTrim(getUserId());
        if (configuredAppKey == null && configuredUserId == null) {
            return null;
        }

        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        if (configuredAppKey != null) {
            metadata.put("appkey", configuredAppKey);
        }
        if (configuredUserId != null) {
            metadata.put("user", configuredUserId);
        }
        return OBJECT_MAPPER.writeValueAsString(metadata);
    }

    private String extractAssistantContent(JsonNode responseJson) throws ExplanationException {
        JsonNode choices = responseJson.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ExplanationException("error", "Chat completion response did not contain any choices.");
        }

        JsonNode content = choices.get(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    text.append(part.asText());
                } else if (part.hasNonNull("text")) {
                    text.append(part.get("text").asText());
                }
            }
            if (!text.isEmpty()) {
                return text.toString();
            }
        }

        throw new ExplanationException("error", "Chat completion response did not contain message content.");
    }

    private JenkinsLogAnalysis parseAnalysis(String content) throws IOException {
        JsonNode json = tryParseJson(content);
        if (json == null || !json.isObject()) {
            return new JenkinsLogAnalysis(content.trim(), null, null, null);
        }

        String errorSummary = Util.fixEmptyAndTrim(json.path("errorSummary").asText(null));
        if (errorSummary == null) {
            errorSummary = content.trim();
        }

        return new JenkinsLogAnalysis(
                errorSummary,
                toStringList(json.path("resolutionSteps")),
                toStringList(json.path("bestPractices")),
                Util.fixEmptyAndTrim(json.path("errorSignature").asText(null)));
    }

    private JsonNode tryParseJson(String content) throws IOException {
        try {
            return OBJECT_MAPPER.readTree(content);
        } catch (IOException firstFailure) {
            String trimmed = content == null ? null : content.trim();
            if (trimmed == null) {
                return null;
            }

            if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
                int firstLineBreak = trimmed.indexOf('\n');
                if (firstLineBreak > 0) {
                    trimmed = trimmed.substring(firstLineBreak + 1, trimmed.length() - 3).trim();
                }
            }

            int jsonStart = trimmed.indexOf('{');
            int jsonEnd = trimmed.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                return OBJECT_MAPPER.readTree(trimmed.substring(jsonStart, jsonEnd + 1));
            }
            return null;
        }
    }

    private List<String> toStringList(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return null;
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = Util.fixEmptyAndTrim(item.asText(null));
            if (value != null) {
                values.add(value);
            }
        }
        return values.isEmpty() ? null : values;
    }

    private int resolveTimeoutSeconds() {
        if (timeoutSeconds == null || timeoutSeconds < 1) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return timeoutSeconds;
    }

    private String resolveAccessTokenHeader() {
        String header = Util.fixEmptyAndTrim(getAccessTokenHeader());
        return header != null ? header : DEFAULT_ACCESS_TOKEN_HEADER;
    }

    private String buildAccessTokenHeaderValue(String accessToken) {
        String prefix = Util.fixEmptyAndTrim(getAccessTokenPrefix());
        return prefix == null ? accessToken : prefix + " " + accessToken;
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500) + "...";
    }

    @Extension
    @Symbol("customOkta")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Custom Okta AI";
        }

        @Override
        public String getDefaultModel() {
            return DEFAULT_MODEL;
        }

        public String getDefaultAccessTokenHeader() {
            return DEFAULT_ACCESS_TOKEN_HEADER;
        }

        public String getDefaultAccessTokenPrefix() {
            return DEFAULT_ACCESS_TOKEN_PREFIX;
        }

        public int getDefaultTimeoutSeconds() {
            return DEFAULT_TIMEOUT_SECONDS;
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @Override
        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("API URL is required.");
            }
            return super.doCheckUrl(value);
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckTokenUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Token URL is required.");
            }
            return super.doCheckUrl(value);
        }

        @POST
        public FormValidation doCheckTimeoutSeconds(@QueryParameter Integer value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (value == null) {
                return FormValidation.ok();
            }
            if (value < 1) {
                return FormValidation.error("Timeout must be greater than 0.");
            }
            if (value > 600) {
                return FormValidation.warning("Timeout above 600 seconds may cause long waits.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doTestConfiguration(@QueryParameter("url") String url,
                                                  @QueryParameter("tokenUrl") String tokenUrl,
                                                  @QueryParameter("model") String model,
                                                  @QueryParameter("clientId") String clientId,
                                                  @QueryParameter("clientSecret") Secret clientSecret,
                                                  @QueryParameter("scope") String scope,
                                                  @QueryParameter("accessTokenHeader") String accessTokenHeader,
                                                  @QueryParameter("accessTokenPrefix") String accessTokenPrefix,
                                                  @QueryParameter("apiVersion") String apiVersion,
                                                  @QueryParameter("appKey") Secret appKey,
                                                  @QueryParameter("userId") String userId,
                                                  @QueryParameter("timeoutSeconds") Integer timeoutSeconds)
                throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            CustomOktaAIProvider provider = new CustomOktaAIProvider(url, tokenUrl, model, clientId, clientSecret);
            provider.setScope(scope);
            provider.setAccessTokenHeader(accessTokenHeader);
            provider.setAccessTokenPrefix(accessTokenPrefix);
            provider.setApiVersion(apiVersion);
            provider.setAppKey(appKey);
            provider.setUserId(userId);
            provider.setTimeoutSeconds(timeoutSeconds);

            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }
    }
}
