package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * What every provider is asked: the shape of the answer, and guidance only for what the logs contain.
 */
@WithJenkins
class PromptTest {

    private static final String DOWNSTREAM_LOGS = String.join("\n",
            "ERROR: build step failed",
            "### Downstream Job: team/deploy #12 ###",
            "Result: FAILURE",
            "--- LOG CONTENT ---",
            "kubectl: connection refused",
            "### END OF DOWNSTREAM JOB: team/deploy ###");

    @Test
    void systemPromptAsksForAShortAnswer() {
        String prompt = BaseAIProvider.SYSTEM_PROMPT;

        assertTrue(prompt.contains("errorSummary: what failed and why, in one or two sentences."), prompt);
        assertTrue(prompt.contains("resolutionSteps: at most four steps"), prompt);
        assertTrue(prompt.contains("bestPractices: at most two"), prompt);
        assertTrue(prompt.contains("errorSignature: the few log lines that show the failure"), prompt);
        assertTrue(prompt.contains("they take precedence over the"), "the user's instructions still win: " + prompt);
    }

    @Test
    void systemPromptKeepsTheLogMarkersOutOfTheAnswer() {
        String prompt = BaseAIProvider.SYSTEM_PROMPT;

        assertTrue(prompt.contains("never quote them or mention node IDs"), prompt);
        assertTrue(prompt.contains("do not say what the logs lack or do not contain"), prompt);
    }

    @Test
    void downstreamGuidanceIsNoLongerPartOfEveryRequest() {
        assertFalse(BaseAIProvider.SYSTEM_PROMPT.contains("Downstream Job"), BaseAIProvider.SYSTEM_PROMPT);
        assertFalse(BaseAIProvider.SYSTEM_PROMPT.contains("sub-job"), BaseAIProvider.SYSTEM_PROMPT);
        assertFalse(BaseAIProvider.USER_PROMPT_TEMPLATE.contains("Downstream Job"),
                BaseAIProvider.USER_PROMPT_TEMPLATE);
        assertFalse(BaseAIProvider.USER_PROMPT_TEMPLATE.contains("Sub-jobs"), BaseAIProvider.USER_PROMPT_TEMPLATE);
    }

    @Test
    void downstreamGuidanceStillSaysEverythingItDid() {
        String guide = BaseAIProvider.DOWNSTREAM_GUIDE;

        for (String part : new String[] {
                "### Downstream Job: <job-name> #<build-number> ###",
                "\"ABORTED (interrupted by fail-fast, not the root cause)\"",
                "\"UNSTABLE\"", "\"UNAVAILABLE\"", "[AI explanation from sub-job]",
                "If multiple sub-jobs have Result: FAILURE, summarize each one separately.",
                "Sub-jobs with Result: FAILURE are the primary ROOT CAUSE"}) {
            assertTrue(guide.contains(part), "missing from the guide: " + part);
        }
    }

    @Test
    void logsWithoutDownstreamSectionsGetNoDownstreamGuidance(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();

        provider.analyzeError("ERROR: npm ci failed", null, null, null, null, null, null);
        assertEquals("", provider.getLastCustomContext());

        provider.analyzeError("ERROR: npm ci failed", null, null, "Mention the wiki", null, null, null);
        assertFalse(provider.getLastCustomContext().contains("sub-job"), provider.getLastCustomContext());
        assertTrue(provider.getLastCustomContext().endsWith("Mention the wiki"), provider.getLastCustomContext());
    }

    @Test
    void logsWithADownstreamSectionGetTheGuidanceAheadOfTheUsersInstructions(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();

        provider.analyzeError(DOWNSTREAM_LOGS, null, null, "Mention the wiki", null, null, null);

        String context = provider.getLastCustomContext();
        assertTrue(context.contains("Sub-jobs with Result: FAILURE are the primary ROOT CAUSE"), context);
        assertTrue(context.endsWith("Mention the wiki"), "the user's instructions stay last: " + context);
        assertTrue(context.indexOf("ROOT CAUSE") < context.indexOf("ADDITIONAL INSTRUCTIONS"), context);
    }

    @Test
    void aHiddenDownstreamSectionCountsToo(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();

        provider.analyzeError(
                "### Downstream Job: [hidden] ###\nResult: UNAVAILABLE\n### END OF DOWNSTREAM JOB: [hidden] ###",
                null, null, null, null, null, null);

        assertTrue(provider.getLastCustomContext().contains("\"UNAVAILABLE\""), provider.getLastCustomContext());
    }
}
