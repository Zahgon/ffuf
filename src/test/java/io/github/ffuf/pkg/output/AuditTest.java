package io.github.ffuf.pkg.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditTest {
    @TempDir
    Path tmp;

    @Test
    void testAuditLogger() throws IOException {
        Path file = Files.createTempFile(tmp, "prefix", "");
        AuditLogger audit = new AuditLogger(file.toString());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        // A superuser can still open a mode-000 file, so the check only applies where the permission takes effect.
        if (!Files.isWritable(file)) {
            assertThrows(IOException.class, () -> new AuditLogger(file.toString()), "opening an unwritable audit log");
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r-xr-x---"));
        audit.close();
        Files.delete(file);
    }

    /**
     * AuditLogger serializes the configuration and requests the program hands it, which are references, so records
     * are typed "*ffuf.Config" and "*ffuf.Request"; the configuration record is the configuration's toJson().
     */
    @Test
    void testAuditLogWrite() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("foo", "bar");
        headers.put("baz", "wibble");
        headers.put("Content-Type", "application/json");
        String data = "{\"quote\":\"I'll still be here tomorrow to high five you yesterday, my friend. Peace.\"}";
        Request req = new Request();
        req.method = "POST";
        req.url = "http://example.com/aaaa";
        req.headers = headers;
        req.data = data.getBytes(StandardCharsets.UTF_8);
        Config config = new Config();
        config.method = "POST";
        config.url = "http://example.com/aaaa";
        config.headers = headers;
        config.data = data;

        Path file = Files.createTempFile(tmp, "prefix", "");
        AuditLogger audit = new AuditLogger(file.toString());
        assertThrows(IOException.class, () -> audit.write(new Object()), "logging an object that cannot be serialized");
        audit.write(config);
        audit.write(req);
        audit.close();

        String expected = "{\"Type\":\"*ffuf.Config\",\"Data\":" + config.toJson() + "}\n"
                + "{\"Type\":\"*ffuf.Request\",\"Data\":{\"Method\":\"POST\",\"Host\":\"\",\"Url\":\"http://example.com/aaaa\","
                + "\"Headers\":{\"Content-Type\":\"application/json\",\"baz\":\"wibble\",\"foo\":\"bar\"},"
                + "\"Data\":\"eyJxdW90ZSI6IkknbGwgc3RpbGwgYmUgaGVyZSB0b21vcnJvdyB0byBoaWdoIGZpdmUgeW91IHllc3RlcmRheSwgbXkgZnJpZW5kLiBQZWFjZS4ifQ==\","
                + "\"Input\":null,\"Position\":0,\"Raw\":\"\",\"Error\":\"\",\"Timestamp\":\"0001-01-01T00:00:00Z\"}}\n";
        assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8), "audit log");

        assertThrows(IOException.class, () -> audit.write(config), "writing to a closed audit log");
    }

    /** Times are written as Go's JSON encoding writes them: RFC 3339 with trimmed nanoseconds. */
    @Test
    void testGoTime() {
        java.util.TimeZone old = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
            assertEquals("2026-11-12T10:49:54.8355+05:30", AuditLogger.goTime(java.time.Instant.parse("2026-11-12T05:19:54.835500Z")));
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            assertEquals("2026-11-12T05:19:54Z", AuditLogger.goTime(java.time.Instant.parse("2026-11-12T05:19:54Z")));
        } finally {
            java.util.TimeZone.setDefault(old);
        }
    }
}
