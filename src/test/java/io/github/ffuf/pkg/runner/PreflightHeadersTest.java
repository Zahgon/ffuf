package io.github.ffuf.pkg.runner;

import static io.github.ffuf.pkg.runner.PreflightTest.close;
import static io.github.ffuf.pkg.runner.PreflightTest.flights;
import static io.github.ffuf.pkg.runner.PreflightTest.newTestConfig;
import static io.github.ffuf.pkg.runner.PreflightTest.regexVar;
import static io.github.ffuf.pkg.runner.PreflightTest.send;
import static io.github.ffuf.pkg.runner.PreflightTest.serve;
import static io.github.ffuf.pkg.runner.PreflightTest.url;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreflightHeadersTest {
    @TempDir
    Path tmp;
    private HttpServer srv;

    /** A body with a token and headers whose values a regex could also match. */
    @BeforeEach
    void start() throws IOException {
        srv = serve(ex -> {
            Headers h = ex.getResponseHeaders();
            h.set("X-Token", "token=hdr1");
            h.set("Location", "/cb?code=abc123&state=xyz");
            h.add("Set-Cookie", "sessionid=sess-1; Path=/");
            h.add("Set-Cookie", "XSRF-TOKEN=xsrf-2; Path=/");
            send(ex, 200, "<p>token=body1</p>");
        });
    }

    @AfterEach
    void stop() {
        close(srv);
    }

    private String regex(String regex) throws Exception {
        return FlightvarsTest.runVar(flights(newTestConfig(url(srv))), tmp, srv, "/", regexVar("V", regex));
    }

    /** A regex that matches the body captures from the body, even when a header would match too. */
    @Test
    void testPreflightVarBodyWinsOverHeaders() throws Exception {
        assertEquals("body1", regex("token=(\\w+)"), "captured value");
    }

    /** A regex that finds nothing in the body runs against the headers. */
    @Test
    void testPreflightVarFallsBackToHeaders() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("(?im)^location:.*[?&]code=([^&\\r\\n]+)", "abc123");
        cases.put("XSRF-TOKEN=([^;\\r\\n]+)", "xsrf-2");
        cases.put("X-Token: token=(\\w+)", "hdr1");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            assertEquals(c.getValue(), regex(c.getKey()), c.getKey());
        }
    }

    @Test
    void testPreflightVarNoMatchMentionsHeaders() {
        SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> regex("nothere=(\\w+)"));
        assertTrue(e.getMessage().contains("body or headers"), "error " + e.getMessage() + ", want it to say the body and headers were searched");
    }

    /** A regex spanning header lines captures a CRLF, which the control-character check refuses. */
    @Test
    void testPreflightVarHeaderSpanRefused() {
        SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> regex("(?s)Location: (.*)Set-Cookie"));
        assertTrue(e.getMessage().contains("control character"), "error " + e.getMessage() + ", want a control character refusal");
    }
}
