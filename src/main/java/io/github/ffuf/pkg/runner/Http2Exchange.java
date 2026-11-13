package io.github.ffuf.pkg.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * One -http2 exchange with a server that chose h2 in ALPN, sent through the JDK's HTTP/2 client. The response comes
 * back as HTTP/1.1-style bytes with Go's "HTTP/2.0" status line, so the runner reads it as it reads any response.
 */
final class Http2Exchange {
    /** Connection-specific fields that Go's h2 transport never sends (RFC 9113 section 8.2.2). */
    private static final Set<String> SKIPPED = Set.of("Connection", "Proxy-Connection", "Transfer-Encoding", "Upgrade",
            "Keep-Alive", "Content-Length", "Host");

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    private final HttpClient client;

    Http2Exchange(SSLContext ctx, int timeoutMs) {
        HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(ctx)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY);
        SSLParameters params = ctx.getDefaultSSLParameters();
        params.setApplicationProtocols(new String[] {"h2", "http/1.1"});
        b.sslParameters(params);
        if (timeoutMs > 0) {
            b.connectTimeout(Duration.ofMillis(timeoutMs));
        }
        client = b.build();
    }

    /**
     * Sends the request and returns the response as bytes, plus the nanoseconds until the response arrived.
     *
     * @throws IOException when the connection or the HTTP/2 exchange fails
     * @throws InterruptedException when the thread is interrupted while waiting for the response
     */
    byte[] send(String method, GoUrl url, String host, Map<String, String> headers, byte[] body, boolean gzip,
            int timeoutMs, long[] firstByteNanos) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString()))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (timeoutMs > 0) {
            b.timeout(Duration.ofMillis(timeoutMs));
        }
        if (!host.isEmpty() && !host.equals(url.host)) {
            b.header("Host", host);
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (!SKIPPED.contains(h.getKey())) {
                b.header(h.getKey(), h.getValue());
            }
        }
        if (gzip) {
            b.header("Accept-Encoding", "gzip");
        }
        long start = System.nanoTime();
        HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        firstByteNanos[0] = System.nanoTime() - start;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder head = new StringBuilder("HTTP/2.0 ").append(r.statusCode()).append(' ')
                .append(GoStatus.text(r.statusCode())).append("\r\n");
        for (Map.Entry<String, List<String>> h : r.headers().map().entrySet()) {
            if (h.getKey().startsWith(":")) {
                continue;
            }
            for (String v : h.getValue()) {
                head.append(h.getKey()).append(": ").append(v).append("\r\n");
            }
        }
        head.append("\r\n");
        out.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(r.body());
        return out.toByteArray();
    }
}
