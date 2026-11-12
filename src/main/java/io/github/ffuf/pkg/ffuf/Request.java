package io.github.ffuf.pkg.ffuf;

import java.util.LinkedHashMap;
import java.util.Map;

/** One HTTP request ffuf sends, before and after keyword substitution. */
public class Request {
    public String method = "";
    public String host = "";
    public String url = "";
    public Map<String, String> headers = new LinkedHashMap<>();
    public byte[] data = new byte[0];
    public Map<String, byte[]> input = new LinkedHashMap<>();
    public int position;
    public String raw = "";
    public String error = "";
    /** When the request was sent; null is Go's zero time. */
    public java.time.Instant timestamp;

    /** {@return a copy with its own header, body and input maps; error and timestamp are not copied} */
    public Request copy() {
        Request r = new Request();
        r.method = method;
        r.host = host;
        r.url = url;
        r.headers = new LinkedHashMap<>(headers);
        r.data = data.clone();
        r.input = new LinkedHashMap<>(input);
        r.position = position;
        r.raw = raw;
        return r;
    }

    /** {@return the request the configuration describes before any keyword is replaced} */
    public static Request base(Config conf) {
        Request r = new Request();
        r.method = conf.method;
        r.url = conf.url;
        r.headers = new LinkedHashMap<>(conf.headers);
        r.data = conf.data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return r;
    }

    /** {@return true when the host, method, URL, body or a header contains the keyword} */
    public boolean containsKeyword(String kw) {
        if (host.contains(kw) || method.contains(kw) || url.contains(kw) || new String(data, java.nio.charset.StandardCharsets.UTF_8).contains(kw)) {
            return true;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().contains(kw) || e.getValue().contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
