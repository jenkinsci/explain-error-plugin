package io.jenkins.plugins.explain_error.autofix.scm;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Percent-encoding for the file paths and refs the SCM clients put into API URLs. */
final class ScmUrls {

    private ScmUrls() {}

    /**
     * Encodes one path segment. {@link URLEncoder} targets form bodies and turns a space into {@code +},
     * which a URL path keeps as a literal plus sign, so spaces become {@code %20}.
     */
    static String pathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Encodes each segment of a slash-separated file path and keeps the slashes. */
    static String path(String filePath) {
        String[] segments = filePath.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(pathSegment(segments[i]));
        }
        return sb.toString();
    }

    /** Encodes a query parameter value. */
    static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
