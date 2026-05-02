package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderSmokeTest {

    private static final String ERROR_LOGS = """
            Started by user admin
            ERROR: Could not find or load main class Application
            FAILURE: Build failed with an exception.
            """;

    @Test
    void openAiCompatibleProviderSmokeTest() throws Exception {
        try (StubAiServer server = StubAiServer.openAi("OpenAI-compatible smoke passed")) {
            OpenAIProvider provider = new OpenAIProvider(
                    server.baseUrl(), "test-model", Secret.fromString("test-key"));

            String explanation = provider.explainError(ERROR_LOGS, null);

            assertTrue(explanation.contains("OpenAI-compatible smoke passed"), explanation);
            assertEquals(1, server.requestCount(), "provider should make one HTTP request");
            assertTrue(server.requestBodies().get(0).contains("test-model"),
                    "request should include the configured model");
            assertTrue(server.requestPaths().get(0).contains("chat/completions"),
                    "OpenAI-compatible request should target chat completions");
        }
    }

    @Test
    void ollamaProviderSmokeTest() throws Exception {
        try (StubAiServer server = StubAiServer.ollama("Ollama smoke passed")) {
            OllamaProvider provider = new OllamaProvider(server.baseUrl(), "test-model");

            String explanation = provider.explainError(ERROR_LOGS, null);

            assertTrue(explanation.contains("Ollama smoke passed"), explanation);
            assertEquals(1, server.requestCount(), "provider should make one HTTP request");
            assertTrue(server.requestBodies().get(0).contains("test-model"),
                    "request should include the configured model");
            assertTrue(server.requestPaths().get(0).contains("/api/chat"),
                    "Ollama request should target the chat endpoint");
        }
    }

    @Test
    void geminiProviderSmokeTest() throws Exception {
        try (StubAiServer server = StubAiServer.gemini("Gemini smoke passed")) {
            GeminiProvider provider = new GeminiProvider(
                    server.baseUrl(), "test-model", Secret.fromString("test-key"));

            String explanation = provider.explainError(ERROR_LOGS, null);

            assertTrue(explanation.contains("Gemini smoke passed"), explanation);
            assertEquals(1, server.requestCount(), "provider should make one HTTP request");
            assertTrue(server.requestPaths().get(0).contains(":generateContent"),
                    "Gemini request should target generateContent");
        }
    }

    private static String analysisJson(String summary) {
        return """
                {
                  "errorSummary": "%s",
                  "resolutionSteps": ["Check the failing command"],
                  "bestPractices": ["Keep provider smoke tests deterministic"],
                  "errorSignature": "FAILURE: Build failed with an exception."
                }
                """.formatted(summary).replace("\n", "").replace("\"", "\\\"");
    }

    private static final class StubAiServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        private final List<String> requestPaths = Collections.synchronizedList(new ArrayList<>());

        private StubAiServer(String responseBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> respond(exchange, responseBody));
            server.start();
        }

        private static StubAiServer openAi(String summary) throws IOException {
            return new StubAiServer("""
                    {
                      "id": "chatcmpl-smoke",
                      "object": "chat.completion",
                      "created": 0,
                      "model": "test-model",
                      "choices": [{
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": "%s"
                        },
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                    }
                    """.formatted(analysisJson(summary)));
        }

        private static StubAiServer ollama(String summary) throws IOException {
            return new StubAiServer("""
                    {
                      "model": "test-model",
                      "created_at": "2026-05-02T00:00:00Z",
                      "message": {
                        "role": "assistant",
                        "content": "%s"
                      },
                      "done": true
                    }
                    """.formatted(analysisJson(summary)));
        }

        private static StubAiServer gemini(String summary) throws IOException {
            return new StubAiServer("""
                    {
                      "candidates": [{
                        "content": {
                          "parts": [{
                            "text": "%s"
                          }],
                          "role": "model"
                        },
                        "finishReason": "STOP",
                        "index": 0
                      }],
                      "usageMetadata": {
                        "promptTokenCount": 1,
                        "candidatesTokenCount": 1,
                        "totalTokenCount": 2
                      }
                    }
                    """.formatted(analysisJson(summary)));
        }

        private void respond(HttpExchange exchange, String responseBody) throws IOException {
            requestCount.incrementAndGet();
            requestPaths.add(exchange.getRequestURI().toString());
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int requestCount() {
            return requestCount.get();
        }

        private List<String> requestBodies() {
            return requestBodies;
        }

        private List<String> requestPaths() {
            return requestPaths;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
