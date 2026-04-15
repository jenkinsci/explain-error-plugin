package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomOktaAIProviderTest {

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
    void explainErrorUsesOktaTokenAndSendsConfiguredMetadata() throws Exception {
        AtomicReference<String> tokenAuthHeader = new AtomicReference<>();
        AtomicReference<String> chatAuthHeader = new AtomicReference<>();
        AtomicReference<String> chatBody = new AtomicReference<>();
        AtomicReference<String> chatPath = new AtomicReference<>();

        server.createContext("/oauth2/default/v1/token", new JsonHandler(exchange -> {
            tokenAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            return "{\"access_token\":\"okta-access-token\"}";
        }));

        server.createContext("/openai/deployments/gpt-5-nano/chat/completions", new JsonHandler(exchange -> {
            chatAuthHeader.set(exchange.getRequestHeaders().getFirst("api-key"));
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            chatPath.set(exchange.getRequestURI().toString());
            return """
                    {
                      \"choices\": [
                        {
                          \"message\": {
                            \"content\": \"{\\\"errorSummary\\\":\\\"Okta-backed provider worked\\\",\\\"resolutionSteps\\\":[\\\"Check the upstream service\\\"],\\\"bestPractices\\\":[\\\"Rotate Okta credentials regularly\\\"],\\\"errorSignature\\\":\\\"FAILURE: token verified\\\"}\"
                          }
                        }
                      ]
                    }
                    """;
        }));

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        CustomOktaAIProvider provider = new CustomOktaAIProvider(
                baseUrl + "/openai/deployments/{model}/chat/completions",
                baseUrl + "/oauth2/default/v1/token",
                "gpt-5-nano",
                "my-client-id",
                Secret.fromString("my-client-secret"));
        provider.setAccessTokenHeader("api-key");
        provider.setAccessTokenPrefix("");
        provider.setApiVersion("2025-04-01-preview");
        provider.setAppKey(Secret.fromString("team-app-key"));
        provider.setUserId("cec-user");

        String explanation = provider.explainError("FAILURE: sample error", null, "English", "Prioritize root cause");

        assertTrue(tokenAuthHeader.get().startsWith("Basic "));
        assertEquals("okta-access-token", chatAuthHeader.get());
        assertEquals("/openai/deployments/gpt-5-nano/chat/completions?api-version=2025-04-01-preview",
                chatPath.get());

        JsonNode payload = OBJECT_MAPPER.readTree(chatBody.get());
        JsonNode userMetadata = OBJECT_MAPPER.readTree(payload.path("user").asText());
        assertEquals("team-app-key", userMetadata.path("appkey").asText());
        assertEquals("cec-user", userMetadata.path("user").asText());

        assertTrue(chatBody.get().contains("Return ONLY valid JSON"));
        assertTrue(explanation.contains("Okta-backed provider worked"));
        assertTrue(explanation.contains("Check the upstream service"));
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