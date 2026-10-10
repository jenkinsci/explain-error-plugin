package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;

/**
 * Turns a piece of AI-written text into HTML for the explanation panel.
 *
 * <p>The text is model output derived from build logs, so all of it is escaped. The one piece of
 * markup recognised is a pair of backticks on a single line, which models put around commands,
 * file names and identifiers; it becomes a {@code <code>} element.
 */
final class ExplanationMarkup {

    private ExplanationMarkup() {
    }

    @NonNull
    static String inline(@CheckForNull String text) {
        if (text == null) {
            return "";
        }
        StringBuilder html = new StringBuilder(text.length() + 32);
        int from = 0;
        while (true) {
            int open = text.indexOf('`', from);
            int close = open < 0 ? -1 : text.indexOf('`', open + 1);
            if (close < 0) {
                break;
            }
            String code = text.substring(open + 1, close);
            if (code.isBlank() || code.indexOf('\n') >= 0) {
                // Not a code span: an empty pair, or one that runs over a line break. Both stay text.
                html.append(Util.xmlEscape(text.substring(from, close + 1)));
                from = close + 1;
                continue;
            }
            html.append(Util.xmlEscape(text.substring(from, open)))
                    .append("<code>").append(Util.xmlEscape(code)).append("</code>");
            from = close + 1;
        }
        return html.append(Util.xmlEscape(text.substring(from))).toString();
    }
}
