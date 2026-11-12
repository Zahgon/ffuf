package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Response;
import org.junit.jupiter.api.Test;

class SizeTest {
    @Test
    void testNewSizeFilter() {
        FilterProvider f = MatcherManager.newFilterByName("size", "1,2,3,444,5-90");
        assertTrue(f.repr().contains("1,2,3,444,5-90"), "size filter repr " + f.repr() + " should contain 1,2,3,444,5-90");
    }

    @Test
    void testNewSizeFilterError() {
        assertThrows(IllegalArgumentException.class, () -> MatcherManager.newFilterByName("size", "invalid"));
    }

    @Test
    void testFiltering() {
        FilterProvider f = MatcherManager.newFilterByName("size", "1,2,3,5-90,444");
        Object[][] cases = {{1, true}, {2, true}, {3, true}, {4, false}, {5, true}, {70, true}, {90, true}, {91, false}, {444, true}};
        for (int i = 0; i < cases.length; i++) {
            Response resp = new Response();
            resp.contentLength = (Integer) cases[i][0];
            assertEquals(cases[i][1], f.filter(resp), "filter test " + i + " for size " + cases[i][0]);
        }
    }
}
