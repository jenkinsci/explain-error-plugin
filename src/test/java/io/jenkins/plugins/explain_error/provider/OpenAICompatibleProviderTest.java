package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.autofix.FixAssistant;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OpenAICompatibleProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void explainErrorUsesCustomBaseUrlWithBearerToken() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/chat/completions", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return chatCompletionResponse("{\"errorSummary\":\"Gateway worked\",\"resolutionSteps\":[\"Check the gateway config\"],"
                    + "\"bestPractices\":[\"Use gateway model names\"],\"errorSignature\":\"FAILURE: gateway path verified\"}");
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                endpoint, "gateway-model", Secret.fromString("test-gateway-key"));

        String explanation = provider.explainError("FAILURE: sample error", null, "English", null);

        assertEquals("/chat/completions", requestPath.get());
        assertEquals("Bearer test-gateway-key", authorizationHeader.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("gateway-model", payload.path("model").asText());
        assertTrue(explanation.contains("Gateway worked"));
        assertTrue(explanation.contains("Check the gateway config"));
    }

    @Test
    void explainErrorWithoutApiKeyOmitsAuthorizationHeader() throws Exception {
        AtomicReference<String> authorizationHeader = new AtomicReference<>();

        server.createContext("/chat/completions", new JsonHandler(exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            return chatCompletionResponse("{\"errorSummary\":\"No auth needed\"}");
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(endpoint, "gateway-model", null);

        String explanation = provider.explainError("FAILURE: sample error", null);

        assertNull(authorizationHeader.get(), "No Authorization header should be sent when apiKey is empty");
        assertTrue(explanation.contains("No auth needed"));
    }

    @Test
    void explainErrorFollowsRedirects() throws Exception {
        AtomicReference<String> redirectedPath = new AtomicReference<>();

        HttpServer targetServer = HttpServer.create(new InetSocketAddress(0), 0);
        targetServer.createContext("/chat/completions", new JsonHandler(exchange -> {
            redirectedPath.set(exchange.getRequestURI().toString());
            return chatCompletionResponse("{\"errorSummary\":\"Redirect followed\"}");
        }));
        targetServer.start();

        try {
            String targetUrl = "http://127.0.0.1:" + targetServer.getAddress().getPort() + "/chat/completions";
            server.createContext("/chat/completions", exchange -> {
                exchange.getResponseHeaders().set("Location", targetUrl);
                sendResponse(exchange, 307, "");
            });

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                    endpoint, "gateway-model", Secret.fromString("test-gateway-key"));

            String explanation = provider.explainError("FAILURE: sample error", null);

            assertEquals("/chat/completions", redirectedPath.get(), "redirected request should reach the target endpoint");
            assertTrue(explanation.contains("Redirect followed"));
        } finally {
            targetServer.stop(0);
        }
    }

    @Test
    void explainErrorWith401ReturnsClearAuthenticationMessage() throws Exception {
        server.createContext("/chat/completions", exchange -> {
            sendResponse(exchange, 401, "{\"error\":{\"message\":\"Invalid API key\"}}");
        });

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                endpoint, "gateway-model", Secret.fromString("wrong-key"));

        ExplanationException result = org.junit.jupiter.api.Assertions.assertThrows(
                ExplanationException.class, () -> provider.explainError("FAILURE: sample error", null));

        assertTrue(result.getMessage().contains("Authentication failed (HTTP 401)"),
                "Expected authentication hint in: " + result.getMessage());
        assertTrue(result.getMessage().contains("Invalid API key"),
                "Expected gateway response body in: " + result.getMessage());
    }

    @Test
    void fixAssistantUsesSameEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/chat/completions", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return chatCompletionResponse("{\"fixable\":true,\"explanation\":\"Update the Jenkinsfile\","
                    + "\"confidence\":\"high\",\"fixType\":\"config\",\"changes\":[]}");
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                endpoint, "gateway-fix-model", Secret.fromString("fix-key"));

        FixAssistant assistant = provider.createFixAssistant();
        String result = assistant.suggestFix("FAILURE: job failed");

        assertEquals("/chat/completions", requestPath.get());
        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("gateway-fix-model", payload.path("model").asText());
        assertTrue(result.contains("\"fixable\":true"));
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static String chatCompletionResponse(String content) {
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 0,
                  "model": "gateway-model",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "%s"
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """.formatted(content.replace("\"", "\\\"").replace("\n", "\\n"));
    }

    private interface ResponseSupplier {
        String get(HttpExchange exchange) throws IOException;
    }

    private static class JsonHandler implements HttpHandler {

        private final ResponseSupplier supplier;

        JsonHandler(ResponseSupplier supplier) {
            this.supplier = supplier;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = supplier.get(exchange);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }
}
