package io.github.ffuf.pkg.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Expected values follow the rules of Go's http.ProxyFromEnvironment. */
class EnvProxyTest {
    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String proxy(Map<String, String> env, String target) throws Exception {
        GoUrl p = new EnvProxy(env).proxyFor(GoUrl.parse(target));
        return p == null ? null : p.scheme + "://" + p.host;
    }

    @Test
    void testSchemeSelection() throws Exception {
        Map<String, String> e = env("HTTP_PROXY", "http://h:1", "HTTPS_PROXY", "http://s:2");
        assertEquals("http://h:1", proxy(e, "http://example.com/"));
        assertEquals("http://s:2", proxy(e, "https://example.com/"));
        assertNull(proxy(env("HTTPS_PROXY", "http://s:2"), "http://example.com/"));
        assertNull(proxy(env(), "http://example.com/"));
    }

    @Test
    void testUpperCaseWinsAndLowerCaseFallback() throws Exception {
        assertEquals("http://up:1", proxy(env("HTTP_PROXY", "http://up:1", "http_proxy", "http://low:2"), "http://example.com/"));
        assertEquals("http://low:2", proxy(env("HTTP_PROXY", "", "http_proxy", "http://low:2"), "http://example.com/"));
        assertEquals("http://low:3", proxy(env("https_proxy", "http://low:3"), "https://example.com/"));
    }

    @Test
    void testValueWithoutSchemeGetsHttp() throws Exception {
        assertEquals("http://127.0.0.1:3128", proxy(env("HTTP_PROXY", "127.0.0.1:3128"), "http://example.com/"));
        assertEquals("http://localhost:3128", proxy(env("HTTP_PROXY", "localhost:3128"), "http://example.com/"));
        assertEquals("socks5://s:1080", proxy(env("HTTP_PROXY", "socks5://s:1080"), "http://example.com/"));
    }

    @Test
    void testLoopbackNeverProxied() throws Exception {
        Map<String, String> e = env("HTTP_PROXY", "http://h:1");
        assertNull(proxy(e, "http://localhost:8080/"));
        assertNull(proxy(e, "http://127.0.0.1/"));
        assertNull(proxy(e, "http://127.9.9.9/"));
        assertNull(proxy(e, "http://[::1]:80/"));
        assertEquals("http://h:1", proxy(e, "http://localhost.example/"));
    }

    @Test
    void testNoProxyRules() throws Exception {
        Map<String, String> e = env("HTTP_PROXY", "http://h:1",
                "NO_PROXY", " 10.0.0.0/8, *.foo.com,bar.com:8080 ,.dot.org,192.168.1.1,192.168.1.2:81,[2001:db8::1]:80");
        assertNull(proxy(e, "http://10.1.2.3/"));
        assertEquals("http://h:1", proxy(e, "http://11.1.2.3/"));
        assertNull(proxy(e, "http://a.foo.com/"));
        assertEquals("http://h:1", proxy(e, "http://foo.com/"));
        assertNull(proxy(e, "http://BAR.com:8080/"));
        assertNull(proxy(e, "http://x.bar.com:8080/"));
        assertEquals("http://h:1", proxy(e, "http://bar.com/"));
        assertNull(proxy(e, "http://www.dot.org/"));
        assertEquals("http://h:1", proxy(e, "http://dot.org/"));
        assertNull(proxy(e, "http://192.168.1.1:9999/"));
        assertNull(proxy(e, "http://192.168.1.2:81/"));
        assertEquals("http://h:1", proxy(e, "http://192.168.1.2/"));
        assertNull(proxy(e, "http://[2001:db8::1]/"));
        assertEquals("http://h:1", proxy(e, "http://notfoo.com.example/"));
    }

    @Test
    void testNoProxyLowerCaseAndWildcard() throws Exception {
        assertNull(proxy(env("HTTP_PROXY", "http://h:1", "no_proxy", "example.com"), "http://example.com/"));
        assertNull(proxy(env("HTTP_PROXY", "http://h:1", "NO_PROXY", "a.com,*"), "http://anything.example/"));
    }

    @Test
    void testCgiRefusesHttpProxy() throws Exception {
        Map<String, String> e = env("HTTP_PROXY", "http://h:1", "HTTPS_PROXY", "http://s:2", "REQUEST_METHOD", "GET");
        EnvProxy.CgiException ex = assertThrows(EnvProxy.CgiException.class,
                () -> new EnvProxy(e).proxyFor(GoUrl.parse("http://example.com/")));
        assertEquals("refusing to use HTTP_PROXY value in CGI environment; see golang.org/s/cgihttpproxy", ex.getMessage());
        assertEquals("http://s:2", proxy(e, "https://example.com/"));
        assertNull(proxy(env("REQUEST_METHOD", "GET"), "http://example.com/"));
    }
}
