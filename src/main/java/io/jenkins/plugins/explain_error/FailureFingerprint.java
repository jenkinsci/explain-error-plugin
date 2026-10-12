package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Computes a fingerprint of a build failure, used to recognize a failure that was already
 * explained so its explanation can be reused instead of calling the AI provider again.
 *
 * <p>The failure log is normalized before hashing, so that values which change on every run do
 * not hide an otherwise identical failure: dates and times, durations, transfer sizes and rates,
 * build numbers, long numeric ids (temporary file names, process ids) and hexadecimal ids of
 * eight or more characters (commit hashes, container ids). Runs of spaces and tabs are collapsed
 * and blank lines dropped. So is the flow node id in the lines {@link PipelineLogExtractor} puts
 * around a step's log: it changes when a step is added earlier in the Pipeline, and says nothing
 * about the failure.
 *
 * <p>Every other number is kept. A version, a line number or an expected value that differs
 * means a different failure, and an explanation that names the old one would be wrong for it.
 * The match is deliberately strict: a missed reuse costs one provider call, a wrong reuse shows
 * someone an explanation of a failure they do not have.
 *
 * <p>The request context (language, custom context, provider and model) is part of the
 * fingerprint, so an explanation is only reused when it would have been requested the same way.
 */
final class FailureFingerprint {

    /** 2026-09-29, optionally followed by a time: T10:11:12.345Z, 10:11:12+03:00, T12_38_32_871Z. */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}[:_]\\d{2}[:_]\\d{2}(?:[.,_]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)?");
    private static final Pattern TIME_OF_DAY = Pattern.compile("\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b");
    private static final Pattern DURATION = Pattern.compile(
            "\\b\\d+(?:[.,]\\d+)?\\s?(?:ms|s|sec|secs|seconds?|m|min|mins|minutes?|h|hrs?|hours?)\\b");
    private static final Pattern SIZE = Pattern.compile(
            "\\b\\d+(?:[.,]\\d+)?\\s?(?:[kKMGT]i?B|B|bytes)(?:/s)?(?![\\w])");
    private static final Pattern BUILD_NUMBER = Pattern.compile("#\\d+");
    private static final Pattern HEX_ID = Pattern.compile("\\b(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{8,}\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{6,}");
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
            String value = TIMESTAMP.matcher(withoutNodeId(line)).replaceAll("<time>");
            value = TIME_OF_DAY.matcher(value).replaceAll("<time>");
            value = DURATION.matcher(value).replaceAll("<duration>");
            value = SIZE.matcher(value).replaceAll("<size>");
            value = BUILD_NUMBER.matcher(value).replaceAll("#<n>");
            value = HEX_ID.matcher(value).replaceAll("<id>");
            value = LONG_NUMBER.matcher(value).replaceAll("<id>");
            value = BLANKS.matcher(value).replaceAll(" ").strip();
            if (!value.isEmpty()) {
                normalized.append(value).append('\n');
            }
        }
        return normalized.toString();
    }

    private static String withoutNodeId(String line) {
        for (String prefix : new String[] {
                PipelineLogExtractor.NODE_HEADER_PREFIX, PipelineLogExtractor.NODE_FOOTER_PREFIX}) {
            if (line.startsWith(prefix) && line.endsWith(PipelineLogExtractor.NODE_MARKER_SUFFIX)) {
                return prefix + "<node>" + PipelineLogExtractor.NODE_MARKER_SUFFIX;
            }
        }
        return line;
    }
}
