package io.jenkins.plugins.explain_error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * Ollama-specific implementation of the AI service.
 */
public class OllamaService extends BaseAIService {

    public OllamaService(GlobalConfigurationImpl config) {
        super(config);
    }

    @Override
    protected HttpRequest buildHttpRequest(HttpRequest.Builder requestBuilder, String requestBody) {
        // Ollama does not require an API key by default, but may require auth in some setups
        return requestBuilder
            .timeout(java.time.Duration.ofSeconds(180)) // 3 minutes for long LLM inference
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    }

    @Override
    protected String buildRequestBody(String prompt) throws IOException {
        ObjectNode requestJson = MAPPER.createObjectNode();
        requestJson.put("model", config.getModel());
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestJson.set("messages", messages);
        requestJson.put("stream", false); // Ensure we get a single, complete response
        return MAPPER.writeValueAsString(requestJson);
    }

    @Override
    protected String parseResponse(String responseBody) throws IOException {
        // Ollama returns different shapes depending on endpoint (/api/chat, /api/generate, streaming vs non-streaming)
        // We try multiple strategies to extract assistant text.
        JsonNode root = MAPPER.readTree(responseBody);

        // 1. Standard chat response: { message: { role: "assistant", content: "..." }, ... }
        JsonNode messageNode = root.get("message");
        if (messageNode != null && messageNode.has("content")) {
            String content = messageNode.get("content").asText();
            if (content != null && !content.trim().isEmpty()) {
                return content.trim();
            }
        }

        // 2. Generate endpoint style: { response: "..." }
        if (root.has("response")) {
            String content = root.get("response").asText();
            if (content != null && !content.trim().isEmpty()) {
                return content.trim();
            }
        }

        // 3. Some variants might return an array of messages or a messages field
        if (root.has("messages")) {
            JsonNode messages = root.get("messages");
            if (messages.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode msg : messages) {
                    if (msg.has("role") && "assistant".equalsIgnoreCase(msg.get("role").asText()) && msg.has("content")) {
                        String part = msg.get("content").asText();
                        if (part != null && !part.trim().isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(part.trim());
                        }
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        }

        // 4. Fallback: if root has a top-level "content"
        if (root.has("content")) {
            String content = root.get("content").asText();
            if (content != null && !content.trim().isEmpty()) {
                return content.trim();
            }
        }

        // 5. As a last resort, return a truncated JSON snippet to aid debugging instead of a generic message
        String jsonSnippet = responseBody.length() > 1200 ? responseBody.substring(0, 1200) + "..." : responseBody;
        return "No direct explanation field found in Ollama response. Raw snippet:\n" + jsonSnippet;
    }
}
