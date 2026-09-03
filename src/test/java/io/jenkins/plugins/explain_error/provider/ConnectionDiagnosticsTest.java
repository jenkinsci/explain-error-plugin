package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import hudson.ProxyConfiguration;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Every test declares the {@link JenkinsRule} parameter even when it never
 * reads it: {@code JenkinsExtension} boots Jenkins from {@code resolveParameter}
 * (it has no {@code BeforeEachCallback}), so dropping the parameter would leave
 * {@code Jenkins.getInstanceOrNull()} null and silently exercise a different
 * code path than the one these tests cover.
 */
@WithJenkins
class ConnectionDiagnosticsTest {

    private static final String PROXY_USER = "proxy-user";
    private static final String PROXY_PASSWORD = "proxy-pass";

    @Test
    void reachableEndpointReportsAllLayers(JenkinsRule jenkins) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            String report = ConnectionDiagnostics.run(url, new RuntimeException("boom"));

            assertTrue(report.contains("Endpoint: " + url), report);
            assertTrue(report.contains("Proxy: none configured"), report);
            assertTrue(report.contains("DNS: 127.0.0.1 ->"), report);
            assertTrue(report.contains("TCP connect: OK"), report);
            assertTrue(report.contains("-> HTTP 401"), report);
            assertTrue(report.contains("check the API key / authentication settings"), report);
            assertTrue(report.contains("unauthorized"), report);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closedPortReportsTcpFailure(JenkinsRule jenkins) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String report = ConnectionDiagnostics.run("http://127.0.0.1:" + closedPort,
                new RuntimeException("boom"));

