package io.jenkins.plugins.explain_error.provider;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
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
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import org.springframework.security.core.Authentication;

/**
 * Azure OpenAI provider backed by Jenkins StringCredentials.
 */
public class AzureOpenAIProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(AzureOpenAIProvider.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String DEFAULT_DEPLOYMENT = "gpt-4o";
    public static final String DEFAULT_API_VERSION = "";
    public static final int DEFAULT_TIMEOUT_SECONDS = 180;

    private final String apiVersion;
    private final String credentialsId;

    @DataBoundConstructor
    public AzureOpenAIProvider(String endpoint, String deployment, String apiVersion, String credentialsId) {
        super(Util.fixEmptyAndTrim(endpoint), Util.fixEmptyAndTrim(deployment));
        this.apiVersion = Util.fixEmptyAndTrim(apiVersion);
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getEndpoint() {
        return getUrl();
    }

    public String getDeployment() {
        return getModel();
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @Override
    public Assistant createAssistant() {
        return (errorLogs, language, customContext) -> {
            try {
                return requestAnalysis(errorLogs, language, customContext, null, null);
            } catch (ExplanationException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        };
    }

    @Override
    public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant() {
        return errorLogs -> {
            try {
                return requestFixSuggestion(errorLogs, null, null);
            } catch (ExplanationException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        };
    }

    @Override
    public Assistant createAssistant(@CheckForNull Item item, @CheckForNull Authentication authentication) {
        return (errorLogs, language, customContext) -> {
            try {
                return requestAnalysis(errorLogs, language, customContext, item, authentication);
            } catch (ExplanationException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        };
    }

    @Override
    public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant(@CheckForNull Item item,
                                                                                     @CheckForNull Authentication authentication) {
        return errorLogs -> {
            try {
                return requestFixSuggestion(errorLogs, item, authentication);
            } catch (ExplanationException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        };
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener) {
        return isNotValid(listener, null, null);
    }

    @Override
    public boolean isNotValid(@CheckForNull TaskListener listener, @CheckForNull Item item,
                              @CheckForNull Authentication authentication) {
        String endpoint = Util.fixEmptyAndTrim(getEndpoint());
        String deployment = Util.fixEmptyAndTrim(getDeployment());
        String configuredCredentialsId = Util.fixEmptyAndTrim(getCredentialsId());
        StringCredentials credentials = null;
        if (endpoint != null && deployment != null && configuredCredentialsId != null) {
            credentials = resolveCredentials(item, authentication);
        }

        if (listener != null) {
            if (endpoint == null) {
                listener.getLogger().println("No endpoint configured for Azure OpenAI.");
            } else if (deployment == null) {
                listener.getLogger().println("No deployment configured for Azure OpenAI.");
            } else if (configuredCredentialsId == null) {
                listener.getLogger().println("No credentials ID configured for Azure OpenAI.");
            } else if (credentials == null) {
                listener.getLogger().println("Azure OpenAI credentials not found for ID: " + configuredCredentialsId);
            }
        }

        return endpoint == null || deployment == null || configuredCredentialsId == null || credentials == null;
    }

    private JenkinsLogAnalysis requestAnalysis(String errorLogs, String language, String customContext,
                                               @CheckForNull Item item, @CheckForNull Authentication authentication)
            throws ExplanationException {
        HttpClient client = newJenkinsHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .build();
        try {
            String content = requestRawContent(client, buildAnalysisRequestBody(errorLogs, language, customContext),
                    item, authentication);
            return parseAnalysis(content);
        } catch (IOException e) {
            throw new ExplanationException("error", "Failed to communicate with Azure OpenAI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExplanationException("error", "Interrupted while communicating with Azure OpenAI", e);
        }
    }

    private String requestFixSuggestion(String errorLogs, @CheckForNull Item item,
                                        @CheckForNull Authentication authentication) throws ExplanationException {
        HttpClient client = newJenkinsHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .build();
        try {
            return requestRawContent(client, buildFixRequestBody(errorLogs), item, authentication);
        } catch (IOException e) {
            throw new ExplanationException("error", "Failed to communicate with Azure OpenAI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExplanationException("error", "Interrupted while communicating with Azure OpenAI", e);
        }
    }

    private String requestRawContent(HttpClient client, String requestBody,
                                     @CheckForNull Item item, @CheckForNull Authentication authentication)
            throws IOException, InterruptedException, ExplanationException {
        StringCredentials credentials = resolveCredentials(item, authentication);
        if (credentials == null) {
            throw new ExplanationException("error", "Azure OpenAI credentials not found for ID: " + getCredentialsId());
        }

        HttpRequest request = HttpRequest.newBuilder(buildResponsesUri())
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("api-key", credentials.getSecret().getPlainText())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Sending Azure OpenAI request to " + request.uri());
        }

        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new ExplanationException("error", "Responses API request failed with status "
                    + response.statusCode() + ": " + abbreviate(response.body()));
        }

        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        return extractAssistantContent(json);
    }

    private URI buildResponsesUri() {
        String endpoint = stripTrailingSlash(getEndpoint());
        String configuredApiVersion = Util.fixEmptyAndTrim(getApiVersion());
        if (configuredApiVersion == null || "v1".equalsIgnoreCase(configuredApiVersion)) {
            return URI.create(appendResponsesPath(endpoint, "/openai/v1/responses"));
        }

        String encodedApiVersion = URLEncoder.encode(configuredApiVersion, StandardCharsets.UTF_8);
        return URI.create(appendResponsesPath(endpoint, "/openai/responses")
                + "?api-version=" + encodedApiVersion);
    }

    private String stripTrailingSlash(String endpoint) {
        String normalizedEndpoint = endpoint;
        while (normalizedEndpoint.endsWith("/")) {
            normalizedEndpoint = normalizedEndpoint.substring(0, normalizedEndpoint.length() - 1);
        }
        return normalizedEndpoint;
    }

    private String appendResponsesPath(String endpoint, String responsesPath) {
        if (endpoint.endsWith("/responses")) {
            return endpoint;
        }
        String basePath = responsesPath.substring(0, responsesPath.length() - "/responses".length());
        if (endpoint.endsWith(basePath)) {
            return endpoint + "/responses";
        }
        return endpoint + responsesPath;
    }

    private String buildAnalysisRequestBody(
            String errorLogs, String language, String customContext) throws IOException {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("model", getDeployment());
        payload.put("temperature", 0.3);
        payload.put("instructions", buildSystemPrompt());
        String outputFormatInstruction = "\n\nReturn ONLY valid JSON with these keys: "
                + "errorSummary, resolutionSteps, bestPractices, errorSignature.";
        payload.put("input", buildUserPrompt(errorLogs, language, customContext) + outputFormatInstruction);

        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    private String buildFixRequestBody(String errorLogs) throws IOException {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("model", getDeployment());
        payload.put("temperature", 0.3);

        payload.put("instructions", """
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
        payload.put("input", "Jenkins build failed. Analyze and suggest a fix.\n\nError logs:\n" + errorLogs);

        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    private String extractAssistantContent(JsonNode responseJson) throws ExplanationException {
        JsonNode outputText = responseJson.path("output_text");
        if (outputText.isTextual()) {
            String text = Util.fixEmptyAndTrim(outputText.asText());
            if (text != null) {
                return text;
            }
        }

        StringBuilder text = new StringBuilder();
        JsonNode output = responseJson.path("output");
        if (output.isArray()) {
            for (JsonNode outputItem : output) {
                JsonNode content = outputItem.path("content");
                if (content.isArray()) {
                    for (JsonNode contentItem : content) {
                        if (contentItem.isTextual()) {
                            text.append(contentItem.asText());
                        } else if (contentItem.hasNonNull("text")) {
                            text.append(contentItem.get("text").asText());
                        }
                    }
                } else if (content.isTextual()) {
                    text.append(content.asText());
                }
            }
        }

        String extractedText = Util.fixEmptyAndTrim(text.toString());
        if (extractedText != null) {
            return extractedText;
        }

        throw new ExplanationException("error", "Responses API response did not contain output text.");
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

    private StringCredentials resolveCredentials(@CheckForNull Item item, @CheckForNull Authentication authentication) {
        String id = Util.fixEmptyAndTrim(getCredentialsId());
        if (id == null) {
            return null;
        }
        if (Jenkins.getInstanceOrNull() == null) {
            return null;
        }
        return CredentialsProvider.findCredentialByIdInItem(
                id,
                StringCredentials.class,
                item,
                authentication != null ? authentication : ACL.SYSTEM2,
                Collections.emptyList());
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500) + "...";
    }

    @Extension
    @Symbol("azureOpenai")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Azure OpenAI";
        }

        @Override
        public String getDefaultModel() {
            return DEFAULT_DEPLOYMENT;
        }

        public String getDefaultApiVersion() {
            return DEFAULT_API_VERSION;
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckEndpoint(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Endpoint is required.");
            }
            return super.doCheckUrl(value);
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckDeployment(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Deployment is required.");
            }
            return FormValidation.ok();
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckApiVersion(@QueryParameter String value) {
            String configuredApiVersion = Util.fixEmptyAndTrim(value);
            if (configuredApiVersion == null || "v1".equalsIgnoreCase(configuredApiVersion)
                    || configuredApiVersion.matches("\\d{4}-\\d{2}-\\d{2}-preview")) {
                return FormValidation.ok();
            }
            return FormValidation.warning("Use v1, leave empty for v1, or specify a dated preview version.");
        }

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Credentials ID is required.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doTestConfiguration(@QueryParameter("endpoint") String endpoint,
                                                  @QueryParameter("deployment") String deployment,
                                                  @QueryParameter("apiVersion") String apiVersion,
                                                  @QueryParameter("credentialsId") String credentialsId)
                throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            AzureOpenAIProvider provider = new AzureOpenAIProvider(endpoint, deployment, apiVersion, credentialsId);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }
    }
}
