package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {
    /**
     * Readers iterate what the getters return while writers churn the maps, as workers and autocalibration do. A
     * getter that handed out the live map would fail here with a ConcurrentModificationException.
     */
    @Test
    void testMatcherManager_ConcurrentReadWrite() throws Exception {
        MatcherManager mm = new MatcherManager();
        mm.addMatcher("status", "all");
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            runReadersAndWriters(mm, stop, pool);
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers stopped, want all terminated");
    }

    /** Starts four writers and eight readers on the pool, lets them run, then stops them and waits for each. */
    private static void runReadersAndWriters(MatcherManager mm, AtomicBoolean stop, ExecutorService pool) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            futures.add(pool.submit(() -> {
                while (!stop.get()) {
                    mm.addFilter("size", "1337", false);
                    mm.removeFilter("size");
                    mm.addPerDomainFilter("host.example", "word", "9");
                    mm.setCalibratedForHost("host.example", true);
                    mm.setCalibrated(true);
                }
            }));
        }
        for (int r = 0; r < 8; r++) {
            futures.add(pool.submit(() -> {
                while (!stop.get()) {
                    iterate(mm.getFilters());
                    iterate(mm.getMatchers());
                    iterate(mm.filtersForDomain("host.example"));
                    mm.calibrated();
                    mm.calibratedForDomain("host.example");
                }
            }));
        }
        Thread.sleep(200);
        stop.set(true);
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
    }

    private static void iterate(Map<String, FilterProvider> m) {
        try {
            for (FilterProvider f : m.values()) {
                f.repr();
            }
        } catch (ConcurrentModificationException e) {
            throw new AssertionError("a getter returned a map that a writer modified during iteration", e);
        }
    }

    @Test
    void testMatcherManager_PerDomainDoesNotAliasGlobal() {
        MatcherManager mm = new MatcherManager();
        mm.addFilter("size", "100", false);
        mm.addPerDomainFilter("host.example", "word", "5");
        assertFalse(mm.getFilters().containsKey("word"), "per-domain 'word' filter leaked into the global filter set");
        Map<String, FilterProvider> perDomain = mm.filtersForDomain("host.example");
        assertTrue(perDomain.containsKey("word"), "per-domain filter set should contain its own 'word' filter");
        assertTrue(perDomain.containsKey("size"), "per-domain filter set should inherit the global 'size' filter");
        assertEquals("5", perDomain.get("word").repr(), "per-domain word filter value");
    }
}
