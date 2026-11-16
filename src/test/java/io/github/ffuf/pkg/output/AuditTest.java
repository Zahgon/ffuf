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
                assertThrows(IOException.class, () -> new AuditLogger(file.toString()).close(), "opening an unwritable audit log");
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r-xr-x---"));
        } finally {
            audit.close();
        }
        Files.delete(file);
    }

    /**
     * TestAuditLogWrite: the same zero-valued configuration (only method, URL, headers and data set) and request, and
     * the Go test's expected log. The program hands the audit logger references, so the two records are typed
     * "*ffuf.Config" and "*ffuf.Request" and the delay is written by its own JSON encoding ({"value":"0.00"}); the Go
     * test passes values, which Go types without the "*" and encodes field by field. Everything else is the Go text.
     */
    @Test
    void testAuditLogWrite() throws IOException {
        String expected = "{\"Type\":\"*ffuf.Config\",\"Data\":{\"auditlog\":\"\",\"autocalibration\":false,\"autocalibration_keyword\":\"\","
                + "\"autocalibration_perhost\":false,\"autocalibration_strategies\":null,\"autocalibration_strings\":null,"
                + "\"colors\":false,\"cmdline\":\"\",\"configfile\":\"\","
                + "\"postdata\":\"{\\\"quote\\\":\\\"I'll still be here tomorrow to high five you yesterday,"
                + " my friend. Peace.\\\"}\",\"debuglog\":\"\",\"delay\":{\"value\":\"0.00\"},\"dirsearch_compatibility\":false,"
                + "\"encoders\":null,\"extensions\":null,\"fmode\":\"\",\"follow_redirects\":false,"
                + "\"headers\":{\"Content-Type\":\"application/json\",\"baz\":\"wibble\",\"foo\":\"bar\"},\"ignorebody\":false,"
                + "\"ignore_wordlist_comments\":false,\"inputmode\":\"\",\"cmd_inputnum\":0,\"inputproviders\":null,"
                + "\"inputshell\":\"\",\"json\":false,\"matchers\":null,\"mmode\":\"\",\"maxtime\":0,\"maxtime_job\":0,\"method\":\"POST\","
                + "\"noninteractive\":false,\"outputdirectory\":\"\",\"outputfile\":\"\",\"outputformat\":\"\","
                + "\"OutputSkipEmptyFile\":false,\"proxyurl\":\"\",\"quiet\":false,\"rate\":0,\"raw\":false,\"recursion\":false,"
                + "\"recursion_depth\":0,\"recursion_strategy\":\"\",\"replayproxyurl\":\"\",\"requestfile\":\"\",\"requestproto\":\"\","
                + "\"scraperfile\":\"\",\"scrapers\":\"\",\"sni\":\"\",\"stop_403\":false,\"stop_all\":false,\"stop_errors\":false,"
                + "\"threads\":0,\"timeout\":0,\"url\":\"http://example.com/aaaa\",\"verbose\":false,\"wordlists\":null,"
                + "\"http2\":false,\"client-cert\":\"\",\"client-key\":\"\",\"preflights\":null,\"postflights\":null,"
                + "\"preflight_mode\":\"\",\"preflight_error\":\"\",\"preflight_anyhost\":false}}"
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
        Config config = zeroConfig();
        config.method = "POST";
        config.url = "http://example.com/aaaa";
        config.headers = headers;
        config.data = data;

        Path file = Files.createTempFile(tmp, "prefix", "");
        AuditLogger audit = new AuditLogger(file.toString());
        try {
            // The Go test logs a cyclic struct, which json.Marshal rejects. The audit logger serializes only the
            // configuration, requests and responses and rejects anything else with json.Marshal's unsupported-type
            // error, so any other object stands for the value that cannot be marshalled.
            assertThrows(IOException.class, () -> audit.write(new Object()), "logging an object that cannot be serialized");
            audit.write(config);
            audit.write(req);
        } finally {
            audit.close();
        }
        assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8), "audit log");

        assertThrows(IOException.class, () -> audit.write(config), "writing to a closed audit log");
    }

    /** A configuration with Go's zero values (empty strings, nil lists) instead of the defaults a new Config carries. */
    private static Config zeroConfig() {
        Config c = new Config();
        c.autoCalibrationKeyword = "";
        c.autoCalibrationStrategies = null;
        c.autoCalibrationStrings = null;
        c.encoders = null;
        c.extensions = null;
        c.filterMode = "";
        c.inputMode = "";
        c.inputProviders = null;
        c.matcherMode = "";
        c.recursionStrategy = "";
        c.requestProto = "";
        c.scrapers = "";
        c.wordlists = null;
        c.preflights = null;
        c.postflights = null;
        c.preflightMode = "";
        c.preflightError = "";
        return c;
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
