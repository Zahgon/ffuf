package io.github.ffuf.pkg.runner;

import static io.github.ffuf.pkg.runner.PreflightTest.close;
import static io.github.ffuf.pkg.runner.PreflightTest.flight;
import static io.github.ffuf.pkg.runner.PreflightTest.flights;
import static io.github.ffuf.pkg.runner.PreflightTest.hostPort;
import static io.github.ffuf.pkg.runner.PreflightTest.newTestConfig;
import static io.github.ffuf.pkg.runner.PreflightTest.send;
import static io.github.ffuf.pkg.runner.PreflightTest.serve;
import static io.github.ffuf.pkg.runner.PreflightTest.url;
import static io.github.ffuf.pkg.runner.PreflightTest.writeTempRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.PreflightConfig;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.VarExtract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlightvarsTest {
    static final String FLIGHT_VARS_PAGE = "<!doctype html><html><head>\n"
            + "<meta name=\"CSRF-Token\" content=\"meta-tok\">\n"
            + "<meta name=\"shared\" content=\"same\">\n"
            + "</head><body>\n"
            + "<form method=\"POST\">\n"
            + "<input type=\"hidden\" name=\"csrf_token\" value=\"a&amp;b+c/d=\">\n"
            + "<input type=\"hidden\" name=\"data[_Token][key]\" value=\"php-tok\">\n"
            + "<input value=\"attr-first\" name=\"loginForm:token\" type=\"hidden\">\n"
            + "<input type=\"checkbox\" name=\"remember\" value=\"unchecked-box\">\n"
            + "<input type=\"checkbox\" name=\"remember\" value=\"checked-box\" checked>\n"
            + "<textarea name=\"note\">text &lt;area&gt;</textarea>\n"
            + "<select name=\"lang\"><option value=\"en\">English</option><option value=\"fi\" selected>Finnish</option></select>\n"
            + "<select name=\"plain\"><option>First</option><option>Second</option></select>\n"
            + "<input type=\"hidden\" name=\"dup\" value=\"form-dup\">\n"
            + "<input type=\"hidden\" name=\"shared\" value=\"same\">\n"
            + "<input type=\"hidden\" name=\"evil\" value=\"x&#13;&#10;Injected: 1\">\n"
            + "</form></body></html>";

    @TempDir
    Path tmp;
    private HttpServer srv;

    /** Serves the page with a mix of cookies and headers; /noform serves the same headers without the HTML. */
    @BeforeEach
    void start() throws IOException {
        srv = serve(ex -> {
            Headers h = ex.getResponseHeaders();
            h.add("Set-Cookie", "sessionid=sess-1; Path=/; HttpOnly");
            h.add("Set-Cookie", "XSRF-TOKEN=old; Path=/");
            h.add("Set-Cookie", "XSRF-TOKEN=tok%3D%3D; Path=/");
            h.add("Set-Cookie", "gone=bye; Max-Age=0");
            h.add("Set-Cookie", "dup=cookie-dup; Path=/");
            h.set("X-Csrf-Token", "hdr-tok");
            if (ex.getRequestURI().getPath().equals("/noform")) {
                h.set("Csrf_Token", "hdr-for-pin-test");
                send(ex, 200, "");
                return;
            }
            send(ex, 200, FLIGHT_VARS_PAGE);
        });
    }

    @AfterEach
    void stop() {
        close(srv);
    }

    static VarExtract byName(String name, String source, String key) {
        VarExtract v = new VarExtract();
        v.name = name;
        v.source = source;
        v.key = key;
        return v;
    }

    /** Runs a one-var preflight chain against srv and returns the value. */
    static String runVar(Flights f, Path dir, HttpServer srv, String path, VarExtract ve) throws Exception {
        PreflightConfig pf = flight(writeTempRequest(dir, "GET " + path + " HTTP/1.1\nHost: " + hostPort(srv) + "\n\n"), ve);
        return f.runChain(List.of(pf), Map.of(), "preflight").get(ve.name);
    }

    private Flights newFlights() {
        return flights(newTestConfig(url(srv)));
    }

    @Test
    void testFlightVarsPinnedSources() throws Exception {
        String[][] cases = {
            {"form", "csrf_token", "a&b+c/d="},
            {"form", "data[_Token][key]", "php-tok"},
            {"form", "loginForm:token", "attr-first"},
            {"form", "remember", "checked-box"},
            {"form", "note", "text <area>"},
            {"form", "lang", "fi"},
            {"form", "plain", "First"},
            {"meta", "csrf-token", "meta-tok"},
            {"cookie", "sessionid", "sess-1"},
            {"cookie", "XSRF-TOKEN", "tok%3D%3D"},
            {"header", "x-csrf-token", "hdr-tok"},
        };
        for (String[] c : cases) {
            assertEquals(c[2], runVar(newFlights(), tmp, srv, "/", byName("V", c[0], c[1])), c[0] + "/" + c[1]);
        }
    }

    @Test
    void testFlightVarsNotFound() {
        VarExtract[] vars = {byName("V", "form", "nope"), byName("V", "meta", "csrf_token"), byName("V", "cookie", "gone"),
            byName("V", "header", "X-Missing"), byName("V", "auto", "nope")};
        for (VarExtract ve : vars) {
            SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> runVar(newFlights(), tmp, srv, "/", ve));
            assertTrue(e.getMessage().contains("not found"), "[" + ve.source + "]" + ve.key + ": error " + e.getMessage() + ", want not found");
        }
    }

    /** The resolution order is form, meta, cookie, header. */
    @Test
    void testFlightVarsAutoOrder() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("csrf_token", "a&b+c/d=");
        cases.put("csrf-token", "meta-tok");
        cases.put("sessionid", "sess-1");
        cases.put("X-Csrf-Token", "hdr-tok");
        cases.put("shared", "same");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            assertEquals(c.getValue(), runVar(newFlights(), tmp, srv, "/", byName("V", "auto", c.getKey())), "auto " + c.getKey());
        }
    }

    /** The same key with different values in two sources is refused with a hint how to pin one. */
    @Test
    void testFlightVarsAutoAmbiguous() {
        SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class,
                () -> runVar(newFlights(), tmp, srv, "/", byName("DUP", "auto", "dup")));
        assertTrue(e.getMessage().contains("pin one with DUP:[form]dup"), "error " + e.getMessage() + ", want a hint DUP:[form]dup");
    }

    /** Once a bare key resolves, its source is pinned: a later response without the form does not fall back to a header. */
    @Test
    void testFlightVarsAutoPinHolds() throws Exception {
        Flights f = newFlights();
        String reqFile = writeTempRequest(tmp, "GET / HTTP/1.1\nHost: " + hostPort(srv) + "\n\n");
        String noForm = writeTempRequest(tmp, "GET /noform HTTP/1.1\nHost: " + hostPort(srv) + "\n\n");
        PreflightConfig pf = flight(reqFile, byName("V", "auto", "csrf_token"));
        List<PreflightConfig> chain = List.of(pf);
        assertEquals("a&b+c/d=", f.runChain(chain, Map.of(), "preflight").get("V"), "first run");
        pf.requestFile = noForm;
        SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> f.runChain(chain, Map.of(), "preflight"));
        assertTrue(e.getMessage().contains("[form]csrf_token not found"), "error " + e.getMessage() + ", want the pinned [form] source enforced");
        assertEquals("hdr-for-pin-test", newFlights().runChain(chain, Map.of(), "preflight").get("V"), "a fresh runner resolves the header");
    }

    /** A value with CR or LF is refused from any source; an entity-encoded newline in a form field decodes to a real one. */
    @Test
    void testFlightVarsControlCharsRefused() throws Exception {
        Config conf = newTestConfig(url(srv));
        Flights f = flights(conf);
        SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> runVar(f, tmp, srv, "/", byName("V", "form", "evil")));
        assertTrue(e.getMessage().contains("control character"), "error " + e.getMessage() + ", want a control character refusal");
        conf.preflightError = "ignore";
        PreflightConfig pf = flight(writeTempRequest(tmp, "GET / HTTP/1.1\nHost: " + hostPort(srv) + "\n\n"), byName("V", "form", "evil"));
        assertFalse(f.runChain(List.of(pf), Map.of(), "preflight").containsKey("V"), "ignore mode: control-character value was still set");
    }

    /** A by-name value is still a captured value: it cannot move a later flight. */
    @Test
    void testFlightVarsCannotChangeDestination() throws Exception {
        HttpServer next = serve(ex -> {
            ex.getResponseHeaders().set("X-Next", "http://attacker.example.invalid/steal");
            send(ex, 200, "");
        });
        try {
            Flights f = PreflightDestinationTest.destFlights(url(next) + "/FUZZ", false);
            List<PreflightConfig> chain = List.of(
                    flight(writeTempRequest(tmp, "GET / HTTP/1.1\nHost: " + hostPort(next) + "\n\n"), byName("NEXT", "header", "X-Next")),
                    flight(writeTempRequest(tmp, "GET NEXT HTTP/1.1\n\n")));
            SimpleRunner.RequestException e = assertThrows(SimpleRunner.RequestException.class, () -> f.runChain(chain, Map.of(), "preflight"));
            assertTrue(e.getMessage().contains("changed the request destination"), "error " + e.getMessage() + ", want the destination guard to refuse");
        } finally {
            close(next);
        }
    }

    private static Flights.FlightResponse flightResponse(String body, Map<String, List<String>> headers) {
        Response r = new Response();
        r.data = body.getBytes(StandardCharsets.UTF_8);
        r.headers = new LinkedHashMap<>(headers);
        return new Flights.FlightResponse(r);
    }

    /**
     * Concurrent first resolutions of one auto var agree on a single pin. Once pinned, a response without the pinned
     * source is "not found"; the test ignores the individual results.
     */
    @Test
    void testFlightVarsAutoPinConcurrent() throws Exception {
        Flights f = flights(newTestConfig("http://unused"));
        VarExtract ve = byName("V", "auto", "tok");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                boolean form = i % 2 == 0;
                Flights.FlightResponse fr = form ? flightResponse("<input name=\"tok\" value=\"same\">", Map.of())
                        : flightResponse("", Map.of("Tok", List.of("same")));
                futures.add(pool.submit(() -> {
                    try {
                        return f.extractByName(ve, fr);
                    } catch (Flights.VarNotFoundException e) {
                        return e.getMessage();
                    }
                }));
            }
            for (Future<?> fu : futures) {
                fu.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        String pinned = f.autoPins.get(ve);
        assertTrue("form".equals(pinned) || "header".equals(pinned), "pin = " + pinned + ", want form or header");
    }

    /** After the pin, a different same-named value in a lower-priority source is not an ambiguity. */
    @Test
    void testFlightVarsAutoPinSkipsAmbiguity() throws Exception {
        Flights f = flights(newTestConfig("http://unused"));
        VarExtract ve = byName("V", "auto", "tok");
        assertEquals("f1", f.extractByName(ve, flightResponse("<input name=\"tok\" value=\"f1\">", Map.of())), "first");
        assertEquals("f2", f.extractByName(ve, flightResponse("<input name=\"tok\" value=\"f2\">", Map.of("Tok", List.of("h2")))), "pinned to [form]");
    }
}
