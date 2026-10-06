package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.service.SystemMessage;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;
import io.jenkins.plugins.explain_error.autofix.FixAssistant;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@link LangGraphProvider} against a local HTTP server that mimics the
 * LangGraph Platform {@code POST /runs/wait} endpoint. No real AI service is contacted.
 */
class LangGraphProviderTest {

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
    void analyzeErrorPostsRunRequestWithApiKeyAndParsesStructuredJson() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/runs/wait", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, aiMessages("""
                    {"errorSummary":"Compilation failed","resolutionSteps":["Fix the import"," "],\
                    "bestPractices":["Run the build locally"],"errorSignature":"cannot find symbol"}"""));
        });

        // A trailing slash on the base URL must not produce a double slash in the request path.
        LangGraphProvider provider = new LangGraphProvider(baseUrl() + "/", "build-doctor",
                Secret.fromString("lg-api-key"));

        JenkinsLogAnalysis analysis = provider.analyzeError("ERROR: cannot find symbol", null, "French",
                "Mention the module", null, null, 0.4);

        assertEquals("/runs/wait", path.get());
        assertEquals("lg-api-key", apiKeyHeader.get());
        assertEquals("Compilation failed", analysis.errorSummary());
        assertEquals(List.of("Fix the import"), analysis.resolutionSteps(), "blank entries must be dropped");
        assertEquals(List.of("Run the build locally"), analysis.bestPractices());
        assertEquals("cannot find symbol", analysis.errorSignature());

        JsonNode payload = OBJECT_MAPPER.readTree(body.get());
        assertEquals("build-doctor", payload.path("assistant_id").asText());
        JsonNode configurable = payload.path("config").path("configurable");
        assertEquals(BaseAIProvider.SYSTEM_PROMPT, configurable.path("system_prompt").asText());
        assertEquals(0.4, configurable.path("temperature").asDouble());
        JsonNode message = payload.path("input").path("messages").get(0);
        assertEquals("human", message.path("role").asText());
        String content = message.path("content").asText();
        assertTrue(content.contains("ERROR: cannot find symbol"), "error logs must be sent");
        assertTrue(content.contains("French"), "language must be applied to the prompt");
        assertTrue(content.contains("Mention the module"), "custom context must be applied to the prompt");
        assertTrue(content.contains("Return ONLY valid JSON"), "JSON output instruction must be appended");
    }

    @Test
    void temperatureIsOmittedWhenUnset() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/runs/wait", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, aiMessages("{\"errorSummary\":\"ok\"}"));
        });

        newProvider().explainError("ERROR: boom", null);

        JsonNode configurable = OBJECT_MAPPER.readTree(body.get()).path("config").path("configurable");
        assertFalse(configurable.has("temperature"), "unset temperature must not be forwarded");
    }

    @Test
    void usesDefaultAssistantIdWhenModelIsBlank() {
        LangGraphProvider provider = new LangGraphProvider(baseUrl(), "   ", Secret.fromString("key"));

        assertEquals(LangGraphProvider.DEFAULT_MODEL, provider.getModel());
    }

    @Test
    void lastAiMessageWinsAndArrayContentIsConcatenated() throws Exception {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200, """
                {"messages":[
                  {"type":"ai","content":"stale answer"},
                  {"type":"human","content":"follow-up"},
                  {"type":"ai","content":["Plain ", {"type":"text","text":"answer"}, {"type":"image"}]}
                ]}"""));

        JenkinsLogAnalysis analysis = newProvider().analyzeError("ERROR: boom", null, null, null, null, null, null);

        assertEquals("Plain answer", analysis.errorSummary());
        assertNull(analysis.resolutionSteps());
        assertNull(analysis.bestPractices());
        assertNull(analysis.errorSignature());
    }

    @Test
    void assistantRoleMessagesAreAccepted() throws Exception {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200,
                "{\"messages\":[{\"role\":\"assistant\",\"content\":\"{\\\"errorSummary\\\":\\\"from role\\\"}\"}]}"));

        JenkinsLogAnalysis analysis = newProvider().analyzeError("ERROR: boom", null, null, null, null, null, null);

        assertEquals("from role", analysis.errorSummary());
    }

    @Test
    void aiMessageWithEmptyArrayContentFallsBackToEarlierAiMessage() throws Exception {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200, """
                {"messages":[
                  {"type":"ai","content":"earlier answer"},
                  {"type":"ai","content":[{"type":"image"}]}
                ]}"""));

        JenkinsLogAnalysis analysis = newProvider().analyzeError("ERROR: boom", null, null, null, null, null, null);

        assertEquals("earlier answer", analysis.errorSummary());
    }

    @Test
    void jsonWrappedInMarkdownFenceIsParsed() throws Exception {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200, aiMessages("""
                ```json
                {"errorSummary":"Fenced","resolutionSteps":["Step"]}
                ```""")));

        JenkinsLogAnalysis analysis = newProvider().analyzeError("ERROR: boom", null, null, null, null, null, null);

        assertEquals("Fenced", analysis.errorSummary());
        assertEquals(List.of("Step"), analysis.resolutionSteps());
    }

    @Test
    void jsonEmbeddedInProseIsParsed() throws Exception {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200,
                aiMessages("Here is the analysis: {\"errorSummary\":\"Embedded\"} Hope it helps.")));

        JenkinsLogAnalysis analysis = newProvider().analyzeError("ERROR: boom", null, null, null, null, null, null);

        assertEquals("Embedded", analysis.errorSummary());
    }

    @Test
    void plainTextAnswerBecomesTheSummary() throws Exception {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200,
                aiMessages("  The build failed because the disk is full.  ")));

        JenkinsLogAnalysis analysis = newProvider().analyzeError("ERROR: boom", null, null, null, null, null, null);

        assertEquals("The build failed because the disk is full.", analysis.errorSummary());
        assertNull(analysis.resolutionSteps());
    }

    @Test
    void nonObjectJsonAnswerBecomesTheSummary() throws Exception {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200, aiMessages("[\"a\",\"b\"]")));

        JenkinsLogAnalysis analysis = newProvider().analyzeError("ERROR: boom", null, null, null, null, null, null);

        assertEquals("[\"a\",\"b\"]", analysis.errorSummary());
    }

    @Test
    void jsonWithoutSummaryUsesRawContentAndDropsEmptyLists() throws Exception {
        String answer = "{\"errorSummary\":\" \",\"resolutionSteps\":[],\"bestPractices\":[\"\",\" \"],"
                + "\"errorSignature\":\"  \"}";
        server.createContext("/runs/wait", exchange -> respond(exchange, 200, aiMessages(answer)));

        JenkinsLogAnalysis analysis = newProvider().analyzeError("ERROR: boom", null, null, null, null, null, null);

        assertEquals(answer, analysis.errorSummary());
        assertNull(analysis.resolutionSteps(), "empty array must map to null");
        assertNull(analysis.bestPractices(), "array with only blank values must map to null");
        assertNull(analysis.errorSignature());
    }

    @Test
    void httpErrorIsReportedWithAbbreviatedBody() {
        String longBody = "x".repeat(600);
        server.createContext("/runs/wait", exchange -> respond(exchange, 503, longBody));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider().explainError("ERROR: boom", null));

        assertTrue(e.getMessage().contains("LangGraph Platform request failed with status 503"), e.getMessage());
        assertTrue(e.getMessage().contains("x".repeat(500) + "..."), "long bodies must be abbreviated");
        assertFalse(e.getMessage().contains("x".repeat(501)), "body must be cut at 500 characters");
    }

    @Test
    void httpErrorWithEmptyBodyIsReported() {
        server.createContext("/runs/wait", exchange -> respond(exchange, 401, ""));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider().explainError("ERROR: boom", null));

        assertTrue(e.getMessage().contains("failed with status 401"), e.getMessage());
    }

    @Test
    void responseWithoutAiMessageIsRejected() {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200,
                "{\"messages\":[{\"type\":\"human\",\"content\":\"only the question\"}]}"));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider().explainError("ERROR: boom", null));

        assertTrue(e.getMessage().contains("did not contain an AI message"), e.getMessage());
    }

    @Test
    void responseWithoutMessagesIsRejected() {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200, "{\"messages\":[]}"));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider().explainError("ERROR: boom", null));

        assertTrue(e.getMessage().contains("did not contain an AI message"), e.getMessage());
    }

    @Test
    void connectionFailureIsReported() {
        LangGraphProvider provider = newProvider();
        server.stop(0);
        server = null;

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> provider.explainError("ERROR: boom", null));

        assertTrue(e.getMessage().contains("Failed to communicate with LangGraph Platform"), e.getMessage());
    }

    @Test
    void interruptedRequestIsReportedAndKeepsInterruptFlag() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/runs/wait", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, aiMessages("late"));
        });
        LangGraphProvider provider = newProvider();

        try {
            Thread.currentThread().interrupt();
            ExplanationException e = assertThrows(ExplanationException.class,
                    () -> provider.explainError("ERROR: boom", null));

            assertTrue(e.getMessage().contains("Interrupted while communicating with LangGraph Platform"),
                    e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test
    void fixAssistantSendsFixPromptAndReturnsRawAnswer() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/runs/wait", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, aiMessages("{\"fixable\":false,\"changes\":[]}"));
        });

        FixAssistant assistant = newProvider().createFixAssistant(null, null);
        String answer = assistant.suggestFix("ERROR: missing dependency");

        assertEquals("{\"fixable\":false,\"changes\":[]}", answer);
        JsonNode payload = OBJECT_MAPPER.readTree(body.get());
        assertEquals("build-doctor", payload.path("assistant_id").asText());
        String expectedPrompt = String.join("\n", FixAssistant.class.getMethod("suggestFix", String.class)
                .getAnnotation(SystemMessage.class).value());
        assertEquals(expectedPrompt, payload.path("config").path("configurable").path("system_prompt").asText());
        String content = payload.path("input").path("messages").get(0).path("content").asText();
        assertTrue(content.startsWith("Jenkins build failed. Analyze and suggest a fix."), content);
        assertTrue(content.endsWith("ERROR: missing dependency"), content);
    }

    @Test
    void fixAssistantWrapsHttpErrors() {
        server.createContext("/runs/wait", exchange -> respond(exchange, 500, "gateway down"));

        FixAssistant assistant = newProvider().createFixAssistant(null, null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> assistant.suggestFix("ERROR: boom"));

        assertTrue(e.getMessage().contains("failed with status 500: gateway down"), e.getMessage());
    }

    @Test
    void fixAssistantWrapsConnectionFailures() {
        LangGraphProvider provider = newProvider();
        server.stop(0);
        server = null;

        FixAssistant assistant = provider.createFixAssistant(null, null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> assistant.suggestFix("ERROR: boom"));

        assertTrue(e.getMessage().contains("Failed to communicate with LangGraph Platform"), e.getMessage());
    }

    @Test
    void fixAssistantReportsInterruption() {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/runs/wait", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, aiMessages("late"));
        });
        FixAssistant assistant = newProvider().createFixAssistant(null, null);

        try {
            Thread.currentThread().interrupt();
            RuntimeException e = assertThrows(RuntimeException.class, () -> assistant.suggestFix("ERROR: boom"));

            assertTrue(e.getMessage().contains("Interrupted while communicating with LangGraph Platform"),
                    e.getMessage());
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test
    void invalidTimeoutsFallBackToDefault() throws Exception {
        server.createContext("/runs/wait", exchange -> respond(exchange, 200, aiMessages("{\"errorSummary\":\"ok\"}")));
        LangGraphProvider provider = newProvider();
        assertEquals(LangGraphProvider.DEFAULT_TIMEOUT_SECONDS, provider.getTimeoutSeconds());

        provider.setTimeoutSeconds(0);
        assertTrue(provider.explainError("ERROR: boom", null).contains("ok"));

        provider.setTimeoutSeconds(null);
        assertNull(provider.getTimeoutSeconds());
        assertTrue(provider.explainError("ERROR: boom", null).contains("ok"));

        provider.setTimeoutSeconds(5);
        assertEquals(5, provider.getTimeoutSeconds());
        assertTrue(provider.explainError("ERROR: boom", null).contains("ok"));
    }

    @Test
    void descriptorExposesDisplayNameAndDefaultModel() {
        LangGraphProvider.DescriptorImpl descriptor = new LangGraphProvider.DescriptorImpl();

        assertEquals("LangGraph Platform", descriptor.getDisplayName());
        assertEquals(LangGraphProvider.DEFAULT_MODEL, descriptor.getDefaultModel());
    }

    private LangGraphProvider newProvider() {
        return new LangGraphProvider(baseUrl(), "build-doctor", Secret.fromString("lg-api-key"));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String aiMessages(String content) {
        return OBJECT_MAPPER.createObjectNode()
                .set("messages", OBJECT_MAPPER.createArrayNode()
                        .add(OBJECT_MAPPER.createObjectNode().put("type", "human").put("content", "question"))
                        .add(OBJECT_MAPPER.createObjectNode().put("type", "ai").put("content", content)))
                .toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
