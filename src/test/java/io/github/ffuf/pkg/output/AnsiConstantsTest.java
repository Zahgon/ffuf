package io.github.ffuf.pkg.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** ANSI terminal codes, or empty strings on Windows. */
class AnsiConstantsTest {
    @Test
    void testConstantsForThisPlatform() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        assertEquals(windows, Stdoutput.WINDOWS);
        assertEquals(windows ? "\r\r" : "\r\u001b[2K", Stdoutput.TERMINAL_CLEAR_LINE);
        assertEquals(windows ? "" : "\u001b[0m", Stdoutput.ANSI_CLEAR);
        assertEquals(windows ? "" : "\u001b[31m", Stdoutput.ANSI_RED);
        assertEquals(windows ? "" : "\u001b[32m", Stdoutput.ANSI_GREEN);
        assertEquals(windows ? "" : "\u001b[34m", Stdoutput.ANSI_BLUE);
        assertEquals(windows ? "" : "\u001b[33m", Stdoutput.ANSI_YELLOW);
    }
}
