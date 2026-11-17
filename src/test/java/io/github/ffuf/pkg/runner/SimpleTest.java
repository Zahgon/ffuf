package io.github.ffuf.pkg.runner;

import static io.github.ffuf.pkg.runner.PreflightTest.close;
import static io.github.ffuf.pkg.runner.PreflightTest.serve;
import static io.github.ffuf.pkg.runner.PreflightTest.url;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimpleTest {
    private static final int MAX = SimpleRunner.MAX_DOWNLOAD_SIZE;

    @TempDir
    Path tmp;

    /** A gzip stream that decompresses to n zero bytes; it is tiny, so almost nothing crosses the wire. */
    private static byte[] gzipBomb(int n) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream zw = new GZIPOutputStream(buf) {
            {
                def.setLevel(Deflater.BEST_COMPRESSION);
            }
        }) {
            byte[] chunk = new byte[1 << 20];
            for (int written = 0; written < n; written += chunk.length) {
                zw.write(chunk, 0, Math.min(chunk.length, n - written));
            }
        }
        return buf.toByteArray();
    }

    private static Response execute(String url, Consumer<Config> mutate, Consumer<Request> request) throws SimpleRunner.RequestException {
        Config conf = new Config();
        conf.method = "GET";
        conf.timeout = 10;
        mutate.accept(conf);
        Request req = Request.base(conf);
        req.method = "GET";
        req.url = url;
        request.accept(req);
        return new SimpleRunner(conf).execute(req);
    }

    private static Response execute(String url, Consumer<Config> mutate) throws SimpleRunner.RequestException {
        return execute(url, mutate, r -> { });
    }

    private static HttpServer gzipServer(byte[] bomb, boolean withLength) throws IOException {
        return serve(ex -> {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            try {
                ex.sendResponseHeaders(200, withLength ? bomb.length : 0);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bomb);
                }
            } catch (IOException e) {
                // The client stops reading at its download limit, which is what the test checks.
            } finally {
                ex.close();
            }
        });
    }

    /** Streams total bytes with no Content-Length, so the response is chunked and the numeric size guard cannot fire. */
    private static HttpServer chunkedBodyServer(int total) throws IOException {
        byte[] chunk = new byte[1 << 20];
        Arrays.fill(chunk, (byte) 'A');
        return serve(ex -> {
            try {
                ex.sendResponseHeaders(200, 0);
                try (OutputStream out = ex.getResponseBody()) {
                    for (int written = 0; written < total; written += chunk.length) {
                        out.write(chunk, 0, Math.min(chunk.length, total - written));
                        out.flush();
                    }
                }
            } catch (IOException e) {
                // The client stops reading at its download limit, which is what the test checks.
            } finally {
                ex.close();
            }
        });
    }

    /** A decompression bomb is capped at the download limit and marked cancelled, with or without manual decoding. */
    @Test
    void testExecute_GzipBombIsCapped() throws Exception {
        HttpServer srv = gzipServer(gzipBomb(64 << 20), true);
        try {
            Response transparent = execute(url(srv) + "/", c -> { });
            assertTrue(transparent.cancelled, "transparent decode: expected a cancelled response, got " + transparent.data.length + " bytes");
            assertTrue(transparent.data.length <= MAX, "transparent decode: read " + transparent.data.length + " bytes, at most " + MAX);

            Response manual = execute(url(srv) + "/", c -> { }, r -> r.headers.put("Accept-Encoding", "gzip"));
            assertTrue(manual.cancelled, "manual decode: expected a cancelled response, got " + manual.data.length + " bytes");
            assertTrue(manual.data.length <= MAX, "manual decode: read " + manual.data.length + " bytes, at most " + MAX);
        } finally {
            close(srv);
        }
    }

    @Test
    void testExecute_NormalResponseUnaffected() throws Exception {
        HttpServer srv = serve(ex -> PreflightTest.send(ex, 200, "hello world"));
        try {
            Response resp = execute(url(srv) + "/", c -> { });
            assertFalse(resp.cancelled, "a normal response must not be cancelled");
            assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), resp.data, "body");
        } finally {
            close(srv);
        }
    }

    /** Bytes the process allocated while fn ran, summed over its threads like Go's MemStats.TotalAlloc. */
    private static long allocatedBy(ThrowingRunnable fn) throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = totalAllocated(mx);
        fn.run();
        return totalAllocated(mx) - before;
    }

    private static long totalAllocated(com.sun.management.ThreadMXBean mx) {
        long sum = 0;
        for (long bytes : mx.getThreadAllocatedBytes(mx.getAllThreadIds())) {
            sum += Math.max(bytes, 0);
        }
        return sum;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * With -od or -audit-log a body far over the cap must not be read into memory: the snapshot stays within the cap
     * and the response is cancelled. As in the Go test, the allocation is the whole process's, the in-process server
     * included, and the ceiling is twice the body.
     */
    @Test
    void testExecute_SnapshotIsBoundedWithOutputFeatures() throws Exception {
        int body = 64 << 20;
        @SuppressWarnings("unchecked")
        Consumer<Config>[] cases = new Consumer[] {
            (Consumer<Config>) c -> c.outputDirectory = tmp.toString(),
            (Consumer<Config>) c -> c.auditLog = tmp.resolve("audit.log").toString(),
        };
        for (Consumer<Config> mutate : cases) {
            HttpServer srv = chunkedBodyServer(body);
            try {
                Response[] resp = new Response[1];
                long allocated = allocatedBy(() -> resp[0] = execute(url(srv) + "/", mutate));
                assertTrue(allocated <= 2L * body, "allocated " + allocated + " bytes for a " + body + " byte body, at most " + 2L * body);
                assertTrue(resp[0].raw.length() <= MAX, "raw snapshot is " + resp[0].raw.length() + ", at most " + MAX);
                assertTrue(resp[0].cancelled, "an over-cap response is cancelled");
            } finally {
                close(srv);
            }
        }
    }

    @Test
    void testExecute_SnapshotIsBoundedForGzipBomb() throws Exception {
        HttpServer srv = gzipServer(gzipBomb(64 << 20), false);
        try {
            Response resp = execute(url(srv) + "/", c -> c.outputDirectory = tmp.toString());
            assertTrue(resp.raw.length() <= MAX, "raw snapshot is " + resp.raw.length() + ", at most " + MAX);
        } finally {
            close(srv);
        }
    }

    @Test
    void testExecute_SnapshotKeptForNormalResponse() throws Exception {
        HttpServer srv = serve(ex -> PreflightTest.send(ex, 200, "hello world"));
        try {
            Response resp = execute(url(srv) + "/", c -> c.outputDirectory = tmp.toString());
            assertFalse(resp.cancelled, "a normal response must not be cancelled");
            assertTrue(resp.raw.contains("hello world"), "raw snapshot contains the body: " + resp.raw);
        } finally {
            close(srv);
        }
    }

    /** -ignore-body holds for a chunked response too. */
    @Test
    void testExecute_IgnoreBodyAppliesToChunkedResponse() throws Exception {
        HttpServer srv = chunkedBodyServer(32 << 10);
        try {
            Response resp = execute(url(srv) + "/", c -> c.ignoreBody = true);
            assertTrue(resp.cancelled, "expected the response to be cancelled");
            assertEquals(0, resp.data.length, "body bytes read despite -ignore-body");
        } finally {
            close(srv);
        }
    }

    /** -ignore-body keeps the server-declared length, which the size filters read. */
    @Test
    void testExecute_IgnoreBodyKeepsContentLength() throws Exception {
        HttpServer srv = serve(ex -> {
            try {
                ex.sendResponseHeaders(200, 32 << 10);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(new byte[32 << 10]);
                }
            } finally {
                ex.close();
            }
        });
        try {
            Response resp = execute(url(srv) + "/", c -> c.ignoreBody = true);
            assertTrue(resp.cancelled, "expected the response to be cancelled");
            assertEquals(32 << 10, resp.contentLength, "ContentLength");
        } finally {
            close(srv);
        }
    }

    /**
     * Response headers sit outside the download cap, so the runner bounds them itself: at most 4 MiB is intended, and
     * a response whose headers pass the bound is refused instead of read.
     */
    @Test
    void testNewSimpleRunner_BoundsResponseHeaders() throws Exception {
        assertTrue(SimpleRunner.MAX_RESPONSE_HEADER_BYTES > 0, "response headers are bounded");
        assertTrue(SimpleRunner.MAX_RESPONSE_HEADER_BYTES <= 4 << 20, "MaxResponseHeaderBytes = " + SimpleRunner.MAX_RESPONSE_HEADER_BYTES + ", larger than intended");
        String pad = "p".repeat(1000);
        HttpServer srv = serve(ex -> {
            int n = ex.getRequestURI().getPath().equals("/huge") ? 2000 : 100;
            for (int i = 0; i < n; i++) {
                ex.getResponseHeaders().add("X-Pad-" + i, pad);
            }
            PreflightTest.send(ex, 200, "ok");
        });
        try {
            assertEquals(200, execute(url(srv) + "/small", c -> { }).statusCode, "status with 100 KB of headers");
            SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> execute(url(srv) + "/huge", c -> { }));
            assertTrue(e.getMessage().endsWith("net/http: server response headers exceeded 1048576 bytes; aborted"), "error: " + e.getMessage());
        } finally {
            close(srv);
        }
    }
}
