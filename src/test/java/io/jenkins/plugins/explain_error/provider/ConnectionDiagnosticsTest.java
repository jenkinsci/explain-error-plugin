package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ConnectionDiagnosticsTest {

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
                "proxy.example.invalid", 3128, "proxy-user", "proxy-pass", "excluded.example.com");

        String report = ConnectionDiagnostics.run("https://api.example.invalid",
                new RuntimeException("boom"));

        assertTrue(report.contains("Proxy: via proxy.example.invalid:3128 (credentials configured)"), report);
        assertTrue(report.contains("Jenkins proxy settings: host=proxy.example.invalid port=3128"
                + " credentials=configured noProxyHost=excluded.example.com"), report);
        assertFalse(report.contains("proxy-pass"), "credentials must never be printed: " + report);
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
