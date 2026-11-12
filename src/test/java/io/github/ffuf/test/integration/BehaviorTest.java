package io.github.ffuf.test.integration;

import static io.github.ffuf.test.integration.HarnessTest.assertSet;
import static io.github.ffuf.test.integration.HarnessTest.runScan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.testtarget.Target;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BehaviorTest {
    @TempDir
    Path tmp;
    private Target target;

    @BeforeEach
    void start() throws Exception {
        target = Target.start();
    }

    @AfterEach
    void stop() throws Exception {
        target.close();
    }

    /** /map keeps the status out of the payload and body, so a status matcher is told apart from a body matcher. */
    @Test
    void testStatusMatcherDecoupled() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/map/FUZZ", List.of("ok", "bad"), null, mm -> mm.addMatcher("status", "200"));
        assertSet(List.of("ok"), got);
    }

    @Test
    void testStatusMatcher() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/status/FUZZ", List.of("200", "403", "404", "500"), null, mm -> mm.addMatcher("status", "200,403"));
        assertSet(List.of("200", "403"), got);
    }

    @Test
    void testSizeFilter() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/size/FUZZ", List.of("10", "20", "30"), null, mm -> {
            mm.addMatcher("status", "all");
            mm.addFilter("size", "20", false);
        });
        assertSet(List.of("10", "30"), got);
    }

    @Test
    void testWordsFilter() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/words/FUZZ", List.of("1", "5", "10"), null, mm -> {
            mm.addMatcher("status", "all");
            mm.addFilter("word", "5", false);
        });
        assertSet(List.of("1", "10"), got);
    }

    @Test
    void testLinesFilter() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/lines/FUZZ", List.of("1", "3", "5"), null, mm -> {
            mm.addMatcher("status", "all");
            mm.addFilter("line", "3", false);
        });
        assertSet(List.of("1", "5"), got);
    }

    @Test
    void testRegexpMatcher() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/reflect/FUZZ", List.of("alpha", "beta"), null, mm -> mm.addMatcher("regexp", "alpha"));
        assertSet(List.of("alpha"), got);
    }

    /** Custom calibration strings keep calibration hermetic; one thread installs the filter before any word is matched. */
    @Test
    void testAutocalibration() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/ac/FUZZ", List.of("junk1", "junk2", "real"), o -> {
            o.general.autoCalibration = true;
            o.general.autoCalibrationStrings = List.of("calibrate-one", "calibrate-two");
            o.general.threads = 1;
        }, mm -> mm.addMatcher("status", "all"));
        assertSet(List.of("real"), got);
    }

    @Test
    void testRecursion() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/FUZZ", List.of("admin", "secret", "missing"), o -> {
            o.http.recursion = true;
            o.http.recursionStrategy = "greedy";
            o.http.recursionDepth = 1;
        }, mm -> mm.addMatcher("status", "200"));
        assertSet(List.of("admin", "secret"), got);
    }

    /**
     * At the default concurrency junk already in flight may leak before the filter is installed, but "real" always
     * matches and no calibration string is ever reported as a payload.
     */
    @Test
    void testAutocalibrationConcurrent() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/ac/FUZZ", List.of("junk1", "junk2", "real"), o -> {
            o.general.autoCalibration = true;
            o.general.autoCalibrationStrings = List.of("calibrate-one", "calibrate-two");
        }, mm -> mm.addMatcher("status", "all"));
        assertTrue(got.contains("real"), "'real' should always match; got " + got);
        for (String p : got) {
            assertTrue(List.of("junk1", "junk2", "real").contains(p), "non-wordlist payload " + p + " in results (calibration string leaked): " + got);
        }
    }

    /** /rdir 301-redirects to /rdir/, a directory the default strategy descends into to find /rdir/found. */
    @Test
    void testRecursionDefaultStrategy() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/FUZZ", List.of("rdir", "found"), o -> {
            o.http.recursion = true;
            o.http.recursionStrategy = "default";
            o.http.recursionDepth = 1;
        }, mm -> mm.addMatcher("status", "200,301"));
        assertTrue(got.contains("found"), "default recursion did not descend into /rdir/ to match 'found'; got " + got);
    }

    @Test
    void testRequestRecorder() throws Exception {
        runScan(tmp, target.url() + "/status/FUZZ", List.of("200"), null, mm -> mm.addMatcher("status", "200"));
        List<Target.Recorded> reqs = target.requests();
        assertFalse(reqs.isEmpty(), "recorder captured no requests");
        assertEquals("GET", reqs.get(0).method(), "recorded method");
    }
}
