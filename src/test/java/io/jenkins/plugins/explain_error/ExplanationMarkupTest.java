package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExplanationMarkupTest {

    @Test
    void plainTextIsEscaped() {
        assertEquals("a &lt; b &amp;&amp; c &gt; d", ExplanationMarkup.inline("a < b && c > d"));
    }

    @Test
    void backticksBecomeCodeElements() {
        assertEquals("Run <code>npm install</code>, then commit <code>package-lock.json</code>.",
                ExplanationMarkup.inline("Run `npm install`, then commit `package-lock.json`."));
    }

    @Test
    void markupFromTheModelIsNeverPassedThrough() {
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt; and <code>&lt;img src=x onerror=alert(1)&gt;</code>",
                ExplanationMarkup.inline("<script>alert(1)</script> and `<img src=x onerror=alert(1)>`"));
    }

    @Test
    void aLoneBacktickStaysText() {
        assertEquals("the ` character", ExplanationMarkup.inline("the ` character"));
    }

    @Test
    void emptyAndMultiLineSpansAreNotCode() {
        assertEquals("`` then `a\nb` then <code>c</code>", ExplanationMarkup.inline("`` then `a\nb` then `c`"));
    }

    @Test
    void dollarSignsAndBackslashesSurvive() {
        assertEquals("set <code>$HOME\\bin</code> for $1", ExplanationMarkup.inline("set `$HOME\\bin` for $1"));
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", ExplanationMarkup.inline(null));
    }
}
