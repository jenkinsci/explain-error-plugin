package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Computes a stable fingerprint of a build failure, used to recognize a failure that was
 * already explained so its explanation can be reused instead of calling the AI provider again.
 *
 * <p>The failure log is normalized before hashing so that values which change on every run
 * do not hide an otherwise identical failure: every number (timestamps, durations, build
 * numbers, temporary file names, line numbers) is replaced with {@code 0}, hexadecimal ids of
 * eight or more characters (commit hashes, container and durable task ids) are replaced with
 * {@code #}, runs of spaces and tabs are collapsed and blank lines are dropped. The request
 * context (language, custom context, provider and model) is part of the fingerprint, so an
 * explanation is only reused when it would have been requested the same way.
 */
final class FailureFingerprint {

    private static final Pattern HEX_ID = Pattern.compile("\\b(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{8,}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern BLANKS = Pattern.compile("[ \\t]+");

    private FailureFingerprint() {
    }

    /**
     * Returns the fingerprint of a failure.
     *
     * @param errorLogs the failure log sent to the AI provider
     * @param context   request settings that change the explanation, such as language and model
     * @return a lowercase hex SHA-256 digest
     */
    @NonNull
    static String of(@CheckForNull String errorLogs, @CheckForNull String... context) {
        StringBuilder input = new StringBuilder(normalize(errorLogs));
        if (context != null) {
            for (String value : context) {
                // A separator that cannot occur in the normalized log keeps the fields apart
                input.append('\u0000').append(value == null ? "" : value);
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @NonNull
    static String normalize(@CheckForNull String errorLogs) {
        if (errorLogs == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(errorLogs.length());
        for (String line : errorLogs.split("\\R")) {
            String value = HEX_ID.matcher(line).replaceAll("#");
            value = NUMBER.matcher(value).replaceAll("0");
            value = BLANKS.matcher(value).replaceAll(" ").strip();
            if (!value.isEmpty()) {
                normalized.append(value).append('\n');
            }
        }
        return normalized.toString();
    }
}
