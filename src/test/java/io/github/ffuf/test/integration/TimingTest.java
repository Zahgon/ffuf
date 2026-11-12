package io.github.ffuf.test.integration;

import static io.github.ffuf.test.integration.HarnessTest.assertSet;
import static io.github.ffuf.test.integration.HarnessTest.runScan;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.testtarget.Target;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wall-clock assertions (response-time matching, rate limiting). They run with the suite and use wide margins
 * because shared runners are noisy.
 */
class TimingTest {
    @TempDir
    Path tmp;

    /** /sleep/1000 answers after about a second, /sleep/1 at once, so a ">500" time matcher keeps only the slow one. */
    @Test
    void testTimeMatcher() throws Exception {
        try (Target target = Target.start()) {
            List<String> got = runScan(tmp, target.url() + "/sleep/FUZZ", List.of("1", "1000"), null, mm -> mm.addMatcher("time", ">500"));
            assertSet(List.of("1000"), got);
        }
    }

    /** 30 requests at -rate 20 take about 1.5 seconds; at least 800ms allows for noise. */
    @Test
    void testRateLimit() throws Exception {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            words.add("w" + i);
        }
        try (Target target = Target.start()) {
            long start = System.nanoTime();
            runScan(tmp, target.url() + "/status/FUZZ", words, o -> o.general.rate = 20, mm -> mm.addMatcher("status", "all"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMs >= 800, "30 requests at -rate 20 took " + elapsedMs + "ms, want >= 800ms");
        }
    }
}
