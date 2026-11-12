package io.github.ffuf.pkg.output;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.InputProviderConfig;
import io.github.ffuf.pkg.ffuf.Progress;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StdoutTest {
    static final String TERMINAL_CLEAR_LINE = "\r\u001b[2K";

    /** Runs fn with System.out redirected and returns what it wrote. */
    static String captureStdout(Runnable fn) {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            fn.run();
        } finally {
            System.setOut(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** Runs fn with System.err redirected and returns what it wrote. */
    static String captureStderr(Runnable fn) {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            fn.run();
        } finally {
            System.setErr(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    static Config config(boolean colors) {
        Config conf = new Config();
        conf.colors = colors;
        conf.inputProviders.add(new InputProviderConfig("wordlist", "/tmp/wl.txt", "FUZZ", ""));
        return conf;
    }

    /** A matched response: status 200, 42 bytes, 3 words, 1 line, for payload. */
    static Response response(String payload, String url) {
        Response r = new Response();
        r.statusCode = 200;
        r.contentLength = 42;
        r.contentWords = 3;
        r.contentLines = 1;
        r.request = new Request();
        r.request.input.put("FUZZ", payload.getBytes(StandardCharsets.UTF_8));
        r.request.url = url;
        return r;
    }

    /** A result with status 200, 42 bytes, 3 words and 1 line. */
    private static Stdoutput.Result result() {
        Stdoutput.Result res = new Stdoutput.Result();
        res.statusCode = 200;
        res.contentLength = 42;
        res.contentWords = 3;
        res.contentLines = 1;
        return res;
    }

    @Test
    void testResultOutputOmitsControlCharsWhenStdoutIsNotATerminal() {
        Stdoutput outp = new Stdoutput(new Config());
        outp.stdoutIsTerminal = false;
        String out = captureStdout(() -> outp.printResult(result()));
        assertFalse(out.contains(TERMINAL_CLEAR_LINE), "result line written to a non-terminal stdout still contains the clear-line sequence: " + out);
    }

    @Test
    void testResultOutputKeepsControlCharsOnATerminal() {
        Stdoutput outp = new Stdoutput(new Config());
        outp.stdoutIsTerminal = true;
        String out = captureStdout(() -> outp.printResult(result()));
        assertTrue(out.contains(TERMINAL_CLEAR_LINE), "result line written to a terminal stdout is missing the clear-line sequence: " + out);
    }

    @Test
    void testErrorOutputOmitsControlCharsWhenStderrIsNotATerminal() {
        Stdoutput outp = new Stdoutput(new Config());
        outp.stderrIsTerminal = false;
        String out = captureStderr(() -> outp.error("something broke"));
        assertFalse(out.contains(TERMINAL_CLEAR_LINE), "error() written to a non-terminal stderr still contains the clear-line sequence: " + out);
    }

    @Test
    void testErrorOutputKeepsControlCharsOnATerminal() {
        Stdoutput outp = new Stdoutput(new Config());
        outp.stderrIsTerminal = true;
        String out = captureStderr(() -> outp.error("something broke"));
        assertTrue(out.contains(TERMINAL_CLEAR_LINE), "error() written to a terminal stderr is missing the clear-line sequence: " + out);
    }

    /** The plain error tag is separated from the message with a no-break space. */
    @Test
    void testErrorLineText() {
        Stdoutput outp = new Stdoutput(new Config());
        outp.stderrIsTerminal = false;
        assertTrue(captureStderr(() -> outp.error("something broke")).equals("[ERR]\u00a0something broke\n"), "error line");
    }

    @Test
    void testProgressOutputOmitsControlCharsWhenStderrIsNotATerminal() {
        Stdoutput outp = new Stdoutput(new Config());
        outp.stderrIsTerminal = false;
        String out = captureStderr(() -> outp.progress(new Progress(System.nanoTime(), 0, 0, 0, 0, 0, 0)));
        assertFalse(out.contains(TERMINAL_CLEAR_LINE), "progress() written to a non-terminal stderr still contains the clear-line sequence: " + out);
    }

    @Test
    void testResultOutputOmitsColorResetWhenColorsAreDisabled() {
        Config conf = new Config();
        conf.colors = false;
        Stdoutput outp = new Stdoutput(conf);
        outp.stdoutIsTerminal = true;
        String out = captureStdout(() -> outp.printResult(result()));
        assertFalse(out.contains(Stdoutput.ANSI_CLEAR), "result line printed without -c still carries an ANSI reset code: " + out);
    }

    @Test
    void testResultOutputKeepsColorResetWhenColorsAreEnabled() {
        Config conf = new Config();
        conf.colors = true;
        Stdoutput outp = new Stdoutput(conf);
        outp.stdoutIsTerminal = false;
        String out = captureStdout(() -> outp.printResult(result()));
        assertTrue(out.contains(Stdoutput.ANSI_CLEAR), "result line printed with -c is missing its ANSI reset code: " + out);
    }

    /** A pipe is not a terminal; the check reads the mode of the file descriptor itself. */
    @Test
    void testIsTerminalOfAPipe() throws Exception {
        Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"),
                TerminalProbe.class.getName()).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        assertTrue(out.equals("stdout=false\n"), "a piped stdout is not a terminal: " + out);
    }

    /** Prints whether this process's stdout is a terminal. */
    public static final class TerminalProbe {
        public static void main(String[] args) {
            System.out.print("stdout=" + Stdoutput.isTerminal(1) + "\n");
        }
    }
}
