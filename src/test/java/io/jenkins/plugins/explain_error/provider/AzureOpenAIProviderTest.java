package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AzureOpenAIProviderTest {

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
    void explainErrorUsesV1ResponsesEndpointAndApiKey(JenkinsRule jenkins) throws Exception {
        addStringCredential(jenkins, "azure-openai-key", "test-azure-key");

        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return outputTextResponse("""
                    {"errorSummary":"Azure OpenAI worked","resolutionSteps":["Check deployment configuration"],\
                    "bestPractices":["Rotate API keys"],"errorSignature":"FAILURE: azure path verified"}""");
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "my-gpt-4o",
                null,
                "azure-openai-key");

        String explanation = provider.explainError("FAILURE: sample error", null, "English", "Prioritize root cause");

        assertEquals("/openai/v1/responses", requestPath.get());
        assertEquals("test-azure-key", apiKeyHeader.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("my-gpt-4o", payload.path("model").asText());
        assertTrue(payload.path("instructions").isTextual());
        assertTrue(payload.path("input").isTextual());
        assertTrue(requestBody.get().contains("Return ONLY valid JSON"));
        assertTrue(explanation.contains("Azure OpenAI worked"));
        assertTrue(explanation.contains("Check deployment configuration"));
    }

    @Test
    void explainErrorParsesNestedResponsesOutput(JenkinsRule jenkins) throws Exception {
        addStringCredential(jenkins, "azure-nested-key", "nested-key");

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> nestedOutputTextResponse("""
                {"errorSummary":"Nested response worked","resolutionSteps":["Inspect response output"],\
                "bestPractices":[],"errorSignature":"NESTED"}""")));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "nested-deployment",
                "v1",
                "azure-nested-key");

        String explanation = provider.explainError("FAILURE: sample error", null, "English", null);

        assertTrue(explanation.contains("Nested response worked"));
        assertTrue(explanation.contains("Inspect response output"));
    }

    @Test
    void fixAssistantUsesPreviewResponsesEndpointAndReturnsRawJson(JenkinsRule jenkins) throws Exception {
        addStringCredential(jenkins, "azure-fix-key", "fix-key");

        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return outputTextResponse("""
                    {"fixable":true,"explanation":"Update the Jenkinsfile","confidence":"high",\
                    "fixType":"config","changes":[]}""");
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/";
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "fix-deployment",
                "2025-04-01-preview",
                "azure-fix-key");

        FixAssistant assistant = provider.createFixAssistant();
        String result = assistant.suggestFix("FAILURE: job failed");

        assertEquals("/openai/responses?api-version=2025-04-01-preview", requestPath.get());
        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("fix-deployment", payload.path("model").asText());
        assertTrue(requestBody.get().contains("\"Jenkins build failed. Analyze and suggest a fix."));
        assertTrue(result.contains("\"fixable\":true"));
    }

    @Test
    void fixAssistantAcceptsV1BaseEndpoint(JenkinsRule jenkins) throws Exception {
        addStringCredential(jenkins, "azure-v1-key", "v1-key");

        AtomicReference<String> requestPath = new AtomicReference<>();

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            return outputTextResponse("""
                    {"fixable":false,"explanation":"No safe fix","confidence":"low",\
                    "fixType":"unknown","changes":[]}""");
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1/";
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "v1-deployment",
                null,
                "azure-v1-key");

        provider.createFixAssistant().suggestFix("FAILURE: job failed");

        assertEquals("/openai/v1/responses", requestPath.get());
    }

    private static String outputTextResponse(String text) throws IOException {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("output_text", text);
        return OBJECT_MAPPER.writeValueAsString(response);
    }

    private static String nestedOutputTextResponse(String text) throws IOException {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        ArrayNode output = response.putArray("output");
        ObjectNode message = output.addObject();
        message.put("type", "message");
        ArrayNode content = message.putArray("content");
        ObjectNode outputText = content.addObject();
        outputText.put("type", "output_text");
        outputText.put("text", text);
        return OBJECT_MAPPER.writeValueAsString(response);
    }

    private void addStringCredential(JenkinsRule jenkins, String id, String secret) throws IOException {
        assertTrue(jenkins.jenkins != null);
        StringCredentialsImpl credentials = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                id,
                "test credential",
                Secret.fromString(secret));
        SystemCredentialsProvider.getInstance().getCredentials().add(credentials);
        SystemCredentialsProvider.getInstance().save();
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
