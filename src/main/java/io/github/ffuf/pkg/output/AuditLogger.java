package io.github.ffuf.pkg.output;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Json;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Appends one JSON record per configuration, request and response to the -audit-log file. */
public class AuditLogger implements AutoCloseable {
    private final FileOutputStream file;

    /**
     * Opens the audit log for appending.
     *
     * @throws IOException when the file cannot be opened
     */
    public AuditLogger(String filename) throws IOException {
        try {
            this.file = new FileOutputStream(filename, true);
        } catch (IOException e) {
            throw new IOException("open " + filename + ": no such file or directory", e);
        }
    }

    /**
     * Writes one record of data, typed by its kind as "*ffuf.Config", "*ffuf.Request" or "*ffuf.Response". Only the configuration, a
     * request and a response can be serialized.
     *
     * @throws IOException when the write fails, or with json.Marshal's unsupported-type error for any other data
     */
    public synchronized void write(Object data) throws IOException {
        if (data instanceof Config c) {
            writeConfig(c);
        } else if (data instanceof Request r) {
            writeRequest(r);
        } else if (data instanceof Response r) {
            writeResponse(r);
        } else {
            throw new IOException("could not marshal json data: json: unsupported type: " + (data == null ? "<nil>" : data.getClass().getName()));
        }
    }

    /**
     * Writes the configuration record.
     *
     * @throws IOException when the write fails
     */
    public synchronized void writeConfig(Config conf) throws IOException {
        write("*ffuf.Config", conf.toJson());
    }

    /**
     * Writes a request record.
     *
     * @throws IOException when the write fails
     */
    public synchronized void writeRequest(Request req) throws IOException {
        write("*ffuf.Request", requestJson(req));
    }

    /**
     * Writes a response record.
     *
     * @throws IOException when the write fails
     */
    public synchronized void writeResponse(Response resp) throws IOException {
        Json.Obj o = new Json.Obj();
        o.put("StatusCode", resp.statusCode);
        o.put("Headers", new TreeMap<>(resp.headers));
        o.put("Data", resp.data);
        o.put("ContentLength", resp.contentLength);
        o.put("ContentWords", resp.contentWords);
        o.put("ContentLines", resp.contentLines);
        o.put("ContentType", resp.contentType);
        o.put("Cancelled", resp.cancelled);
        o.put("Request", requestJson(resp.request));
        o.put("Raw", resp.raw);
        o.put("ResultFile", resp.resultFile);
        o.put("ScraperData", resp.scraperData);
        o.put("Duration", resp.durationNanos);
        o.put("Timestamp", goTime(resp.request.timestamp == null ? null : resp.request.timestamp.plusNanos(resp.durationNanos)));
        write("*ffuf.Response", o);
    }

    private static Json.Obj requestJson(Request req) {
        Json.Obj o = new Json.Obj();
        o.put("Method", req.method);
        o.put("Host", req.host);
        o.put("Url", req.url);
        o.put("Headers", new TreeMap<String, Object>(req.headers));
        o.put("Data", req.data);
        Map<String, Object> in = new TreeMap<>(req.input);
        o.put("Input", in.isEmpty() ? null : in);
        o.put("Position", req.position);
        o.put("Raw", req.raw);
        o.put("Error", req.error);
        o.put("Timestamp", goTime(req.timestamp));
        return o;
    }

    /** A time as Go's JSON encoding writes it: RFC 3339 with nanoseconds, in the local zone; null is the zero time. */
    static String goTime(java.time.Instant t) {
        if (t == null) {
            return "0001-01-01T00:00:00Z";
        }
        java.time.ZonedDateTime z = t.atZone(java.time.ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder(z.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")));
        if (z.getNano() != 0) {
            sb.append('.').append(String.format("%09d", z.getNano()).replaceAll("0+$", ""));
        }
        int offset = z.getOffset().getTotalSeconds();
        sb.append(offset == 0 ? "Z" : z.getOffset().getId());
        return sb.toString();
    }

    private void write(String type, Json.Obj data) throws IOException {
        Json.Obj d = new Json.Obj().put("Type", type).put("Data", data);
        try {
            file.write((d + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("could not write json data to audit log: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
