package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ProviderSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
            OllamaProvider provider = new OllamaProvider(server.baseUrl(), "test-model", null);

            String explanation = provider.explainError(ERROR_LOGS, null);

            assertTrue(explanation.contains("Ollama smoke passed"), explanation);
            assertEquals(1, server.requestCount(), "provider should make one HTTP request");
            assertTrue(server.requestBodies().get(0).contains("test-model"),
                    "request should include the configured model");
            assertTrue(server.requestPaths().get(0).contains("/api/chat"),
                    "Ollama request should target the chat endpoint");

            JsonNode requestBody = OBJECT_MAPPER.readTree(server.requestBodies().get(0));
            assertTrue(requestBody.has("think"), "Ollama requests should explicitly configure thinking");
            assertFalse(requestBody.path("think").asBoolean(true),
                    "Ollama requests should disable thinking traces");
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

    @Test
    void openAiProviderParsesJsonWrappedInMarkdownCodeFence() throws Exception {
        try (StubAiServer server = StubAiServer.openAiContent(fenced(analysisJson("Fenced OpenAI answer")))) {
            OpenAIProvider provider = new OpenAIProvider(
                    server.baseUrl(), "test-model", Secret.fromString("test-key"));

            String explanation = provider.explainError(ERROR_LOGS, null);

            assertTrue(explanation.startsWith("Summary: Fenced OpenAI answer"), explanation);
            assertTrue(explanation.contains("- Check the failing command"), explanation);
            assertFalse(explanation.contains("```"), explanation);
        }
    }

    @Test
    void anthropicProviderParsesJsonWrappedInMarkdownCodeFence() throws Exception {
        try (StubAiServer server = StubAiServer.anthropicContent(fenced(analysisJson("Fenced Claude answer")))) {
            AnthropicProvider provider = new AnthropicProvider(
                    server.baseUrl(), "test-model", Secret.fromString("test-key"), null, null);

            String explanation = provider.explainError(ERROR_LOGS, null);

            assertTrue(explanation.startsWith("Summary: Fenced Claude answer"), explanation);
            assertTrue(explanation.contains("- Check the failing command"), explanation);
            assertFalse(explanation.contains("```"), explanation);
        }
    }

    @Test
    void chatModelProvidersAskForTheStructuredAnalysisFields() throws Exception {
        // LangChain4j only adds the JSON output instructions when the Assistant returns
        // JenkinsLogAnalysis; a String return type would silently drop them.
        try (StubAiServer server = StubAiServer.anthropicContent(analysisJson("Structured request"))) {
            AnthropicProvider provider = new AnthropicProvider(
                    server.baseUrl(), "test-model", Secret.fromString("test-key"), null, null);

            provider.explainError(ERROR_LOGS, null);

            String requestBody = server.requestBodies().get(0);
            assertTrue(requestBody.contains("resolutionSteps") && requestBody.contains("errorSignature"),
                    "request should describe the JenkinsLogAnalysis fields: " + requestBody);
        }
    }

    @Test
    void openAiFamilyProvidersSendBearerKeyModelAndTemperature() throws Exception {
        try (StubAiServer server = StubAiServer.openAi("OpenAI family answer")) {
            List<BaseAIProvider> providers = List.of(
                    new OpenAIProvider(server.baseUrl(), "openai-model", Secret.fromString("openai-key")),
                    new DeepSeekProvider(server.baseUrl(), "deepseek-model", Secret.fromString("deepseek-key")),
                    new QwenProvider(server.baseUrl(), "qwen-model", Secret.fromString("qwen-key"), null),
                    new MicrosoftFoundryProvider(server.baseUrl() + "/", "foundry-model",
                            Secret.fromString("foundry-key")));

            for (BaseAIProvider provider : providers) {
                String explanation = provider.explainError(ERROR_LOGS, null, null, null, null, null, 0.25);
                assertTrue(explanation.contains("OpenAI family answer"), provider.getClass() + ": " + explanation);
            }

            assertEquals(List.of("Bearer openai-key", "Bearer deepseek-key", "Bearer qwen-key", "Bearer foundry-key"),
                    server.authorizationHeaders());
            List<String> models = new ArrayList<>();
            for (String body : server.requestBodies()) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                models.add(request.path("model").asText());
                assertEquals(0.25, request.path("temperature").asDouble(), body);
            }
            assertEquals(List.of("openai-model", "deepseek-model", "qwen-model", "foundry-model"), models);
            assertEquals("/openai/v1/chat/completions", server.requestPaths().get(3),
                    "Microsoft Foundry requests must target the OpenAI v1 path");
        }
    }

    @Test
    void openAiFamilyProvidersOmitTemperatureWhenUnset() throws Exception {
        try (StubAiServer server = StubAiServer.openAi("No temperature")) {
            new DeepSeekProvider(server.baseUrl(), "deepseek-model", Secret.fromString("key"))
                    .explainError(ERROR_LOGS, null);
            new QwenProvider(server.baseUrl(), "qwen-model", Secret.fromString("key"), " ")
                    .explainError(ERROR_LOGS, null);

            for (String body : server.requestBodies()) {
                assertFalse(OBJECT_MAPPER.readTree(body).has("temperature"), body);
            }
        }
    }

    @Test
    void geminiProviderSendsTemperatureInGenerationConfig() throws Exception {
        try (StubAiServer server = StubAiServer.gemini("Gemini temperature")) {
            GeminiProvider provider = new GeminiProvider(server.baseUrl(), "test-model", Secret.fromString("key"));

            String explanation = provider.explainError(ERROR_LOGS, null, null, null, null, null, 0.4);

            assertTrue(explanation.contains("Gemini temperature"), explanation);
            JsonNode request = OBJECT_MAPPER.readTree(server.requestBodies().get(0));
            assertEquals(0.4, request.path("generationConfig").path("temperature").asDouble(),
                    server.requestBodies().get(0));
        }
    }

    @Test
    void ollamaProviderSendsBearerApiKeyAndTemperature() throws Exception {
        try (StubAiServer server = StubAiServer.ollama("Ollama with key")) {
            OllamaProvider provider = new OllamaProvider(server.baseUrl(), "test-model",
                    Secret.fromString("ollama-key"));

            String explanation = provider.explainError(ERROR_LOGS, null, null, null, null, null, 0.6);

            assertTrue(explanation.contains("Ollama with key"), explanation);
            assertEquals(List.of("Bearer ollama-key"), server.authorizationHeaders());
            JsonNode request = OBJECT_MAPPER.readTree(server.requestBodies().get(0));
            assertEquals(0.6, request.path("options").path("temperature").asDouble(), server.requestBodies().get(0));
        }
    }

    @Test
    void anthropicProviderClampsTemperatureAndSkipsItForClaude47AndNewer() throws Exception {
        Logger logger = Logger.getLogger(AnthropicProvider.class.getName());
        Logger langChainLogger = Logger.getLogger("dev.langchain4j");
        Level previousLevel = logger.getLevel();
        Level previousLangChainLevel = langChainLogger.getLevel();
        // FINE logging evaluates the lazily built log messages of both temperature branches;
        // it also turns on request logging, which is kept out of the test output.
        logger.setLevel(Level.FINE);
        langChainLogger.setLevel(Level.WARNING);
        try (StubAiServer server = StubAiServer.anthropicContent(analysisJson("Claude temperature"))) {
            List<String> models = List.of("claude-sonnet-4-6", "Claude-Opus-4-7", "claude-sonnet-4-8-20270101",
                    "claude-haiku-5", "claude-opus-5-1", "claude-sonnet-4-6");
            List<Double> temperatures = List.of(1.5, 0.2, 0.2, 0.2, 0.2, -1.0);
            for (int i = 0; i < models.size(); i++) {
                AnthropicProvider provider = new AnthropicProvider(server.baseUrl(), models.get(i),
                        Secret.fromString("anthropic-key"), null, 512);
                provider.explainError(ERROR_LOGS, null, null, null, null, null, temperatures.get(i));
            }
            new AnthropicProvider(server.baseUrl(), "claude-opus-4-7", Secret.fromString("anthropic-key"), null, null)
                    .explainError(ERROR_LOGS, null);

            List<String> bodies = server.requestBodies();
            assertEquals(1.0, OBJECT_MAPPER.readTree(bodies.get(0)).path("temperature").asDouble(),
                    "temperatures above 1 must be clamped for Anthropic");
            for (int i = 1; i <= 4; i++) {
                assertFalse(OBJECT_MAPPER.readTree(bodies.get(i)).has("temperature"),
                        "Claude 4.7+ must not receive a temperature: " + models.get(i));
            }
            assertEquals(0.0, OBJECT_MAPPER.readTree(bodies.get(5)).path("temperature").asDouble(-1),
                    "negative temperatures must be clamped to 0");
            assertFalse(OBJECT_MAPPER.readTree(bodies.get(6)).has("temperature"));
            assertEquals(512, OBJECT_MAPPER.readTree(bodies.get(0)).path("max_tokens").asInt());
            assertEquals(4096, OBJECT_MAPPER.readTree(bodies.get(6)).path("max_tokens").asInt());
            assertTrue(server.requestHeaders("x-api-key").stream().allMatch("anthropic-key"::equals));
        } finally {
            logger.setLevel(previousLevel);
            langChainLogger.setLevel(previousLangChainLevel);
        }
    }

    @Test
    void anthropicProviderUsesDefaultTemperatureWhenUnset() throws Exception {
        try (StubAiServer server = StubAiServer.anthropicContent(analysisJson("Default temperature"))) {
            new AnthropicProvider(server.baseUrl(), "claude-sonnet-4-6", Secret.fromString("key"), null, null)
                    .explainError(ERROR_LOGS, null);

            assertEquals(0.3, OBJECT_MAPPER.readTree(server.requestBodies().get(0)).path("temperature").asDouble());
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

    /** Wraps escaped JSON content in a markdown code fence, as some models do despite instructions. */
    private static String fenced(String escapedJson) {
        return "```json\\n" + escapedJson + "\\n```";
    }

    private static final class StubAiServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        private final List<String> requestPaths = Collections.synchronizedList(new ArrayList<>());
        private final List<Headers> requestHeaders =
                Collections.synchronizedList(new ArrayList<>());

        private StubAiServer(String responseBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> respond(exchange, responseBody));
            server.start();
        }

        private static StubAiServer openAi(String summary) throws IOException {
            return openAiContent(analysisJson(summary));
        }

        private static StubAiServer openAiContent(String content) throws IOException {
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
                    """.formatted(content));
        }

        private static StubAiServer anthropicContent(String content) throws IOException {
            return new StubAiServer("""
                    {
                      "id": "msg_smoke",
                      "type": "message",
                      "role": "assistant",
                      "model": "test-model",
                      "content": [{"type": "text", "text": "%s"}],
                      "stop_reason": "end_turn",
                      "stop_sequence": null,
                      "usage": {"input_tokens": 1, "output_tokens": 1}
                    }
                    """.formatted(content));
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
            requestHeaders.add(exchange.getRequestHeaders());
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

        private List<String> requestHeaders(String name) {
            List<String> values = new ArrayList<>();
            for (Headers headers : requestHeaders) {
                values.add(headers.getFirst(name));
            }
            return values;
        }

        private List<String> authorizationHeaders() {
            return requestHeaders("Authorization");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
