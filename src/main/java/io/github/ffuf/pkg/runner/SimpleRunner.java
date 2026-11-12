package io.github.ffuf.pkg.runner;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.Util;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.brotli.dec.BrotliInputStream;

/**
 * Sends ffuf requests over HTTP/1.1 the way Go's http.Client does: http.NewRequest validation, the transport's
 * header checks, the exact request bytes, redirect handling and the errors as url.Error texts.
 *
 * <p>Method, URL, header and Location values are handled as byte strings (see {@link GoText}).
 */
public class SimpleRunner {
    static final int MAX_DOWNLOAD_SIZE = 5242880;
    private static final int MAX_REDIRECTS = 10;
    /** The bound on a response's status line and headers, which MAX_DOWNLOAD_SIZE does not cover. */
    static final int MAX_RESPONSE_HEADER_BYTES = 1 << 20;
    private final Config config;
    /** The -x (or -replay-proxy) URL; null means http.ProxyFromEnvironment decides per request. */
    private final GoUrl customProxy;
    /** -cc and -ck, loaded once when the runner is created. */
    private final KeyManager[] clientCert;
    private SSLContext sslContext;
    private Http2Exchange http2;

    /** A failed request; the message is the error text Go's http.Client reports for it. */
    public static class RequestException extends Exception {
        /** Whether the request timed out. */
        public final boolean timeout;

        /** Creates the exception; timeout is true when the request timed out. */
        public RequestException(String message, boolean timeout) {
            super(message);
            this.timeout = timeout;
        }
    }

    /** One outgoing request of a client exchange: the http.Request fields that decide its bytes. */
    private static final class Outgoing {
        String method;
        GoUrl url;
        String host;
        Map<String, String> header;
        byte[] body;
    }

    /** A received response with the raw (byte string) value of its first Location header. */
    private static final class Received {
        Response resp;
        String location = "";
    }

    /** Creates the runner for the configuration, loading -cc and -ck when given. */
    public SimpleRunner(Config conf) {
        this(conf, false);
    }

    /**
     * Creates a runner for the configuration.
     *
     * @param replay true for a runner through the -replay-proxy, false for one through the -x proxy
     */
    public SimpleRunner(Config conf, boolean replay) {
        this.config = conf;
        String customProxy = replay ? conf.replayProxyUrl : conf.proxyUrl;
        GoUrl p = null;
        if (!customProxy.isEmpty()) {
            try {
                p = GoUrl.parse(GoText.bin(customProxy));
            } catch (GoUrl.UrlException e) {
                p = null;
            }
        }
        this.customProxy = p;
        this.clientCert = ClientCert.load(conf.clientCert, conf.clientKey);
    }

    /** The proxy for a request to target, or null for a direct connection. */
    private GoUrl proxyFor(GoUrl target, String op) throws RequestException {
        if (customProxy != null) {
            return customProxy;
        }
        try {
            return EnvProxy.system().proxyFor(target);
        } catch (EnvProxy.CgiException e) {
            throw urlError(op, target, e.getMessage(), false);
        }
    }

    /** Whether requests go to an HTTP proxy in absolute form (an http target behind an http or https proxy). */
    private static boolean usingHttpProxy(GoUrl proxy, GoUrl target) {
        return proxy != null && (proxy.scheme.equals("http") || proxy.scheme.equals("https")) && target.scheme.equals("http");
    }

