package io.github.ffuf.pkg.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The default shell is /bin/sh, or cmd.exe on Windows. */
class CommandShellTest {
    @Test
    void testShellForThisPlatform() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        assertEquals(windows ? "cmd.exe" : "/bin/sh", CommandInput.SHELL_CMD);
        assertEquals(windows ? "/C" : "-c", CommandInput.SHELL_ARG);
    }
}
