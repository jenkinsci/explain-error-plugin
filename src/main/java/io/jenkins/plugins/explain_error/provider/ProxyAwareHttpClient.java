package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * langchain4j {@link HttpClientBuilder} that adds a preemptive
 * {@code Proxy-Authorization} header to every request when the Jenkins proxy
 * requires credentials.
 *
 * <p>Jenkins core's {@code ProxyConfiguration.newHttpClientBuilder()} attaches a
 * {@link java.net.Authenticator} to the JDK {@link java.net.http.HttpClient} for
 * proxy authentication. Since JDK-8326949 (fixed only in JDK 24), the JDK HTTP
 * client silently drops user-set {@code Authorization} headers whenever an
 * Authenticator is present, so API keys never reach the AI endpoint on Jenkins
 * instances with an authenticated proxy. To work around this, no Authenticator is
 * used; instead the proxy credentials are sent preemptively as a user-set
 * {@code Proxy-Authorization} header. The JDK attaches that header to the CONNECT
 * request of tunneled connections and filters proxy-* headers from direct and
 * tunneled inner requests, so credentials never leak to the origin server.
 */
class ProxyAwareHttpClientBuilder implements HttpClientBuilder {

    private final HttpClientBuilder delegate;

    @CheckForNull
    private final String proxyAuthorizationHeader;

    ProxyAwareHttpClientBuilder(HttpClientBuilder delegate, @CheckForNull String proxyAuthorizationHeader) {
        this.delegate = delegate;
        this.proxyAuthorizationHeader = proxyAuthorizationHeader;
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        delegate.connectTimeout(connectTimeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        delegate.readTimeout(readTimeout);
        return this;
    }

    @Override
    public HttpClient build() {
        HttpClient httpClient = delegate.build();
        return proxyAuthorizationHeader == null
                ? httpClient
                : new ProxyAwareHttpClient(httpClient, proxyAuthorizationHeader);
    }
}

/**
 * Delegating {@link HttpClient} that injects the Jenkins proxy credentials into
 * every request, see {@link ProxyAwareHttpClientBuilder}.
 */
class ProxyAwareHttpClient implements HttpClient {

    private final HttpClient delegate;

    private final String proxyAuthorizationHeader;

    ProxyAwareHttpClient(HttpClient delegate, String proxyAuthorizationHeader) {
        this.delegate = delegate;
        this.proxyAuthorizationHeader = proxyAuthorizationHeader;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException {
        return delegate.execute(withProxyAuthorizationHeader(request));
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener eventListener) {
        delegate.execute(withProxyAuthorizationHeader(request), parser, eventListener);
    }

    private HttpRequest withProxyAuthorizationHeader(HttpRequest request) {
        return HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(new LinkedHashMap<>(request.headers()))
                .addHeader("Proxy-Authorization", proxyAuthorizationHeader)
                .formDataFields(request.formDataFields())
                .formDataFiles(request.formDataFiles())
                .body(request.body())
                .build();
    }
}
