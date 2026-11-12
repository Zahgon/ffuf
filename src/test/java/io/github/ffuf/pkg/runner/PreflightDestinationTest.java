package io.github.ffuf.pkg.runner;

import static io.github.ffuf.pkg.runner.PreflightTest.writeTempRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Config;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreflightDestinationTest {
    @TempDir
    Path tmp;

    static Flights destFlights(String targetUrl, boolean allowCaptured) {
        Config conf = new Config();
        conf.url = targetUrl;
        conf.timeout = 10;
        conf.preflightAnyHost = allowCaptured;
        return new Flights(conf, new SimpleRunner(conf), s -> { });
    }

    /** A captured value that moves a flight to a host the operator did not write is refused. */
    @Test
    void testPreflight_CapturedValueCannotChangeDestination() {
        String[][] cases = {
            {"captured absolute URL in the request line", "GET NEXT HTTP/1.1\n\n", "NEXT", "http://attacker.example.invalid/steal"},
            {"captured host in the Host header, relative path", "GET /steal HTTP/1.1\nHost: NEXTHOST\n\n", "NEXTHOST", "attacker.example.invalid"},
            {"captured value changes the port only", "GET /steal HTTP/1.1\nHost: NEXTHOST\n\n", "NEXTHOST", "target.example.com:9999"},
            {"captured value downgrades the scheme", "GET NEXT HTTP/1.1\n\n", "NEXT", "http://target.example.com/steal"},
        };
        for (String[] c : cases) {
            Flights f = destFlights("https://target.example.com/FUZZ", false);
            SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> f.build(writeTempRequest(tmp, c[1]), Map.of(c[2], c[3])),
                    c[0] + ": the request was built");
            assertTrue(e.getMessage().contains("changed the request destination"), c[0] + ": unexpected error " + e.getMessage());
        }
    }

    /** The check is scoped to captured values: destinations the operator wrote still work. */
    @Test
    void testPreflight_AuthoredDestinationsStillWork() throws Exception {
        String[][] cases = {
            {"operator writes an absolute URL to a different host", "POST https://login.example.com/token HTTP/1.1\n\n", "https://login.example.com/token"},
            {"operator writes a different host in the Host header", "POST /token HTTP/1.1\nHost: login.example.com\n\n", "https://login.example.com/token"},
            {"captured value lands in the path, destination unchanged", "GET /api/TOKEN/profile HTTP/1.1\n\n", "https://target.example.com/api/abc123/profile"},
            {"captured value lands in a header, destination unchanged", "GET /api/profile HTTP/1.1\nX-Csrf-Token: TOKEN\n\n", "https://target.example.com/api/profile"},
            {"captured value lands in the body, destination unchanged", "POST /api/login HTTP/1.1\n\ncsrf=TOKEN", "https://target.example.com/api/login"},
        };
        for (String[] c : cases) {
            Flights f = destFlights("https://target.example.com/FUZZ", false);
            assertEquals(c[2], f.build(writeTempRequest(tmp, c[1]), Map.of("TOKEN", "abc123")).url, c[0]);
        }
    }

    /** -preflight-anyhost lets the target name the next host, as discovery-driven flows need. */
    @Test
    void testPreflight_AllowCapturedHostOptsBackIn() throws Exception {
        Flights f = destFlights("https://target.example.com/FUZZ", true);
        assertEquals("https://login.example.com/oauth2/token",
                f.build(writeTempRequest(tmp, "POST NEXT HTTP/1.1\n\n"), Map.of("NEXT", "https://login.example.com/oauth2/token")).url, "URL");
    }

    @Test
    void testPreflight_NoVarsIsUnaffected() throws Exception {
        Flights f = destFlights("https://target.example.com/FUZZ", false);
        assertEquals("https://target.example.com/login", f.build(writeTempRequest(tmp, "GET /login HTTP/1.1\n\n"), Map.of()).url, "URL");
    }
}
