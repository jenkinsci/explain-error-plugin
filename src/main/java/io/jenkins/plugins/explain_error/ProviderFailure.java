package io.jenkins.plugins.explain_error;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

/**
 * Says in plain words why a request to an AI provider failed, for the cases the exception
 * identifies. What providers send back is often a raw response body; that stays available
 * as the detail, and this is the line a reader sees first.
 */
final class ProviderFailure {

    private static final int MAX_CAUSE_DEPTH = 10;

    private ProviderFailure() {
    }

    /**
     * @return a sentence describing the failure, or {@code null} when the exception does not tell
     */
    @CheckForNull
    static String describe(@CheckForNull Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String reason = describeOne(current);
            if (reason != null) {
                return reason;
            }
            current = current.getCause();
        }
        return null;
    }

    @CheckForNull
    private static String describeOne(Throwable failure) {
        if (failure instanceof AuthenticationException) {
            return "The AI provider rejected the credentials. Check the API key in the plugin configuration.";
        }
        if (failure instanceof RateLimitException) {
            return "The AI provider is limiting requests, or its quota is used up. Try again later.";
        }
        if (failure instanceof ModelNotFoundException) {
            return "The AI provider does not know the configured model. Check the model name.";
        }
        if (failure instanceof TimeoutException || failure instanceof HttpTimeoutException
                || failure instanceof SocketTimeoutException) {
            return "The AI provider did not answer in time.";
        }
        if (failure instanceof UnresolvedModelServerException || failure instanceof UnknownHostException
                || failure instanceof ConnectException) {
            return "Jenkins could not reach the AI provider. Check the provider URL and the proxy settings.";
        }
        if (failure instanceof InternalServerException) {
            return "The AI provider reported a server error. Try again later.";
        }
        return null;
    }
}
