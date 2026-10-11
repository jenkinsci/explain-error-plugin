package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.exception.ContentFilteredException;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.GlobalConfigurationImpl;
import io.jenkins.plugins.explain_error.autofix.FixAssistant;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * Covers AWS Bedrock guardrail support against a local HTTP server that speaks the Converse wire
 * format, so both the request the AWS SDK really sends and the handling of guardrail
 * interventions are exercised without calling AWS.
 */
class BedrockProviderGuardrailTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ACCESS_KEY_PROPERTY = "aws.accessKeyId";
    private static final String SECRET_KEY_PROPERTY = "aws.secretAccessKey";
    private static final String GUARDRAIL_ARN = "arn:aws:bedrock:us-east-1:123456789012:guardrail/abc123";
    private static final String ANALYSIS = "{\"errorSummary\":\"Compilation failed\","
            + "\"resolutionSteps\":[\"Fix the import\"],\"bestPractices\":[],"
            + "\"errorSignature\":\"cannot find symbol\"}";
    private static final String BLOCKED_MESSAGE = "Sorry, this build log cannot be analyzed.";
    private static final String INPUT_BLOCKED_TRACE = """
            {"inputAssessment":{"abc123":{"topicPolicy":{"topics":[
              {"name":"Credentials","type":"DENY","action":"BLOCKED"}]}}}}""";
    private static final String OUTPUT_BLOCKED_TRACE = """
            {"outputAssessments":{"abc123":[{"contentPolicy":{"filters":[
              {"type":"VIOLENCE","confidence":"HIGH","action":"BLOCKED"}]}}]}}""";
    private static final String OUTPUT_MASKED_TRACE = """
            {"outputAssessments":{"abc123":[{"sensitiveInformationPolicy":{"piiEntities":[
              {"type":"EMAIL","match":"dev@example.com","action":"ANONYMIZED"}]}}]}}""";

    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private String previousAccessKey;
    private String previousSecretKey;

    @BeforeEach
    void setUp() throws IOException {
        // The AWS SDK signs every request: dummy credentials keep its default chain off the network.
        previousAccessKey = System.setProperty(ACCESS_KEY_PROPERTY, "test-access-key");
        previousSecretKey = System.setProperty(SECRET_KEY_PROPERTY, "test-secret-key");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        restoreProperty(ACCESS_KEY_PROPERTY, previousAccessKey);
        restoreProperty(SECRET_KEY_PROPERTY, previousSecretKey);
    }

    @Test
    void guardrailIsAttachedToExplainRequests() throws Exception {
        respondWith(converseResponse(ANALYSIS, "end_turn", null));

        String explanation = guardedProvider().explainError("FAILURE: sample error", null, "English", null);

        assertEquals("/model/test-model/converse", requestPath.get());
        JsonNode guardrailConfig = sentRequest().path("guardrailConfig");
        assertEquals(GUARDRAIL_ARN, guardrailConfig.path("guardrailIdentifier").asText());
        assertEquals("1", guardrailConfig.path("guardrailVersion").asText());
        assertEquals("enabled", guardrailConfig.path("trace").asText());
        assertTrue(explanation.contains("Compilation failed"), explanation);
        assertTrue(explanation.contains("Fix the import"), explanation);
    }

    @Test
    void guardrailIsAttachedToAutoFixRequests() throws Exception {
        respondWith(converseResponse("{\"fixable\":false}", "end_turn", null));

        FixAssistant fixAssistant = guardedProvider().createFixAssistant(null, null);

        assertEquals("{\"fixable\":false}", fixAssistant.suggestFix("FAILURE: sample error"));
        assertEquals(GUARDRAIL_ARN, sentRequest().path("guardrailConfig").path("guardrailIdentifier").asText());
    }

    @Test
    void requestsWithoutGuardrailCarryNoGuardrailConfig() throws Exception {
        respondWith(converseResponse(ANALYSIS, "end_turn", null));

        String explanation = provider().explainError("FAILURE: sample error", null);

        assertFalse(sentRequest().has("guardrailConfig"), requestBody.get());
        assertTrue(explanation.contains("Compilation failed"), explanation);
    }

    @Test
    void blockedInputIsReportedWithTheGuardrailMessage() throws Exception {
        respondWith(converseResponse(BLOCKED_MESSAGE, "guardrail_intervened", INPUT_BLOCKED_TRACE));

        ExplanationException failure = assertThrows(ExplanationException.class,
                () -> guardedProvider().explainError("FAILURE: sample error", null));

        assertEquals("API request failed: AWS Bedrock guardrail blocked the request: " + BLOCKED_MESSAGE,
                failure.getMessage());
        assertEquals("error", failure.getLevel());
    }

    @Test
    void blockedOutputIsReportedWithTheGuardrailMessage() throws Exception {
        respondWith(converseResponse(BLOCKED_MESSAGE, "guardrail_intervened", OUTPUT_BLOCKED_TRACE));

        ExplanationException failure = assertThrows(ExplanationException.class,
                () -> guardedProvider().explainError("FAILURE: sample error", null));

        assertEquals("API request failed: AWS Bedrock guardrail blocked the request: " + BLOCKED_MESSAGE,
                failure.getMessage());
    }

    @Test
    void interventionWithoutTraceIsReportedAsBlocked() throws Exception {
        respondWith(converseResponse(BLOCKED_MESSAGE, "guardrail_intervened", null));

        ExplanationException failure = assertThrows(ExplanationException.class,
                () -> guardedProvider().explainError("FAILURE: sample error", null));

        assertTrue(failure.getMessage().endsWith("AWS Bedrock guardrail blocked the request: " + BLOCKED_MESSAGE),
                failure.getMessage());
    }

    @Test
    void blockWithoutMessageIsStillReported() throws Exception {
        respondWith(converseResponse(" ", "guardrail_intervened", INPUT_BLOCKED_TRACE));

        ExplanationException failure = assertThrows(ExplanationException.class,
                () -> guardedProvider().explainError("FAILURE: sample error", null));

        assertEquals("API request failed: AWS Bedrock guardrail blocked the request.", failure.getMessage());
    }

    @Test
    void blockedAutoFixRequestFailsInsteadOfReturningTheGuardrailMessage() throws Exception {
        respondWith(converseResponse(BLOCKED_MESSAGE, "guardrail_intervened", INPUT_BLOCKED_TRACE));

        FixAssistant fixAssistant = guardedProvider().createFixAssistant(null, null);

        ContentFilteredException failure = assertThrows(ContentFilteredException.class,
                () -> fixAssistant.suggestFix("FAILURE: sample error"));
        assertEquals("AWS Bedrock guardrail blocked the request: " + BLOCKED_MESSAGE, failure.getMessage());
    }

    @Test
    void maskedAnswerIsStillExplained() throws Exception {
        String masked = ANALYSIS.replace("Compilation failed", "Notify {EMAIL} about the failed compilation");
        respondWith(converseResponse(masked, "guardrail_intervened", OUTPUT_MASKED_TRACE));

        String explanation = guardedProvider().explainError("FAILURE: sample error", null);

        assertTrue(explanation.contains("Notify {EMAIL} about the failed compilation"), explanation);
    }

    @Test
    void filteredResponseWithoutGuardrailKeepsItsExistingHandling() throws Exception {
        respondWith(converseResponse(BLOCKED_MESSAGE, "content_filtered", null));

        ExplanationException failure = assertThrows(ExplanationException.class,
                () -> provider().explainError("FAILURE: sample error", null));

        assertFalse(failure.getMessage().contains("guardrail"), failure.getMessage());
    }

    @Test
    @WithJenkins
    void testConfigurationSendsTheGuardrail(JenkinsRule jenkins) throws Exception {
        respondWith(converseResponse(ANALYSIS, "end_turn", null));

        FormValidation result = descriptor(jenkins)
                .doTestConfiguration(null, endpoint(), "test-model", "us-east-1", null, GUARDRAIL_ARN, "DRAFT");

        assertEquals(FormValidation.Kind.OK, result.kind, result.getMessage());
        JsonNode guardrailConfig = sentRequest().path("guardrailConfig");
        assertEquals(GUARDRAIL_ARN, guardrailConfig.path("guardrailIdentifier").asText());
        assertEquals("DRAFT", guardrailConfig.path("guardrailVersion").asText());
    }

    @Test
    @WithJenkins
    void testConfigurationReportsABlockedRequest(JenkinsRule jenkins) throws Exception {
        respondWith(converseResponse(BLOCKED_MESSAGE, "guardrail_intervened", INPUT_BLOCKED_TRACE));

        FormValidation result = descriptor(jenkins)
                .doTestConfiguration(null, endpoint(), "test-model", "us-east-1", null, GUARDRAIL_ARN, "1");

        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertTrue(result.renderHtml().contains("AWS Bedrock guardrail blocked the request: " + BLOCKED_MESSAGE),
                result.renderHtml());
    }

    @Test
    @WithJenkins
    void testConfigurationButtonSubmitsTheGuardrailFields(JenkinsRule jenkins) throws Exception {
        respondWith(converseResponse(ANALYSIS, "end_turn", null));
        GlobalConfigurationImpl.get().setAiProvider(guardedProvider());

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.goTo("configure");
            HtmlElement button = page.getFirstByXPath("//input[@name='_.guardrailIdentifier']"
                    + "/following::button[normalize-space()='Test Configuration'][1]");
            button.click();
            client.waitForBackgroundJavaScript(30_000);

            assertTrue(page.asNormalizedText().contains("Configuration test successful"));
        }
        JsonNode guardrailConfig = sentRequest().path("guardrailConfig");
        assertEquals(GUARDRAIL_ARN, guardrailConfig.path("guardrailIdentifier").asText());
        assertEquals("1", guardrailConfig.path("guardrailVersion").asText());
    }

    @Test
    @WithJenkins
    void guardrailFieldsSurviveAConfigurationRoundTrip(JenkinsRule jenkins) throws Exception {
        BedrockProvider provider = new BedrockProvider(null, "test-model", "us-east-1", null);
        provider.setGuardrailIdentifier(GUARDRAIL_ARN);
        provider.setGuardrailVersion("DRAFT");
        GlobalConfigurationImpl.get().setAiProvider(provider);

        jenkins.configRoundtrip();

        BedrockProvider saved = assertInstanceOf(BedrockProvider.class, GlobalConfigurationImpl.get().getAiProvider());
        assertEquals(GUARDRAIL_ARN, saved.getGuardrailIdentifier());
        assertEquals("DRAFT", saved.getGuardrailVersion());
        assertEquals("test-model", saved.getModel());
        assertEquals("us-east-1", saved.getRegion());
    }

    @Test
    @WithJenkins
    void guardrailChecksRequireConfigurePermission(JenkinsRule jenkins) {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("reader")
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        BedrockProvider.DescriptorImpl descriptor = descriptor(jenkins);

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertThrows(AccessDeniedException.class,
                    () -> descriptor.doCheckGuardrailIdentifier(null, "abc123", "1"));
            assertThrows(AccessDeniedException.class,
                    () -> descriptor.doCheckGuardrailVersion(null, "1", "abc123"));
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(FormValidation.Kind.OK, descriptor.doCheckGuardrailIdentifier(null, "abc123", "1").kind);
            assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckGuardrailIdentifier(null, "abc123!", "1").kind);
            assertEquals(FormValidation.Kind.OK, descriptor.doCheckGuardrailVersion(null, "1", "abc123").kind);
            assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckGuardrailVersion(null, "", "abc123").kind);
        }
    }

    private BedrockProvider provider() {
        return new BedrockProvider(endpoint(), "test-model", "us-east-1", null);
    }

    private BedrockProvider guardedProvider() {
        BedrockProvider provider = provider();
        provider.setGuardrailIdentifier(GUARDRAIL_ARN);
        provider.setGuardrailVersion("1");
        return provider;
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static BedrockProvider.DescriptorImpl descriptor(JenkinsRule jenkins) {
        return jenkins.jenkins.getDescriptorByType(BedrockProvider.DescriptorImpl.class);
    }

    private JsonNode sentRequest() throws IOException {
        return OBJECT_MAPPER.readTree(requestBody.get());
    }

    private void respondWith(String body) {
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private static String converseResponse(String text, String stopReason, String guardrailTrace) throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ObjectNode message = root.putObject("output").putObject("message");
        message.put("role", "assistant");
        message.putArray("content").addObject().put("text", text);
        root.put("stopReason", stopReason);
        root.putObject("usage").put("inputTokens", 1).put("outputTokens", 1).put("totalTokens", 2);
        root.putObject("metrics").put("latencyMs", 1);
        if (guardrailTrace != null) {
            root.putObject("trace").set("guardrail", OBJECT_MAPPER.readTree(guardrailTrace));
        }
        return root.toString();
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
