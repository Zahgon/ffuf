package io.github.ffuf.pkg.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Expected values come from Go 1.27's net/http (Request.write through httputil.DumpRequestOut). */
class HttpGutsTest {
    @Test
    void testPunycodeHostPort() throws GoUrl.UrlException {
        assertEquals("xn--tda.local", HttpGuts.punycodeHostPort(GoText.bin("ü.local")));
        assertEquals("xn--tda.local:8080", HttpGuts.punycodeHostPort(GoText.bin("ü.local:8080")));
        assertEquals("xn--bcher-kva.example", HttpGuts.punycodeHostPort(GoText.bin("bücher.example")));
        assertEquals("xn--mnchen-3ya.de", HttpGuts.punycodeHostPort(GoText.bin("münchen.de")));
    }

    @Test
    void testValidators() {
        assertTrue(HttpGuts.validHeaderFieldName("X-Test_1"));
        assertFalse(HttpGuts.validHeaderFieldName(""));
        assertFalse(HttpGuts.validHeaderFieldName("X Bad"));
        assertFalse(HttpGuts.validHeaderFieldName(GoText.bin("X-Ü")));
        assertTrue(HttpGuts.validHeaderFieldValue("a\tb c" + GoText.bin("ü")));
        assertFalse(HttpGuts.validHeaderFieldValue("a\u0001b"));
        assertFalse(HttpGuts.validHeaderFieldValue("a\u007fb"));
        assertTrue(HttpGuts.validHostHeader("[::1]:80"));
        assertFalse(HttpGuts.validHostHeader("a b"));
    }

    @Test
    void testRequestHead() throws GoUrl.UrlException {
        Map<String, String> headers = new TreeMap<>();
        headers.put("User-Agent", "ua");
        headers.put("X-B", " two ");
        headers.put("Accept-Encoding", "");
        headers.put("Content-Length", "5");
        GoUrl u = GoUrl.parse("http://h:1/p q?x");
        assertEquals("POST /p%20q?x HTTP/1.1\r\nHost: \r\nUser-Agent: ua\r\nContent-Length: 0\r\nAccept-Encoding: \r\nX-B: two\r\n"
                + "Accept-Encoding: gzip\r\n\r\n", SimpleRunner.head("POST", u, "a b", headers, new byte[0], null));
        assertEquals("GET http://h:1/p%20q?x HTTP/1.1\r\nHost: h:1\r\nUser-Agent: ua\r\nAccept-Encoding: \r\nX-B: two\r\n"
                + "Accept-Encoding: gzip\r\nProxy-Authorization: Basic dTpw\r\n\r\n",
                SimpleRunner.head("GET", u, "", headers, new byte[0], "Basic dTpw"));
    }
}
