package io.jenkins.plugins.explain_error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * OpenAI-specific implementation of the AI service.
 */
public class OpenAIService extends BaseAIService {

    public OpenAIService(GlobalConfigurationImpl config) {
        super(config);
    }

    @Override
    protected HttpRequest buildHttpRequest(HttpRequest.Builder requestBuilder, String requestBody) {
        return requestBuilder
            .timeout(java.time.Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.getApiKey().getPlainText())
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    }

    @Override
    protected String buildRequestBody(String prompt) throws IOException {
        ObjectNode requestJson = MAPPER.createObjectNode();
        requestJson.put("model", config.getModel());
        requestJson.put("max_tokens", 1000);
        requestJson.put("temperature", 0.3);

        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);

        requestJson.set("messages", messages);

        return MAPPER.writeValueAsString(requestJson);
    }

    @Override
    protected String parseResponse(String responseBody) throws IOException {
        try {
            JsonNode jsonNode = MAPPER.readTree(responseBody);
            JsonNode choices = jsonNode.get("choices");

            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null && !content.isNull()) {
                        return content.asText().trim();
                    }
                }
            }

            // Check for error in response
            JsonNode error = jsonNode.get("error");
            if (error != null) {
                JsonNode errorMessage = error.get("message");
                if (errorMessage != null) {
                    return "AI API Error: " + errorMessage.asText();
                }
            }

            return "Unable to parse AI response. Response: " + responseBody;

        } catch (Exception e) {
            LOGGER.severe("Failed to parse AI response: " + e.getMessage());
            return "Failed to parse AI response: " + e.getMessage();
        }
    }
}
