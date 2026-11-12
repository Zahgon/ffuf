package io.github.ffuf.pkg.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.PreflightConfig;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.VarExtract;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreflightTest {
    @TempDir
    Path tmp;

    /** A local HTTP server on an ephemeral 127.0.0.1 port; close it with {@link #close}. */
    static HttpServer serve(HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
        s.createContext("/", handler);
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-server");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        return s;
    }

    static void close(HttpServer s) {
        s.stop(0);
        ((ExecutorService) s.getExecutor()).shutdownNow();
    }

    static String url(HttpServer s) {
        return "http://" + hostPort(s);
    }

    static String hostPort(HttpServer s) {
        return "127.0.0.1:" + s.getAddress().getPort();
    }

    /** Writes body with a fixed length, or no body for an empty one. */
    static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        }
        ex.close();
    }

    /** A minimal config for runner tests. */
    static Config newTestConfig(String targetUrl) {
        Config conf = new Config();
        conf.url = targetUrl;
        conf.threads = 1;
        conf.timeout = 5;
        conf.preflightMode = "per-request";
        conf.preflightError = "abort";
        return conf;
    }

    static Flights flights(Config conf) {
        return new Flights(conf, new SimpleRunner(conf), s -> { });
    }

    /** Writes a Burp-style raw HTTP request file and returns its path. */
    static String writeTempRequest(Path dir, String content) throws IOException {
        Path f = Files.createTempFile(dir, "preflight-", ".txt");
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f.toString();
    }

    static VarExtract regexVar(String name, String regex) {
        VarExtract v = new VarExtract();
        v.name = name;
        v.regex = regex;
        return v;
    }

    static PreflightConfig flight(String file, VarExtract... vars) {
        PreflightConfig pf = new PreflightConfig(file);
        pf.vars.addAll(List.of(vars));
        return pf;
    }

    /** One main request as the job sends it: preflights, the request itself, then postflights. */
    static Response execute(Flights f, Config conf, Request req) throws SimpleRunner.RequestException {
        Map<String, String> applied = f.runPreflights(req);
        Response resp = new SimpleRunner(conf).execute(req);
        f.runPostflights(applied);
        return resp;
    }

    @Test
    void testPreflightVarsSubstitutedIntoMainRequest() throws Exception {
        HttpServer srv = serve(ex -> send(ex, 200, "<input name=\"csrf\" value=\"tok123\">"));
        try {
            Config conf = newTestConfig(url(srv));
            conf.preflights = List.of(flight(writeTempRequest(tmp, "GET / HTTP/1.1\nHost: " + hostPort(srv) + "\n\n"),
                    regexVar("CSRFTOKEN", "value=\"([^\"]+)\"")));
            Map<String, String> vars = flights(conf).runChain(conf.preflights, Map.of(), "preflight");
            assertEquals("tok123", vars.get("CSRFTOKEN"), "CSRFTOKEN");

            Request req = new Request();
            req.url = "http://x/submit?token=CSRFTOKEN";
            req.headers.put("X-CSRF", "CSRFTOKEN");
            req.data = "csrf=CSRFTOKEN".getBytes(StandardCharsets.UTF_8);
            Flights.applyVars(req, vars);
            assertEquals("http://x/submit?token=tok123", req.url, "URL substitution");
            assertEquals("tok123", req.headers.get("X-CSRF"), "header substitution");
            assertEquals("csrf=tok123", new String(req.data, StandardCharsets.UTF_8), "body substitution");
        } finally {
            close(srv);
        }
    }

    /**
     * Serves a fresh token per hit at /preflight, so amortization and freshness are observable, and records the
     * X-Token header of every request at /main.
     */
    static final class PreflightServer {
        final AtomicInteger preflightHits = new AtomicInteger();
        final List<String> mainTokens = new ArrayList<>();
        final HttpServer srv;

        PreflightServer() throws IOException {
            srv = serve(ex -> {
                if (ex.getRequestURI().getPath().equals("/preflight")) {
                    send(ex, 200, "token=TOK" + preflightHits.incrementAndGet() + ";");
                } else {
                    synchronized (mainTokens) {
                        String tok = ex.getRequestHeaders().getFirst("X-Token");
                        mainTokens.add(tok == null ? "" : tok);
                    }
                    send(ex, 200, "");
                }
            });
        }

        Config config(Path dir, String mode) throws IOException {
            Config conf = newTestConfig(url(srv));
            conf.preflightMode = mode;
            conf.preflights = List.of(flight(writeTempRequest(dir, "GET /preflight HTTP/1.1\nHost: " + hostPort(srv) + "\n\n"),
                    regexVar("TOKENKW", "token=(\\w+)")));
            return conf;
        }

        List<String> tokens() {
            synchronized (mainTokens) {
                return new ArrayList<>(mainTokens);
            }
        }
    }

    /** A fresh main request carrying the TOKENKW keyword in a header. */
    static Request mainReq(String baseUrl) {
        Request r = new Request();
        r.method = "GET";
        r.url = baseUrl + "/main";
        r.headers.put("X-Token", "TOKENKW");
        return r;
    }

    @Test
    void testPerRequestRunsPreflightEveryTime() throws Exception {
        PreflightServer ps = new PreflightServer();
        try {
            Config conf = ps.config(tmp, "per-request");
            Flights f = flights(conf);
            for (int i = 0; i < 5; i++) {
                execute(f, conf, mainReq(url(ps.srv)));
            }
            assertEquals(5, ps.preflightHits.get(), "preflight hits (per-request runs every time)");
            Set<String> seen = new HashSet<>();
            for (String tok : ps.tokens()) {
                assertTrue(tok.startsWith("TOK"), "main request token " + tok + ", want a fresh TOK*");
                assertTrue(seen.add(tok), "token " + tok + " reused; per-request tokens must be fresh");
            }
        } finally {
            close(ps.srv);
        }
    }

    @Test
    void testPerThreadAmortizesPreflight() throws Exception {
        PreflightServer ps = new PreflightServer();
        try {
            Config conf = ps.config(tmp, "per-thread");
            Flights f = flights(conf);
            for (int i = 0; i < 10; i++) {
                execute(f, conf, mainReq(url(ps.srv)));
            }
            assertEquals(1, ps.preflightHits.get(), "preflight hits (per-thread amortizes to one per lane)");
            for (String tok : ps.tokens()) {
                assertEquals("TOK1", tok, "main request token");
            }
        } finally {
            close(ps.srv);
        }
    }

    /** Many threads share one runner in per-thread mode: amortized preflights, and a valid token on every request. */
    @Test
    void testConcurrentPerThreadRaceSafe() throws Exception {
        PreflightServer ps = new PreflightServer();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Config conf = ps.config(tmp, "per-thread");
            Flights f = flights(conf);
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 25; i++) {
                        execute(f, conf, mainReq(url(ps.srv)));
                    }
                    return null;
                }));
            }
            for (Future<?> fu : futures) {
                fu.get(60, TimeUnit.SECONDS);
            }
            int hits = ps.preflightHits.get();
            assertTrue(hits >= 1 && hits <= 8, "preflight hits = " + hits + ", want between 1 and 8");
            List<String> tokens = ps.tokens();
            assertEquals(100, tokens.size(), "recorded main requests");
            for (String tok : tokens) {
                assertTrue(tok.matches("TOK\\d+"), "main request got invalid token " + tok);
            }
        } finally {
            pool.shutdownNow();
            close(ps.srv);
        }
    }

    @Test
    void testPreflightErrorAbort() throws Exception {
        HttpServer srv = serve(ex -> send(ex, 200, "no token here"));
        try {
            Config conf = newTestConfig(url(srv));
            conf.preflightError = "abort";
            conf.preflights = List.of(flight(writeTempRequest(tmp, "GET / HTTP/1.1\nHost: " + hostPort(srv) + "\n\n"), regexVar("X", "token=(\\w+)")));
            assertThrows(SimpleRunner.RequestException.class, () -> flights(conf).runChain(conf.preflights, Map.of(), "preflight"),
                    "a non-matching extraction in abort mode");
        } finally {
            close(srv);
        }
    }

    @Test
    void testPreflightErrorIgnore() throws Exception {
        HttpServer srv = serve(ex -> send(ex, 200, "no token here"));
        try {
            Config conf = newTestConfig(url(srv));
            conf.preflightError = "ignore";
            conf.preflights = List.of(flight(writeTempRequest(tmp, "GET / HTTP/1.1\nHost: " + hostPort(srv) + "\n\n"), regexVar("X", "token=(\\w+)")));
            Map<String, String> vars = flights(conf).runChain(conf.preflights, Map.of(), "preflight");
            assertFalse(vars.containsKey("X"), "no variable from a non-matching response");
        } finally {
            close(srv);
        }
    }
}
