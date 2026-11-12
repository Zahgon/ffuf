package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Response;
import org.junit.jupiter.api.Test;

class MatchesTest {
    /** A minimal response for the non-per-host match path. */
    private static Response resp(long status, long size) {
        Response r = new Response();
        r.statusCode = status;
        r.contentLength = size;
        return r;
    }

    @Test
    void testMatches_StatusMatcher() {
        MatcherManager mm = new MatcherManager();
        mm.addMatcher("status", "200,204");
        assertTrue(mm.matches(resp(200, 10), false, "or", "or", ""), "200 should match status 200,204");
        assertFalse(mm.matches(resp(404, 10), false, "or", "or", ""), "404 should not match status 200,204");
    }

    @Test
    void testMatches_MatcherModeAnd() {
        MatcherManager mm = new MatcherManager();
        mm.addMatcher("status", "200");
        mm.addMatcher("size", "10");
        assertTrue(mm.matches(resp(200, 10), false, "and", "or", ""), "200 + size 10 should match in and-mode");
        assertFalse(mm.matches(resp(200, 99), false, "and", "or", ""), "200 + size 99 should not match in and-mode (size fails)");
    }

    @Test
    void testMatches_FilterDrops() {
        MatcherManager mm = new MatcherManager();
        mm.addMatcher("status", "all");
        mm.addFilter("size", "10", false);
        assertFalse(mm.matches(resp(200, 10), false, "or", "or", ""), "a size-10 response should be filtered out");
        assertTrue(mm.matches(resp(200, 20), false, "or", "or", ""), "a size-20 response should survive the size-10 filter");
    }
}
