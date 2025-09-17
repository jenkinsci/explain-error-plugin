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
        // Add a system message for context, then the user message
        return String.format(
            "{\"model\": \"%s\", \"messages\": [" +
            "{\"role\": \"system\", \"content\": \"You are a helpful assistant.\"}," +
            "{\"role\": \"user\", \"content\": \"%s\"}]}",
            config.getModel(), prompt.replace("\"", "\\\"")
        );
    }

    @Override
    protected String parseResponse(String responseBody) throws IOException {
        try {
            JsonNode jsonNode = MAPPER.readTree(responseBody);
            JsonNode choices = jsonNode.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.get("content") != null) {
                    return message.get("content").asText();
                }
            }
            // If choices missing or empty, log and return raw response
            System.err.println("[CustomAIService] Unexpected response structure: " + responseBody);
            return "Custom AI service returned unexpected response: " + responseBody;
        } catch (Exception e) {
            // Log the full response and stack trace for debugging
            System.err.println("[CustomAIService] Exception parsing response: " + e.getMessage());
            System.err.println("[CustomAIService] Raw response: " + responseBody);
            e.printStackTrace();
            return "Failed to parse custom AI service response: " + e.getMessage() + " | Raw: " + responseBody;
        }
    }
}
