package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Exercises the "Test Configuration" form endpoints of the providers and item-scoped credential
 * resolution against a local HTTP server that speaks each provider's wire format.
 */
@WithJenkins
class ProviderTestConfigurationTest {

    private static final String ANALYSIS =
            "{\\\"errorSummary\\\":\\\"Configuration test successful\\\",\\\"resolutionSteps\\\":[],"
                    + "\\\"bestPractices\\\":[],\\\"errorSignature\\\":\\\"none\\\"}";

    private HttpServer server;
    private final Map<String, String> lastAuthorization = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai", exchange -> respond(exchange, "openai", 200, """
                {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"m",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".formatted(ANALYSIS)));
        server.createContext("/foundry", exchange -> respond(exchange, "foundry", 200, """
                {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"m",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".formatted(ANALYSIS)));
        server.createContext("/anthropic", exchange -> respond(exchange, "anthropic", 200, """
                {"id":"msg_1","type":"message","role":"assistant","model":"m",
                 "content":[{"type":"text","text":"%s"}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":1,"output_tokens":1}}""".formatted(ANALYSIS)));
        server.createContext("/gemini", exchange -> respond(exchange, "gemini", 200, """
                {"candidates":[{"content":{"parts":[{"text":"%s"}],"role":"model"},"finishReason":"STOP","index":0}],
                 "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}"""
                .formatted(ANALYSIS)));
        server.createContext("/ollama", exchange -> respond(exchange, "ollama", 200, """
                {"model":"m","created_at":"2026-01-01T00:00:00Z",
                 "message":{"role":"assistant","content":"%s"},"done":true}""".formatted(ANALYSIS)));
        server.createContext("/langgraph", exchange -> respond(exchange, "langgraph", 200,
                "{\"messages\":[{\"type\":\"ai\",\"content\":\"%s\"}]}".formatted(ANALYSIS)));
        server.createContext("/okta/token", exchange -> respond(exchange, "okta-token", 200,
                "{\"access_token\":\"okta-access-token\"}"));
        server.createContext("/okta/chat", exchange -> respond(exchange, "okta-chat", 200,
                "{\"choices\":[{\"message\":{\"content\":\"%s\"}}]}".formatted(ANALYSIS)));
        server.createContext("/broken", exchange -> respond(exchange, "broken", 500, "{\"error\":\"down\"}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void testConfigurationSucceedsForEveryProvider(JenkinsRule jenkins) {
        Secret key = Secret.fromString("form-key");

        assertOk(descriptor(jenkins, OpenAIProvider.DescriptorImpl.class)
                .doTestConfiguration(null, key, url("/openai"), "gpt-test"));
        assertOk(descriptor(jenkins, DeepSeekProvider.DescriptorImpl.class)
                .doTestConfiguration(null, key, url("/openai"), "deepseek-test"));
        assertOk(descriptor(jenkins, QwenProvider.DescriptorImpl.class)
                .doTestConfiguration(null, key, null, url("/openai"), "qwen-test"));
        assertOk(descriptor(jenkins, MicrosoftFoundryProvider.DescriptorImpl.class)
                .doTestConfiguration(null, key, url("/foundry"), "foundry-test"));
        assertOk(descriptor(jenkins, AnthropicProvider.DescriptorImpl.class)
                .doTestConfiguration(null, key, null, url("/anthropic"), "claude-sonnet-4-6", 256));
        assertOk(descriptor(jenkins, GeminiProvider.DescriptorImpl.class)
                .doTestConfiguration(null, key, url("/gemini"), "gemini-test"));
        assertOk(descriptor(jenkins, OllamaProvider.DescriptorImpl.class)
                .doTestConfiguration(null, null, url("/ollama"), "ollama-test"));
        assertOk(descriptor(jenkins, LangGraphProvider.DescriptorImpl.class)
                .doTestConfiguration(null, key, url("/langgraph"), "agent"));
        assertOk(descriptor(jenkins, CustomOktaAIProvider.DescriptorImpl.class)
                .doTestConfiguration(null, url("/okta/chat/{model}/chat/completions"), url("/okta/token"),
                        "okta-model", "client", Secret.fromString("client-secret"), "scope", "api-key", "", null,
                        null, null, 30));

        assertEquals("Bearer form-key", lastAuthorization.get("openai"));
        assertEquals("Bearer form-key", lastAuthorization.get("foundry"));
        assertEquals("okta-access-token", lastAuthorization.get("okta-chat"));
    }

    @Test
    void testConfigurationFailureReportsTheProviderErrorWithDiagnostics(JenkinsRule jenkins) {
        FormValidation result = descriptor(jenkins, LangGraphProvider.DescriptorImpl.class)
                .doTestConfiguration(null, Secret.fromString("form-key"), url("/broken"), "agent");

        assertEquals(FormValidation.Kind.ERROR, result.kind);
        String message = result.renderHtml();
        assertTrue(message.contains("Configuration test failed:"), message);
        assertTrue(message.contains("LangGraph Platform request failed with status 500"), message);
        assertTrue(message.contains("<pre"), "the connection diagnostics report must be attached: " + message);
    }

    @Test
    void credentialsAreResolvedInTheContextOfTheItem(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "anthropic-key", "Anthropic", Secret.fromString("stored-anthropic-key")));
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "qwen-key", "Qwen", Secret.fromString("stored-qwen-key")));
        SystemCredentialsProvider.getInstance().save();
        FreeStyleProject job = jenkins.createFreeStyleProject("credentials-job");

        AnthropicProvider anthropic = new AnthropicProvider(url("/anthropic"), "claude-sonnet-4-6",
                Secret.fromString("direct-key"), "anthropic-key", null);
        String explanation = anthropic.explainError("ERROR: boom", null, null, null, job, null, null);
        assertTrue(explanation.contains("Configuration test successful"), explanation);
        assertEquals("stored-anthropic-key", lastAuthorization.get("anthropic"),
                "the stored credential must win over the direct API key");

        QwenProvider qwen = new QwenProvider(url("/openai"), "qwen-test", null, "qwen-key");
        qwen.explainError("ERROR: boom", null, null, null, job, null, null);
        assertEquals("Bearer stored-qwen-key", lastAuthorization.get("openai"));

        for (BaseAIProvider provider : List.<BaseAIProvider>of(
                new AnthropicProvider(url("/anthropic"), "m", null, "unknown-id", null),
                new QwenProvider(url("/openai"), "m", null, "unknown-id"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(provider.isNotValid(new StreamTaskListener(out, StandardCharsets.UTF_8), job, null));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("No API key or credentials configured"),
                    out.toString(StandardCharsets.UTF_8));
        }
        assertFalse(anthropic.isNotValid(null, job, null));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static <T extends Descriptor<?>> T descriptor(JenkinsRule jenkins, Class<T> type) {
        return jenkins.jenkins.getDescriptorByType(type);
    }

    private static void assertOk(FormValidation validation) {
        assertEquals(FormValidation.Kind.OK, validation.kind, validation.renderHtml());
        assertTrue(validation.renderHtml().contains("Configuration test successful"), validation.renderHtml());
    }

    private void respond(HttpExchange exchange, String route, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null) {
            authorization = exchange.getRequestHeaders().getFirst("x-api-key");
        }
        if (authorization == null) {
            authorization = exchange.getRequestHeaders().getFirst("api-key");
        }
        if (authorization != null) {
            lastAuthorization.put(route, authorization);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
