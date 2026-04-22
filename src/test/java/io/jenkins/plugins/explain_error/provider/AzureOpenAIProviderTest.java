package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
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
    void explainErrorUsesAzureEndpointAndApiKey(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-openai-key", "test-azure-key");

        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/deployments/my-gpt-4o/chat/completions", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\\"errorSummary\\":\\"Azure OpenAI worked\\",\\"resolutionSteps\\":[\\"Check deployment configuration\\"],\\"bestPractices\\":[\\"Rotate API keys\\"],\\"errorSignature\\":\\"FAILURE: azure path verified\\"}"
                          }
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "my-gpt-4o",
                "2025-01-01-preview",
                "azure-openai-key");

        String explanation = provider.explainError("FAILURE: sample error", null, "English", "Prioritize root cause");

        assertEquals("/openai/deployments/my-gpt-4o/chat/completions?api-version=2025-01-01-preview", requestPath.get());
        assertEquals("test-azure-key", apiKeyHeader.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertNotNull(payload.path("messages"));
        assertTrue(requestBody.get().contains("Return ONLY valid JSON"));
        assertTrue(explanation.contains("Azure OpenAI worked"));
        assertTrue(explanation.contains("Check deployment configuration"));
    }

    @Test
    void fixAssistantUsesAzureEndpointAndReturnsRawJson(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-fix-key", "fix-key");

        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/deployments/fix-deployment/chat/completions", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\\"fixable\\":true,\\"explanation\\":\\"Update the Jenkinsfile\\",\\"confidence\\":\\"high\\",\\"fixType\\":\\"config\\",\\"changes\\":[]}"
                          }
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "fix-deployment",
                "2025-02-01-preview",
                "azure-fix-key");

        FixAssistant assistant = provider.createFixAssistant();
        String result = assistant.suggestFix("FAILURE: job failed");

        assertEquals("/openai/deployments/fix-deployment/chat/completions?api-version=2025-02-01-preview",
                requestPath.get());
        assertTrue(requestBody.get().contains("\"Jenkins build failed. Analyze and suggest a fix."));
        assertTrue(result.contains("\"fixable\":true"));
    }

    private void addStringCredential(String id, String secret) throws IOException {
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
