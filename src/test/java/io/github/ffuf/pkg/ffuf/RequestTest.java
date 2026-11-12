package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestTest {
    private static Request request(String method, String url, Map<String, String> headers, String data) {
        Request r = new Request();
        r.method = method;
        r.url = url;
        r.headers = new LinkedHashMap<>(headers);
        r.data = data.getBytes(StandardCharsets.UTF_8);
        return r;
    }

    /** Field-by-field equality over every Request field. */
    private static boolean same(Request a, Request b) {
        if (!a.method.equals(b.method) || !a.host.equals(b.host) || !a.url.equals(b.url) || !a.headers.equals(b.headers)
                || !java.util.Arrays.equals(a.data, b.data) || a.position != b.position || !a.raw.equals(b.raw)
                || !a.input.keySet().equals(b.input.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> e : a.input.entrySet()) {
            if (!java.util.Arrays.equals(e.getValue(), b.input.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Request r) {
        return "{Method:" + r.method + " Url:" + r.url + " Headers:" + r.headers + " Data:" + new String(r.data, StandardCharsets.UTF_8) + "}";
    }

    @Test
    void testBaseRequest() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("foo", "bar");
        headers.put("baz", "wibble");
        headers.put("Content-Type", "application/json");
        String data = "{\"quote\":\"I'll still be here tomorrow to high five you yesterday, my friend. Peace.\"}";
        Config config = new Config();
        config.method = "POST";
        config.url = "http://example.com/aaaa";
        config.headers = headers;
        config.data = data;
        Request expected = request("POST", "http://example.com/aaaa", headers, data);
        Request base = Request.base(config);
        assertTrue(same(expected, base), "BaseRequest = " + describe(base) + ", want " + describe(expected));
    }

    @Test
    void testCopyRequest() {
        Request base = new Request();
        base.method = "POST";
        base.host = "testhost.local";
        base.url = "http://example.com/aaaa";
        base.headers.put("foo", "bar");
        base.headers.put("omg", "bbq");
        base.data = "line=Is+that+where+creativity+comes+from?+From+sad+biz?".getBytes(StandardCharsets.UTF_8);
        base.input.put("matthew", "If you are the head that floats atop the §ziggurat§, then the stairs that lead to you must be infinite."
                .getBytes(StandardCharsets.UTF_8));
        base.position = 2;
        base.raw = "We're not oil and water, we're oil and vinegar! It's good. It's yummy.";
        Request copy = base.copy();
        assertTrue(same(base, copy), "CopyRequest = " + describe(copy) + ", want an equal request " + describe(base));
        assertArrayEquals(base.input.get("matthew"), copy.input.get("matthew"));
    }

    @Test
    void testSniperRequests() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("foo", "§bar§");
        headers.put("§omg§", "bbq");
        Request req = request("§POST§", "http://example.com/aaaa?param=§lemony§", headers, "line=§yo yo, it's grease§");
        List<Request> requests = Sniper.requests(req, "§");
        assertEquals(5, requests.size(), "number of sniper requests");

        Map<String, String> plain = Map.of("foo", "bar", "omg", "bbq");
        Object[][] expected = {
            {"Method", request("FUZZ", "http://example.com/aaaa?param=lemony", plain, "line=yo yo, it's grease")},
            {"Url", request("POST", "http://example.com/aaaa?param=FUZZ", plain, "line=yo yo, it's grease")},
            {"Data", request("POST", "http://example.com/aaaa?param=lemony", plain, "line=FUZZ")},
            {"Header value", request("POST", "http://example.com/aaaa?param=lemony", Map.of("foo", "FUZZ", "omg", "bbq"), "line=yo yo, it's grease")},
            {"Header key", request("POST", "http://example.com/aaaa?param=lemony", Map.of("foo", "bar", "FUZZ", "bbq"), "line=yo yo, it's grease")},
        };
        for (Object[] e : expected) {
            Request want = (Request) e[1];
            assertTrue(requests.stream().anyMatch(r -> same(r, want)), "SniperRequests (" + e[0] + ") should contain " + describe(want));
        }
    }

    @Test
    void testTemplateLocations() {
        assertEquals(List.of(12, 29), Sniper.locations("§", "this is my 1§template locator§ test"));
        assertEquals(List.of(0, 17), Sniper.locations("§", "§template locator§"));
        assertEquals(1, Sniper.locations("§", "te§st2").size(), "locations in te§st2");
    }

    @Test
    void testInjectKeyword() {
        String input = "§Greetings, creator§";
        List<Integer> offsets = Sniper.locations("§", input);
        assertEquals("FUZZ", Sniper.inject(input, offsets.get(0), offsets.get(1)));
        assertEquals(input, Sniper.inject(input, -32, 44), "offset validation");
        assertEquals(input, Sniper.inject(input, 12, 2), "offset validation");
        assertEquals(input, Sniper.inject(input, 0, 25), "offset validation");

        input = "id=§a§&sort=desc";
        offsets = Sniper.locations("§", input);
        assertEquals("id=FUZZ&sort=desc", Sniper.inject(input, offsets.get(0), offsets.get(1)));

        input = "feature=aaa&thingie=bbb&array[§0§]=baz";
        offsets = Sniper.locations("§", input);
        assertEquals("feature=aaa&thingie=bbb&array[FUZZ]=baz", Sniper.inject(input, offsets.get(0), offsets.get(1)));
    }

    @Test
    void testScrubTemplates() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("foo", "§bar§");
        headers.put("§omg§", "bbq");
        Request req = request("§POST§", "http://example.com/aaaa?param=§lemony§", headers, "line=§yo yo, it's grease§");
        Request expected = request("POST", "http://example.com/aaaa?param=lemony", Map.of("foo", "bar", "omg", "bbq"), "line=yo yo, it's grease");
        Sniper.scrub(req, "§");
        assertTrue(same(expected, req), "scrubTemplates = " + describe(req) + ", want " + describe(expected));
    }
}
