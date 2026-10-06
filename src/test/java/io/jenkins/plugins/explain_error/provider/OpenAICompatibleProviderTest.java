package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.autofix.FixAssistant;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
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

    @Test
    void responsesApiPostsToResponsesEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return responsesApiResponse("{\"errorSummary\":\"Responses API worked\","
                    + "\"resolutionSteps\":[\"Check the gateway config\"]}");
        }));

        // A trailing slash must not produce "//responses"
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                endpoint, "gateway-model", Secret.fromString("test-gateway-key"));
        provider.setApiType(OpenAICompatibleProvider.ApiType.RESPONSES);

        String explanation = provider.explainError("FAILURE: sample error", null, "English", null);

        assertEquals("/v1/responses", requestPath.get());
        assertEquals("Bearer test-gateway-key", authorizationHeader.get());
        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("gateway-model", payload.path("model").asText());
        assertEquals(false, payload.path("store").asBoolean(true), "build logs must not be stored by the provider");
        assertTrue(payload.path("input").isArray() && !payload.path("input").isEmpty(), requestBody.get());
        assertTrue(payload.path("input").toString().contains("FAILURE: sample error"), requestBody.get());
        assertTrue(payload.path("text").path("format").path("type").asText().startsWith("json"), requestBody.get());
        assertTrue(explanation.contains("Responses API worked"), explanation);
        assertTrue(explanation.contains("Check the gateway config"), explanation);
    }

    @Test
    void responsesApiWith401ReturnsClearAuthenticationMessage() throws Exception {
        server.createContext("/responses", exchange -> {
            sendResponse(exchange, 401, "{\"error\":{\"message\":\"Invalid API key\"}}");
        });

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                endpoint, "gateway-model", Secret.fromString("wrong-key"));
        provider.setApiType(OpenAICompatibleProvider.ApiType.RESPONSES);

        ExplanationException result = org.junit.jupiter.api.Assertions.assertThrows(
                ExplanationException.class, () -> provider.explainError("FAILURE: sample error", null));

        assertTrue(result.getMessage().contains("Authentication failed (HTTP 401)"),
                "Expected authentication hint in: " + result.getMessage());
    }

    @Test
    void fixAssistantUsesResponsesEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();

        server.createContext("/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            return responsesApiResponse("{\"fixable\":true,\"explanation\":\"Update the Jenkinsfile\","
                    + "\"confidence\":\"high\",\"fixType\":\"config\",\"changes\":[]}");
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                endpoint, "gateway-fix-model", Secret.fromString("fix-key"));
        provider.setApiType(OpenAICompatibleProvider.ApiType.RESPONSES);

        String result = provider.createFixAssistant().suggestFix("FAILURE: job failed");

        assertEquals("/responses", requestPath.get());
        assertTrue(result.contains("\"fixable\":true"), result);
    }

    @Test
    void apiTypeCanBeSetFromConfigurationAsCode() throws Exception {
        OpenAICompatibleProvider provider = DescribableModel.of(OpenAICompatibleProvider.class).instantiate(Map.of(
                "url", "https://gateway.example.com/v1",
                "model", "gateway-model",
                "apiType", "RESPONSES"));

        assertEquals(OpenAICompatibleProvider.ApiType.RESPONSES, provider.getApiType());
    }

    @Test
    void testConfigurationUsesTheSelectedApiType(JenkinsRule jenkins) throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        server.createContext("/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            return responsesApiResponse("{\"errorSummary\":\"ok\"}");
        }));
        OpenAICompatibleProvider.DescriptorImpl descriptor =
                Jenkins.get().getDescriptorByType(OpenAICompatibleProvider.DescriptorImpl.class);

        ListBoxModel apiTypes = descriptor.doFillApiTypeItems();
        FormValidation result = descriptor.doTestConfiguration(null, Secret.fromString("key"),
                "http://127.0.0.1:" + server.getAddress().getPort(), "gateway-model", "RESPONSES");

        assertEquals(2, apiTypes.size());
        assertEquals("CHAT_COMPLETIONS", apiTypes.get(0).value);
        assertEquals("RESPONSES", apiTypes.get(1).value);
        assertEquals(FormValidation.Kind.OK, result.kind, result.getMessage());
        assertEquals("/responses", requestPath.get());

        server.createContext("/chat/completions", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            return chatCompletionResponse("{\"errorSummary\":\"ok\"}");
        }));
        FormValidation unknownType = descriptor.doTestConfiguration(null, Secret.fromString("key"),
                "http://127.0.0.1:" + server.getAddress().getPort(), "gateway-model", "BOGUS");
        assertEquals(FormValidation.Kind.OK, unknownType.kind, unknownType.getMessage());
        assertEquals("/chat/completions", requestPath.get(), "an unknown API type falls back to Chat Completions");
    }

    @Test
    void temperatureIsSentForBothApiTypes() throws Exception {
        AtomicReference<String> chatBody = new AtomicReference<>();
        AtomicReference<String> responsesBody = new AtomicReference<>();
        AtomicReference<String> responsesAuthorization = new AtomicReference<>();
        server.createContext("/chat/completions", new JsonHandler(exchange -> {
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return chatCompletionResponse("{\"errorSummary\":\"chat\"}");
        }));
        server.createContext("/responses", new JsonHandler(exchange -> {
            responsesAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            responsesBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return responsesApiResponse("{\"errorSummary\":\"responses\"}");
        }));
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        String chat = new OpenAICompatibleProvider(endpoint, "gateway-model", Secret.fromString("key"))
                .explainError("FAILURE: sample error", null, null, null, null, null, 0.3);
        OpenAICompatibleProvider responsesProvider = new OpenAICompatibleProvider(endpoint, "gateway-model", null);
        responsesProvider.setApiType(OpenAICompatibleProvider.ApiType.RESPONSES);
        String responses = responsesProvider.explainError("FAILURE: sample error", null, null, null, null, null, 0.6);

        assertTrue(chat.contains("chat"), chat);
        assertTrue(responses.contains("responses"), responses);
        assertEquals(0.3, OBJECT_MAPPER.readTree(chatBody.get()).path("temperature").asDouble());
        assertEquals(0.6, OBJECT_MAPPER.readTree(responsesBody.get()).path("temperature").asDouble());
        assertNull(responsesAuthorization.get(), "no Authorization header without an API key");
    }

    @Test
    void nonStandardHttpStatusIsReportedWithTheStatusCode() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("gateway exploded");
        server.createContext("/chat/completions", exchange -> sendResponse(exchange, 600, body.get()));
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(), "gateway-model", Secret.fromString("key"));

        ExplanationException withBody = org.junit.jupiter.api.Assertions.assertThrows(
                ExplanationException.class, () -> provider.explainError("FAILURE: sample error", null));
        assertTrue(withBody.getMessage().contains("Request to the AI endpoint failed with HTTP 600: gateway exploded"),
                withBody.getMessage());

        body.set("");
        ExplanationException withoutBody = org.junit.jupiter.api.Assertions.assertThrows(
                ExplanationException.class, () -> provider.explainError("FAILURE: sample error", null));
        assertTrue(withoutBody.getMessage().endsWith("Request to the AI endpoint failed with HTTP 600."),
                withoutBody.getMessage());
    }

    private static String responsesApiResponse(String text) {
        return """
                {
                  "id": "resp_test",
                  "object": "response",
                  "created_at": 0,
                  "status": "completed",
                  "model": "gateway-model",
                  "output": [
                    {
                      "type": "message",
                      "id": "msg_test",
                      "status": "completed",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "%s",
                          "annotations": []
                        }
                      ]
                    }
                  ],
                  "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}
                }
                """.formatted(text.replace("\"", "\\\"").replace("\n", "\\n"));
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
