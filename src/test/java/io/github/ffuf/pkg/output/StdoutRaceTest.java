package io.github.ffuf.pkg.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StdoutRaceTest {
    /** Results reported from many threads at once must all be kept. */
    @Test
    void testStdoutput_ConcurrentResult() {
        Stdoutput s = new Stdoutput(new Config());
        int n = 200;
        StdoutTest.captureStdout(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(16);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    Response resp = new Response();
                    resp.statusCode = 200;
                    resp.request = new Request();
                    resp.request.input.put("FUZZ", ("w" + k).getBytes(StandardCharsets.UTF_8));
                    resp.request.url = "http://example/" + k;
                    s.result(resp);
                }));
            }
            try {
                for (Future<?> f : fs) {
                    f.get(10, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                pool.shutdown();
            }
        });
        assertEquals(n, s.getCurrentResults().size(), "results lost to an unsynchronized append");
    }
}
