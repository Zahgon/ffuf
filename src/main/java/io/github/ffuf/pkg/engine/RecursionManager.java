package io.github.ffuf.pkg.engine;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.output.Stdoutput;

/** Queues recursion jobs for matched directories, by the greedy or the default (redirect) strategy. */
final class RecursionManager {
    private final Config config;
    private final JobQueue queue;
    private final Stdoutput output;

    RecursionManager(Config config, JobQueue queue, Stdoutput output) {
        this.config = config;
        this.queue = queue;
        this.output = output;
    }

    /** Greedy strategy: every match becomes a recursion job, up to the depth limit. */
    void handleGreedy(Job.JobContext ctx, Response resp) {
        if (config.recursionDepth == 0 || ctx.depth() < config.recursionDepth) {
            String recUrl = resp.request.url + "/FUZZ";
            queue.push(new Job.QueueJob(recUrl, ctx.depth() + 1, recursionRequest(recUrl)));
            output.info("Adding a new job to the queue: " + recUrl);
        } else {
            output.warning("Maximum recursion depth reached. Ignoring: " + resp.request.url);
        }
    }

    /** Default strategy: a response redirecting to the same URL with a trailing slash is a directory. */
    void handleDefault(Job.JobContext ctx, Response resp) {
        String recUrl = resp.request.url + "/FUZZ";
        String abs = absoluteRedirect(resp);
        if (!(resp.request.url + "/").equals(abs)) {
            return;
        }
        if (config.recursionDepth == 0 || ctx.depth() < config.recursionDepth) {
            queue.push(new Job.QueueJob(recUrl, ctx.depth() + 1, recursionRequest(recUrl)));
            output.info("Adding a new job to the queue: " + recUrl);
        } else {
            output.warning("Directory found, but recursion depth exceeded. Ignoring: " + abs);
        }
    }

    /** The redirect location of the response, resolved against its request URL. */
    private static String absoluteRedirect(Response resp) {
        String loc = resp.redirectLocation();
        String base = resp.request.url;
        if (loc.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) {
            return loc;
        }
        int schemeEnd = base.indexOf("://");
        if (schemeEnd < 0) {
            return loc;
        }
        int pathStart = base.indexOf('/', schemeEnd + 3);
        String origin = pathStart < 0 ? base : base.substring(0, pathStart);
        if (loc.startsWith("//")) {
            return base.substring(0, schemeEnd + 1) + loc;
        }
        if (loc.startsWith("/")) {
            return origin + loc;
        }
        String path = pathStart < 0 ? "/" : base.substring(pathStart);
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        return origin + path.substring(0, path.lastIndexOf('/') + 1) + loc;
    }

    /** The base request of a recursion job: the configured request with its URL replaced by url. */
    private Request recursionRequest(String url) {
        Request r = Request.base(config);
        r.url = url;
        return r;
    }
}
