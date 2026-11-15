package io.github.ffuf.pkg.testtarget;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A deterministic mock HTTP target for ffuf integration tests. It models
 * the response dimensions ffuf matches and filters on (status code, body size, word count, line count, latency,
 * reflection, soft-404 baselines, redirects, request-gated endpoints and a directory tree for recursion) and records
 * every request it receives. It binds an ephemeral port on 127.0.0.1 and returns identical responses for identical
 * requests.
 */
public final class Target implements AutoCloseable {
    /** Bounds every path-derived integer so a request cannot pick an unbounded allocation size. */
    static final int MAX_TAIL_INT = 1 << 20;

    /** One request the target received. */
    public record Recorded(String method, String path, String query, Headers header, String body, Instant at) {
        /**
         * @param name the header name
         * @return the first value of that request header, or "" when it is absent
         */
        public String headerValue(String name) {
            String v = header.getFirst(name);
            return v == null ? "" : v;
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "testtarget");
        t.setDaemon(true);
        return t;
    });
    private final List<Recorded> recorded = new ArrayList<>();

    private Target(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts a new target; the caller must close it.
     *
     * @throws IOException when the server socket cannot be opened
     */
    public static Target start() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 128);
        Target t = new Target(s);
        s.createContext("/", t::handle);
        s.setExecutor(t.executor);
        s.start();
        return t;
    }

    /** @return the target's base URL, http://127.0.0.1:port */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** host:port, as a raw request file's Host header names it. */
    public String hostPort() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    /** @return a copy of the requests received so far, in arrival order */
    public synchronized List<Recorded> requests() {
        return new ArrayList<>(recorded);
    }

    /** @return how many requests the target has received */
    public synchronized int count() {
        return recorded.size();
    }

    /**
     * Stops the server and its worker threads.
     *
     * @throws InterruptedException when interrupted while waiting for the workers
     */
    @Override
    public void close() throws InterruptedException {
        server.stop(0);
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String body;
            try (InputStream in = ex.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String p = ex.getRequestURI().getRawPath();
            Headers copy = new Headers();
            copy.putAll(ex.getRequestHeaders());
            synchronized (this) {
                recorded.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), copy, body,
                        Instant.now()));
            }
            route(ex, p, body);
        }
    }

    private static void route(HttpExchange ex, String p, String body) throws IOException {
        Integer n;
        if (p.startsWith("/status/") && (n = tailInt(p, "/status/")) != null) {
            send(ex, n, "status " + n);
        } else if (p.startsWith("/size/") && (n = tailInt(p, "/size/")) != null) {
            send(ex, 200, filled(n));
        } else if (p.startsWith("/words/") && (n = tailInt(p, "/words/")) != null) {
            send(ex, 200, words(n));
        } else if (p.startsWith("/lines/") && (n = tailInt(p, "/lines/")) != null) {
            send(ex, 200, lines(n));
        } else if (p.startsWith("/sleep/") && (n = tailInt(p, "/sleep/")) != null) {
            try {
                Thread.sleep(n);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(ex, 200, "slept " + n);
        } else if (p.startsWith("/reflect/")) {
            send(ex, 200, "reflected: " + ex.getRequestURI().getPath().substring("/reflect/".length()));
        } else if (p.startsWith("/redirect/") && (n = tailInt(p, "/redirect/")) != null) {
            if (n <= 0) {
                send(ex, 200, "arrived");
            } else {
                ex.getResponseHeaders().set("Location", "/redirect/" + (n - 1));
                send(ex, 302, "");
            }
        } else if (p.equals("/ac/real")) {
            send(ex, 200, "REAL content that is meaningfully larger than the junk baseline response");
        } else if (p.startsWith("/ac/")) {
            send(ex, 200, "junk");
        } else if (p.equals("/needs-header")) {
            gate(ex, "yes".equals(ex.getRequestHeaders().getFirst("X-Test")), "header ok");
        } else if (p.equals("/needs-cookie")) {
            gate(ex, !cookie(ex.getRequestHeaders(), "SESSION").isEmpty(), "cookie ok");
        } else if (p.equals("/needs-method")) {
            gate(ex, ex.getRequestMethod().equals("POST"), "method ok");
        } else if (p.equals("/needs-body")) {
            gate(ex, body.contains("token"), "body ok");
        } else if (p.equals("/map/ok")) {
            send(ex, 200, "mapped alpha");
        } else if (p.equals("/map/bad")) {
            send(ex, 500, "mapped gamma");
        } else if (p.equals("/")) {
            send(ex, 200, "root");
        } else if (p.equals("/admin")) {
            send(ex, 200, "admin directory");
        } else if (p.equals("/admin/secret")) {
            send(ex, 200, "the secret");
        } else if (p.equals("/rdir")) {
            ex.getResponseHeaders().set("Location", "/rdir/");
            send(ex, 301, "");
        } else if (p.equals("/rdir/")) {
            send(ex, 200, "rdir index");
        } else if (p.equals("/rdir/found")) {
            send(ex, 200, "found under rdir");
        } else {
            send(ex, 404, "not found");
        }
    }

    private static void gate(HttpExchange ex, boolean open, String okBody) throws IOException {
        if (open) {
            send(ex, 200, okBody);
        } else {
            send(ex, 403, "forbidden");
        }
    }

    private static String cookie(Headers h, String name) {
        for (String line : h.getOrDefault("Cookie", List.of())) {
            for (String part : line.split(";")) {
                String[] kv = part.strip().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) {
                    return kv[1];
                }
            }
        }
        return "";
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        }
    }

    /**
     * The integer right after prefix: the rest must be exactly an integer in [0, MAX_TAIL_INT], so /size/20 parses and
     * /size/20/extra does not.
     */
    static Integer tailInt(String path, String prefix) {
        String rest = path.substring(prefix.length());
        if (rest.isEmpty() || rest.contains("/") || !rest.matches("[+-]?[0-9]+")) {
            return null;
        }
        try {
            int n = Integer.parseInt(rest);
            return n < 0 || n > MAX_TAIL_INT ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** n bytes of 'A'. */
    static String filled(int n) {
        return "A".repeat(n);
    }

    /** A body of exactly n single-space separated tokens. */
    static String words(int n) {
        return n <= 0 ? "" : String.join(" ", java.util.Collections.nCopies(n, "w"));
    }

    /** A body ffuf counts as exactly n lines: n "L"s joined by newlines, no trailing newline. */
    static String lines(int n) {
        return n <= 0 ? "" : String.join("\n", java.util.Collections.nCopies(n, "L"));
    }
}
