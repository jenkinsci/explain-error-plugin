package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * Tidies a title written by the AI before it is used as a heading, a pull request title or a
 * commit subject.
 */
public final class TitleText {

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
        line = line.replaceAll("^[\"'`]+|[\"'`]+$", "").strip();
        while (line.endsWith(".")) {
            line = line.substring(0, line.length() - 1).stripTrailing();
        }
        if (line.length() > maxLength) {
            int cut = line.lastIndexOf(' ', maxLength);
            line = line.substring(0, cut > maxLength / 2 ? cut : maxLength).stripTrailing();
        }
        return line.isEmpty() ? null : line;
    }
}
