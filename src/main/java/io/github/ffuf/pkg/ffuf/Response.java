package io.github.ffuf.pkg.ffuf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A received HTTP response and the counts ffuf derives from it. */
public class Response {
    public long statusCode;
    public Map<String, List<String>> headers = new LinkedHashMap<>();
    public byte[] data = new byte[0];
    public long contentLength;
    public long contentWords;
    public long contentLines;
    public String contentType = "";
    public boolean cancelled;
    public Request request;
    public String raw = "";
    public String resultFile = "";
    public Map<String, List<String>> scraperData = new LinkedHashMap<>();
    public long durationNanos;

    /** Creates an empty response record. */
    public Response() {
        // Fields start at their declared defaults.
    }

    /** {@return the first value of the header, or an empty string} */
    public String header(String name) {
        List<String> v = headers.get(name);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    /** {@return the Location header of a 3xx response, or an empty string} */
    public String redirectLocation() {
        if (statusCode >= 300 && statusCode <= 399) {
            List<String> loc = headers.get("Location");
            if (loc != null && !loc.isEmpty()) {
                return loc.get(0);
            }
        }
        return "";
    }
}
