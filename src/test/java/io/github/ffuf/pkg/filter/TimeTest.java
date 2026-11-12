package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ffuf.pkg.ffuf.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TimeTest {
    private static Response withMillis(long ms) {
        Response resp = new Response();
        resp.data = "dahhhhhtaaaaa".getBytes(StandardCharsets.UTF_8);
        resp.durationNanos = ms * 1_000_000L;
        return resp;
    }

    @Test
    void testNewTimeFilter() {
        TimeFilter f = (TimeFilter) MatcherManager.newFilterByName("time", ">100");
        assertEquals(">100", f.repr(), "time filter repr");
        assertEquals("Response time: >100", f.reprVerbose(), "time filter verbose repr");
        assertEquals(true, f.filter(withMillis(101)), "greater-than 100 matches 101ms");
        assertEquals(false, f.filter(withMillis(100)), "greater-than 100 does not match 100ms (ms == 100)");
        assertEquals(false, f.filter(withMillis(99)), "greater-than 100 does not match 99ms");
    }

    @Test
    void testNewTimeFilterError() {
        assertThrows(IllegalArgumentException.class, () -> MatcherManager.newFilterByName("time", "100>"));
    }

    @Test
    void testTimeFiltering() {
        FilterProvider f = MatcherManager.newFilterByName("time", ">100");
        Object[][] cases = {{1342L, true}, {2000L, true}, {35000L, true}, {1458700L, true}, {99L, false}, {2L, false}};
        for (int i = 0; i < cases.length; i++) {
            assertEquals(cases[i][1], f.filter(withMillis((Long) cases[i][0])), "filter test " + i + " for " + cases[i][0] + "ms");
        }
    }
}
