package io.jenkins.plugins.explain_error.provider;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import hudson.ProxyConfiguration;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ProviderTest {

    @Test
    void testExplainErrorWithNullInput() throws IOException, ExplanationException {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError(null, null));
        assertEquals("No error logs provided for explanation.", result.getMessage());
    }

    @Test
    void testExplainErrorWithEmptyInput() throws IOException, ExplanationException {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("", null));
        assertEquals("No error logs provided for explanation.", result.getMessage());
    }

    @Test
    void testExplainErrorWithBlankInput() throws IOException, ExplanationException {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("   ", null));
        assertEquals("No error logs provided for explanation.", result.getMessage());
    }

    @Test
    void testErrorLogsProcessing() throws IOException, ExplanationException {
        BaseAIProvider provider = new TestProvider();
        String complexErrorLogs = "Started by user admin\n" +
                                 "Building in workspace /var/jenkins_home/workspace/test\n" +
                                 "ERROR: Could not find or load main class Application\n" +
                                 "FAILURE: Build failed with an exception.\n" +
                                 "* What went wrong:\n" +
                                 "Execution failed for task ':compileJava'.\n" +
                                 "> Compilation failed; see the compiler error output for details.\n" +
                                 "BUILD FAILED in 15s";

        String result = provider.explainError(complexErrorLogs, null);

        // Should not return the "no error logs" message for valid input
        assertEquals("Summary: Request was successful\n", result);
    }

    @Test
    void testErrorLogsProcessingFailure() throws IOException {
        TestProvider provider = new TestProvider();
        provider.setThrowError(true);
        String logs = "All is good.";

        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError(logs, null));
        assertEquals("API request failed: Request failed.", result.getMessage());
    }

    @Test
    void testOpenAIWithNullApiKey() {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", null);
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOpenAIWithEmptyApiKey() {
        BaseAIProvider provider = new OpenAIProvider(null, "test-model", Secret.fromString(""));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOpenAIWithNullModel() {
        BaseAIProvider provider = new OpenAIProvider(null, null, Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOpenAIWithEmptyModel() {
        BaseAIProvider provider = new OpenAIProvider(null, "", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testGeminiNullApiKey() {
        BaseAIProvider provider = new GeminiProvider(null, "test-model", null);
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testGeminiEmptyApiKey() {
        BaseAIProvider provider = new GeminiProvider(null, "test-model", Secret.fromString(""));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testGeminiEmptyModel() {
        BaseAIProvider provider = new GeminiProvider(null, "", Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testGeminiNullModel() {
        BaseAIProvider provider = new GeminiProvider(null, null, Secret.fromString("test-key"));
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOllamaNullModel() {
        BaseAIProvider provider = new OllamaProvider("http://localhost:1234", null);
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOllamaEmptyModel() {
        BaseAIProvider provider = new OllamaProvider("http://localhost:1234", "");
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOllamaEmptyUrl() {
        BaseAIProvider provider = new OllamaProvider("", "test-model");
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testOllamaNullUrl() {
        BaseAIProvider provider = new OllamaProvider(null, "test-model");
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testBedrockNullModel() {
        BaseAIProvider provider = new BedrockProvider(null, null, "eu-west-1");
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testBedrockEmptyModel() {
        BaseAIProvider provider = new BedrockProvider(null, "", "eu-west-1");
        ExplanationException result = assertThrows(ExplanationException.class, () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testCustomOktaProviderNullChatUrl() {
        BaseAIProvider provider = new CustomOktaAIProvider(null, "https://id.example.com/oauth2/default/v1/token",
                "test-model", "client-id", Secret.fromString("test-secret"));
        ExplanationException result = assertThrows(ExplanationException.class,
                () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testCustomOktaProviderNullTokenUrl() {
        BaseAIProvider provider = new CustomOktaAIProvider("https://chat.example.com/openai/deployments",
                null, "test-model", "client-id", Secret.fromString("test-secret"));
        ExplanationException result = assertThrows(ExplanationException.class,
                () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testCustomOktaProviderNullClientId() {
        BaseAIProvider provider = new CustomOktaAIProvider("https://chat.example.com/openai/deployments",
                "https://id.example.com/oauth2/default/v1/token", "test-model", null,
                Secret.fromString("test-secret"));
        ExplanationException result = assertThrows(ExplanationException.class,
                () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testCustomOktaProviderNullClientSecret() {
        BaseAIProvider provider = new CustomOktaAIProvider("https://chat.example.com/openai/deployments",
                "https://id.example.com/oauth2/default/v1/token", "test-model", "client-id", null);
        ExplanationException result = assertThrows(ExplanationException.class,
                () -> provider.explainError("Test error", null));

        assertEquals("The provider is not properly configured.", result.getMessage());
    }

    @Test
    void testJenkinsProxyBuilderRoutesRequestsThroughConfiguredProxy(JenkinsRule jenkins) throws Exception {
        HttpServer proxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requestCount = new AtomicInteger();
        proxyServer.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "proxied".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        proxyServer.start();

        try {
            jenkins.jenkins.setProxy(new ProxyConfiguration("127.0.0.1", proxyServer.getAddress().getPort()));

            HttpClient client = new ProxyAwareProvider().newClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://example.invalid/proxy-check")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, response.statusCode());
            assertEquals("proxied", response.body());
            assertTrue(requestCount.get() > 0, "request should have been routed through the configured proxy");
        } finally {
            jenkins.jenkins.setProxy(null);
            proxyServer.stop(0);
        }
    }

    private static final class ProxyAwareProvider extends BaseAIProvider {

        private ProxyAwareProvider() {
            super("http://example.invalid", "test-model");
        }

        private HttpClient newClient() {
            return newJenkinsHttpClientBuilder().build();
        }

        @Override
        public Assistant createAssistant() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isNotValid(hudson.model.TaskListener listener) {
            return false;
        }
    }
}
