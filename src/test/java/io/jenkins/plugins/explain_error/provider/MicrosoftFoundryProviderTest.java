package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
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
class MicrosoftFoundryProviderTest {

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
    void explainErrorUsesOpenAiV1EndpointAndBearerToken() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/v1/chat/completions", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": 0,
                      "model": "foundry-gpt",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "{\\"errorSummary\\":\\"Foundry worked\\",\\"resolutionSteps\\":[\\"Check deployment configuration\\"],\\"bestPractices\\":[\\"Use deployment names\\"],\\"errorSignature\\":\\"FAILURE: foundry path verified\\"}"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        MicrosoftFoundryProvider provider = new MicrosoftFoundryProvider(
                endpoint, "foundry-gpt", Secret.fromString("test-foundry-key"));

        String explanation = provider.explainError("FAILURE: sample error", null, "English", "Prioritize root cause");

        assertEquals("/openai/v1/chat/completions", requestPath.get());
        assertEquals("Bearer test-foundry-key", authorizationHeader.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("foundry-gpt", payload.path("model").asText());
        assertFalse(payload.has("temperature"), "Unset temperature should be omitted from Microsoft Foundry payload");
        assertTrue(requestBody.get().contains("Prioritize root cause"));
        assertTrue(explanation.contains("Foundry worked"));
        assertTrue(explanation.contains("Check deployment configuration"));
    }

    @Test
    void explainErrorSendsConfiguredTemperature() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/v1/chat/completions", new JsonHandler(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": 0,
                      "model": "foundry-gpt",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "{\\"errorSummary\\":\\"Foundry temperature worked\\",\\"resolutionSteps\\":[\\"Check temperature\\"],\\"bestPractices\\":[\\"Prefer provider defaults when unset\\"],\\"errorSignature\\":\\"FAILURE: temperature verified\\"}"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        MicrosoftFoundryProvider provider = new MicrosoftFoundryProvider(
                endpoint, "foundry-gpt", Secret.fromString("test-foundry-key"));

        provider.explainError("FAILURE: sample error", null, "English", null, null, null, 0.65);

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals(0.65, payload.path("temperature").asDouble());
    }

    @Test
    void fixAssistantDoesNotDuplicateOpenAiV1Path() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/v1/chat/completions", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": 0,
                      "model": "foundry-fix",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "{\\"fixable\\":true,\\"explanation\\":\\"Update the Jenkinsfile\\",\\"confidence\\":\\"high\\",\\"fixType\\":\\"config\\",\\"changes\\":[]}"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1/";
        MicrosoftFoundryProvider provider = new MicrosoftFoundryProvider(
                endpoint, "foundry-fix", Secret.fromString("fix-key"));

        FixAssistant assistant = provider.createFixAssistant();
        String result = assistant.suggestFix("FAILURE: job failed");

        assertEquals("/openai/v1/chat/completions", requestPath.get());
        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("foundry-fix", payload.path("model").asText());
        assertTrue(result.contains("\"fixable\":true"));
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
