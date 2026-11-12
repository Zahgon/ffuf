package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The word and line counts are separator counts plus one. These pin them to len(strings.Split(body, sep))
 * across the edge cases where an off-by-one would hide.
 */
class CountEquivalenceTest {
    private static final String[] BODIES = {"", "a", "a b c", "     ", " a ", "a\nb\nc", "\n\n", "abc", "a\n", "one two\nthree four\n"};

    /** len(strings.Split(s, sep)) for a non-empty separator. */
    private static int splitLen(String s, String sep) {
        return s.split(java.util.regex.Pattern.quote(sep), -1).length;
    }

    private static void check(String name, String sep) {
        for (String body : BODIES) {
            int expected = splitLen(body, sep);
            Response resp = new Response();
            resp.data = body.getBytes(StandardCharsets.UTF_8);
            assertTrue(MatcherManager.newFilterByName(name, Integer.toString(expected)).filter(resp),
                    name + " count for \"" + body + "\": expected filter to match " + expected);
            assertFalse(MatcherManager.newFilterByName(name, Integer.toString(expected + 1)).filter(resp),
                    name + " count for \"" + body + "\": filter matched " + (expected + 1) + ", count is off by one");
        }
    }

    @Test
    void testWordFilterCountMatchesSplit() {
        check("word", " ");
    }

    @Test
    void testLineFilterCountMatchesSplit() {
        check("line", "\n");
    }
}
