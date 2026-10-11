package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TitleTextTest {

    @Test
    void keepsAGoodTitleAsItIs() {
        assertEquals("Pinned requests version does not exist on PyPI",
                TitleText.clean("Pinned requests version does not exist on PyPI", 70));
    }

    @Test
    void dropsQuotesTrailingPeriodsAndExtraWhitespace() {
        assertEquals("pin requests to 2.34.2", TitleText.clean("  \"pin  requests to 2.34.2.\" ", 60));
        assertEquals("npm ci fails: lock file out of sync",
                TitleText.clean("`npm ci fails: lock file out of sync`", 60));
    }

    @Test
    void keepsOnlyTheFirstLine() {
        assertEquals("Docker daemon is not running",
                TitleText.clean("Docker daemon is not running\nStart Colima.", 70));
    }

    @Test
    void cutsALongTitleAtAWordBoundary() {
        assertEquals("RefundServiceTest fails with a NullPointerException in",
                TitleText.clean("RefundServiceTest fails with a NullPointerException in CurrencyConverter", 60));
    }

    @Test
    void cutsAWordThatIsLongerThanTheLimit() {
        assertEquals("abcdefghij", TitleText.clean("abcdefghijklmnopqrstuvwxyz", 10));
    }

    @Test
    void nothingUsableIsNull() {
        assertNull(TitleText.clean(null, 70));
        assertNull(TitleText.clean("  ", 70));
        assertNull(TitleText.clean("\"...\"", 70));
    }
}
