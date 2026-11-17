package io.github.ffuf.pkg.runner;

import static io.github.ffuf.pkg.runner.PreflightTest.close;
import static io.github.ffuf.pkg.runner.PreflightTest.flight;
import static io.github.ffuf.pkg.runner.PreflightTest.flights;
import static io.github.ffuf.pkg.runner.PreflightTest.hostPort;
import static io.github.ffuf.pkg.runner.PreflightTest.mainReq;
import static io.github.ffuf.pkg.runner.PreflightTest.newTestConfig;
import static io.github.ffuf.pkg.runner.PreflightTest.regexVar;
import static io.github.ffuf.pkg.runner.PreflightTest.send;
import static io.github.ffuf.pkg.runner.PreflightTest.serve;
import static io.github.ffuf.pkg.runner.PreflightTest.url;
import static io.github.ffuf.pkg.runner.PreflightTest.writeTempRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreflightHardeningTest {
    @TempDir
    Path tmp;

    /** A captured value that would change the main request's host makes the request refuse to go out. */
    @Test
    void testHostPinBlocksHostChange() throws Exception {
        HttpServer srv = serve(ex -> send(ex, 200, "host=evil.example"));
        try {
            Config conf = newTestConfig(url(srv));
            conf.preflights = List.of(flight(writeTempRequest(tmp, "GET / HTTP/1.1\nHost: " + hostPort(srv) + "\n\n"), regexVar("HOSTVAR", "host=([\\w.]+)")));
            Request req = new Request();
            req.method = "GET";
            req.url = "http://HOSTVAR/main";
            SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> flights(conf).execute(req));
            assertTrue(e.getMessage().contains("changed the request host"), "unexpected error: " + e.getMessage());
        } finally {
            close(srv);
        }
    }

    /** Substitution is longest-name first and single-pass, so overlapping names and cascades give one fixed result. */
    @Test
    void testSubstitutionDeterministic() {
        Map<String, String> vars = Map.of("TOK", "aaa", "TOKEN", "bbb", "AAA", "BBB", "BBB", "zzz");
        for (int i = 0; i < 50; i++) {
            Request req = new Request();
            req.url = "http://x/TOKEN?a=AAA";
            req.headers.put("X", "TOK");
            req.data = "v=TOK".getBytes(StandardCharsets.UTF_8);
            Flights.applyVars(req, vars);
            assertEquals("http://x/bbb?a=BBB", req.url, "run " + i + ": url");
            assertEquals("v=aaa", new String(req.data, StandardCharsets.UTF_8), "run " + i + ": data");
        }
    }

    /** Preflight requests wait for the rate limiter; the main request is metered by the dispatch loop instead. */
    @Test
    void testPreflightHonorsRateLimit() throws Exception {
        PreflightTest.PreflightServer ps = new PreflightTest.PreflightServer();
        try {
            Config conf = ps.config(tmp, "per-request");
            AtomicInteger calls = new AtomicInteger();
            conf.rateLimitFunc = calls::incrementAndGet;
            Flights f = flights(conf);
            for (int i = 0; i < 3; i++) {
                f.execute(mainReq(url(ps.srv)));
            }
            assertEquals(3, calls.get(), "rate limiter consultations (one per preflight request)");
        } finally {
            close(ps.srv);
        }
    }

    /** Postflight runs when the main request produced a response, even one whose body was not read (-ignore-body). */
    @Test
    void testPostflightRunsOnIgnoredBody() throws Exception {
        AtomicInteger postHits = new AtomicInteger();
        HttpServer srv = serve(ex -> {
            if (ex.getRequestURI().getPath().equals("/post")) {
                postHits.incrementAndGet();
                send(ex, 200, "");
            } else {
                send(ex, 200, "abc");
            }
        });
        try {
            Config conf = newTestConfig(url(srv));
            conf.ignoreBody = true;
            conf.postflights = List.of(flight(writeTempRequest(tmp, "GET /post HTTP/1.1\nHost: " + hostPort(srv) + "\n\n")));
            flights(conf).execute(mainReq(url(srv)));
            assertEquals(1, postHits.get(), "postflight runs for an ignored-body response");
        } finally {
            close(srv);
        }
    }

    @Test
    void testPreflightRejectsControlCharValue() throws Exception {
        HttpServer srv = serve(ex -> send(ex, 200, "token=aaa\rbbb"));
        try {
            String reqFile = writeTempRequest(tmp, "GET / HTTP/1.1\nHost: " + hostPort(srv) + "\n\n");
            Config abort = newTestConfig(url(srv));
            abort.preflights = List.of(flight(reqFile, regexVar("T", "token=(.+)")));
            assertThrows(SimpleRunner.RequestException.class, () -> flights(abort).runChain(abort.preflights, Map.of(), "preflight"),
                    "abort mode: a control-char value");
            Config ignore = newTestConfig(url(srv));
            ignore.preflightError = "ignore";
            ignore.preflights = List.of(flight(reqFile, regexVar("T", "token=(.+)")));
            assertFalse(flights(ignore).runChain(ignore.preflights, Map.of(), "preflight").containsKey("T"), "ignore mode: control-char value stored");
        } finally {
            close(srv);
        }
    }

    /** A request file ending on a header with no trailing newline keeps that header. */
    @Test
    void testParsePreflightKeepsLastHeader() throws Exception {
        Flights f = flights(newTestConfig("http://example.com/"));
        Request req = f.build(writeTempRequest(tmp, "GET / HTTP/1.1\r\nHost: example.com\r\nX-Last: kept"), Map.of());
        assertEquals("kept", req.headers.get("X-Last"), "last header");
    }
}
