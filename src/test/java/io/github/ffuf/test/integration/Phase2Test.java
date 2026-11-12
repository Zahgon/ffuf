package io.github.ffuf.test.integration;

import static io.github.ffuf.test.integration.HarnessTest.assertSet;
import static io.github.ffuf.test.integration.HarnessTest.recordedPaths;
import static io.github.ffuf.test.integration.HarnessTest.runScan;
import static io.github.ffuf.test.integration.HarnessTest.writeWordlist;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.testtarget.Target;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase2Test {
    @TempDir
    Path tmp;
    private Target target;

    @BeforeEach
    void start() throws Exception {
        target = Target.start();
    }

    @AfterEach
    void stop() throws Exception {
        target.close();
    }

    private boolean anyRequest(Predicate<Target.Recorded> pred) {
        return target.requests().stream().anyMatch(pred);
    }

    @Test
    void testRequestHeaderSent() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/needs-header?p=FUZZ", List.of("h"), o -> o.http.headers = List.of("X-Test: yes"),
                mm -> mm.addMatcher("status", "200"));
        assertSet(List.of("h"), got);
        assertTrue(anyRequest(r -> r.headerValue("X-Test").equals("yes")), "the -H header was sent on the wire");
    }

    @Test
    void testRequestHeaderMissingDoesNotMatch() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/needs-header?p=FUZZ", List.of("h"), null, mm -> mm.addMatcher("status", "200"));
        assertSet(List.of(), got);
        assertTrue(target.count() > 0, "engine sent a request, so the empty match set means a 403");
    }

    @Test
    void testRequestCookieSent() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/needs-cookie?p=FUZZ", List.of("c"), o -> o.http.cookies = List.of("SESSION=abc"),
                mm -> mm.addMatcher("status", "200"));
        assertSet(List.of("c"), got);
        assertTrue(anyRequest(r -> r.headerValue("Cookie").contains("SESSION=abc")), "the -b cookie SESSION=abc was sent on the wire");
    }

    @Test
    void testRequestMethod() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/needs-method?p=FUZZ", List.of("m"), o -> o.http.method = "POST", mm -> mm.addMatcher("status", "200"));
        assertSet(List.of("m"), got);
        assertTrue(anyRequest(r -> r.method().equals("POST")), "the -X method was sent as POST");
    }

    @Test
    void testRequestBody() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/needs-body?p=FUZZ", List.of("b"), o -> {
            o.http.method = "POST";
            o.http.data = "token=FUZZ";
        }, mm -> mm.addMatcher("status", "200"));
        assertSet(List.of("b"), got);
        assertTrue(anyRequest(r -> r.body().equals("token=b")), "the -d body was sent with FUZZ substituted, want \"token=b\"");
    }

    @Test
    void testFollowRedirects() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/redirect/FUZZ", List.of("3"), o -> o.http.followRedirects = true,
                mm -> mm.addMatcher("status", "200"));
        assertSet(List.of("3"), got);
        assertTrue(recordedPaths(target.requests()).containsAll(List.of("/redirect/3", "/redirect/2", "/redirect/1", "/redirect/0")),
                "the whole redirect chain was walked: " + recordedPaths(target.requests()));
    }

    @Test
    void testFilterExcludesStatuses() throws Exception {
        List<String> got = runScan(tmp, target.url() + "/status/FUZZ", List.of("200", "404", "500"), null, mm -> {
            mm.addMatcher("status", "all");
            mm.addFilter("status", "404,500", false);
        });
        assertSet(List.of("200"), got);
    }

    @Test
    void testInputModeClusterbomb() throws Exception {
        runScan(tmp, target.url() + "/reflect/KEY1-KEY2", List.of(), o -> {
            o.input.inputMode = "clusterbomb";
            o.input.wordlists = List.of(wordlist(List.of("a", "b")) + ":KEY1", wordlist(List.of("1", "2")) + ":KEY2");
        }, mm -> mm.addMatcher("status", "all"));
        assertSet(List.of("/reflect/a-1", "/reflect/a-2", "/reflect/b-1", "/reflect/b-2"), recordedPaths(target.requests()));
        assertEquals(4, target.count(), "clusterbomb 2x2 requests");
    }

    @Test
    void testInputModePitchfork() throws Exception {
        runScan(tmp, target.url() + "/reflect/KEY1-KEY2", List.of(), o -> {
            o.input.inputMode = "pitchfork";
            o.input.wordlists = List.of(wordlist(List.of("a", "b")) + ":KEY1", wordlist(List.of("1", "2")) + ":KEY2");
        }, mm -> mm.addMatcher("status", "all"));
        assertSet(List.of("/reflect/a-1", "/reflect/b-2"), recordedPaths(target.requests()));
        assertEquals(2, target.count(), "pitchfork requests");
    }

    private String wordlist(List<String> words) {
        try {
            return writeWordlist(tmp, words);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
