package io.jenkins.plugins.explain_error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * Google Gemini-specific implementation of the AI service.
 */
public class GeminiService extends BaseAIService {

    public GeminiService(GlobalConfigurationImpl config) {
        super(config);
    }

    @Override
    protected HttpRequest buildHttpRequest(HttpRequest.Builder requestBuilder, String requestBody) {
        String apiKey = config.getApiKey().getPlainText();
        String url = getApiUrl();
        
        // Add API key as query parameter for Gemini
        if (!url.contains("key=")) {
            url += (url.contains("?") ? "&" : "?") + "key=" + apiKey;
        }
        
        return HttpRequest.newBuilder(java.net.URI.create(url))
            .timeout(java.time.Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    }

    @Override
    protected String buildRequestBody(String prompt) throws IOException {
        ObjectNode requestJson = MAPPER.createObjectNode();
        
        // Create contents array
        ArrayNode contents = MAPPER.createArrayNode();
        ObjectNode content = MAPPER.createObjectNode();
        
        // Create parts array within content
        ArrayNode parts = MAPPER.createArrayNode();
        ObjectNode part = MAPPER.createObjectNode();
        part.put("text", prompt);
        parts.add(part);
        
        content.set("parts", parts);
        contents.add(content);
        
        requestJson.set("contents", contents);
        
        // Add generation config
        ObjectNode generationConfig = MAPPER.createObjectNode();
        generationConfig.put("temperature", 0.3);
        generationConfig.put("maxOutputTokens", 1000);
        requestJson.set("generationConfig", generationConfig);

        return MAPPER.writeValueAsString(requestJson);
    }

    @Override
    protected String parseResponse(String responseBody) throws IOException {
        try {
            JsonNode jsonNode = MAPPER.readTree(responseBody);
            
            // Check for error first
            JsonNode error = jsonNode.get("error");
            if (error != null) {
                JsonNode errorMessage = error.get("message");
                if (errorMessage != null) {
                    return "AI API Error: " + errorMessage.asText();
                }
                return "AI API Error: " + error.toString();
            }
            
            // Parse candidates array
            JsonNode candidates = jsonNode.get("candidates");
            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                JsonNode firstCandidate = candidates.get(0);
                JsonNode content = firstCandidate.get("content");
                if (content != null) {
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray() && parts.size() > 0) {
                        JsonNode firstPart = parts.get(0);
                        JsonNode text = firstPart.get("text");
                        if (text != null && !text.isNull()) {
                            return text.asText().trim();
                        }
                    }
                }
            }

            return "Unable to parse Gemini response. Response: " + responseBody;

        } catch (Exception e) {
            LOGGER.severe("Failed to parse Gemini response: " + e.getMessage());
            return "Failed to parse Gemini response: " + e.getMessage();
        }
    }
}