        assertTrue(report.contains("TCP connect: FAILED"), report);
        assertTrue(report.contains("HTTP probe: FAILED"), report);
    }

    @Test
    void unknownHostReportsDnsFailure(JenkinsRule jenkins) {
        String report = ConnectionDiagnostics.run("https://no-such-host.invalid",
                new RuntimeException("boom"));

        assertTrue(report.contains("DNS: FAILED to resolve no-such-host.invalid"), report);
        assertTrue(report.contains("TCP connect: skipped (DNS resolution failed)"), report);
    }

    @Test
    void missingEndpointSkipsProbesButKeepsErrorChain(JenkinsRule jenkins) {
        String report = ConnectionDiagnostics.run(null,
                new IllegalStateException("outer", new IOException("root cause")));

        assertTrue(report.contains("Endpoint: not determined"), report);
        assertTrue(report.contains("IllegalStateException: outer"), report);
        assertTrue(report.contains("IOException: root cause"), report);
        assertFalse(report.contains("TCP connect"), report);
    }

    @Test
    void proxiedHostReportsProxyDecision(JenkinsRule jenkins) {
        jenkins.jenkins.proxy = new ProxyConfiguration(
                "proxy.example.invalid", 3128, PROXY_USER, PROXY_PASSWORD, "excluded.example.com");

        String report = ConnectionDiagnostics.run("https://api.example.invalid",
                new RuntimeException("boom"));

        assertTrue(report.contains("Proxy: via proxy.example.invalid:3128 (credentials configured)"), report);
        assertTrue(report.contains("Jenkins proxy settings: host=proxy.example.invalid port=3128"
                + " credentials=configured noProxyHost=excluded.example.com"), report);
        assertFalse(report.contains(PROXY_PASSWORD), "credentials must never be printed: " + report);
    }

    @Test
    void noProxyHostExclusionReportsDirectConnection(JenkinsRule jenkins) {
        jenkins.jenkins.proxy = new ProxyConfiguration(
                "proxy.example.invalid", 3128, null, null, "excluded.example.com");

        String report = ConnectionDiagnostics.run("https://excluded.example.com",
                new RuntimeException("boom"));

        assertTrue(report.contains("matches noProxyHost — connecting directly"), report);
    }

    @Test
    void directConnectionNeverSendsProxyCredentials(JenkinsRule jenkins) throws Exception {
        AtomicReference<String> proxyAuthorization = new AtomicReference<>();
        HttpServer server = startCapturingServer(proxyAuthorization);
        try {
            // Proxy credentials are configured, but this host is excluded from
            // the proxy, so the probe connects directly and must not leak them.
            jenkins.jenkins.proxy = new ProxyConfiguration(
                    "proxy.example.invalid", 3128, PROXY_USER, PROXY_PASSWORD, "127.0.0.1");

            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            String report = ConnectionDiagnostics.run(url, new RuntimeException("boom"));

            assertTrue(report.contains("matches noProxyHost — connecting directly"), report);
            assertTrue(report.contains("-> HTTP 200"), report);
            assertNull(proxyAuthorization.get(),
                    "Proxy credentials must not be sent on a direct connection");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxiedConnectionSendsProxyCredentials(JenkinsRule jenkins) throws Exception {
        AtomicReference<String> proxyAuthorization = new AtomicReference<>();
        // Stands in for the proxy: a plain http request through a proxy is sent
        // to the proxy itself, so this server sees the probe and its headers.
        HttpServer proxyServer = startCapturingServer(proxyAuthorization);
        try {
            jenkins.jenkins.proxy = new ProxyConfiguration(
                    "127.0.0.1", proxyServer.getAddress().getPort(), PROXY_USER, PROXY_PASSWORD, null);

            String report = ConnectionDiagnostics.run("http://target.example.invalid/v1",
                    new RuntimeException("boom"));

            assertTrue(report.contains("-> HTTP 200"), report);
            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (PROXY_USER + ":" + PROXY_PASSWORD).getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, proxyAuthorization.get(),
                    "Proxy credentials must be sent when the host goes through the proxy");
            assertFalse(report.contains(PROXY_PASSWORD), "credentials must never be printed: " + report);
        } finally {
            proxyServer.stop(0);
        }
    }

    /**
     * Starts a local server that records the {@code Proxy-Authorization} header
     * of the first request it receives and answers 200.
     */
    private static HttpServer startCapturingServer(AtomicReference<String> proxyAuthorization) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            proxyAuthorization.compareAndSet(null, exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    @Test
    void httpProbeIsHardBoundedAgainstStreamingEndpoints(JenkinsRule jenkins) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // Chunked response that never completes — mimics an SSE/streaming
            // endpoint. HttpRequest.timeout() alone would not unblock this.
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: partial".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        Duration originalLimit = ConnectionDiagnostics.probeHardLimit;
        ConnectionDiagnostics.probeHardLimit = Duration.ofSeconds(2);
        try {
            long start = System.nanoTime();
            String report = ConnectionDiagnostics.run(
                    "http://127.0.0.1:" + server.getAddress().getPort(), new RuntimeException("boom"));
            long elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000L;

            assertTrue(report.contains("no complete result after 2 s"), report);
            assertTrue(elapsedSeconds < 10,
                    "probe must be hard-bounded, took " + elapsedSeconds + " s");
        } finally {
            ConnectionDiagnostics.probeHardLimit = originalLimit;
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void nullExceptionMessageFallsBackToClassName(JenkinsRule jenkins) {
        OpenAICompatibleProvider.DescriptorImpl descriptor =
                jenkins.jenkins.getDescriptorByType(OpenAICompatibleProvider.DescriptorImpl.class);
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                "http://127.0.0.1:1", "gateway-model", null);

        FormValidation validation = descriptor.testConfigurationFailed(
                provider, new IllegalStateException((String) null));

        String html = validation.renderHtml();
        assertTrue(html.contains("Configuration test failed:</b> IllegalStateException"), html);
        assertFalse(html.contains("failed:</b> null"), html);
    }

    @Test
    void testConfigurationFailureCarriesDiagnosticsReport(JenkinsRule jenkins) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OpenAICompatibleProvider.DescriptorImpl descriptor =
                jenkins.jenkins.getDescriptorByType(OpenAICompatibleProvider.DescriptorImpl.class);

        FormValidation validation = descriptor.doTestConfiguration(null,
                Secret.fromString("test-key"), "http://127.0.0.1:" + closedPort, "gateway-model");

        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        String html = validation.renderHtml();
        assertTrue(html.contains("Connection diagnostics"), html);
        assertTrue(html.contains("TCP connect: FAILED"), html);
        assertTrue(html.contains("Error chain:"), html);
    }
}
