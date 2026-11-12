package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RegexpTest {
    @Test
    void testNewRegexpFilter() {
        FilterProvider f = MatcherManager.newFilterByName("regexp", "s([a-z]+)arch");
        assertTrue(f.repr().contains("s([a-z]+)arch"), "regexp filter repr " + f.repr() + " should contain s([a-z]+)arch");
    }

    @Test
    void testNewRegexpFilterError() {
        assertThrows(IllegalArgumentException.class, () -> MatcherManager.newFilterByName("regexp", "r(("));
    }

    @Test
    void testRegexpFiltering() {
        FilterProvider f = MatcherManager.newFilterByName("regexp", "s([a-z]+)arch");
        Object[][] cases = {{"search", true}, {"text and search", true}, {"sbarch in beginning", true}, {"midd scarch le", true}, {"s1arch", false},
            {"invalid", false}};
        for (int i = 0; i < cases.length; i++) {
            Response resp = new Response();
            resp.data = ((String) cases[i][0]).getBytes(StandardCharsets.UTF_8);
            resp.request = new Request();
            assertEquals(cases[i][1], f.filter(resp), "filter test " + i + " for " + cases[i][0]);
        }
    }
}
