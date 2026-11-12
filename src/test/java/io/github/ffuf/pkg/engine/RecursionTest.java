package io.github.ffuf.pkg.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.output.Stdoutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The greedy and default recursion strategies, observed through the jobs they queue. */
class RecursionTest {
    /** An output that discards every message. */
    private static Stdoutput nullOutput(Config conf) {
        return new Stdoutput(conf) {
            @Override
            public void info(String s) {
            }

            @Override
            public void warning(String s) {
            }
        };
    }

    private static RecursionManager manager(long depth, JobQueue q) {
        Config conf = new Config();
        conf.method = "GET";
        conf.recursionDepth = depth;
        return new RecursionManager(conf, q, nullOutput(conf));
    }

    private static Response response(String url) {
        Response resp = new Response();
        resp.request = new Request();
        resp.request.url = url;
        return resp;
    }

    @Test
    void testRecursionManager_GreedyQueuesWithinDepth() {
        JobQueue q = new JobQueue();
        RecursionManager rm = manager(2, q);
        rm.handleGreedy(new Job.JobContext(null, 0), response("http://x/admin"));

        assertTrue(q.hasNext(), "expected a recursion job to be queued");
        Job.QueueJob job = q.advance();
        assertEquals("http://x/admin/FUZZ", job.url(), "queued url");
        assertEquals(1, job.depth(), "queued depth (parent depth + 1)");
    }

    @Test
    void testRecursionManager_GreedyRespectsMaxDepth() {
        JobQueue q = new JobQueue();
        RecursionManager rm = manager(1, q);
        // depth == RecursionDepth: at the limit, nothing more is queued.
        rm.handleGreedy(new Job.JobContext(null, 1), response("http://x/admin"));

        assertEquals(0, q.total(), "expected no queued job at max depth");
    }

    @Test
    void testRecursionManager_DefaultSkipsNonDirectory() {
        JobQueue q = new JobQueue();
        RecursionManager rm = manager(0, q);
        // A 200 with no redirect is not a directory, so the default strategy queues nothing.
        Response resp = response("http://x/file");
        resp.statusCode = 200;
        rm.handleDefault(new Job.JobContext(null, 0), resp);

        assertEquals(0, q.total(), "non-directory response should not queue a job");
    }

    @Test
    void testRecursionManager_DefaultQueuesDirectory() {
        JobQueue q = new JobQueue();
        RecursionManager rm = manager(0, q);
        // A 301 redirecting to url + "/" is a directory; the default strategy descends.
        Response resp = response("http://x/dir");
        resp.statusCode = 301;
        resp.headers = Map.of("Location", List.of("http://x/dir/"));
        rm.handleDefault(new Job.JobContext(null, 0), resp);

        assertEquals(1, q.total(), "directory response should queue a recursion job");
        assertEquals("http://x/dir/FUZZ", q.advance().url(), "queued url");
    }
}
