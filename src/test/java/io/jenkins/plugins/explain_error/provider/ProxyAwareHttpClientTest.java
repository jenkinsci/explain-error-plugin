package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyAwareHttpClientTest {

    private static final String PROXY_AUTHORIZATION = "Basic dXNlcjpwYXNz"; // user:pass

    private static final class StubHttpClient implements HttpClient {

        final List<HttpRequest> requests = new ArrayList<>();

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            requests.add(request);
            return SuccessfulHttpResponse.builder()
                    .statusCode(200)
                    .headers(Map.of())
                    .body("{}")
                    .build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener eventListener) {
            requests.add(request);
        }
    }

    private static final class StubHttpClientBuilder implements HttpClientBuilder {

        private final HttpClient httpClient;

        StubHttpClientBuilder(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public Duration connectTimeout() {
            return Duration.ofSeconds(10);
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration connectTimeout) {
            return this;
        }

        @Override
        public Duration readTimeout() {
            return Duration.ofSeconds(60);
        }

        @Override
        public HttpClientBuilder readTimeout(Duration readTimeout) {
            return this;
        }

        @Override
        public HttpClient build() {
            return httpClient;
        }
    }

    @Test
    void addsProxyAuthorizationHeaderToRegularRequests() {
        StubHttpClient delegate = new StubHttpClient();
        HttpClient client = new ProxyAwareHttpClient(delegate, PROXY_AUTHORIZATION);

        HttpRequest request = sampleRequest();
        client.execute(request);

        assertEquals(1, delegate.requests.size());
        assertEquals(List.of(PROXY_AUTHORIZATION), delegate.requests.get(0).headers().get("Proxy-Authorization"));
        assertEquals(List.of("Bearer secret"), delegate.requests.get(0).headers().get("Authorization"));
    }

    @Test
    void addsProxyAuthorizationHeaderToStreamingRequests() {
        StubHttpClient delegate = new StubHttpClient();
        HttpClient client = new ProxyAwareHttpClient(delegate, PROXY_AUTHORIZATION);

        client.execute(sampleRequest(), (inputStream, listener) -> {}, error -> {});

        assertEquals(1, delegate.requests.size());
        assertEquals(List.of(PROXY_AUTHORIZATION), delegate.requests.get(0).headers().get("Proxy-Authorization"));
    }

    @Test
    void doesNotMutateOriginalRequest() {
        StubHttpClient delegate = new StubHttpClient();
        HttpClient client = new ProxyAwareHttpClient(delegate, PROXY_AUTHORIZATION);

        HttpRequest request = sampleRequest();
        client.execute(request);

        assertTrue(request.headers().get("Proxy-Authorization") == null);
    }

    @Test
    void returnsPlainDelegateWhenNoProxyCredentialsConfigured() {
        StubHttpClient delegate = new StubHttpClient();
        HttpClientBuilder builder = new ProxyAwareHttpClientBuilder(new StubHttpClientBuilder(delegate), null);

        assertSame(delegate, builder.build());
    }

    @Test
    void wrapsDelegateWhenProxyCredentialsConfigured() {
        StubHttpClient delegate = new StubHttpClient();
        HttpClientBuilder builder =
                new ProxyAwareHttpClientBuilder(new StubHttpClientBuilder(delegate), PROXY_AUTHORIZATION);

        assertTrue(builder.build() instanceof ProxyAwareHttpClient);
    }

    private static HttpRequest sampleRequest() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.com/chat/completions")
                .addHeader("Authorization", "Bearer secret")
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build();
    }
}
