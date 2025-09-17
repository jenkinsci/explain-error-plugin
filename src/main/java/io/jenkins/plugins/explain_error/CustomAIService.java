package io.jenkins.plugins.explain_error;

import java.io.IOException;
import java.net.http.HttpRequest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI service for custom/on-premise models (Ollama, DeepSeek, OSS, etc).
 */
public class CustomAIService extends BaseAIService {
    public CustomAIService(GlobalConfigurationImpl config) {
        super(config);
    }

    @Override
    protected HttpRequest buildHttpRequest(HttpRequest.Builder requestBuilder, String requestBody) {
        return requestBuilder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    }

    @Override
    protected String buildRequestBody(String prompt) throws IOException {
        // OpenAI/Ollama-compatible JSON body
        return String.format("{\"model\": \"%s\", \"messages\": [{\"role\": \"user\", \"content\": \"%s\"}]}",
            config.getModel(), prompt.replace("\"", "\\\""));
    }

    @Override
    protected String parseResponse(String responseBody) throws IOException {
        JsonNode jsonNode = MAPPER.readTree(responseBody);
        JsonNode choices = jsonNode.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode message = choices.get(0).get("message");
            if (message != null && message.get("content") != null) {
                return message.get("content").asText();
            }
        }
        return "No explanation returned by custom AI service.";
    }
}
