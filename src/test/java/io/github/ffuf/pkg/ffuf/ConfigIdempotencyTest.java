package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigIdempotencyTest {
    /**
     * Config.fromOptions must not mutate its input and must give the same result when called twice: folding -b into
     * the caller's header list in place would double the Cookie header on a second call.
     */
    @Test
    void testConfigFromOptions_Idempotent() throws Config.ConfigException {
        ConfigOptions opts = new ConfigOptions();
        opts.http.url = "https://example.org/FUZZ";
        opts.input.wordlists = List.of("/tmp/wl.txt");
        opts.http.cookies = List.of("SESSION=abc");

        Config c1 = Config.fromOptions(opts, List.of("ffuf"));
        Config c2 = Config.fromOptions(opts, List.of("ffuf"));

        assertTrue(opts.http.headers == null || opts.http.headers.isEmpty(), "fromOptions mutated its input: opts.http.headers = " + opts.http.headers);
        int call = 1;
        for (Config c : List.of(c1, c2)) {
            assertEquals("SESSION=abc", c.headers.get("Cookie"), "call " + call + ": Cookie header");
            long n = c.options.http.headers.stream().filter(h -> h.startsWith("Cookie: ")).count();
            assertEquals(1, n, "call " + call + ": retained snapshot Cookie headers in " + c.options.http.headers);
            call++;
        }
    }
}
