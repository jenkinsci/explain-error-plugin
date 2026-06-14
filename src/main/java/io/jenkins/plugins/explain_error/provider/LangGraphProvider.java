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
import dev.langchain4j.service.SystemMessage;
import io.jenkins.plugins.explain_error.autofix.FixAssistant;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * AI provider that calls the LangGraph Platform native API ({@code POST /runs/wait}).
 * Authenticates using the {@code x-api-key} header required by the LangGraph Platform.
 * The {@code model} field maps to the assistant/graph ID configured in the deployment.
 */
public class LangGraphProvider extends BaseAIProvider {

    private static final Logger LOGGER = Logger.getLogger(LangGraphProvider.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RUNS_WAIT_PATH = "/runs/wait";

    public static final String DEFAULT_MODEL = "my-agent";
    public static final int DEFAULT_TIMEOUT_SECONDS = 180;

    private Secret apiKey;
    private Integer timeoutSeconds;

    @DataBoundConstructor
    public LangGraphProvider(String url, String model, Secret apiKey) {
        super(Util.fixEmptyAndTrim(url), normalizeModel(model));
        this.apiKey = apiKey;
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    private static String normalizeModel(String model) {
        String normalizedModel = Util.fixEmptyAndTrim(model);
        return normalizedModel == null ? DEFAULT_MODEL : normalizedModel;
    }

    public Secret getApiKey() {
        return apiKey;
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
                return analyzeWithLangGraph(errorLogs, language, customContext);
            } catch (ExplanationException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        };
    }

    @Override
    public FixAssistant createFixAssistant() {
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
        String keyValue = Util.fixEmptyAndTrim(Secret.toString(getApiKey()));

        if (listener != null) {
            if (urlValue == null) {
                listener.getLogger().println("No URL configured for LangGraph Platform.");
            } else if (keyValue == null) {
                listener.getLogger().println("No API key configured for LangGraph Platform.");
            }
        }
        return urlValue == null || keyValue == null;
    }

    private JenkinsLogAnalysis analyzeWithLangGraph(String errorLogs, String language, String customContext)
            throws ExplanationException {
        HttpClient client = newJenkinsHttpClientBuilder()
            .connectTimeout(Duration.ofSeconds(resolveTimeout()))
            .build();

        try {
            String body = buildRunRequestBody(errorLogs, language, customContext);
            String content = sendRequest(client, body);
            return parseAnalysis(content);
        } catch (IOException e) {
            LOGGER.warning("LangGraph Platform communication failed: " + e);
            throw new ExplanationException("error", "Failed to communicate with LangGraph Platform: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExplanationException("error", "Interrupted while communicating with LangGraph Platform", e);
        }
    }

    private String requestFixSuggestion(String errorLogs) throws ExplanationException {
        HttpClient client = newJenkinsHttpClientBuilder()
            .connectTimeout(Duration.ofSeconds(resolveTimeout()))
            .build();

        try {
            String body = buildFixRunRequestBody(errorLogs);
            return sendRequest(client, body);
        } catch (IOException e) {
            LOGGER.warning("LangGraph Platform communication failed (fix request): " + e);
            throw new ExplanationException("error", "Failed to communicate with LangGraph Platform: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExplanationException("error", "Interrupted while communicating with LangGraph Platform", e);
        }
    }

    private String sendRequest(HttpClient client, String requestBody)
            throws IOException, InterruptedException, ExplanationException {
        URI endpoint = buildEndpointUri();

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(resolveTimeout()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("x-api-key", getApiKey().getPlainText())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        LOGGER.info("Sending LangGraph Platform request to " + endpoint);

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new ExplanationException("error", "LangGraph Platform request failed with status "
                    + response.statusCode() + ": " + abbreviate(response.body()));
        }

        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        return extractAssistantContent(json);
    }

    private URI buildEndpointUri() {
        String base = getUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + RUNS_WAIT_PATH);
    }

    private String buildRunRequestBody(String errorLogs, String language, String customContext) throws IOException {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("assistant_id", getModel());

        // Pass system prompt override via the agent's configurable context
        payload.putObject("config")
               .putObject("configurable")
               .put("system_prompt", buildSystemPrompt());

        // Input: error logs as a human message with JSON output instruction
        ObjectNode input = payload.putObject("input");
        ArrayNode messages = input.putArray("messages");
        messages.addObject()
                .put("role", "human")
                .put("content", buildUserPrompt(errorLogs, language, customContext)
                        + "\n\nReturn ONLY valid JSON with these keys: errorSummary, resolutionSteps (array), "
                        + "bestPractices (array), errorSignature.");

        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    private String buildFixRunRequestBody(String errorLogs) throws IOException {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("assistant_id", getModel());

        payload.putObject("config")
               .putObject("configurable")
               .put("system_prompt", getFixSystemPrompt());

        ObjectNode input = payload.putObject("input");
        ArrayNode messages = input.putArray("messages");
        messages.addObject()
                .put("role", "human")
                .put("content", "Jenkins build failed. Analyze and suggest a fix.\n\nError logs:\n" + errorLogs);

        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    /**
     * Extracts the last AI message content from the LangGraph Platform {@code /runs/wait} response.
     * The response is the graph's output state; {@code messages} contains the conversation history
     * where the final entry is the AI's reply.
     */
    private String extractAssistantContent(JsonNode responseJson) throws ExplanationException {
        JsonNode messages = responseJson.path("messages");
        if (messages.isArray() && !messages.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode msg = messages.get(i);
                String type = msg.path("type").asText("");
                String role = msg.path("role").asText("");
                if ("ai".equalsIgnoreCase(type) || "assistant".equalsIgnoreCase(role)) {
                    JsonNode content = msg.path("content");
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
                }
            }
        }
        throw new ExplanationException("error", "LangGraph Platform response did not contain an AI message.");
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

    private static String getFixSystemPrompt() {
        try {
            SystemMessage annotation = FixAssistant.class
                    .getMethod("suggestFix", String.class)
                    .getAnnotation(SystemMessage.class);
            if (annotation != null && annotation.value().length > 0) {
                return String.join("\n", annotation.value());
            }
        } catch (NoSuchMethodException e) {
            LOGGER.warning("Could not read @SystemMessage from FixAssistant: " + e.getMessage());
        }
        throw new IllegalStateException("@SystemMessage annotation not found on FixAssistant.suggestFix");
    }

    private int resolveTimeout() {
        if (timeoutSeconds == null || timeoutSeconds < 1) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return timeoutSeconds;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    @Extension
    @Symbol("langGraph")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "LangGraph Platform";
        }

        @Override
        public String getDefaultModel() {
            return DEFAULT_MODEL;
        }

        @POST
        public FormValidation doTestConfiguration(@QueryParameter("apiKey") Secret apiKey,
                                                  @QueryParameter("url") String url,
                                                  @QueryParameter("model") String model) throws ExplanationException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            LangGraphProvider provider = new LangGraphProvider(url, model, apiKey);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! LangGraph Platform connection is working.");
            } catch (ExplanationException e) {
                return FormValidation.error("Configuration test failed: " + e.getMessage(), e);
            }
        }
    }
}
