package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

/**
 * Tests for {@link BaseAIProvider} parsing logic:
 * <ul>
 *   <li>Markdown code-fence stripping (fix for AI models that wrap JSON in {@code ```json ... ```})</li>
 *   <li>{@link BaseAIProvider#getJenkinsLogAnalysis()} ThreadLocal lifecycle</li>
 *   <li>Plain-text fallback when the response is not valid JSON</li>
 * </ul>
 */
@WithJenkins
class BaseAIProviderParsingTest {

    // -------------------------------------------------------------------------
    // Markdown code-fence stripping
    // -------------------------------------------------------------------------

    @Test
    void markdownFencedJsonIsParsedIntoStructuredAnalysis(JenkinsRule jenkins) throws Exception {
        String fenced = "```json\n"
                + "{\"errorSummary\":\"Build failed\","
                + "\"resolutionSteps\":[\"Check logs\",\"Fix imports\"],"
                + "\"bestPractices\":[\"Enable CI\"],"
                + "\"errorSignature\":\"COMPILATION ERROR\"}\n"
                + "```";

        BaseAIProvider provider = new FencedResponseProvider(fenced);
        String explanation = provider.explainError("some error logs", null);

        assertNotNull(explanation);
        assertTrue(explanation.contains("Build failed"), "errorSummary must appear in plain-text output");
        assertTrue(explanation.contains("Check logs"), "resolutionStep must appear in plain-text output");

        JenkinsLogAnalysis analysis = provider.getJenkinsLogAnalysis();
        assertNotNull(analysis, "getJenkinsLogAnalysis() must be set after a successful call");
        assertEquals("Build failed", analysis.errorSummary());
        assertEquals("COMPILATION ERROR", analysis.errorSignature());
        assertNotNull(analysis.resolutionSteps());
        assertEquals(2, analysis.resolutionSteps().size());
        assertEquals("Check logs", analysis.resolutionSteps().get(0));
        assertNotNull(analysis.bestPractices());
        assertEquals("Enable CI", analysis.bestPractices().get(0));
    }

    @Test
    void markdownFenceWithoutLanguageTagIsStripped(JenkinsRule jenkins) throws Exception {
        String fenced = "```\n{\"errorSummary\":\"Timeout\",\"errorSignature\":\"TIMEOUT\"}\n```";

        BaseAIProvider provider = new FencedResponseProvider(fenced);
        provider.explainError("some error", null);

        JenkinsLogAnalysis analysis = provider.getJenkinsLogAnalysis();
        assertNotNull(analysis);
        assertEquals("Timeout", analysis.errorSummary());
        assertEquals("TIMEOUT", analysis.errorSignature());
    }

    @Test
    void plainJsonWithoutFencesIsParsedCorrectly(JenkinsRule jenkins) throws Exception {
        String plain = "{\"errorSummary\":\"NPE in Foo\",\"resolutionSteps\":[\"Add null check\"]}";

        BaseAIProvider provider = new FencedResponseProvider(plain);
        provider.explainError("stacktrace", null);

        JenkinsLogAnalysis analysis = provider.getJenkinsLogAnalysis();
        assertNotNull(analysis);
        assertEquals("NPE in Foo", analysis.errorSummary());
        assertNotNull(analysis.resolutionSteps());
        assertEquals("Add null check", analysis.resolutionSteps().get(0));
    }

    @Test
    void plainTextFallsBackToErrorSummaryField(JenkinsRule jenkins) throws Exception {
        // Non-JSON response — should be returned verbatim as errorSummary
        String plainText = "The build failed because the disk is full.";

        BaseAIProvider provider = new FencedResponseProvider(plainText);
        String explanation = provider.explainError("some error", null);

        assertNotNull(explanation);
        assertTrue(explanation.contains("disk is full"), "plain-text response must appear in output");

        JenkinsLogAnalysis analysis = provider.getJenkinsLogAnalysis();
        assertNotNull(analysis);
        assertEquals(plainText, analysis.errorSummary());
        assertNull(analysis.resolutionSteps());
        assertNull(analysis.bestPractices());
    }

    // -------------------------------------------------------------------------
    // getJenkinsLogAnalysis() ThreadLocal lifecycle
    // -------------------------------------------------------------------------

    @Test
    void getJenkinsLogAnalysisIsPopulatedAfterSuccessfulExplainError(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setAnswerMessage("Deployment failed");

        assertNull(provider.getJenkinsLogAnalysis(), "must be null before any call");

        provider.explainError("some logs", null);

        JenkinsLogAnalysis analysis = provider.getJenkinsLogAnalysis();
        assertNotNull(analysis, "must be set after a successful call");
        assertEquals("Deployment failed", analysis.errorSummary());
    }

    @Test
    void getJenkinsLogAnalysisIsNullAfterProviderThrows(JenkinsRule jenkins) {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setThrowError(true);

        assertThrows(ExplanationException.class, () -> provider.explainError("some logs", null));
        assertNull(provider.getJenkinsLogAnalysis(), "must be cleared when the provider throws");
    }

    @Test
    void getJenkinsLogAnalysisReflectsFullStructure(JenkinsRule jenkins) throws Exception {
        String json = "{\"errorSummary\":\"OOM\","
                + "\"resolutionSteps\":[\"Increase heap\",\"Profile memory\"],"
                + "\"bestPractices\":[\"Set JVM flags\"],"
                + "\"errorSignature\":\"java.lang.OutOfMemoryError\"}";

        BaseAIProvider provider = new FencedResponseProvider(json);
        provider.explainError("error", null);

        JenkinsLogAnalysis analysis = provider.getJenkinsLogAnalysis();
        assertNotNull(analysis);
        assertEquals("OOM", analysis.errorSummary());
        assertEquals("java.lang.OutOfMemoryError", analysis.errorSignature());
        assertEquals(2, analysis.resolutionSteps().size());
        assertEquals(1, analysis.bestPractices().size());
    }

    // -------------------------------------------------------------------------
    // toString() formatting contract
    // -------------------------------------------------------------------------

    @Test
    void toStringContainsAllSections(JenkinsRule jenkins) throws Exception {
        String json = "{\"errorSummary\":\"Test failure\","
                + "\"resolutionSteps\":[\"Run tests locally\"],"
                + "\"bestPractices\":[\"Add unit tests\"],"
                + "\"errorSignature\":\"AssertionError\"}";

        BaseAIProvider provider = new FencedResponseProvider(json);
        String result = provider.explainError("error", null);

        assertTrue(result.contains("Summary:"), "output must include Summary: section");
        assertTrue(result.contains("Resolution Steps:"), "output must include Resolution Steps: section");
        assertTrue(result.contains("Best Practices:"), "output must include Best Practices: section");
        assertTrue(result.contains("Failed Snippet:"), "output must include Failed Snippet: section");
    }

    // -------------------------------------------------------------------------
    // Stub provider that returns a controlled raw String from its assistant
    // -------------------------------------------------------------------------

    /**
     * A {@link FakeAIProvider} variant whose assistant always returns the given
     * raw string verbatim, bypassing the normal JSON serialization in
     * {@link FakeAIProvider}. Used to inject arbitrary AI responses (including
     * markdown-fenced JSON) without a real HTTP call.
     */
    private static class FencedResponseProvider extends FakeAIProvider {

        private final String rawResponse;

        FencedResponseProvider(String rawResponse) {
            this.rawResponse = rawResponse;
        }

        @Override
        public Assistant createAssistant(@CheckForNull Item item,
                                         @CheckForNull Authentication authentication,
                                         @CheckForNull Double temperature) {
            return (errorLogs, language, customContext) -> rawResponse;
        }
    }
}
