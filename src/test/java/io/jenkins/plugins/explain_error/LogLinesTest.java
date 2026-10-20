package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.console.AnnotatedLargeText;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.framework.io.ByteBuffer;

class LogLinesTest {

    @Test
    void readsTheLastLinesOfALongLog() throws Exception {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= 50_000; i++) {
            log.append("line ").append(i).append('\n');
        }

        List<String> lines = LogLines.read(text(log.toString()), 100, null);

        assertEquals(100, lines.size());
        assertEquals("line 49901", lines.get(0));
        assertEquals("line 50000", lines.get(99));
    }

    @Test
    void readsFurtherBackWhenTheLinesAreLong() throws Exception {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            log.append(i).append(' ').append("x".repeat(1000)).append('\n');
        }

        List<String> lines = LogLines.read(text(log.toString()), 100, null);

        assertEquals(100, lines.size());
        assertTrue(lines.get(0).startsWith("201 "), lines.get(0));
    }

    @Test
    void aShortLogIsReadWhole() throws Exception {
        assertEquals(List.of("one", "two", "three"), LogLines.read(text("one\ntwo\nthree"), 100, null));
        assertEquals(List.of(), LogLines.read(text(""), 100, null));
    }

    @Test
    void aPatternSearchesTheWholeLog() throws Exception {
        StringBuilder log = new StringBuilder("ERROR: missing dependency\n");
        for (int i = 1; i <= 10_000; i++) {
            log.append("info ").append(i).append('\n');
        }
        log.append("Error again at the end\n");

        List<String> lines = LogLines.read(text(log.toString()), 100, PipelineLogExtractor.compileLogPattern("error"));

        assertEquals(List.of("ERROR: missing dependency", "Error again at the end"), lines);
    }

    @Test
    void aPatternKeepsTheLastMatches() throws Exception {
        String log = "ERROR 1\nERROR 2\nok\nERROR 3\n";

        assertEquals(List.of("ERROR 2", "ERROR 3"), LogLines.read(text(log), 2, Pattern.compile("ERROR")));
    }

    @Test
    void terminalColoursAreTakenOut() {
        assertEquals("red error", LogLines.clean("\u001B[31mred\u001B[0m \u001B[1;4merror\u001B[m"));
        assertEquals("see docs", LogLines.clean("see \u001B]8;;https://example.com\u0007docs\u001B]8;;\u0007"));
        assertEquals("plain", LogLines.clean("plain"));
    }

    @Test
    void aPatternMatchesTheLineWithoutItsColours() throws Exception {
        assertEquals(List.of("ERROR: boom"),
                LogLines.read(text("\u001B[31mERROR\u001B[0m: boom\nok\n"), 10, Pattern.compile("ERROR: boom")));
    }

    @Test
    void onlyWhatACarriageReturnLeftShows() {
        assertEquals("done", LogLines.clean("10%\r50%\rdone\r"));
        assertEquals("windows line", LogLines.clean("windows line\r"));
    }

    @Test
    void aVeryLongLineIsCutShort() {
        String line = LogLines.clean("y".repeat(10_000));

        assertEquals(LogLines.MAX_LINE_LENGTH + LogLines.TRUNCATED.length(), line.length());
        assertTrue(line.endsWith(LogLines.TRUNCATED));
    }

    @Test
    void linesAreSharedOutAmongTheFailingSteps() {
        assertArrayEquals(new int[] {33, 33, 34}, PipelineLogExtractor.share(new int[] {151, 151, 151}, 100));
        // A short log leaves the rest of its share to the others
        assertArrayEquals(new int[] {5, 95}, PipelineLogExtractor.share(new int[] {5, 200}, 100));
        assertArrayEquals(new int[] {5, 7}, PipelineLogExtractor.share(new int[] {5, 7}, 100));
        assertArrayEquals(new int[] {}, PipelineLogExtractor.share(new int[] {}, 100));
    }

    private static AnnotatedLargeText<Void> text(String content) throws Exception {
        ByteBuffer buffer = new ByteBuffer();
        buffer.write(content.getBytes(StandardCharsets.UTF_8));
        return new AnnotatedLargeText<>(buffer, StandardCharsets.UTF_8, true, null);
    }
}
