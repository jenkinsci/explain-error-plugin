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
}
