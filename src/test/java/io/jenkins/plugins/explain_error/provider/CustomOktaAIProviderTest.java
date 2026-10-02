package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;
import io.jenkins.plugins.explain_error.autofix.FixAssistant;
import java.io.ByteArrayOutputStream;
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
        assertFalse(payload.has("temperature"), "Unset temperature should be omitted from Custom Okta request payload");
        JsonNode userMetadata = OBJECT_MAPPER.readTree(payload.path("user").asText());
        assertEquals("team-app-key", userMetadata.path("appkey").asText());
        assertEquals("cec-user", userMetadata.path("user").asText());

        assertTrue(chatBody.get().contains("Return ONLY valid JSON"));
        assertTrue(explanation.contains("Okta-backed provider worked"));
        assertTrue(explanation.contains("Check the upstream service"));
    }

    @Test
    void chatUrlWithoutPlaceholderGetsModelPathAndBearerPrefixAndScopeAreSent() throws Exception {
        AtomicReference<String> tokenBody = new AtomicReference<>();
        AtomicReference<String> tokenContentType = new AtomicReference<>();
        AtomicReference<String> chatPath = new AtomicReference<>();
        AtomicReference<String> chatAuthHeader = new AtomicReference<>();
        AtomicReference<String> chatBody = new AtomicReference<>();
        server.createContext("/token", new JsonHandler(exchange -> {
            tokenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return "{\"access_token\":\" okta-token \"}";
        }));
        server.createContext("/openai/deployments/", new JsonHandler(exchange -> {
            chatPath.set(exchange.getRequestURI().toString());
            chatAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return chatResponse("{\"errorSummary\":\"Scoped\"}");
        }));

        CustomOktaAIProvider provider = newProvider(baseUrl() + "/openai/deployments", "gpt-5-mini");
        provider.setScope("ai.read ai.write");
        provider.setAccessTokenPrefix("Bearer");
        provider.setApiVersion("2025-01-01");

        String explanation = provider.explainError("FAILURE: sample error", null, null, null, null, null, 0.2);

        assertEquals("application/x-www-form-urlencoded", tokenContentType.get());
        assertEquals("grant_type=client_credentials&scope=ai.read+ai.write", tokenBody.get());
        assertEquals("/openai/deployments/gpt-5-mini/chat/completions?api-version=2025-01-01", chatPath.get());
        assertEquals("Bearer okta-token", chatAuthHeader.get(), "access token must be trimmed and prefixed");
        JsonNode payload = OBJECT_MAPPER.readTree(chatBody.get());
        assertEquals("gpt-5-mini", payload.path("model").asText());
        assertEquals(0.2, payload.path("temperature").asDouble());
        assertFalse(payload.has("user"), "user metadata must be omitted when neither app key nor user ID is set");
        assertEquals("system", payload.path("messages").get(0).path("role").asText());
        assertEquals(BaseAIProvider.SYSTEM_PROMPT, payload.path("messages").get(0).path("content").asText());
        assertTrue(explanation.contains("Scoped"));
    }

    @Test
    void chatUrlWithTrailingSlashAndNoScopeOrApiVersion() throws Exception {
        AtomicReference<String> tokenBody = new AtomicReference<>();
        AtomicReference<String> chatPath = new AtomicReference<>();
        AtomicReference<String> chatAuthHeader = new AtomicReference<>();
        server.createContext("/token", new JsonHandler(exchange -> {
            tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return "{\"access_token\":\"okta-token\"}";
        }));
        server.createContext("/deployments/", new JsonHandler(exchange -> {
            chatPath.set(exchange.getRequestURI().toString());
            chatAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            return chatResponse("{\"errorSummary\":\"Default header\"}");
        }));

        CustomOktaAIProvider provider = newProvider(baseUrl() + "/deployments/", "model-a");
        // A blank header name falls back to the default Authorization header.
        provider.setAccessTokenHeader("  ");

        provider.explainError("FAILURE: sample error", null);

        assertEquals("grant_type=client_credentials", tokenBody.get());
        assertEquals("/deployments/model-a/chat/completions", chatPath.get());
        assertEquals("okta-token", chatAuthHeader.get(), "the default prefix is empty");
    }

    @Test
    void fullChatCompletionsUrlKeepsExistingQueryParameters() throws Exception {
        AtomicReference<String> chatPath = new AtomicReference<>();
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/v1/chat/completions", new JsonHandler(exchange -> {
            chatPath.set(exchange.getRequestURI().toString());
            return chatResponse("{\"errorSummary\":\"ok\"}");
        }));

        CustomOktaAIProvider provider = newProvider(baseUrl() + "/v1/chat/completions?tenant=a", "model-a");
        provider.setApiVersion("2024-10-21");
        provider.explainError("FAILURE: sample error", null);
        assertEquals("/v1/chat/completions?tenant=a&api-version=2024-10-21", chatPath.get());

        CustomOktaAIProvider pinned = newProvider(baseUrl() + "/v1/chat/completions?api-version=2023-05-15", "model-a");
        pinned.setApiVersion("2024-10-21");
        pinned.explainError("FAILURE: sample error", null);
        assertEquals("/v1/chat/completions?api-version=2023-05-15", chatPath.get(),
                "an api-version already present in the URL must not be duplicated");
    }

    @Test
    void userMetadataContainsOnlyConfiguredFields() throws Exception {
        AtomicReference<String> chatBody = new AtomicReference<>();
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange -> {
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return chatResponse("{\"errorSummary\":\"ok\"}");
        }));

        CustomOktaAIProvider userOnly = newProvider(baseUrl() + "/chat/{model}/chat/completions", "m");
        userOnly.setUserId(" build-bot ");
        userOnly.explainError("FAILURE: sample error", null);
        JsonNode userMetadata = OBJECT_MAPPER.readTree(OBJECT_MAPPER.readTree(chatBody.get()).path("user").asText());
        assertEquals("build-bot", userMetadata.path("user").asText());
        assertFalse(userMetadata.has("appkey"));

        CustomOktaAIProvider appKeyOnly = newProvider(baseUrl() + "/chat/{model}/chat/completions", "m");
        appKeyOnly.setAppKey(Secret.fromString("team-key"));
        appKeyOnly.explainError("FAILURE: sample error", null);
        userMetadata = OBJECT_MAPPER.readTree(OBJECT_MAPPER.readTree(chatBody.get()).path("user").asText());
        assertEquals("team-key", userMetadata.path("appkey").asText());
        assertFalse(userMetadata.has("user"));
    }

    @Test
    void tokenEndpointErrorIsReported() {
        server.createContext("/token", exchange -> respond(exchange, 401, "{\"error\":\"invalid_client\"}"));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider(baseUrl() + "/chat", "m").explainError("FAILURE", null));

        assertTrue(e.getMessage().contains("Token request failed with status 401: {\"error\":\"invalid_client\"}"),
                e.getMessage());
    }

    @Test
    void tokenResponseWithoutAccessTokenIsRejected() {
        server.createContext("/token", new JsonHandler(exchange ->
                "{\"token_type\":\"Bearer\",\"access_token\":\" \"}"));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider(baseUrl() + "/chat", "m").explainError("FAILURE", null));

        assertTrue(e.getMessage().contains("Token response did not contain an access_token."), e.getMessage());
    }

    @Test
    void chatEndpointErrorIsReportedWithAbbreviatedBody() {
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", exchange -> respond(exchange, 500, "y".repeat(700)));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider(baseUrl() + "/chat/", "m").explainError("FAILURE", null));

        assertTrue(e.getMessage().contains(
                "Chat completion request failed with status 500: " + "y".repeat(500) + "..."),
                e.getMessage());
        assertFalse(e.getMessage().contains("y".repeat(501)));
    }

    @Test
    void responseWithoutChoicesIsRejected() {
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange -> "{\"choices\":[]}"));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider(baseUrl() + "/chat/", "m").explainError("FAILURE", null));

        assertTrue(e.getMessage().contains("did not contain any choices"), e.getMessage());
    }

    @Test
    void responseWithoutMessageContentIsRejected() {
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange ->
                "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"image_url\"}]}}]}"));

        ExplanationException e = assertThrows(ExplanationException.class,
                () -> newProvider(baseUrl() + "/chat/", "m").explainError("FAILURE", null));

        assertTrue(e.getMessage().contains("did not contain message content"), e.getMessage());
    }

    @Test
    void arrayContentPartsAreConcatenated() throws Exception {
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange ->
                "{\"choices\":[{\"message\":{\"content\":"
                        + "[\"{\\\"errorSummary\\\":\", {\"text\":\"\\\"Parts\\\"}\"}]}}]}"));

        JenkinsLogAnalysis analysis = newProvider(baseUrl() + "/chat/", "m")
                .analyzeError("FAILURE", null, null, null, null, null, null);

        assertEquals("Parts", analysis.errorSummary());
    }

    @Test
    void nonJsonAndPartialJsonAnswersAreHandled() throws Exception {
        AtomicReference<String> answer = new AtomicReference<>();
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange -> chatResponse(answer.get())));
        CustomOktaAIProvider provider = newProvider(baseUrl() + "/chat/", "m");

        answer.set("  Disk is full.  ");
        assertEquals("Disk is full.",
                provider.analyzeError("FAILURE", null, null, null, null, null, null).errorSummary());

        answer.set("```json\n{\"errorSummary\":\"Fenced\",\"bestPractices\":[\"Pin versions\",\"\"]}\n```");
        JenkinsLogAnalysis fenced = provider.analyzeError("FAILURE", null, null, null, null, null, null);
        assertEquals("Fenced", fenced.errorSummary());
        assertEquals(List.of("Pin versions"), fenced.bestPractices());

        answer.set("Result: {\"errorSummary\":\"Embedded\",\"errorSignature\":\" sig \"} done");
        JenkinsLogAnalysis embedded = provider.analyzeError("FAILURE", null, null, null, null, null, null);
        assertEquals("Embedded", embedded.errorSummary());
        assertEquals("sig", embedded.errorSignature());

        answer.set("[\"not\",\"an\",\"object\"]");
        assertEquals("[\"not\",\"an\",\"object\"]",
                provider.analyzeError("FAILURE", null, null, null, null, null, null).errorSummary());

        answer.set("{\"resolutionSteps\":[]}");
        JenkinsLogAnalysis noSummary = provider.analyzeError("FAILURE", null, null, null, null, null, null);
        assertEquals("{\"resolutionSteps\":[]}", noSummary.errorSummary());
        assertNull(noSummary.resolutionSteps());
    }

    @Test
    void connectionFailureIsReported() {
        CustomOktaAIProvider provider = newProvider(baseUrl() + "/chat/", "m");
        server.stop(0);
        server = null;

        ExplanationException e = assertThrows(ExplanationException.class, () -> provider.explainError("FAILURE", null));

        assertTrue(e.getMessage().contains("Failed to communicate with Custom Okta AI provider"), e.getMessage());
    }

    @Test
    void interruptedTokenRequestIsReported() {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/token", exchange -> {
            awaitQuietly(release);
            respond(exchange, 200, "{\"access_token\":\"late\"}");
        });
        CustomOktaAIProvider provider = newProvider(baseUrl() + "/chat/", "m");

        try {
            Thread.currentThread().interrupt();
            ExplanationException e = assertThrows(ExplanationException.class,
                    () -> provider.explainError("FAILURE", null));

            assertTrue(e.getMessage().contains("Interrupted while communicating with Custom Okta AI provider"),
                    e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test
    void fixAssistantSendsFixPromptWithMetadataAndReturnsRawAnswer() throws Exception {
        AtomicReference<String> chatBody = new AtomicReference<>();
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange -> {
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return chatResponse("{\"fixable\":false,\"changes\":[]}");
        }));
        CustomOktaAIProvider provider = newProvider(baseUrl() + "/chat/", "m");
        provider.setUserId("bot");

        FixAssistant assistant = provider.createFixAssistant(null, null);
        String answer = assistant.suggestFix("ERROR: missing dependency");

        assertEquals("{\"fixable\":false,\"changes\":[]}", answer);
        JsonNode payload = OBJECT_MAPPER.readTree(chatBody.get());
        assertEquals(0.3, payload.path("temperature").asDouble());
        assertTrue(payload.path("messages").get(0).path("content").asText().contains("\"fixable\": <boolean>"));
        assertEquals("Jenkins build failed. Analyze and suggest a fix.\n\nError logs:\nERROR: missing dependency",
                payload.path("messages").get(1).path("content").asText());
        assertEquals("bot", OBJECT_MAPPER.readTree(payload.path("user").asText()).path("user").asText());
    }

    @Test
    void fixAssistantWithoutMetadataOmitsUserField() throws Exception {
        AtomicReference<String> chatBody = new AtomicReference<>();
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange -> {
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return chatResponse("{}");
        }));

        newProvider(baseUrl() + "/chat/", "m").createFixAssistant(null, null).suggestFix("ERROR");

        assertFalse(OBJECT_MAPPER.readTree(chatBody.get()).has("user"));
    }

    @Test
    void fixAssistantWrapsFailures() {
        server.createContext("/token", exchange -> respond(exchange, 503, "unavailable"));
        FixAssistant assistant = newProvider(baseUrl() + "/chat/", "m").createFixAssistant(null, null);

        RuntimeException httpError = assertThrows(RuntimeException.class, () -> assistant.suggestFix("ERROR"));
        assertTrue(httpError.getMessage().contains("Token request failed with status 503: unavailable"),
                httpError.getMessage());

        server.stop(0);
        server = null;
        RuntimeException ioError = assertThrows(RuntimeException.class, () -> assistant.suggestFix("ERROR"));
        assertTrue(ioError.getMessage().contains("Failed to communicate with Custom Okta AI provider"),
                ioError.getMessage());
    }

    @Test
    void fixAssistantReportsInterruption() {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/token", exchange -> {
            awaitQuietly(release);
            respond(exchange, 200, "{\"access_token\":\"late\"}");
        });
        FixAssistant assistant = newProvider(baseUrl() + "/chat/", "m").createFixAssistant(null, null);

        try {
            Thread.currentThread().interrupt();
            RuntimeException e = assertThrows(RuntimeException.class, () -> assistant.suggestFix("ERROR"));

            assertTrue(e.getMessage().contains("Interrupted while communicating with Custom Okta AI provider"),
                    e.getMessage());
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test
    void invalidTimeoutsFallBackToDefault() throws Exception {
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange -> chatResponse("{\"errorSummary\":\"ok\"}")));
        CustomOktaAIProvider provider = newProvider(baseUrl() + "/chat/", "m");
        assertEquals(CustomOktaAIProvider.DEFAULT_TIMEOUT_SECONDS, provider.getTimeoutSeconds());

        provider.setTimeoutSeconds(0);
        assertTrue(provider.explainError("FAILURE", null).contains("ok"));
        provider.setTimeoutSeconds(null);
        assertTrue(provider.explainError("FAILURE", null).contains("ok"));
        provider.setTimeoutSeconds(30);
        assertEquals(30, provider.getTimeoutSeconds());
        assertTrue(provider.explainError("FAILURE", null).contains("ok"));
    }

    @Test
    void settersNormalizeAndGettersExposeConfiguration() {
        CustomOktaAIProvider provider = new CustomOktaAIProvider(" https://chat ", " https://token ", " model ",
                " client ", Secret.fromString("secret"));
        provider.setScope("  ");
        provider.setApiVersion(" v1 ");
        provider.setAccessTokenHeader(" api-key ");
        provider.setAccessTokenPrefix(" Bearer ");
        provider.setUserId("  ");
        provider.setAppKey(Secret.fromString("app"));

        assertEquals("https://chat", provider.getUrl());
        assertEquals("https://token", provider.getTokenUrl());
        assertEquals("model", provider.getModel());
        assertEquals("client", provider.getClientId());
        assertEquals("secret", provider.getClientSecret().getPlainText());
        assertNull(provider.getScope());
        assertEquals("v1", provider.getApiVersion());
        assertEquals("api-key", provider.getAccessTokenHeader());
        assertEquals("Bearer", provider.getAccessTokenPrefix());
        assertNull(provider.getUserId());
        assertEquals("app", provider.getAppKey().getPlainText());
    }

    @Test
    void isNotValidReportsTheFirstMissingSetting() {
        assertEquals("No API URL configured for Custom Okta AI provider.",
                invalidMessage(new CustomOktaAIProvider(null, "https://t", "m", "c", Secret.fromString("s"))));
        assertEquals("No token URL configured for Custom Okta AI provider.",
                invalidMessage(new CustomOktaAIProvider("https://u", " ", "m", "c", Secret.fromString("s"))));
        assertEquals("No model configured for Custom Okta AI provider.",
                invalidMessage(new CustomOktaAIProvider("https://u", "https://t", null, "c", Secret.fromString("s"))));
        assertEquals("No client ID configured for Custom Okta AI provider.",
                invalidMessage(new CustomOktaAIProvider("https://u", "https://t", "m", "", Secret.fromString("s"))));
        assertEquals("No client secret configured for Custom Okta AI provider.",
                invalidMessage(new CustomOktaAIProvider("https://u", "https://t", "m", "c", Secret.fromString(" "))));

        CustomOktaAIProvider valid = new CustomOktaAIProvider("https://u", "https://t", "m", "c",
                Secret.fromString("s"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertFalse(valid.isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8)));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void descriptorValidatesFields() {
        CustomOktaAIProvider.DescriptorImpl descriptor = new CustomOktaAIProvider.DescriptorImpl();

        assertEquals("Custom Okta AI", descriptor.getDisplayName());
        assertEquals(CustomOktaAIProvider.DEFAULT_MODEL, descriptor.getDefaultModel());
        assertEquals(CustomOktaAIProvider.DEFAULT_ACCESS_TOKEN_HEADER, descriptor.getDefaultAccessTokenHeader());
        assertEquals(CustomOktaAIProvider.DEFAULT_ACCESS_TOKEN_PREFIX, descriptor.getDefaultAccessTokenPrefix());
        assertEquals(CustomOktaAIProvider.DEFAULT_TIMEOUT_SECONDS, descriptor.getDefaultTimeoutSeconds());

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckUrl(" ").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckUrl(null).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUrl("https://chat.example.com/openai").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckUrl("ftp://chat.example.com").kind);

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckTokenUrl("").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckTokenUrl(null).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckTokenUrl("https://id.example.com/v1/token").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckTokenUrl("https://user:pw@id.example.com").kind);

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckTimeoutSeconds(null).kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckTimeoutSeconds(0).kind);
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckTimeoutSeconds(601).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckTimeoutSeconds(600).kind);
    }

    private CustomOktaAIProvider newProvider(String chatUrl, String model) {
        return new CustomOktaAIProvider(chatUrl, baseUrl() + "/token", model, "my-client-id",
                Secret.fromString("my-client-secret"));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String invalidMessage(CustomOktaAIProvider provider) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(provider.isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8)));
        return out.toString(StandardCharsets.UTF_8).trim();
    }

    private static String chatResponse(String content) {
        return OBJECT_MAPPER.createObjectNode()
                .set("choices", OBJECT_MAPPER.createArrayNode().add(OBJECT_MAPPER.createObjectNode()
                        .set("message", OBJECT_MAPPER.createObjectNode().put("content", content))))
                .toString();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
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

    @Test
    void requestUriIsLoggedOnlyAtFineLevel() throws Exception {
        server.createContext("/token", new JsonHandler(exchange -> "{\"access_token\":\"okta-token\"}"));
        server.createContext("/chat/", new JsonHandler(exchange -> chatResponse("{\"errorSummary\":\"fine\"}")));
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(CustomOktaAIProvider.class.getName());
        java.util.logging.Level previous = logger.getLevel();
        logger.setLevel(java.util.logging.Level.FINE);
        try {
            assertTrue(newProvider(baseUrl() + "/chat/", "m").explainError("FAILURE", null).contains("fine"));
        } finally {
            logger.setLevel(previous);
        }
    }
}
