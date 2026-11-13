package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * Tidies a title written by the AI before it is used as a heading, a pull request title or a
 * commit subject.
 */
public final class TitleText {

    private static final String WRAPPING_QUOTES = "\"'`";

    private TitleText() {
    }

    /**
     * Reduces {@code title} to one line without surrounding quotes or a trailing period, cut at a
     * word boundary to at most {@code maxLength} characters.
     *
     * @return the cleaned title, or {@code null} when nothing usable is left
     */
    @CheckForNull
    public static String clean(@CheckForNull String title, int maxLength) {
        if (title == null) {
            return null;
        }
        String line = title.strip();
        int lineBreak = line.indexOf('\n');
        if (lineBreak >= 0) {
            line = line.substring(0, lineBreak);
        }
        line = line.replaceAll("\\s+", " ").strip();
        line = withoutWrappingQuotes(line);
        while (line.endsWith(".")) {
            line = line.substring(0, line.length() - 1).stripTrailing();
        }
        if (line.length() > maxLength) {
            int cut = line.lastIndexOf(' ', maxLength);
            line = line.substring(0, cut > maxLength / 2 ? cut : maxLength).stripTrailing();
        }
        return line.isEmpty() ? null : line;
    }

    /**
     * Takes off quotes or backticks that wrap the whole title, as in {@code "Fix the build"}. A quote that
     * belongs to the text stays: {@code `npm ci` fails} keeps both backticks of its code span.
     */
    private static String withoutWrappingQuotes(String line) {
        while (line.length() >= 2) {
            char first = line.charAt(0);
            String inner = line.substring(1, line.length() - 1);
            if (WRAPPING_QUOTES.indexOf(first) < 0 || line.charAt(line.length() - 1) != first
                    || inner.indexOf(first) >= 0) {
                break;
            }
            line = inner.strip();
        }
        return line;
    }
}
