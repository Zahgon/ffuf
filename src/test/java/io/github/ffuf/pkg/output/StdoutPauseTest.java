package io.github.ffuf.pkg.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StdoutPauseTest {
    private static Response resp(int i) {
        Response r = new Response();
        r.statusCode = 200;
        r.request = new Request();
        r.request.input.put("FUZZ", ("w" + i).getBytes(StandardCharsets.UTF_8));
        r.request.url = "http://example/" + i;
        return r;
    }

    private static Stdoutput newOutput() {
        Stdoutput s = new Stdoutput(StdoutTest.config(false));
        s.stdoutIsTerminal = false;
        return s;
    }

    /**
     * While the console is open, result() records the match but writes nothing, so in-flight requests cannot scroll
     * the console off screen; the recorded results stay retrievable.
     */
    @Test
    void testResultSuppressedWhilePaused() {
        Stdoutput s = newOutput();
        s.setPaused(true);
        int n = 5;
        String out = StdoutTest.captureStdout(() -> {
            for (int i = 0; i < n; i++) {
                s.result(resp(i));
            }
        });
        assertEquals("", out, "result printed to stdout while paused");
        assertEquals(n, s.getCurrentResults().size(), "paused result did not record");
        assertEquals(n, s.pendingResults(), "pendingResults");
    }

    /** Without a pause, result() streams to the terminal and nothing is pending. */
    @Test
    void testResultPrintsWhenNotPaused() {
        Stdoutput s = newOutput();
        String out = StdoutTest.captureStdout(() -> s.result(resp(1)));
        assertTrue(out.contains("http://example/1") || out.contains("Status: 200"), "result did not print while unpaused: " + out);
        assertEquals(0, s.pendingResults(), "pendingResults while unpaused");
    }

    /** A held result that a filter change prunes from the current results also leaves the pending count. */
    @Test
    void testPendingReconcilesWithFilter() {
        Stdoutput s = newOutput();
        s.setPaused(true);
        s.result(resp(1));
        s.result(resp(2));
        assertEquals(2, s.pendingResults(), "pendingResults");
        s.filterCurrentResults(r -> new String(r.input.get("FUZZ"), StandardCharsets.UTF_8).equals("w1"));
        assertEquals(1, s.pendingResults(), "pendingResults after filtering out a held result (count must track the current results)");
        assertEquals(1, s.getCurrentResults().size(), "current results");
    }

    /** Leaving a pause marks the held results seen, so a later pause with no new matches reports none. */
    @Test
    void testUnpauseMarksHeldSeen() {
        Stdoutput s = newOutput();
        s.setPaused(true);
        s.result(resp(1));
        s.result(resp(2));
        assertEquals(2, s.pendingResults(), "pendingResults");
        s.setPaused(false);
        assertEquals(0, s.pendingResults(), "pendingResults after resume (held results not marked seen)");
        s.setPaused(true);
        assertEquals(0, s.pendingResults(), "pendingResults on re-pause (old results re-counted as new)");
    }

    /** Results reported from many threads while paused are all recorded and none is printed. */
    @Test
    void testConcurrentResultWhilePaused() {
        Stdoutput s = newOutput();
        s.setPaused(true);
        int n = 200;
        String out = StdoutTest.captureStdout(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(16);
            try {
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int k = i;
                    fs.add(pool.submit(() -> s.result(resp(k))));
                }
                for (Future<?> f : fs) {
                    f.get(10, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                pool.shutdown();
            }
        });
        assertEquals("", out, "result printed to stdout while paused under concurrency");
        assertEquals(n, s.getCurrentResults().size(), "results lost while paused");
        assertEquals(n, s.pendingResults(), "pendingResults");
    }

    /** savejson writes the results so far, and an unwritable name reports the file error. */
    @Test
    void testSaveFile() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ffuf-save");
        Config conf = StdoutTest.config(false);
        Stdoutput s = new Stdoutput(conf);
        s.stdoutIsTerminal = false;
        StdoutTest.captureStdout(() -> s.result(resp(1)));
        String file = dir.resolve("saved.json").toString();
        assertEquals(null, s.saveFile(file, "json"), "saving to a writable file");
        assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of(file)).contains("\"url\":\"http://example/1\""), "saved results");
        String missing = dir.resolve("no/such/dir.json").toString();
        assertEquals("open " + missing + ": no such file or directory", s.saveFile(missing, "json"), "saving to a missing folder");
    }
}
