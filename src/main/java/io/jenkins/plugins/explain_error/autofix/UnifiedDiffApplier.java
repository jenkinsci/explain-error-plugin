package io.jenkins.plugins.explain_error.autofix;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone utility class that applies unified diffs to file content.
 */
public class UnifiedDiffApplier {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");

    private UnifiedDiffApplier() {}

    /**
     * Applies a unified diff string to original file content.
     *
     * @param originalContent the original file content
     * @param diff            the unified diff to apply
     * @return the modified file content
     * @throws IllegalArgumentException if the diff cannot be applied (e.g., context mismatch)
     */
    public static String apply(String originalContent, String diff) {
        // Split original into lines preserving empty trailing line behaviour
        List<String> lines = splitLines(originalContent);

        List<String> result = new ArrayList<>(lines);
        String[] diffLines = diff.split("\r?\n", -1);

        int offset = 0; // cumulative offset from previous hunk applications

        int i = 0;
        while (i < diffLines.length) {
            String line = diffLines[i];

            // Skip file header lines (--- / +++)
            if (line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("diff ") || line.startsWith("index ")) {
                i++;
                continue;
            }

            Matcher m = HUNK_HEADER.matcher(line);
            if (!m.matches()) {
                i++;
                continue;
            }

            // Parse hunk header
            int startOld = Integer.parseInt(m.group(1));
            int countOld = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
            // startNew and countNew not used directly beyond validation
            i++;

            // Collect hunk body lines
            List<String> hunkLines = new ArrayList<>();
            while (i < diffLines.length && !diffLines[i].startsWith("@@ ") && !diffLines[i].startsWith("--- ") && !diffLines[i].startsWith("+++ ")) {
                hunkLines.add(diffLines[i]);
                i++;
            }

            // Calculate insertion point in result (0-based)
            // startOld is 1-based; apply cumulative offset.
            // Special case: startOld=0 means "create new file" or "insert at beginning" —
            // treat as position 0 without fuzzy matching.
            int actualPos;
            if (startOld == 0) {
                actualPos = 0;
            } else {
                int expectedPos = (startOld - 1) + offset;
                // Extract context lines from hunk to find actual position (fuzzy match ±3)
                actualPos = findActualPosition(result, hunkLines, expectedPos, countOld);
            }
            if (actualPos < 0) {
                throw new IllegalArgumentException(
                        "Cannot apply diff hunk at line " + startOld + ": context lines do not match");
            }

            // Apply the hunk: remove old lines, insert new lines
            int removedCount = 0;
            List<String> insertLines = new ArrayList<>();

            for (String hunkLine : hunkLines) {
                if (hunkLine.startsWith("\\")) {
                    // "\ No newline at end of file" — skip
                    continue;
                }
                if (hunkLine.startsWith("-")) {
                    // Remove line: just track count
                    removedCount++;
                } else if (hunkLine.startsWith("+")) {
                    insertLines.add(hunkLine.substring(1));
                } else {
                    // Context line: keep as-is (will be part of result after removal/insertion)
                    insertLines.add(hunkLine.length() > 0 ? hunkLine.substring(1) : "");
                    removedCount++;
                }
            }

            // Remove old lines and insert new lines atomically
            for (int r = 0; r < removedCount && actualPos < result.size(); r++) {
                result.remove(actualPos);
            }
            result.addAll(actualPos, insertLines);

            // Update offset: new lines added minus old lines removed
            offset += insertLines.size() - removedCount;
        }

        return String.join("\n", result);
    }

    /**
     * Validates that a diff string is syntactically valid unified diff format.
     * Checks for: valid @@ hunk headers, non-empty hunks.
     *
     * @return null if valid, or an error message string if invalid
     */
    public static String validate(String diff) {
        if (diff == null || diff.isBlank()) {
            return "Diff is null or empty";
        }

        String[] lines = diff.split("\r?\n", -1);
        boolean foundHunk = false;
        boolean hunkHasChange = false;

        for (String line : lines) {
            if (line.startsWith("@@ ")) {
                // Validate format
                if (!line.matches("^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@.*$")) {
                    return "Invalid hunk header: " + line;
                }
                if (foundHunk && !hunkHasChange) {
                    return "Hunk has no changed lines (no + or - lines)";
                }
                foundHunk = true;
                hunkHasChange = false;
            } else if (foundHunk && (line.startsWith("+") || line.startsWith("-"))) {
                hunkHasChange = true;
            }
        }

        if (!foundHunk) {
            return "No @@ hunk headers found in diff";
        }

        if (!hunkHasChange) {
            return "Last hunk has no changed lines (no + or - lines)";
        }

        return null; // valid
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        // Split on \r\n or \n, preserving trailing empty string
        String[] parts = content.split("\r?\n", -1);
        List<String> list = new ArrayList<>();
        for (String part : parts) {
            list.add(part);
        }
        // If content ends with newline, split produces a trailing empty string — keep it
        // but remove it at the end since join("\n") will reconstruct the newline
        if (!list.isEmpty() && list.get(list.size() - 1).isEmpty() && content.endsWith("\n")) {
            // Keep it: join("\n") on ["a","b",""] => "a\nb\n"
        }
        return list;
    }

    /**
     * Finds the actual start position in {@code result} where this hunk should be applied.
     * Uses the context lines (lines starting with ' ') for validation.
     * Tries exact position first, then fuzzy match ±3.
     *
     * @return the 0-based index into {@code result}, or -1 if not found
     */
    private static int findActualPosition(List<String> result, List<String> hunkLines, int expectedPos, int countOld) {
        // Try exact position first, then fuzzy ±3
        for (int delta = 0; delta <= 3; delta++) {
            for (int sign : new int[]{0, 1, -1}) {
                if (sign == 0 && delta != 0) continue;
                int candidate = expectedPos + sign * delta;
                if (candidate < 0 || candidate > result.size()) continue;
                if (contextMatches(result, hunkLines, candidate)) {
                    return candidate;
                }
            }
        }
        return -1;
    }

    /**
     * Checks whether the context lines of the hunk match the result at the given position.
     */
    private static boolean contextMatches(List<String> result, List<String> hunkLines, int pos) {
        int resultIdx = pos;
        for (String hunkLine : hunkLines) {
            if (hunkLine.startsWith("\\")) continue;
            if (hunkLine.startsWith("+")) continue; // new lines don't need to match
            // Context line (' ') or removed line ('-') must match existing content
            String expected = hunkLine.length() > 0 ? hunkLine.substring(1) : "";
            if (resultIdx >= result.size()) {
                return false;
            }
            if (!result.get(resultIdx).equals(expected)) {
                return false;
            }
            resultIdx++;
        }
        return true;
    }
}
