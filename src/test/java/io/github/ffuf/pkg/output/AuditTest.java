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
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
            // Like the Go test, this needs an unprivileged user: root opens a mode-000 file anyway, so there the
            // reopen is not an error in Go or in Java and only the remaining steps are checked.
            if (!Files.isWritable(file)) {
                assertThrows(IOException.class, () -> new AuditLogger(file.toString()), "opening an unwritable audit log");
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r-xr-x---"));
        } finally {
            audit.close();
        }
        Files.delete(file);
    }

    /**
     * TestAuditLogWrite with the same headers, data, method and URL. The records are the ones the program writes: it
     * hands the audit logger references, so they are typed "*ffuf.Config" and "*ffuf.Request" and the delay uses its
     * {"value": ...} encoding, and a configuration always carries the defaults NewConfig gives it; the Go test encodes
     * a zero-valued ffuf.Config struct instead. The request record is the Go test's text apart from the type.
     */
    @Test
    void testAuditLogWrite() throws IOException {
        String expected = "{\"Type\":\"*ffuf.Config\",\"Data\":{\"auditlog\":\"\",\"autocalibration\":false,"
                + "\"autocalibration_keyword\":\"FUZZ\",\"autocalibration_perhost\":false,\"autocalibration_strategies\":[],"
                + "\"autocalibration_strings\":[],\"colors\":false,\"cmdline\":\"\",\"configfile\":\"\","
                + "\"postdata\":\"{\\\"quote\\\":\\\"I'll still be here tomorrow to high five you yesterday,"
                + " my friend. Peace.\\\"}\",\"debuglog\":\"\",\"delay\":{\"value\":\"0.00\"},\"dirsearch_compatibility\":false,"
                + "\"encoders\":[],\"extensions\":[],\"fmode\":\"or\",\"follow_redirects\":false,"
                + "\"headers\":{\"Content-Type\":\"application/json\",\"baz\":\"wibble\",\"foo\":\"bar\"},\"ignorebody\":false,"
                + "\"ignore_wordlist_comments\":false,\"inputmode\":\"clusterbomb\",\"cmd_inputnum\":0,\"inputproviders\":[],"
                + "\"inputshell\":\"\",\"json\":false,\"matchers\":null,\"mmode\":\"or\",\"maxtime\":0,\"maxtime_job\":0,"
                + "\"method\":\"POST\",\"noninteractive\":false,\"outputdirectory\":\"\",\"outputfile\":\"\",\"outputformat\":\"\","
                + "\"OutputSkipEmptyFile\":false,\"proxyurl\":\"\",\"quiet\":false,\"rate\":0,\"raw\":false,\"recursion\":false,"
                + "\"recursion_depth\":0,\"recursion_strategy\":\"default\",\"replayproxyurl\":\"\",\"requestfile\":\"\","
                + "\"requestproto\":\"https\",\"scraperfile\":\"\",\"scrapers\":\"all\",\"sni\":\"\",\"stop_403\":false,\"stop_all\":false,"
                + "\"stop_errors\":false,\"threads\":0,\"timeout\":0,\"url\":\"http://example.com/aaaa\",\"verbose\":false,"
                + "\"wordlists\":[],\"http2\":false,\"client-cert\":\"\",\"client-key\":\"\",\"preflights\":[],\"postflights\":[],"
                + "\"preflight_mode\":\"per-request\",\"preflight_error\":\"abort\",\"preflight_anyhost\":false}}"
                + "\n"
                + "{\"Type\":\"*ffuf.Request\",\"Data\":{\"Method\":\"POST\",\"Host\":\"\",\"Url\":\"http://example.com/aaaa\","
                + "\"Headers\":{\"Content-Type\":\"application/json\",\"baz\":\"wibble\",\"foo\":\"bar\"},"
                + "\"Data\":\"eyJxdW90ZSI6IkknbGwgc3RpbGwgYmUgaGVyZSB0b21vcnJvdyB0byBoaWdoIGZpdmUgeW91IHllc3RlcmRheSwgbXkgZnJpZW5kLiBQZWFjZS4ifQ==\","
                + "\"Input\":null,\"Position\":0,\"Raw\":\"\",\"Error\":\"\",\"Timestamp\":\"0001-01-01T00:00:00Z\"}}"
                + "\n";

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
        try {
            assertThrows(IOException.class, () -> audit.write(new Object()), "logging an object that cannot be serialized");
            audit.write(config);
            audit.write(req);
        } finally {
            audit.close();
        }
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
