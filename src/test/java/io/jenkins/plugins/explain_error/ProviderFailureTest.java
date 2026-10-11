package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Plain words for the provider failures an exception identifies.
 */
class ProviderFailureTest {

    private static final String REJECTED =
            "The AI provider rejected the credentials. Check the API key in the plugin configuration.";

    @Test
    void namesTheFailuresTheExceptionIdentifies() {
        assertEquals(REJECTED, ProviderFailure.describe(new AuthenticationException("{\"code\": \"invalid_key\"}")));
        assertEquals("The AI provider is limiting requests, or its quota is used up. Try again later.",
                ProviderFailure.describe(new RateLimitException("429")));
        assertEquals("The AI provider does not know the configured model. Check the model name.",
                ProviderFailure.describe(new ModelNotFoundException("no such model")));
        assertEquals("The AI provider did not answer in time.",
                ProviderFailure.describe(new HttpTimeoutException("request timed out")));
        assertEquals("Jenkins could not reach the AI provider. Check the provider URL and the proxy settings.",
                ProviderFailure.describe(new ConnectException("Connection refused")));
        assertEquals("The AI provider reported a server error. Try again later.",
                ProviderFailure.describe(new InternalServerException("503")));
    }

    @Test
    void looksThroughTheExceptionsWrappedAroundTheFailure() {
        ExplanationException wrapped = new ExplanationException("error", "API request failed: {\"error\": ...}",
                new RuntimeException("request failed", new AuthenticationException("401")));

        assertEquals(REJECTED, ProviderFailure.describe(wrapped));
    }

    @Test
    void saysNothingWhenTheExceptionDoesNotTell() {
        assertNull(ProviderFailure.describe(null));
        assertNull(ProviderFailure.describe(new ExplanationException("error", "No AI provider configured.")));
        assertNull(ProviderFailure.describe(new IllegalStateException("the provider returned no analysis")));
    }
}
