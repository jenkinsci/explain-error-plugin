package io.jenkins.plugins.explain_error.provider;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ProxyConfiguration;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.net.ssl.SSLException;
import jenkins.model.Jenkins;

/**
 * Produces a plain-text connectivity report for a failed "Test Configuration"
 * call, so that admins can see <em>why</em> a provider endpoint cannot be
 * reached instead of a bare error message.
 * <p>
 * The report walks the connection layers one by one — URL syntax, Jenkins
 * proxy applicability (including {@code noProxyHost} exclusions), local DNS
 * resolution, TCP connect, and a full-stack HTTP probe that uses the same
 * proxy and redirect configuration as the real provider calls — and finishes
 * with the unwrapped error-cause chain.
 * <p>
 * Diagnostics run only on demand from the configuration form (behind the same
 * permission check as the test itself), never during builds. Each probe is
 * individually bounded by a timeout and guarded so this class can never throw.
 * Proxy credentials are reported as configured/not configured, never printed.
 */
final class ConnectionDiagnostics {

    private static final int TCP_TIMEOUT_MS = 5_000;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);
    private static final int BODY_EXCERPT_CHARS = 200;
    private static final int MAX_CAUSE_CHAIN = 6;

    private ConnectionDiagnostics() {
    }

    /**
     * Builds the report.
     *
     * @param endpointUrl the endpoint the provider would call, or {@code null}
     *                    when it cannot be determined
     * @param failure     the exception the configuration test failed with
     * @return a multi-line plain-text report, never {@code null}
     */
    static String run(@CheckForNull String endpointUrl, @CheckForNull Throwable failure) {
        StringBuilder out = new StringBuilder();
        try {
            out.append("--- Connection diagnostics ---\n");
            appendErrorChain(out, failure);

            URI uri = parseEndpoint(out, endpointUrl);
            if (uri != null) {
                Proxy proxy = appendProxyDecision(out, uri.getHost());
                boolean dnsOk = appendDnsProbe(out, uri.getHost(), proxy);
                appendTcpProbe(out, uri, proxy, dnsOk);
                appendHttpProbe(out, uri);
            }

            appendJenkinsProxySummary(out);
        } catch (RuntimeException e) {
            // Diagnostics must never break the error response they decorate.
            out.append("(diagnostics aborted: ").append(e).append(")\n");
        }
        return out.toString();
    }

    private static void appendErrorChain(StringBuilder out, @CheckForNull Throwable failure) {
        if (failure == null) {
            return;
        }
        List<String> chain = new ArrayList<>();
        Throwable current = failure;
        String previousDescription = null;
        while (current != null && chain.size() < MAX_CAUSE_CHAIN) {
            String description = current.getClass().getSimpleName() + ": " + summarize(current.getMessage());
            if (!description.equals(previousDescription)) {
                chain.add(description);
                previousDescription = description;
            }
            current = current.getCause();
        }
        out.append("Error chain: ").append(String.join("\n  <- ", chain)).append('\n');
    }

    @CheckForNull
    private static URI parseEndpoint(StringBuilder out, @CheckForNull String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            out.append("Endpoint: not determined for this provider; network probes skipped.\n");
            return null;
        }
        try {
            URI uri = URI.create(endpointUrl.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                out.append("Endpoint: ").append(endpointUrl)
                        .append(" — not a usable http(s) URL; network probes skipped.\n");
                return null;
            }
            out.append("Endpoint: ").append(uri).append('\n');
            return uri;
        } catch (IllegalArgumentException e) {
            out.append("Endpoint: ").append(endpointUrl).append(" — invalid URL: ")
                    .append(summarize(e.getMessage())).append('\n');
            return null;
        }
    }

    /**
     * Reports which proxy Jenkins would use for the endpoint host, honouring
     * {@code noProxyHost} exclusions.
     *
     * @return the proxy to use, or {@link Proxy#NO_PROXY} for a direct connection
     */
    private static Proxy appendProxyDecision(StringBuilder out, String host) {
        ProxyConfiguration proxyConfiguration = jenkinsProxy();
        if (proxyConfiguration == null || proxyConfiguration.getName() == null) {
            out.append("Proxy: none configured; connecting directly.\n");
            return Proxy.NO_PROXY;
        }
        Proxy proxy = proxyConfiguration.createProxy(host);
        if (proxy == Proxy.NO_PROXY || proxy.type() == Proxy.Type.DIRECT) {
            out.append("Proxy: configured (").append(proxyConfiguration.getName()).append(':')
                    .append(proxyConfiguration.getPort())
                    .append(") but this host matches noProxyHost — connecting directly.\n");
            return Proxy.NO_PROXY;
        }
        out.append("Proxy: via ").append(proxyConfiguration.getName()).append(':')
                .append(proxyConfiguration.getPort())
                .append(proxyConfiguration.getUserName() != null
                        ? " (credentials configured)" : " (no credentials)")
                .append('\n');
        return proxy;
    }

    private static boolean appendDnsProbe(StringBuilder out, String host, Proxy proxy) {
        long start = System.nanoTime();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            String shown = java.util.Arrays.stream(addresses).limit(3)
                    .map(InetAddress::getHostAddress)
                    .collect(Collectors.joining(", "));
            out.append("DNS: ").append(host).append(" -> ").append(shown)
                    .append(addresses.length > 3 ? ", ..." : "")
                    .append(" (").append(elapsedMs(start)).append(" ms)\n");
            return true;
        } catch (Exception e) {
            out.append("DNS: FAILED to resolve ").append(host).append(" locally: ")
                    .append(summarize(e.getMessage()));
            if (proxy != Proxy.NO_PROXY) {
                out.append(" — may still work: the proxy resolves names for tunneled connections");
            }
            out.append('\n');
            return false;
        }
    }

    private static void appendTcpProbe(StringBuilder out, URI uri, Proxy proxy, boolean dnsOk) {
        String targetHost;
        int targetPort;
        String label;
        if (proxy != Proxy.NO_PROXY && proxy.address() instanceof InetSocketAddress proxyAddress) {
            targetHost = proxyAddress.getHostString();
            targetPort = proxyAddress.getPort();
            label = "proxy " + targetHost + ":" + targetPort;
        } else {
            if (!dnsOk) {
                out.append("TCP connect: skipped (DNS resolution failed).\n");
                return;
            }
            targetHost = uri.getHost();
            targetPort = uri.getPort() != -1 ? uri.getPort()
                    : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            label = targetHost + ":" + targetPort;
        }

        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetHost, targetPort), TCP_TIMEOUT_MS);
            out.append("TCP connect: OK to ").append(label)
                    .append(" (").append(elapsedMs(start)).append(" ms)\n");
        } catch (Exception e) {
            out.append("TCP connect: FAILED to ").append(label).append(" after ")
                    .append(elapsedMs(start)).append(" ms: ")
                    .append(summarize(e.getMessage())).append('\n');
        }
    }

    /**
     * Full-stack probe with the same proxy, credentials and redirect settings
     * the real provider calls use. A response of any HTTP status counts as
     * reachable — 401/404 from the base URL still proves DNS, TCP, TLS and
     * proxy traversal all work.
     */
    private static void appendHttpProbe(StringBuilder out, URI uri) {
        long start = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(diagnosticsProxySelector())
                    .build();
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(HTTP_TIMEOUT)
                    .GET();
            String proxyAuthorization = BaseAIProvider.proxyAuthorizationHeaderOrNull();
            if (proxyAuthorization != null) {
                request.header("Proxy-Authorization", proxyAuthorization);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            out.append("HTTP probe: GET ").append(uri).append(" -> HTTP ").append(response.statusCode())
                    .append(" (").append(elapsedMs(start)).append(" ms)");
            if (response.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED
                    || response.statusCode() == HttpURLConnection.HTTP_FORBIDDEN) {
                out.append(" — endpoint reachable; check the API key / authentication settings");
            }
            out.append('\n');
            String excerpt = summarize(response.body());
            if (!excerpt.isEmpty()) {
                out.append("HTTP probe response excerpt: ").append(excerpt).append('\n');
            }
        } catch (SSLException e) {
            out.append("HTTP probe: TLS handshake FAILED after ").append(elapsedMs(start))
                    .append(" ms: ").append(summarize(e.getMessage()))
                    .append(" — check certificates/truststore on the controller\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("HTTP probe: interrupted\n");
        } catch (Exception e) {
            out.append("HTTP probe: FAILED after ").append(elapsedMs(start)).append(" ms: ")
                    .append(e.getClass().getSimpleName()).append(": ")
                    .append(summarize(e.getMessage())).append('\n');
        }
    }

    private static void appendJenkinsProxySummary(StringBuilder out) {
        ProxyConfiguration proxyConfiguration = jenkinsProxy();
        if (proxyConfiguration == null || proxyConfiguration.getName() == null) {
            return;
        }
        out.append("Jenkins proxy settings: host=").append(proxyConfiguration.getName())
                .append(" port=").append(proxyConfiguration.getPort())
                .append(" credentials=").append(proxyConfiguration.getUserName() != null ? "configured" : "none")
                .append(" noProxyHost=").append(nullToNone(proxyConfiguration.getNoProxyHost()))
                .append('\n');
    }

    private static ProxySelector diagnosticsProxySelector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                ProxyConfiguration proxyConfiguration = jenkinsProxy();
                if (proxyConfiguration != null && proxyConfiguration.getName() != null && uri.getHost() != null) {
                    return List.of(proxyConfiguration.createProxy(uri.getHost()));
                }
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
                // Reported by the probe's own exception handling.
            }
        };
    }

    @CheckForNull
    private static ProxyConfiguration jenkinsProxy() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins != null ? jenkins.getProxy() : null;
    }

    private static String summarize(@CheckForNull String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() > BODY_EXCERPT_CHARS
                ? flattened.substring(0, BODY_EXCERPT_CHARS) + "..."
                : flattened;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String nullToNone(@CheckForNull String value) {
        return value == null || value.isBlank() ? "(none)" : value.replaceAll("\\s+", " ");
    }
}
