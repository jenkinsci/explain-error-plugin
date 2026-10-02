package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
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
    void explainErrorUsesAzureChatCompletionsEndpoint(JenkinsRule jenkins) throws Exception {
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
                "azure-openai-key",
                AzureOpenAIProvider.ApiType.CHAT_COMPLETIONS);

        String explanation = provider.explainError("FAILURE: sample error", null, "English", "Prioritize root cause");

        assertEquals("/openai/deployments/my-gpt-4o/chat/completions?api-version=2025-01-01-preview",
                requestPath.get());
        assertEquals("test-azure-key", apiKeyHeader.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals(1000, payload.path("max_tokens").asInt());
        assertNotNull(payload.path("messages"));
        assertFalse(payload.has("temperature"), "Unset temperature should be omitted from Azure request payload");
        assertTrue(requestBody.get().contains("Return ONLY valid JSON"));
        assertTrue(explanation.contains("Azure OpenAI worked"));
        assertTrue(explanation.contains("Check deployment configuration"));
    }

    @Test
    void explainErrorUsesAzureResponsesEndpoint(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-responses-key", "test-responses-key");

        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "output": [
                        {
                          "type": "message",
                          "role": "assistant",
                          "content": [
                            {
                              "type": "output_text",
                              "text": "{\\"errorSummary\\":\\"Responses API test worked\\",\\"resolutionSteps\\":[\\"Verify deployment\\"],\\"bestPractices\\":[\\"Use latest API\\"],\\"errorSignature\\":\\"FAILURE: responses path verified\\"}"
                            }
                          ]
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "gpt-5-pro",
                "2025-01-01-preview",
                "azure-responses-key",
                AzureOpenAIProvider.ApiType.RESPONSES);

        String explanation = provider.explainError("FAILURE: responses test", null, "English", null);

        assertEquals("/openai/v1/responses", requestPath.get());
        assertEquals("test-responses-key", apiKeyHeader.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("gpt-5-pro", payload.path("model").asText());
        assertEquals(1000, payload.path("max_output_tokens").asInt());
        assertEquals("minimal", payload.path("reasoning").path("effort").asText());
        assertEquals("low", payload.path("text").path("verbosity").asText());
        assertTrue(payload.has("store"));
        assertFalse(payload.path("store").asBoolean(true));
        assertNotNull(payload.path("input"));
        assertTrue(requestBody.get().contains("Return ONLY valid JSON"));
        assertTrue(explanation.contains("Responses API test worked"));
        assertTrue(explanation.contains("Verify deployment"));
    }

    @Test
    void fixAssistantUsesAzureChatCompletionsEndpoint(JenkinsRule jenkins) throws Exception {
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
                "azure-fix-key",
                AzureOpenAIProvider.ApiType.CHAT_COMPLETIONS);

        FixAssistant assistant = provider.createFixAssistant();
        String result = assistant.suggestFix("FAILURE: job failed");

        assertEquals("/openai/deployments/fix-deployment/chat/completions?api-version=2025-02-01-preview",
                requestPath.get());
        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals(2000, payload.path("max_tokens").asInt());
        assertTrue(requestBody.get().contains("\"Jenkins build failed. Analyze and suggest a fix."));
        assertTrue(result.contains("\"fixable\":true"));
    }

    @Test
    void explainErrorSendsConfiguredTemperature(JenkinsRule jenkins) throws Exception {
        assertNotNull(jenkins.jenkins);
        addStringCredential("azure-temperature-key", "test-azure-key");

        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/deployments/temperature-deployment/chat/completions", new JsonHandler(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\\"errorSummary\\":\\"Azure temperature worked\\",\\"resolutionSteps\\":[\\"Check temperature\\"],\\"bestPractices\\":[\\"Prefer provider defaults when unset\\"],\\"errorSignature\\":\\"FAILURE: temperature verified\\"}"
                          }
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "temperature-deployment",
                "2025-01-01-preview",
                "azure-temperature-key",
                AzureOpenAIProvider.ApiType.CHAT_COMPLETIONS);

        provider.explainError("FAILURE: sample error", null, "English", null, null, null, 0.65);

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals(0.65, payload.path("temperature").asDouble());
    }

    @Test
    void fixAssistantUsesAzureResponsesEndpoint(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-fix-responses-key", "fix-responses-key");

        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "output": [
                        {
                          "type": "message",
                          "role": "assistant",
                          "content": [
                            {
                              "type": "output_text",
                              "text": "{\\"fixable\\":false,\\"explanation\\":\\"Cannot determine root cause\\",\\"confidence\\":\\"low\\",\\"fixType\\":\\"unknown\\",\\"changes\\":[]}"
                            }
                          ]
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "gpt-5-fix",
                "2025-01-01-preview",
                "azure-fix-responses-key",
                AzureOpenAIProvider.ApiType.RESPONSES);

        FixAssistant assistant = provider.createFixAssistant();
        String result = assistant.suggestFix("FAILURE: obscure error");

        assertEquals("/openai/v1/responses", requestPath.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("gpt-5-fix", payload.path("model").asText());
        assertEquals(2000, payload.path("max_output_tokens").asInt());
        assertEquals("minimal", payload.path("reasoning").path("effort").asText());
        assertEquals("low", payload.path("text").path("verbosity").asText());
        assertTrue(payload.has("store"));
        assertFalse(payload.path("store").asBoolean(true));
        assertTrue(result.contains("\"fixable\":false"));
        assertTrue(result.contains("Cannot determine root cause"));
    }

    @Test
    void descriptorAllowsResponsesApiVersionBecauseV1EndpointIgnoresIt() {
        AzureOpenAIProvider.DescriptorImpl descriptor = new AzureOpenAIProvider.DescriptorImpl();

        FormValidation validation = descriptor.doCheckApiVersion(
                "2025-01-01-preview",
                AzureOpenAIProvider.ApiType.RESPONSES.name());

        assertEquals(FormValidation.Kind.OK, validation.kind);
    }

    @Test
    void descriptorListsApiTypeOptionsWithLabels() {
        AzureOpenAIProvider.DescriptorImpl descriptor = new AzureOpenAIProvider.DescriptorImpl();

        ListBoxModel items = descriptor.doFillApiTypeItems();

        assertEquals(2, items.size());
        assertEquals("Chat Completions API", items.get(0).name);
        assertEquals(AzureOpenAIProvider.ApiType.CHAT_COMPLETIONS.name(), items.get(0).value);
        assertEquals("Responses API", items.get(1).name);
        assertEquals(AzureOpenAIProvider.ApiType.RESPONSES.name(), items.get(1).value);
    }

    @Test
    void descriptorTestConfigurationUsesLightweightResponsesRequest(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-test-responses-key", "test-responses-key");

        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "output": [
                        {
                          "type": "message",
                          "content": [
                            {
                              "type": "output_text",
                              "text": "Configuration test successful"
                            }
                          ]
                        }
                      ]
                    }
                    """;
        }));

        AzureOpenAIProvider.DescriptorImpl descriptor = new AzureOpenAIProvider.DescriptorImpl();
        FormValidation validation = descriptor.doTestConfiguration(
                null,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "gpt-5-pro",
                "2025-01-01-preview",
                "azure-test-responses-key",
                AzureOpenAIProvider.ApiType.RESPONSES.name());

        assertEquals(FormValidation.Kind.OK, validation.kind);
        assertEquals("/openai/v1/responses", requestPath.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("gpt-5-pro", payload.path("model").asText());
        assertEquals(32, payload.path("max_output_tokens").asInt());
        assertEquals("minimal", payload.path("reasoning").path("effort").asText());
        assertEquals("low", payload.path("text").path("verbosity").asText());
        assertEquals("Test the connection.", payload.path("input").asText());
        assertFalse(requestBody.get().contains("Return ONLY valid JSON"));
    }

    @Test
    void descriptorTestConfigurationWithItemContext(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-item-context-key", "item-context-key");

        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return """
                    {
                      "output": [
                        {
                          "type": "message",
                          "content": [
                            {
                              "type": "output_text",
                              "text": "Configuration test successful"
                            }
                          ]
                        }
                      ]
                    }
                    """;
        }));

        AzureOpenAIProvider.DescriptorImpl descriptor = new AzureOpenAIProvider.DescriptorImpl();
        Folder folder = jenkins.jenkins.createProject(Folder.class, "test-folder");
        FormValidation validation = descriptor.doTestConfiguration(
                folder,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "gpt-5-pro",
                "2025-01-01-preview",
                "azure-item-context-key",
                AzureOpenAIProvider.ApiType.RESPONSES.name());

        assertEquals(FormValidation.Kind.OK, validation.kind);
        assertEquals("/openai/v1/responses", requestPath.get());

        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("gpt-5-pro", payload.path("model").asText());
        assertEquals(32, payload.path("max_output_tokens").asInt());
        assertEquals("minimal", payload.path("reasoning").path("effort").asText());
        assertEquals("low", payload.path("text").path("verbosity").asText());
        assertEquals("Test the connection.", payload.path("input").asText());
        assertFalse(requestBody.get().contains("Return ONLY valid JSON"));
    }

    @Test
    void responsesApiParsesTopLevelOutputText(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-output-text-key", "output-text-key");
        AtomicReference<String> requestPath = new AtomicReference<>();

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            return """
                    {
                      "output_text": "{\\"errorSummary\\":\\"Top-level output_text worked\\"}"
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "gpt-5-pro",
                "2025-01-01-preview",
                "azure-output-text-key",
                AzureOpenAIProvider.ApiType.RESPONSES);

        String result = provider.explainError("FAILURE: responses test", null, "English", null);

        assertEquals("/openai/v1/responses", requestPath.get());
        assertTrue(result.contains("Top-level output_text worked"));
    }

    @Test
    void responsesApiHandlesErrorResponse(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-error-key", "error-key");

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            return """
                    {
                      "error": {
                        "message": "Deployment not found"
                      }
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "gpt-5-pro",
                "2025-01-01-preview",
                "azure-error-key",
                AzureOpenAIProvider.ApiType.RESPONSES);

        ExplanationException ex = assertThrows(ExplanationException.class,
                () -> provider.explainError("FAILURE: error test", null, "English", null));
        assertTrue(ex.getMessage().contains("Deployment not found"));
    }

    @Test
    void responsesApiUsesV1EndpointAndIgnoresConfiguredApiVersion(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-old-responses-key", "old-responses-key");
        AtomicReference<String> requestPath = new AtomicReference<>();

        server.createContext("/openai/v1/responses", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            return """
                    {
                      "output": [
                        {
                          "type": "message",
                          "content": [
                            {
                              "type": "output_text",
                              "text": "{\\"errorSummary\\":\\"V1 endpoint worked\\"}"
                            }
                          ]
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "gpt-5-pro",
                "2025-01-01-preview",
                "azure-old-responses-key",
                AzureOpenAIProvider.ApiType.RESPONSES);

        String result = provider.explainError("FAILURE: responses test", null, "English", null);

        assertEquals("/openai/v1/responses", requestPath.get());
        assertTrue(result.contains("V1 endpoint worked"));
    }

    @Test
    void apiTypeDefaultsToChatCompletionsWhenNull(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-default-key", "default-key");

        AtomicReference<String> requestPath = new AtomicReference<>();

        server.createContext("/openai/deployments/default-gpt/chat/completions", new JsonHandler(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            return """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\\"errorSummary\\":\\"Default test passed\\",\\"resolutionSteps\\":[\\"Step 1\\"],\\"bestPractices\\":[],\\"errorSignature\\":\\"test\\"}"
                          }
                        }
                      ]
                    }
                    """;
        }));

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        // Pass null ApiType — should default to CHAT_COMPLETIONS
        AzureOpenAIProvider provider = new AzureOpenAIProvider(
                endpoint,
                "default-gpt",
                "2025-01-01-preview",
                "azure-default-key",
                null);

        provider.explainError("test error", null, "English", null);

        assertTrue(requestPath.get().contains("/chat/completions"),
                "Should default to Chat Completions when ApiType is null");
    }

    @Test
    void chatCompletionsResponseVariantsAreParsed(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-variants", "variants-key");
        // Credentials are resolved in the context of a job, as during a build.
        Item job = jenkins.createFreeStyleProject();
        AtomicReference<String> response = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200);
        server.createContext("/openai/deployments/dep/chat/completions",
                exchange -> respond(exchange, status.get(), response.get()));
        AzureOpenAIProvider provider = new AzureOpenAIProvider(endpoint() + "/", "dep", "2025-01-01-preview",
                "azure-variants", null);

        response.set(chatResponse(OBJECT_MAPPER.createArrayNode()
                .add("{\"errorSummary\":")
                .add(OBJECT_MAPPER.createObjectNode().put("type", "text").put("text", "\"Array parts\"}"))
                .add(OBJECT_MAPPER.createObjectNode().put("type", "image_url"))));
        assertEquals("Array parts", analyze(provider, job).errorSummary());

        response.set(chatResponse(OBJECT_MAPPER.getNodeFactory().textNode("  Plain answer ")));
        assertEquals("Plain answer", analyze(provider, job).errorSummary());

        response.set(chatResponse(OBJECT_MAPPER.getNodeFactory().textNode(
                "```json\n{\"errorSummary\":\"Fenced\",\"resolutionSteps\":[\" Step \",\"\"]}\n```")));
        JenkinsLogAnalysis fenced = analyze(provider, job);
        assertEquals("Fenced", fenced.errorSummary());
        assertEquals(List.of("Step"), fenced.resolutionSteps());

        response.set(chatResponse(OBJECT_MAPPER.getNodeFactory().textNode(
                "Sure: {\"errorSummary\":\"Embedded\",\"errorSignature\":\" sig \"} done")));
        JenkinsLogAnalysis embedded = analyze(provider, job);
        assertEquals("Embedded", embedded.errorSummary());
        assertEquals("sig", embedded.errorSignature());

        response.set(chatResponse(OBJECT_MAPPER.getNodeFactory().textNode("{\"bestPractices\":[]}")));
        JenkinsLogAnalysis noSummary = analyze(provider, job);
        assertEquals("{\"bestPractices\":[]}", noSummary.errorSummary());
        assertNull(noSummary.bestPractices());

        response.set(chatResponse(OBJECT_MAPPER.getNodeFactory().textNode("[1,2]")));
        assertEquals("[1,2]", analyze(provider, job).errorSummary());

        response.set("{\"choices\":[]}");
        assertExplanationError(provider, job, "Chat completion response did not contain any choices.");

        response.set(chatResponse(OBJECT_MAPPER.createArrayNode()
                .add(OBJECT_MAPPER.createObjectNode().put("type", "image_url"))));
        assertExplanationError(provider, job, "Chat completion response did not contain message content.");

        status.set(500);
        response.set("z".repeat(600));
        assertExplanationError(provider, job,
                "Azure OpenAI request failed with status 500: " + "z".repeat(500) + "...");

        // The request URI is only logged at FINE level.
        Logger logger = Logger.getLogger(AzureOpenAIProvider.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        try {
            status.set(200);
            response.set(chatResponse(OBJECT_MAPPER.getNodeFactory().textNode("{\"errorSummary\":\"fine\"}")));
            assertEquals("fine", analyze(provider, job).errorSummary());
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void responsesApiResponseVariantsAreParsed(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-responses", "responses-key");
        // Credentials are resolved in the context of a job, as during a build.
        Item job = jenkins.createFreeStyleProject();
        AtomicReference<String> response = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/openai/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response.get());
        });
        AzureOpenAIProvider provider = new AzureOpenAIProvider(endpoint(), "o4-mini", "2025-01-01-preview",
                "azure-responses", AzureOpenAIProvider.ApiType.RESPONSES);

        response.set("""
                {"output":[{"type":"reasoning","summary":[]},
                 {"type":"message","content":[{"type":"refusal","refusal":"no"},
                  {"type":"output_text","text":"{\\"errorSummary\\":\\"From output\\"}"}]}]}""");
        JenkinsLogAnalysis analysis = provider.analyzeError("ERROR: boom", null, null, null, job, null, 0.1);
        assertEquals("From output", analysis.errorSummary());
        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals(0.1, payload.path("temperature").asDouble());
        assertFalse(payload.has("reasoning"), "latency controls only apply to gpt-5 deployments");
        assertFalse(payload.path("store").asBoolean(true), "responses must not be stored");

        // An error object without a message and a blank output_text are ignored.
        response.set("""
                {"error":{"code":"none"},"output_text":"  ",
                 "output":[{"type":"message",
                   "content":[{"type":"output_text","text":"{\\"errorSummary\\":\\"ok\\"}"}]}]}""");
        assertEquals("ok", analyze(provider, job).errorSummary());

        response.set("{\"output\":[]}");
        assertExplanationError(provider, job, "Responses API response did not contain any output.");

        response.set("""
                {"output":[{"type":"message","content":[]},{"type":"message","content":[{"type":"output_text"}]}]}""");
        assertExplanationError(provider, job, "Responses API response did not contain output text.");
    }

    @Test
    void communicationFailuresAreReported(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-failures", "failures-key");
        // Credentials are resolved in the context of a job, as during a build.
        Item job = jenkins.createFreeStyleProject();
        AtomicInteger status = new AtomicInteger(500);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/openai/deployments/dep/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, status.get(), status.get() == 200
                    ? chatResponse(OBJECT_MAPPER.getNodeFactory().textNode("Configuration test successful"))
                    : "{\"error\":\"down\"}");
        });
        AzureOpenAIProvider provider = new AzureOpenAIProvider(endpoint(), "dep", "2025-01-01-preview",
                "azure-failures", AzureOpenAIProvider.ApiType.CHAT_COMPLETIONS);
        AzureOpenAIProvider.DescriptorImpl descriptor =
                jenkins.jenkins.getDescriptorByType(AzureOpenAIProvider.DescriptorImpl.class);

        RuntimeException fixFailure = assertThrows(RuntimeException.class,
                () -> provider.createFixAssistant(job, null).suggestFix("ERROR: boom"));
        assertTrue(fixFailure.getMessage().contains("Azure OpenAI request failed with status 500"),
                fixFailure.getMessage());

        FormValidation failedTest = descriptor.doTestConfiguration(null, endpoint(), "dep", "2025-01-01-preview",
                "azure-failures", "CHAT_COMPLETIONS");
        assertEquals(FormValidation.Kind.ERROR, failedTest.kind);
        assertTrue(failedTest.renderHtml().contains("Configuration test failed:"), failedTest.renderHtml());

        status.set(200);
        FormValidation passedTest = descriptor.doTestConfiguration(null, endpoint(), "dep", "2025-01-01-preview",
                "azure-failures", " ");
        assertEquals(FormValidation.Kind.OK, passedTest.kind, passedTest.renderHtml());
        JsonNode testPayload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals(32, testPayload.path("max_tokens").asInt());
        assertEquals("Reply with exactly: Configuration test successful",
                testPayload.path("messages").get(0).path("content").asText());

        FormValidation invalidType = descriptor.doTestConfiguration(null, endpoint(), "dep", "2025-01-01-preview",
                "azure-failures", "BOGUS");
        assertEquals(FormValidation.Kind.ERROR, invalidType.kind);
        assertEquals("Invalid API type.", invalidType.getMessage());

        FormValidation noCredentials = descriptor.doTestConfiguration(null, endpoint(), "dep", "2025-01-01-preview",
                " ", "CHAT_COMPLETIONS");
        assertEquals(FormValidation.Kind.ERROR, noCredentials.kind);
        assertTrue(noCredentials.renderHtml().contains("Azure OpenAI credentials not found for ID: null"),
                noCredentials.renderHtml());

        server.stop(0);
        server = null;
        assertExplanationError(provider, job, "Failed to communicate with Azure OpenAI");
        RuntimeException fixIo = assertThrows(RuntimeException.class,
                () -> provider.createFixAssistant(job, null).suggestFix("ERROR: boom"));
        assertTrue(fixIo.getMessage().contains("Failed to communicate with Azure OpenAI"), fixIo.getMessage());
        FormValidation unreachable = descriptor.doTestConfiguration(null, provider.getEndpoint(), "dep",
                "2025-01-01-preview", "azure-failures", null);
        assertEquals(FormValidation.Kind.ERROR, unreachable.kind);
        assertTrue(unreachable.renderHtml().contains("Failed to communicate with Azure OpenAI"),
                unreachable.renderHtml());
    }

    @Test
    void interruptedRequestsAreReported(JenkinsRule jenkins) throws Exception {
        addStringCredential("azure-interrupt", "interrupt-key");
        // Credentials are resolved in the context of a job, as during a build.
        Item job = jenkins.createFreeStyleProject();
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/openai/deployments/dep/chat/completions", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        AzureOpenAIProvider provider = new AzureOpenAIProvider(endpoint(), "dep", "2025-01-01-preview",
                "azure-interrupt", null);

        try {
            Thread.currentThread().interrupt();
            assertExplanationError(provider, job, "Interrupted while communicating with Azure OpenAI");
            assertTrue(Thread.interrupted(), "interrupt flag must be restored");

            Thread.currentThread().interrupt();
            RuntimeException fix = assertThrows(RuntimeException.class,
                    () -> provider.createFixAssistant(job, null).suggestFix("ERROR: boom"));
            assertTrue(fix.getMessage().contains("Interrupted while communicating with Azure OpenAI"),
                    fix.getMessage());
            assertTrue(Thread.interrupted(), "interrupt flag must be restored");
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static JenkinsLogAnalysis analyze(AzureOpenAIProvider provider, Item job) throws ExplanationException {
        return provider.analyzeError("ERROR: boom", null, null, null, job, null, null);
    }

    private static void assertExplanationError(AzureOpenAIProvider provider, Item job, String expected) {
        ExplanationException e = assertThrows(ExplanationException.class, () -> analyze(provider, job));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    private static String chatResponse(JsonNode content) {
        return OBJECT_MAPPER.createObjectNode()
                .set("choices", OBJECT_MAPPER.createArrayNode().add(OBJECT_MAPPER.createObjectNode()
                        .set("message", OBJECT_MAPPER.createObjectNode().set("content", content))))
                .toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
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
