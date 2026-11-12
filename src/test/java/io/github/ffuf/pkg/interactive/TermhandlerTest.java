package io.github.ffuf.pkg.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.output.Stdoutput;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TermhandlerTest {
    /** The probe that re-runs the filters on a collected result takes each count from its own field. */
    @Test
    void testResultProbeUsesLineCount() {
        Stdoutput.Result res = new Stdoutput.Result();
        res.statusCode = 200;
        res.contentLength = 4096;
        res.contentWords = 100;
        res.contentLines = 7;
        Response probe = Termhandler.resultProbe(res);
        assertEquals(7, probe.contentLines, "resultProbe contentLines (fed contentLength instead of contentLines?)");
        assertEquals(4096, probe.contentLength, "resultProbe contentLength");
        assertEquals(100, probe.contentWords, "resultProbe contentWords");
        assertEquals(200, probe.statusCode, "resultProbe statusCode");
    }

    /** Lines end at "\n" with one trailing "\r" dropped; a last line without "\n" still counts. */
    @Test
    void testReadLine() throws IOException {
        InputStream in = new ByteArrayInputStream("fc 404\r\n\nshow".getBytes(StandardCharsets.UTF_8));
        assertEquals("fc 404", new String(Termhandler.readLine(in), StandardCharsets.UTF_8));
        assertEquals("", new String(Termhandler.readLine(in), StandardCharsets.UTF_8));
        assertEquals("show", new String(Termhandler.readLine(in), StandardCharsets.UTF_8));
        assertNull(Termhandler.readLine(in), "end of input");
    }

    /** The rate and queue commands report strconv.Atoi's own error text. */
    @Test
    void testAtoiError() {
        assertNull(Termhandler.atoiError("50"));
        assertNull(Termhandler.atoiError("-3"));
        assertEquals("strconv.Atoi: parsing \"abc\": invalid syntax", Termhandler.atoiError("abc"));
        assertEquals("strconv.Atoi: parsing \"99999999999999999999\": value out of range", Termhandler.atoiError("99999999999999999999"));
    }
}
