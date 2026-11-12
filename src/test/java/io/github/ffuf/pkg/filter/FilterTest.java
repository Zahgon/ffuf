package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FilterTest {
    @Test
    void testNewFilterByName() {
        assertInstanceOf(RangeFilter.class, MatcherManager.newFilterByName("status", "200"));
        assertInstanceOf(RangeFilter.class, MatcherManager.newFilterByName("size", "200"));
        assertInstanceOf(RangeFilter.class, MatcherManager.newFilterByName("word", "200"));
        assertInstanceOf(RangeFilter.class, MatcherManager.newFilterByName("line", "200"));
        assertInstanceOf(RegexpFilter.class, MatcherManager.newFilterByName("regexp", "200"));
        IllegalArgumentException timeErr = assertThrows(IllegalArgumentException.class, () -> MatcherManager.newFilterByName("time", "200"));
        assertTrue(timeErr.getMessage().startsWith("Time filter or matcher (-ft / -mt)"), "time name builds a time filter: " + timeErr.getMessage());
    }

    @Test
    void testNewFilterByNameError() {
        assertThrows(IllegalArgumentException.class, () -> MatcherManager.newFilterByName("status", "invalid"));
    }

    @Test
    void testNewFilterByNameNotFound() {
        assertThrows(IllegalArgumentException.class, () -> MatcherManager.newFilterByName("nonexistent", "invalid"));
    }
}