    /**
     * Replaces every keyword in a copy of the base request.
     *
     * @param input the payload of each keyword
     * @param base the request with keywords
     * @return the request to send
     */
    public Request prepare(Map<String, byte[]> input, Request base) {
        Request req = base.copy();
        for (Map.Entry<String, byte[]> e : input.entrySet()) {
            String kw = e.getKey();
            String val = io.github.ffuf.pkg.ffuf.GoString.decode(e.getValue());
            req.method = req.method.replace(kw, val);
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> h : req.headers.entrySet()) {
                headers.put(Util.canonicalHeaderKey(h.getKey().replace(kw, val)), h.getValue().replace(kw, val));
            }
            req.headers = headers;
            req.url = req.url.replace(kw, val);
            req.data = io.github.ffuf.pkg.ffuf.GoString.encode(io.github.ffuf.pkg.ffuf.GoString.decode(req.data).replace(kw, val));
        }
        req.input = input;
        return req;
    }

    /** Builds the outgoing request: validates method and URL with the rules of Go's http.NewRequest, applies the headers and fills its host. */
    private Outgoing newRequest(Request req) throws RequestException {
        Outgoing o = new Outgoing();
        o.method = GoText.bin(req.method.isEmpty() ? "GET" : req.method);
        if (!HttpGuts.validHeaderFieldName(o.method)) {
            throw new RequestException("net/http: invalid method " + GoText.quote(o.method), false);
        }
        try {
            o.url = GoUrl.parse(GoText.bin(req.url));
        } catch (GoUrl.UrlException e) {
            throw new RequestException(e.getMessage(), false);
        }
        o.url.host = removeEmptyPort(o.url.host);
        if (!req.headers.containsKey("User-Agent")) {
            req.headers.put("User-Agent", "Fuzz Faster U Fool v" + Util.version());
        }
        o.host = req.headers.containsKey("Host") ? GoText.bin(req.headers.get("Host")) : o.url.host;
        req.host = GoText.text(o.host);
        if (config.raw) {
            o.url.opaque = GoText.bin(req.url);
        }
        o.header = new TreeMap<>();
        for (Map.Entry<String, String> h : req.headers.entrySet()) {
            o.header.put(Util.canonicalHeaderKey(GoText.bin(h.getKey())), GoText.bin(h.getValue()));
        }
        o.body = req.data;
        return o;
    }

    private static String removeEmptyPort(String host) {
        if (host.lastIndexOf(':') > host.lastIndexOf(']') && host.endsWith(":")) {
            return host.substring(0, host.length() - 1);
        }
        return host;
    }

    /**
     * Sends the request and follows redirects when -r is set.
     *
     * @return the response
     * @throws RequestException with the client error text when the request fails
     */
    public Response execute(Request req) throws RequestException {
        Outgoing first = newRequest(req);
        req.timestamp = java.time.Instant.now();
        String op = urlErrorOp(first.method);
        Outgoing cur = first;
        Received last = null;
        boolean includeBody = true;
        boolean stripSensitive = false;
        String redirectMethod = "";
        String firstRaw = null;
        int sent = 0;
        while (true) {
            if (sent > 0) {
                String loc = last.location;
                if (loc.isEmpty()) {
                    return last.resp;
                }
                Outgoing next = redirect(first, cur, loc, op, includeBody);
                next.method = redirectMethod;
                stripSensitive = stripSensitive || !first.url.host.equals(next.url.host) && !shouldCopyHeaderOnRedirect(first.url, next.url);
                next.header = copyHeaders(first.header, stripSensitive, !includeBody);
                String ref = refererForUrl(cur.url, next.url, next.header.getOrDefault("Referer", ""));
                if (!ref.isEmpty()) {
                    next.header.put("Referer", ref);
                }
                if (!config.followRedirects) {
                    return last.resp;
                }
                if (sent >= MAX_REDIRECTS) {
                    throw new RequestException(op + " " + GoText.quote(loc) + ": stopped after 10 redirects", false);
                }
                cur = next;
            }
            sent++;
            last = send(req, cur, op);
            if (firstRaw == null) {
                firstRaw = req.raw;
            }
            req.raw = firstRaw;
            long sc = last.resp.statusCode;
            if (sc == 301 || sc == 302 || sc == 303) {
                redirectMethod = cur.method.equals("GET") || cur.method.equals("HEAD") ? cur.method : "GET";
                includeBody = false;
            } else if (sc == 307 || sc == 308) {
                redirectMethod = cur.method;
            } else {
                return last.resp;
            }
        }
    }

    /** The next request of a redirect chain: the Location resolved against the current URL. */
    private static Outgoing redirect(Outgoing first, Outgoing cur, String loc, String op, boolean includeBody)
            throws RequestException {
        Outgoing next = new Outgoing();
        try {
            next.url = cur.url.parseRef(loc);
        } catch (GoUrl.UrlException e) {
            throw urlError(op, cur.url, "failed to parse Location header " + GoText.quote(loc) + ": " + e.getMessage(), false);
        }
        next.host = "";
        if (!cur.host.isEmpty() && !cur.host.equals(cur.url.host)) {
            try {
                if (!GoUrl.parse(loc).isAbs()) {
                    next.host = cur.host;
                }
            } catch (GoUrl.UrlException e) {
                next.host = "";
            }
        }
        next.body = includeBody ? first.body : new byte[0];
        return next;
    }

    private static Map<String, String> copyHeaders(Map<String, String> initial, boolean stripSensitive, boolean stripBody) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> h : initial.entrySet()) {
            String k = Util.canonicalHeaderKey(h.getKey());
            boolean sensitive = switch (k) {
                case "Authorization", "Www-Authenticate", "Cookie", "Cookie2", "Proxy-Authorization", "Proxy-Authenticate" -> true;
                default -> false;
            };
            boolean body = switch (k) {
                case "Content-Encoding", "Content-Language", "Content-Location", "Content-Type" -> true;
                default -> false;
            };
            if (!(sensitive && stripSensitive) && !(body && stripBody)) {
                out.put(h.getKey(), h.getValue());
            }
        }
        return out;
    }

    private static boolean shouldCopyHeaderOnRedirect(GoUrl initial, GoUrl dest) {
        String ihost;
        String dhost;
        try {
            ihost = HttpGuts.punycodeHostPort(initial.hostname());
            dhost = HttpGuts.punycodeHostPort(dest.hostname());
        } catch (GoUrl.UrlException e) {
            return false;
        }
        if (!(ihost + dhost).chars().allMatch(c -> c < 0x80)) {
            return false;
        }
        ihost = GoText.asciiLower(ihost);
        dhost = GoText.asciiLower(dhost);
        if (dhost.equals(ihost)) {
            return true;
        }
        if (dhost.contains(":") || dhost.contains("%")) {
            return false;
        }
        return dhost.endsWith(ihost) && dhost.length() > ihost.length() && dhost.charAt(dhost.length() - ihost.length() - 1) == '.';
    }

    private static String refererForUrl(GoUrl lastUrl, GoUrl newUrl, String explicitRef) {
        if (lastUrl.scheme.equals("https") && newUrl.scheme.equals("http")) {
            return "";
        }
        if (!explicitRef.isEmpty()) {
            return explicitRef;
        }
        return lastUrl.withoutUserinfo();
    }

    static String urlErrorOp(String method) {
        if (method.isEmpty()) {
            return "Get";
        }
        return method.charAt(0) + GoText.asciiLower(method.substring(1));
    }

    private static RequestException urlError(String op, GoUrl url, String err, boolean timeout) {
        return new RequestException(op + " " + GoText.quote(url.stripPassword()) + ": " + err, timeout);
    }

    /** The transport's checks before it dials, as RoundTrip reports them, or null. */
    private static String validate(Outgoing o) {
        boolean isHttp = o.url.scheme.equals("http") || o.url.scheme.equals("https");
        if (!isHttp) {
            return "unsupported protocol scheme " + GoText.quote(o.url.scheme);
        }
        for (Map.Entry<String, String> h : o.header.entrySet()) {
            if (!HttpGuts.validHeaderFieldName(h.getKey())) {
                return "net/http: invalid header field name " + GoText.quote(h.getKey());
            }
            if (!HttpGuts.validHeaderFieldValue(h.getValue())) {
                return "net/http: invalid header field value for " + GoText.quote(h.getKey());
            }
        }
        if (o.url.host.isEmpty()) {
            return "http: no Host in request URL";
        }
        return null;
    }

    private static Map<String, String> withBasicAuth(Outgoing o) {
        if (!o.url.hasUser || !o.header.getOrDefault("Authorization", "").isEmpty()) {
            return o.header;
        }
        Map<String, String> h = new TreeMap<>(o.header);
        String auth = o.url.username + ":" + o.url.password;
        h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(GoText.bytes(auth)));
        return h;
    }

    /** {@return s without leading and trailing ASCII space, tab, CR and LF} */
    private static String trimString(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && " \t\n\r".indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && " \t\n\r".indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean requestsGzip(String method, Map<String, String> headers) {
        return headers.getOrDefault("Accept-Encoding", "").isEmpty() && headers.getOrDefault("Range", "").isEmpty() && !method.equals("HEAD");
    }

    /** Request.write without the body: the request line and headers exactly as Go writes them. */
    static String head(String method, GoUrl url, String reqHost, Map<String, String> headers, byte[] body, String proxyAuth)
            throws GoUrl.UrlException {
        boolean usingProxy = proxyAuth != null;
        String host = HttpGuts.punycodeHostPort(reqHost.isEmpty() ? url.host : reqHost);
        if (!HttpGuts.validHostHeader(host)) {
            if (usingProxy) {
                throw new GoUrl.UrlException("http: invalid Host header");
            }
            host = "";
        }
        host = removeZone(host);
        String ruri = url.requestUri();
        if (usingProxy && !url.scheme.isEmpty() && url.opaque.isEmpty()) {
            ruri = url.scheme + "://" + host + ruri;
        }
        if (GoUrl.containsCtlByte(ruri)) {
            throw new GoUrl.UrlException("net/http: can't write control character in Request.URL");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(ruri).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        String ua = headers.getOrDefault("User-Agent", "Go-http-client/1.1");
        if (!ua.isEmpty()) {
            sb.append("User-Agent: ").append(trimString(ua.replace('\r', ' ').replace('\n', ' '))).append("\r\n");
        }
        if (body.length > 0 || method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            String k = h.getKey();
            if (k.equals("Host") || k.equals("User-Agent") || k.equals("Content-Length") || k.equals("Transfer-Encoding")
                    || k.equals("Trailer") || !HttpGuts.validHeaderFieldName(k)) {
                continue;
            }
            sb.append(k).append(": ").append(trimString(h.getValue().replace('\r', ' ').replace('\n', ' '))).append("\r\n");
        }
        if (requestsGzip(method, headers)) {
            sb.append("Accept-Encoding: gzip\r\n");
        }
        if (usingProxy && !proxyAuth.isEmpty()) {
            sb.append("Proxy-Authorization: ").append(proxyAuth).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private static String removeZone(String host) {
        if (!host.startsWith("[")) {
            return host;
        }
        int i = host.lastIndexOf(']');
        if (i < 0) {
            return host;
        }
        int j = host.substring(0, i).lastIndexOf('%');
        if (j < 0) {
            return host;
        }
        return host.substring(0, j) + host.substring(i);
    }

    /**
     * Renders a prepared request as the raw bytes it sends, for -search.
     *
     * @return the request text
     * @throws RequestException when the request is invalid
     */
    public String dump(Request req) throws RequestException {
        Outgoing o = newRequest(req);
        try {
            return GoText.text(head(o.method, o.url, o.host, o.header, o.body, null)) + io.github.ffuf.pkg.ffuf.GoString.decode(o.body);
        } catch (GoUrl.UrlException e) {
            throw new RequestException(e.getMessage(), false);
        }
    }

    private Received send(Request req, Outgoing o, String op) throws RequestException {
        String invalid = validate(o);
        if (invalid != null) {
            throw urlError(op, o.url, invalid, false);
        }
        Map<String, String> headers = withBasicAuth(o);
        long timeoutNanos = config.timeout * 1_000_000_000L;
        int timeoutMs = timeoutNanos < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, timeoutNanos / 1_000_000L);
        if (timeoutNanos > 0 && timeoutMs == 0) {
            timeoutMs = 1;
        }
        if (config.context.isDone()) {
            throw urlError(op, o.url, "context canceled", false);
        }
        GoUrl proxy = proxyFor(o.url, op);
        Socket registered = null;
        try (Socket socket = connect(proxy, o.url, timeoutMs, op)) {
            if (!config.context.register(socket)) {
                throw urlError(op, o.url, "context canceled", false);
            }
            registered = socket;
            byte[] head;
            try {
                head = GoText.bytes(head(o.method, o.url, o.host, headers, o.body, usingHttpProxy(proxy, o.url) ? proxyAuth(proxy) : null));
            } catch (GoUrl.UrlException e) {
                throw urlError(op, o.url, e.getMessage(), false);
            }
            OutputStream out = socket.getOutputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            all.write(head);
            all.write(o.body);
            req.raw = io.github.ffuf.pkg.ffuf.GoString.decode(all.toByteArray());
            if (socket instanceof SSLSocket ssl && "h2".equals(ssl.getApplicationProtocol())) {
                socket.close();
                return sendHttp2(req, o, headers, timeoutMs, op);
            }
            out.write(all.toByteArray());
            out.flush();
            long start = System.nanoTime();
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            in.mark(1);
            int firstByte = in.read();
            long firstByteNanos = System.nanoTime() - start;
            if (firstByte < 0) {
                throw urlError(op, o.url, "EOF", false);
            }
            in.reset();
            return readResponse(in, req, o, requestsGzip(o.method, headers), firstByteNanos, op);
        } catch (SocketTimeoutException e) {
            throw urlError(op, o.url, "context deadline exceeded (Client.Timeout exceeded while awaiting headers)", true);
        } catch (IOException e) {
            throw urlError(op, o.url, config.context.isDone() ? "context canceled" : String.valueOf(e.getMessage()), false);
        } finally {
            if (registered != null) {
                config.context.unregister(registered);
            }
        }
    }

    /** The exchange with a server that chose h2: -http2 forces the HTTP/2 attempt. */
    private Received sendHttp2(Request req, Outgoing o, Map<String, String> headers, int timeoutMs, String op)
            throws IOException, RequestException {
        Http2Exchange h2;
        synchronized (this) {
            if (http2 == null) {
                http2 = new Http2Exchange(sslContext(), timeoutMs);
            }
            h2 = http2;
        }
        long[] firstByte = new long[1];
        byte[] resp;
        try {
            resp = h2.send(o.method, o.url, o.host, headers, o.body, requestsGzip(o.method, headers), timeoutMs, firstByte);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SocketTimeoutException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw urlError(op, o.url, "context canceled", false);
        } catch (IllegalArgumentException e) {
            throw urlError(op, o.url, String.valueOf(e.getMessage()), false);
        }
        return readResponse(new BufferedInputStream(new ByteArrayInputStream(resp)), req, o, requestsGzip(o.method, headers), firstByte[0], op);
    }

    /** A failed dial, with the net.OpError text Go reports. */
    private static final class DialException extends Exception {
        final boolean timeout;

        DialException(String message, boolean timeout) {
            super(message);
            this.timeout = timeout;
        }
    }

    private static String asciiHostname(GoUrl u) {
        try {
            return HttpGuts.toAscii(u.hostname());
        } catch (GoUrl.UrlException e) {
            return u.hostname();
        }
    }

    private static int portOf(GoUrl u) {
        String p = u.port();
        if (!p.isEmpty()) {
            return Integer.parseInt(p);
        }
        return switch (u.scheme) {
            case "https" -> 443;
            case "socks5", "socks5h" -> 1080;
            default -> 80;
        };
    }

    private static String joinHostPort(String host, int port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    private static Socket dial(String host, int port, int timeoutMs) throws DialException, IOException {
        String addr = joinHostPort(host, port);
        if (timeoutMs < 0) {
            throw new DialException("dial tcp " + addr + ": i/o timeout", true);
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(GoText.text(host), port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            return s;
        } catch (ConnectException e) {
            s.close();
            throw new DialException("dial tcp " + addr + ": connect: connection refused", false);
        } catch (java.net.UnknownHostException | IllegalArgumentException e) {
            s.close();
            throw new DialException("dial tcp: lookup " + host + ": no such host", false);
        } catch (SocketTimeoutException e) {
            s.close();
            throw new DialException("dial tcp " + addr + ": i/o timeout", true);
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /**
     * Dials the target, directly or through the proxy, and starts TLS for https targets. The dialled socket is closed
     * when the proxy handshake or TLS setup fails.
     */
    private Socket connect(GoUrl proxy, GoUrl u, int timeoutMs, String op) throws RequestException, IOException {
        String host = asciiHostname(u);
        int port = portOf(u);
        Socket s;
        try {
            s = proxy == null ? dial(host, port, timeoutMs) : dial(asciiHostname(proxy), portOf(proxy), timeoutMs);
        } catch (DialException e) {
            throw urlError(op, u, proxy == null ? e.getMessage() : "proxyconnect tcp: " + e.getMessage(), e.timeout);
        }
        try {
            return handshake(s, proxy, u, host, port, timeoutMs, op);
        } catch (IOException | RequestException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /** Runs the proxy handshake, if any, on the dialled socket and starts TLS for https targets. */
    private Socket handshake(Socket s, GoUrl proxy, GoUrl u, String host, int port, int timeoutMs, String op)
            throws RequestException, IOException {
        if (proxy != null) {
            if (proxy.scheme.equals("socks5") || proxy.scheme.equals("socks5h")) {
                socks5Connect(proxy, s, host, port);
            } else {
                if (proxy.scheme.equals("https")) {
                    s = tls(s, asciiHostname(proxy), portOf(proxy), timeoutMs, false);
                }
                if (!u.scheme.equals("https")) {
                    return s;
                }
                proxyTunnel(proxy, s, joinHostPort(host, port), op, u);
            }
        }
        return u.scheme.equals("https") ? tls(s, host, port, timeoutMs, proxy == null && config.http2) : s;
    }

    /** The CONNECT request Go's transport sends to an HTTP proxy for an https target. */
    private static void proxyTunnel(GoUrl proxy, Socket s, String target, String op, GoUrl u) throws IOException, RequestException {
        StringBuilder req = new StringBuilder("CONNECT ").append(target).append(" HTTP/1.1\r\nHost: ").append(target)
                .append("\r\nUser-Agent: Go-http-client/1.1\r\n");
        String auth = proxyAuth(proxy);
        if (!auth.isEmpty()) {
            req.append("Proxy-Authorization: ").append(auth).append("\r\n");
        }
        s.getOutputStream().write(GoText.bytes(req.append("\r\n").toString()));
        InputStream in = s.getInputStream();
        String status = readLine(in);
        while (true) {
            String l = readLine(in);
            if (l == null || l.isEmpty()) {
                break;
            }
        }
        if (status == null) {
            throw urlError(op, u, "unexpected EOF", false);
        }
        String[] sp = status.split(" ", 3);
        if (sp.length < 2 || !sp[1].equals("200")) {
            throw urlError(op, u, sp.length < 3 ? "unknown status code" : sp[2], false);
        }
    }

    private static String proxyAuth(GoUrl proxy) {
        if (!proxy.hasUser) {
            return "";
        }
        return "Basic " + Base64.getEncoder().encodeToString(GoText.bytes(proxy.username + ":" + proxy.password));
    }

    /** The SOCKS5 CONNECT handshake of golang.org/x/net/internal/socks. */
    private static void socks5Connect(GoUrl proxy, Socket s, String host, int port) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        boolean auth = proxy.hasUser;
        out.write(auth ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0});
        byte[] reply = in.readNBytes(2);
        if (reply.length < 2 || reply[0] != 5) {
            throw new IOException("unexpected protocol version");
        }
        if (reply[1] == 2) {
            byte[] user = GoText.bytes(proxy.username);
            byte[] pass = GoText.bytes(proxy.password);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(1);
            b.write(user.length);
            b.write(user);
            b.write(pass.length);
            b.write(pass);
            out.write(b.toByteArray());
            byte[] ok = in.readNBytes(2);
            if (ok.length < 2 || ok[1] != 0) {
                throw new IOException("username/password authentication failed");
            }
        } else if (reply[1] != 0) {
            throw new IOException("no acceptable authentication methods");
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[] {5, 1, 0});
        if (NetIp.parseAddrError(host) == null) {
            byte[] ip = java.net.InetAddress.getByName(host).getAddress();
            b.write(ip.length == 4 ? 1 : 4);
            b.write(ip);
        } else {
            byte[] name = GoText.bytes(host);
            b.write(3);
            b.write(name.length);
            b.write(name);
        }
        b.write(port >> 8);
        b.write(port & 0xff);
        out.write(b.toByteArray());
        byte[] head = in.readNBytes(4);
        if (head.length < 4 || head[1] != 0) {
            throw new IOException("unknown error " + (head.length < 2 ? "" : Integer.toString(head[1] & 0xff)));
        }
        int len = switch (head[3]) {
            case 1 -> 4;
            case 4 -> 16;
            case 3 -> in.read();
            default -> throw new IOException("unknown address type " + head[3]);
        };
        in.readNBytes(len + 2);
    }

    /**
     * Starts TLS on the socket: no certificate verification, -sni as the server name, the -cc/-ck client
     * certificate, and with offerH2 the ALPN list "h2", "http/1.1".
     */
    private SSLSocket tls(Socket s, String host, int port, int timeoutMs, boolean offerH2) throws IOException {
        SSLSocket ssl = (SSLSocket) sslContext().getSocketFactory().createSocket(s, config.sni.isEmpty() ? GoText.text(host) : config.sni, port, true);
        if (offerH2) {
            SSLParameters params = ssl.getSSLParameters();
            params.setApplicationProtocols(new String[] {"h2", "http/1.1"});
            ssl.setSSLParameters(params);
        }
        ssl.setSoTimeout(timeoutMs);
        ssl.startHandshake();
        return ssl;
    }

    private synchronized SSLContext sslContext() throws IOException {
        if (sslContext == null) {
            try {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(clientCert, new TrustManager[] {new TrustAll()}, null);
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return sslContext;
    }

    /** Every server certificate is accepted, whatever its name. */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // the client side never receives client certificates
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // the client side never receives client certificates
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // the client side never receives client certificates
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // ffuf never verifies certificates (InsecureSkipVerify)
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // ffuf never verifies certificates (InsecureSkipVerify)
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // ffuf never verifies certificates (InsecureSkipVerify)
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static String readLine(InputStream in) throws IOException {
        return readLine(in, new long[] {Long.MAX_VALUE});
    }

    /** Reads one line, charging its bytes to budget; past the budget it throws a {@link HeaderLimitException}. */
    private static String readLine(InputStream in, long[] budget) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (--budget[0] < 0) {
                throw new HeaderLimitException();
            }
            if (c == '\n') {
                break;
            }
            b.write(c);
        }
        if (c < 0 && b.size() == 0) {
            return null;
        }
        String s = b.toString(StandardCharsets.ISO_8859_1);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /** The response headers outgrew {@link #MAX_RESPONSE_HEADER_BYTES}. */
    private static final class HeaderLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private Received readResponse(InputStream in, Request req, Outgoing o, boolean autoGzip, long firstByte, String op)
            throws IOException, RequestException {
        try {
            return readResponse(in, req, o, autoGzip, firstByte, op, new long[] {MAX_RESPONSE_HEADER_BYTES});
        } catch (HeaderLimitException e) {
            throw urlError(op, o.url, "net/http: server response headers exceeded " + MAX_RESPONSE_HEADER_BYTES + " bytes; aborted", false);
        }
    }

    private Received readResponse(InputStream in, Request req, Outgoing o, boolean autoGzip, long firstByte, String op, long[] headerBudget)
            throws IOException, RequestException {
        String method = o.method;
        String status;
        while (true) {
            status = readLine(in, headerBudget);
            if (status == null) {
                throw urlError(op, o.url, "EOF", false);
            }
            String[] sp = status.split(" ", 3);
            if (sp.length < 2 || !sp[0].startsWith("HTTP/") || sp[1].length() != 3) {
                throw urlError(op, o.url, "net/http: HTTP/1.x transport connection broken: malformed HTTP response " + GoText.quote(status), false);
            }
            int code;
            try {
                code = Integer.parseInt(sp[1]);
            } catch (NumberFormatException e) {
                throw urlError(op, o.url, "net/http: HTTP/1.x transport connection broken: malformed HTTP status code " + GoText.quote(sp[1]), false);
            }
            if (code >= 100 && code < 200 && code != 101) {
                while (true) {
                    String l = readLine(in, headerBudget);
                    if (l == null || l.isEmpty()) {
                        break;
                    }
                }
                continue;
            }
            break;
        }
        String[] sp = status.split(" ", 3);
        int code = Integer.parseInt(sp[1]);
        String statusText = status.substring(status.indexOf(' ') + 1).replaceFirst("^ +", "");
        String reason = statusText.startsWith(code + " ") ? statusText.substring(4) : statusText;
        StringBuilder rawResp = new StringBuilder(sp[0]).append(String.format(" %03d ", code)).append(Util.latin1ToUtf8(reason)).append("\r\n");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Received rec = new Received();
        while (true) {
            String l = readLine(in, headerBudget);
            if (l == null || l.isEmpty()) {
                break;
            }
            int c = l.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String k = Util.canonicalHeaderKey(l.substring(0, c));
            String raw = trimString(l.substring(c + 1));
            if (k.equals("Location") && !headers.containsKey(k)) {
                rec.location = raw;
            }
            headers.computeIfAbsent(k, x -> new ArrayList<>()).add(Util.latin1ToUtf8(raw));
        }
        Response resp = new Response();
        rec.resp = resp;
        resp.request = req;
        resp.statusCode = code;
        resp.headers = headers;
        resp.contentType = resp.header("Content-Type");
        resp.durationNanos = firstByte;
        boolean noBody = method.equals("HEAD") || code == 204 || code == 304;
        boolean chunked = !noBody && resp.header("Transfer-Encoding").equalsIgnoreCase("chunked");
        String clHeader = resp.header("Content-Length");
        byte[] body;
        boolean bodyError = false;
        try {
            if (noBody) {
                body = new byte[0];
            } else if (chunked) {
                headers.remove("Transfer-Encoding");
                headers.remove("Content-Length");
                body = readChunked(in);
            } else if (!clHeader.isEmpty()) {
                long want = Math.min(Long.parseLong(clHeader.trim()), MAX_DOWNLOAD_SIZE + 1L);
                body = in.readNBytes((int) want);
                bodyError = body.length < want;
            } else {
                body = in.readNBytes(MAX_DOWNLOAD_SIZE + 1);
            }
        } catch (IOException e) {
            body = new byte[0];
            bodyError = true;
        }
        if (autoGzip && resp.header("Content-Encoding").equalsIgnoreCase("gzip")) {
            headers.remove("Content-Encoding");
            headers.remove("Content-Length");
            if (!bodyError) {
                try {
                    body = new GZIPInputStream(new ByteArrayInputStream(body)).readNBytes(MAX_DOWNLOAD_SIZE + 1);
                } catch (IOException e) {
                    body = new byte[0];
                    bodyError = true;
                }
            }
        }
        if (chunked) {
            rawResp.append("Transfer-Encoding: chunked\r\n");
        }
        boolean zeroLength = resp.header("Content-Length").equals("0") && (method.equals("GET") || method.equals("HEAD"));
        if (headers.containsKey("Content-Length") && !zeroLength) {
            rawResp.append("Content-Length: ").append(resp.header("Content-Length")).append("\r\n");
        }
        for (Map.Entry<String, List<String>> h : new TreeMap<>(headers).entrySet()) {
            if (h.getKey().equals("Content-Length")) {
                continue;
            }
            for (String v : h.getValue()) {
                rawResp.append(h.getKey()).append(": ").append(v).append("\r\n");
            }
        }
        // Response.Write adds a zero length after the other headers when it did not send one with them.
        boolean bodyAllowed = !(code >= 100 && code <= 199) && code != 204 && code != 304;
        if (zeroLength && !chunked && bodyAllowed) {
            rawResp.append("Content-Length: 0\r\n");
        }
        rawResp.append("\r\n");
        String cl = resp.header("Content-Length");
        if (!cl.isEmpty()) {
            try {
                resp.contentLength = Long.parseLong(cl);
            } catch (NumberFormatException e) {
                resp.contentLength = 0;
            }
            if (resp.contentLength > MAX_DOWNLOAD_SIZE) {
                resp.cancelled = true;
                return rec;
            }
        }
        if (config.ignoreBody) {
            resp.cancelled = true;
            return rec;
        }
        if (chunked) {
            String b = io.github.ffuf.pkg.ffuf.GoString.decode(body);
            resp.raw = rawResp + (body.length > 0 ? Integer.toHexString(body.length) + "\r\n" + b + "\r\n" : "") + "0\r\n\r\n";
        } else {
            resp.raw = rawResp + io.github.ffuf.pkg.ffuf.GoString.decode(body);
        }
        byte[] decoded = bodyError ? null : decode(resp.header("Content-Encoding"), body);
        if (decoded != null) {
            if (decoded.length > MAX_DOWNLOAD_SIZE) {
                resp.raw = "";
                resp.cancelled = true;
                return rec;
            }
            resp.contentLength = decoded.length;
            resp.data = decoded;
        }
        resp.contentWords = countByte(resp.data, (byte) ' ') + 1;
        resp.contentLines = countByte(resp.data, (byte) '\n') + 1;
        return rec;
    }

    /** ffuf's own Content-Encoding decoding; null when reading the decoded body fails. */
    private static byte[] decode(String encoding, byte[] body) {
        try {
            return switch (encoding) {
                case "gzip" -> body.length < 10 || (body[0] & 0xff) != 0x1f || (body[1] & 0xff) != 0x8b ? body
                        : new GZIPInputStream(new ByteArrayInputStream(body)).readNBytes(MAX_DOWNLOAD_SIZE + 1);
                case "br" -> new BrotliInputStream(new ByteArrayInputStream(body)).readNBytes(MAX_DOWNLOAD_SIZE + 1);
                case "deflate" -> new InflaterInputStream(new ByteArrayInputStream(body), new Inflater(true)).readNBytes(MAX_DOWNLOAD_SIZE + 1);
                default -> body;
            };
        } catch (IOException e) {
            return null;
        }
    }

    private static long countByte(byte[] data, byte b) {
        long c = 0;
        for (byte x : data) {
            if (x == b) {
                c++;
            }
        }
        return c;
    }

    /** The chunked body as Go's chunkedReader reads it; malformed framing is an IOException. */
    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new IOException("unexpected EOF");
            }
            int semi = line.indexOf(';');
            String sz = (semi >= 0 ? line.substring(0, semi) : line).replaceFirst("[ \t]+$", "");
            if (sz.isEmpty() || sz.length() > 16 || !sz.chars().allMatch(c -> Character.digit(c, 16) >= 0 && c < 0x80)) {
                throw new IOException("invalid byte in chunk length");
            }
            long n = Long.parseUnsignedLong(sz, 16);
            if (n == 0) {
                while (true) {
                    String l = readLine(in);
                    if (l == null || l.isEmpty()) {
                        break;
                    }
                }
                return out.toByteArray();
            }
            if (out.size() + n > MAX_DOWNLOAD_SIZE + 1L) {
                out.write(in.readNBytes(MAX_DOWNLOAD_SIZE + 1 - out.size()));
                return out.toByteArray();
            }
            byte[] chunk = in.readNBytes((int) n);
            if (chunk.length < n) {
                throw new IOException("unexpected EOF");
            }
            out.write(chunk);
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("malformed chunked encoding");
            }
        }
    }
}
