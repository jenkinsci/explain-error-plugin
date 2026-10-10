package io.jenkins.plugins.explain_error.autofix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedDiffApplierTest {

    // -----------------------------------------------------------------------
    // apply() — happy paths
    // -----------------------------------------------------------------------

    @Test
    void applySimpleAddition() {
        String original = "line1\nline2\nline3\n";
        String diff = "--- a/file.txt\n+++ b/file.txt\n@@ -1,3 +1,4 @@\n line1\n line2\n+added line\n line3\n";
        String result = UnifiedDiffApplier.apply(original, diff);
        assertEquals("line1\nline2\nadded line\nline3\n", result);
    }

    @Test
    void applySimpleRemoval() {
        String original = "line1\nline2\nline3\n";
        String diff = "--- a/file.txt\n+++ b/file.txt\n@@ -1,3 +1,2 @@\n line1\n-line2\n line3\n";
        String result = UnifiedDiffApplier.apply(original, diff);
        assertEquals("line1\nline3\n", result);
    }

    @Test
    void applySimpleModification() {
        String original = "line1\nold line\nline3\n";
        String diff = "--- a/file.txt\n+++ b/file.txt\n@@ -1,3 +1,3 @@\n line1\n-old line\n+new line\n line3\n";
        String result = UnifiedDiffApplier.apply(original, diff);
        assertEquals("line1\nnew line\nline3\n", result);
    }

    @Test
    void applyMultipleHunks() {
        String original = "alpha\nbeta\ngamma\ndelta\nepsilon\n";
        // First hunk modifies line 1, second hunk modifies line 5
        String diff = "--- a/file.txt\n+++ b/file.txt\n"
                + "@@ -1,2 +1,2 @@\n-alpha\n+ALPHA\n beta\n"
                + "@@ -4,2 +4,2 @@\n delta\n-epsilon\n+EPSILON\n";
        String result = UnifiedDiffApplier.apply(original, diff);
        assertTrue(result.contains("ALPHA"));
        assertTrue(result.contains("EPSILON"));
        assertFalse(result.contains("alpha"));
        assertFalse(result.contains("epsilon"));
    }

    @Test
    void applyPomXmlDependencyAddition() {
        String original = "  <dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>junit</groupId>\n"
                + "    </dependency>\n"
                + "  </dependencies>\n";
        String diff = "--- a/pom.xml\n+++ b/pom.xml\n"
                + "@@ -1,5 +1,9 @@\n"
                + "   <dependencies>\n"
                + "     <dependency>\n"
                + "       <groupId>junit</groupId>\n"
                + "     </dependency>\n"
                + "+    <dependency>\n"
                + "+      <groupId>mockito</groupId>\n"
                + "+      <artifactId>mockito-core</artifactId>\n"
                + "+    </dependency>\n"
                + "   </dependencies>\n";
        String result = UnifiedDiffApplier.apply(original, diff);
        assertTrue(result.contains("mockito-core"));
        assertTrue(result.contains("junit"));
    }

    @Test
    void applyToEmptyFile() {
        String original = "";
        String diff = "--- a/file.txt\n+++ b/file.txt\n@@ -0,0 +1,2 @@\n+line1\n+line2\n";
        // Should not throw; result contains the added lines
        String result = UnifiedDiffApplier.apply(original, diff);
        assertTrue(result.contains("line1"));
        assertTrue(result.contains("line2"));
    }

    @Test
    void applyAdditionAtEndOfFile() {
        String original = "existing\n";
        String diff = "--- a/file.txt\n+++ b/file.txt\n@@ -1,1 +1,2 @@\n existing\n+appended\n";
        String result = UnifiedDiffApplier.apply(original, diff);
        assertTrue(result.contains("appended"));
        assertTrue(result.contains("existing"));
    }

    // -----------------------------------------------------------------------
    // apply() — the diff's own trailing newline is not a context line
    // -----------------------------------------------------------------------

    @Test
    void applyHunkInTheMiddleOfAFileWhenTheDiffEndsWithANewline() {
        // The diff a provider returned for a bad pin on line 2 of a three-line requirements.txt
        String original = "flask==3.0.3\nrequests==2.99.0\npytest==8.3.3\n";
        String diff = "--- a/requirements.txt\n+++ b/requirements.txt\n@@ -1,2 +1,2 @@\n"
                + " flask==3.0.3\n-requests==2.99.0\n+requests==2.32.3\n";

        assertEquals("flask==3.0.3\nrequests==2.32.3\npytest==8.3.3\n", UnifiedDiffApplier.apply(original, diff));
    }

    @Test
    void applyHunkInTheMiddleOfAFileWhenTheDiffUsesCrlf() {
        String original = "flask==3.0.3\nrequests==2.99.0\npytest==8.3.3\n";
        String diff = "--- a/requirements.txt\r\n+++ b/requirements.txt\r\n@@ -1,2 +1,2 @@\r\n"
                + " flask==3.0.3\r\n-requests==2.99.0\r\n+requests==2.32.3\r\n";

        assertEquals("flask==3.0.3\nrequests==2.32.3\npytest==8.3.3\n", UnifiedDiffApplier.apply(original, diff));
    }

    @Test
    void applyIgnoresBlankLinesBetweenHunksAndAfterTheLastOne() {
        String original = "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\n";
        String diff = "--- a/file.txt\n+++ b/file.txt\n"
                + "@@ -1,2 +1,2 @@\n-alpha\n+ALPHA\n beta\n\n"
                + "@@ -4,2 +4,2 @@\n delta\n-epsilon\n+EPSILON\n\n";

        assertEquals("ALPHA\nbeta\ngamma\ndelta\nEPSILON\nzeta\n", UnifiedDiffApplier.apply(original, diff));
    }

    @Test
    void applyStillTreatsAnEmptyLineInsideAHunkAsBlankContext() {
        // Some tools strip the single space that marks a blank context line
        String diff = "@@ -1,3 +1,3 @@\n a\n\n-b\n+B\n";

        assertEquals("a\n\nB\nc\n", UnifiedDiffApplier.apply("a\n\nb\nc\n", diff));
        assertThrows(IllegalArgumentException.class, () -> UnifiedDiffApplier.apply("a\nx\nb\nc\n", diff));
    }

    @Test
    void applyKeepsATrailingBlankContextLineThatIsMarkedWithASpace() {
        String diff = "@@ -1,2 +1,2 @@\n-a\n+A\n \n";

        assertEquals("A\n\nb\n", UnifiedDiffApplier.apply("a\n\nb\n", diff));
        assertThrows(IllegalArgumentException.class, () -> UnifiedDiffApplier.apply("a\nx\nb\n", diff));
    }

    @Test
    void applyInsertionAtTheBeginningKeepsTheFirstLine() {
        // The trailing newline used to count as one context line to remove, which replaced
        // the file's first line with an empty one without raising an error
        String diff = "@@ -0,0 +1,1 @@\n+# header\n";

        assertEquals("# header\na\nb\n", UnifiedDiffApplier.apply("a\nb\n", diff));
    }

    @Test
    void applyToEmptyFileEndsWithANewlineUnlessTheDiffSaysOtherwise() {
        assertEquals("line1\nline2\n", UnifiedDiffApplier.apply("", "@@ -0,0 +1,2 @@\n+line1\n+line2\n"));
        assertEquals("line1\nline2\n", UnifiedDiffApplier.apply("", "@@ -0,0 +1,2 @@\n+line1\n+line2"));
        assertEquals("line1\nline2", UnifiedDiffApplier.apply("",
                "@@ -0,0 +1,2 @@\n+line1\n+line2\n\\ No newline at end of file\n"));
    }

    // -----------------------------------------------------------------------
    // apply() — error paths
    // -----------------------------------------------------------------------

    @Test
    void applyThrowsOnContextMismatch() {
        String original = "line1\nline2\nline3\n";
        // Diff expects "lineX" at position 1 but original has "line1"
        String diff = "--- a/f\n+++ b/f\n@@ -1,1 +1,2 @@\n lineX\n+new\n";
        assertThrows(IllegalArgumentException.class, () -> UnifiedDiffApplier.apply(original, diff));
    }

    // -----------------------------------------------------------------------
    // validate() — valid cases
    // -----------------------------------------------------------------------

    @Test
    void validateValidDiff() {
        String diff = "--- a/f\n+++ b/f\n@@ -1,1 +1,2 @@\n context\n+new line\n";
        assertNull(UnifiedDiffApplier.validate(diff));
    }

    @Test
    void validateValidDiffWithOnlyAdditions() {
        String diff = "--- a/f\n+++ b/f\n@@ -0,0 +1,3 @@\n+line1\n+line2\n+line3\n";
        assertNull(UnifiedDiffApplier.validate(diff));
    }

    @Test
    void validateValidDiffWithMultipleHunks() {
        String diff = "--- a/f\n+++ b/f\n"
                + "@@ -1,1 +1,2 @@\n context\n+added\n"
                + "@@ -5,1 +6,1 @@\n-old\n+new\n";
        assertNull(UnifiedDiffApplier.validate(diff));
    }

    // -----------------------------------------------------------------------
    // validate() — invalid cases
    // -----------------------------------------------------------------------

    @Test
    void validateInvalidDiff_noHunks() {
        assertNotNull(UnifiedDiffApplier.validate("--- a/f\n+++ b/f\nno hunks here"));
    }

    @Test
    void validateInvalidDiff_malformedHunkHeader() {
        assertNotNull(UnifiedDiffApplier.validate("--- a/f\n+++ b/f\n@@ bad header @@\n+line\n"));
    }

    @Test
    void validateNullDiff() {
        assertNotNull(UnifiedDiffApplier.validate(null));
    }

    @Test
    void validateBlankDiff() {
        assertNotNull(UnifiedDiffApplier.validate("   "));
    }

    @Test
    void validateHunkWithNoChangedLines() {
        // A hunk header that is followed by only context lines (no + or -)
        String diff = "--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n context1\n context2\n";
        assertNotNull(UnifiedDiffApplier.validate(diff));
    }

    @Test
    void applyIgnoresGitMetadataAndNoNewlineMarkers() {
        String original = "alpha\nbeta";
        String diff = "diff --git a/f.txt b/f.txt\nindex 123..456 100644\nsome preamble\n--- a/f.txt\n+++ b/f.txt\n"
                + "@@ -1,2 +1,2 @@\n alpha\n-beta\n\\ No newline at end of file\n+gamma\n\\ No newline at end of file";

        assertEquals("alpha\ngamma", UnifiedDiffApplier.apply(original, diff));
    }

    @Test
    void applyRejectsContextBeyondTheEndOfTheFile() {
        String diff = "@@ -1,3 +1,3 @@\n one\n-two\n+2\n three";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> UnifiedDiffApplier.apply("one\ntwo", diff));
        assertTrue(e.getMessage().contains("context lines do not match"), e.getMessage());
    }

    @Test
    void validateRejectsAHunkWithoutChangesBeforeAnotherHunk() {
        String diff = "@@ -1,1 +1,1 @@\n context only\n@@ -5,1 +5,1 @@\n-old\n+new";

        assertEquals("Hunk has no changed lines (no + or - lines)", UnifiedDiffApplier.validate(diff));
    }
}
