package io.github.ffuf.pkg.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Expected values come from Go 1.27's strconv.Quote. */
class GoTextTest {
    @Test
    void testQuote() {
        assertEquals("\"a\\x01b\"", GoText.quote("a\u0001b"));
        assertEquals("\"ü\"", GoText.quote(GoText.bin("ü")));
        assertEquals("\"\\xff\"", GoText.quote("\u00ff"));
        assertEquals("\"\\u00ad\"", GoText.quote(GoText.bin("\u00ad")));
        assertEquals("\"😀\"", GoText.quote(GoText.bin("😀")));
        assertEquals("\"\\\"\\\\\"", GoText.quote("\"\\"));
    }
}
