package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleNote;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the lines of a build or step log that are sent to the AI provider: the last ones, at most
 * {@code maxLines} of them, or with a pattern the last lines it matches. Jenkins annotations and
 * terminal colour codes are taken out, and a very long line is cut short.
 * <p>
 * Memory stays bounded whatever the size of the log: without a pattern only the end of the log is
 * read, and with one the log is read through once, line by line, keeping no more lines than asked for.
 */
final class LogLines {

    /** The most characters of one line that are sent: a minified file or a base64 blob says nothing more. */
    static final int MAX_LINE_LENGTH = 2000;

    static final String TRUNCATED = " [line cut short]";

    /** The most bytes of one line that are looked at; the rest of the line is skipped as it is read. */
    private static final int MAX_RAW_LINE_LENGTH = 64 * 1024;

    /** How much of the end of a log is read first, per line asked for. Read again from further back if short. */
    private static final long BYTES_PER_LINE = 256;

    private static final long MIN_TAIL_BYTES = 8 * 1024;

    private static final long MAX_TAIL_BYTES = 16L * 1024 * 1024;

    /** Terminal escape sequences: colours and cursor moves (CSI), titles and links (OSC), and the rest. */
    private static final Pattern ANSI_ESCAPE =
            Pattern.compile("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])");

    private LogLines() {
    }

    /**
     * @param text     the log
     * @param maxLines the most lines to return
     * @param pattern  when set, only lines it finds a match in count
     * @return the last {@code maxLines} lines of the log, or of the lines {@code pattern} matches, oldest first
     */
    static List<String> read(AnnotatedLargeText<?> text, int maxLines, @CheckForNull Pattern pattern)
            throws IOException {
        return read(text, StandardCharsets.UTF_8, maxLines, pattern);
    }

    /**
     * @param charset what the log is written in
     * @see #read(AnnotatedLargeText, int, Pattern)
     */
    static List<String> read(AnnotatedLargeText<?> text, Charset charset, int maxLines,
                             @CheckForNull Pattern pattern) throws IOException {
        long length = text.length();
        if (maxLines <= 0 || length <= 0) {
            return List.of();
        }
        if (pattern != null) {
            Collector collector = new Collector(charset, maxLines, pattern, false);
            if (text.writeRawLogTo(0, collector) <= 0) {
                return List.of();
            }
            return collector.finish();
        }
        long window = Math.min(length, Math.max(MIN_TAIL_BYTES, maxLines * BYTES_PER_LINE));
        while (true) {
            long start = length - window;
            // Reading from the middle of the log starts in the middle of a line, which is left out
            Collector collector = new Collector(charset, maxLines, null, start > 0);
            if (text.writeRawLogTo(start, collector) <= start) {
                return List.of();
            }
            List<String> lines = collector.finish();
            if (lines.size() >= maxLines || start == 0 || window >= MAX_TAIL_BYTES) {
                return lines;
            }
            window = Math.min(length, Math.min(MAX_TAIL_BYTES, window * 4));
        }
    }

    /** One line as it is sent: without annotations, colours or what a carriage return wrote over. */
    static String clean(String raw) {
        String line = ConsoleNote.removeNotes(raw);
        if (line.indexOf('\u001B') >= 0) {
            line = ANSI_ESCAPE.matcher(line).replaceAll("");
        }
        while (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        // A progress bar redraws its line after a carriage return; a terminal shows the last drawing only
        int carriageReturn = line.lastIndexOf('\r');
        if (carriageReturn >= 0) {
            line = line.substring(carriageReturn + 1);
        }
        if (line.length() > MAX_LINE_LENGTH) {
            int end = Character.isHighSurrogate(line.charAt(MAX_LINE_LENGTH - 1)) ? MAX_LINE_LENGTH - 1
                    : MAX_LINE_LENGTH;
            line = line.substring(0, end) + TRUNCATED;
        }
        return line;
    }

    /**
     * Takes the log as it is written, byte by byte, and keeps the last lines that count. Lines are split on
     * the newline byte, which UTF-8 never uses inside a character, and decoded one by one.
     */
    private static final class Collector extends OutputStream {

        private final Charset charset;
        private final int maxLines;
        @CheckForNull
        private final Pattern pattern;
        private final Deque<String> lines = new ArrayDeque<>();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();
        private boolean skipFirstLine;

        Collector(Charset charset, int maxLines, @CheckForNull Pattern pattern, boolean skipFirstLine) {
            this.charset = charset;
            this.maxLines = maxLines;
            this.pattern = pattern;
            this.skipFirstLine = skipFirstLine;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                endLine();
            } else if (current.size() < MAX_RAW_LINE_LENGTH) {
                current.write(b);
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            int lineStart = offset;
            int end = offset + length;
            for (int i = offset; i < end; i++) {
                if (buffer[i] == '\n') {
                    append(buffer, lineStart, i - lineStart);
                    endLine();
                    lineStart = i + 1;
                }
            }
            append(buffer, lineStart, end - lineStart);
        }

        private void append(byte[] buffer, int offset, int length) {
            int room = MAX_RAW_LINE_LENGTH - current.size();
            if (room > 0 && length > 0) {
                current.write(buffer, offset, Math.min(room, length));
            }
        }

        private void endLine() {
            if (skipFirstLine) {
                skipFirstLine = false;
            } else {
                String line = clean(current.toString(charset));
                if (pattern == null || pattern.matcher(line).find()) {
                    lines.addLast(line);
                    if (lines.size() > maxLines) {
                        lines.removeFirst();
                    }
                }
            }
            current.reset();
        }

        List<String> finish() {
            if (current.size() > 0) {
                endLine();
            }
            return new ArrayList<>(lines);
        }
    }
}
