package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Response;
import org.junit.jupiter.api.Test;

class StatusTest {
    @Test
    void testNewStatusFilter() {
        FilterProvider f = MatcherManager.newFilterByName("status", "200,301,400-410,500");
        assertTrue(f.repr().contains("200,301,400-410,500"), "status filter repr " + f.repr() + " should contain 200,301,400-410,500");
    }

    @Test
    void testNewStatusFilterError() {
        assertThrows(IllegalArgumentException.class, () -> MatcherManager.newFilterByName("status", "invalid"));
    }

    @Test
    void testStatusFiltering() {
        FilterProvider f = MatcherManager.newFilterByName("status", "200,301,400-498,500");
        Object[][] cases = {{200, true}, {301, true}, {500, true}, {4, false}, {399, false}, {400, true}, {444, true}, {498, true}, {499, false},
            {302, false}};
        for (int i = 0; i < cases.length; i++) {
            Response resp = new Response();
            resp.statusCode = (Integer) cases[i][0];
            assertEquals(cases[i][1], f.filter(resp), "filter test " + i + " for status " + cases[i][0]);
        }
    }
}
