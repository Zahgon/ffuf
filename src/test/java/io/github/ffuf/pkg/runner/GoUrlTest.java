package io.github.ffuf.pkg.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Expected values come from Go 1.27's net/url. */
class GoUrlTest {
    private static GoUrl parse(String s) throws GoUrl.UrlException {
        return GoUrl.parse(GoText.bin(s));
    }

    private static String error(String s) {
        return assertThrows(GoUrl.UrlException.class, () -> parse(s)).getMessage();
    }

    @Test
    void testParseAndString() throws GoUrl.UrlException {
        GoUrl u = parse("http://127.0.0.1:8080/a b/ü?q=ü#frag");
        assertEquals(GoText.bin("http://127.0.0.1:8080/a%20b/%C3%BC?q=ü#frag"), u.toString());
        assertEquals(GoText.bin("/a%20b/%C3%BC?q=ü"), u.requestUri());
        assertEquals("127.0.0.1", u.hostname());
        assertEquals("8080", u.port());

        GoUrl zone = parse("http://[fe80::1%25en0]:8080/p");
        assertEquals("http://[fe80::1%25en0]:8080/p", zone.toString());
        assertEquals("fe80::1%en0", zone.hostname());

        assertEquals("/x?", parse("http://u:p@h/x?").requestUri());
        assertEquals("http://u:p@h/x?", parse("http://u:p@h/x?").toString());
        assertEquals("http://u:***@h/x?", parse("http://u:p@h/x?").stripPassword());
        assertEquals("/a%2Fb", parse("http://h/a%2Fb").requestUri());
        assertEquals("x@y", parse("mailto:x@y").requestUri());
    }

    @Test
    void testParseErrors() {
        assertEquals("parse \"http://h/%zz\": invalid URL escape \"%zz\"", error("http://h/%zz"));
        assertEquals("parse \"http://h:port/\": invalid port \":port\" after host", error("http://h:port/"));
        assertEquals("parse \"http://h:1:2/\": invalid port \":1:2\" after host", error("http://h:1:2/"));
        assertEquals("parse \"http://[::1\": missing ']' in host", error("http://[::1"));
        assertEquals("parse \"http://h/a\\x01\": net/url: invalid control character in URL", error("http://h/a\u0001"));
        assertEquals("parse \"://x\": missing protocol scheme", error("://x"));
        assertEquals("parse \"http://[1.2.3.4]/\": invalid IP-literal", error("http://[1.2.3.4]/"));
        assertEquals("parse \"http://h/#a%zz\": invalid URL escape \"%zz\"", error("http://h/#a%zz"));
    }

    @Test
    void testResolveReference() throws GoUrl.UrlException {
        GoUrl base = parse("http://h/a/b?x=1#f");
        String[][] cases = {
            {"../up", "http://h/up"},
            {"./same", "http://h/a/same"},
            {"?q=1", "http://h/a/b?q=1"},
            {"#frag", "http://h/a/b?x=1#frag"},
            {"/a/../b", "http://h/b"},
            {"//other/x", "http://other/x"},
            {"c", "http://h/a/c"},
            {"", "http://h/a/b?x=1#f"},
            {"http://x/y/../z", "http://x/z"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], base.parseRef(c[0]).toString(), c[0]);
        }
    }
}
