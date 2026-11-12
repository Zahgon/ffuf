package io.github.ffuf.pkg.testtarget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerTest {
    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }

    @Test
    void testBodyHelpers() {
        for (int n : new int[] {0, 1, 37, 100}) {
            assertEquals(n, Target.filled(n).length(), "filled(" + n + ") length");
        }
        for (int n : new int[] {1, 5, 10}) {
            assertEquals(n - 1, count(Target.words(n), ' '), "words(" + n + ") spaces");
        }
        for (int n : new int[] {1, 3, 5}) {
            assertEquals(n - 1, count(Target.lines(n), '\n'), "lines(" + n + ") newlines");
        }
    }

    @Test
    void testEndpoints() throws Exception {
        try (Target tt = Target.start()) {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
            Object[][] cases = {
                {"/status/418", 418},
                {"/size/50", 200},
                {"/map/ok", 200},
                {"/map/bad", 500},
                {"/needs-header", 403},
                {"/rdir", 301},
                {"/nope", 404},
            };
            for (Object[] c : cases) {
                HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(tt.url() + c[0])).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(c[1], resp.statusCode(), "GET " + c[0] + " status");
            }
            HttpResponse<byte[]> sized = client.send(HttpRequest.newBuilder(URI.create(tt.url() + "/size/50")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(50, sized.body().length, "/size/50 body length");
            HttpResponse<String> gated = client.send(HttpRequest.newBuilder(URI.create(tt.url() + "/needs-header")).header("X-Test", "yes").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, gated.statusCode(), "/needs-header with X-Test status");
            assertTrue(tt.count() > 0, "recorder captured requests, want more than 0");
        }
    }
}
