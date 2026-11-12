package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Response;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class WordsTest {
    @Test
    void testNewWordFilter() {
        FilterProvider f = MatcherManager.newFilterByName("word", "200,301,400-410,500");
        assertTrue(f.repr().contains("200,301,400-410,500"), "word filter repr " + f.repr() + " should contain 200,301,400-410,500");
    }

    @Test
    void testNewWordFilterError() {
        assertThrows(IllegalArgumentException.class, () -> MatcherManager.newFilterByName("word", "invalid"));
    }

    @Test
    void testWordFiltering() {
        FilterProvider f = MatcherManager.newFilterByName("word", "200,301,402-450,500");
        Object[][] cases = {{200, true}, {301, true}, {500, true}, {4, false}, {444, true}, {302, false}, {401, false}, {402, true}, {450, true},
            {451, false}};
        for (int i = 0; i < cases.length; i++) {
            Response resp = new Response();
            resp.data = String.join(" ", Collections.nCopies((Integer) cases[i][0], "A")).getBytes(StandardCharsets.UTF_8);
            assertEquals(cases[i][1], f.filter(resp), "filter test " + i + " for " + cases[i][0] + " words");
        }
    }
}
