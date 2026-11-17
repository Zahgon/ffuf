package io.github.ffuf.pkg.engine;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.ffuf.Progress;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.filter.FilterProvider;
import io.github.ffuf.pkg.input.MainInputProvider;
import io.github.ffuf.pkg.output.AuditLogger;
import io.github.ffuf.pkg.output.Stdoutput;
import io.github.ffuf.pkg.runner.Flights;
import io.github.ffuf.pkg.runner.SimpleRunner;
import io.github.ffuf.pkg.scraper.Scraper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Ties together the configuration, the input, the runner and the output, and runs the queue of fuzzing jobs. The
 * interactive console drives it through pause, resume, reset, skipQueue and the queue operations.
 */
public class Job {
    private final Config config;
    private final MainInputProvider input;
    private final SimpleRunner runner;
    private final Stdoutput output;
    private final JobQueue queue = new JobQueue();
    private RecursionManager recursion;
    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicInteger errorCounter = new AtomicInteger();
    private final AtomicInteger spuriousErrorCounter = new AtomicInteger();
    private final AtomicInteger count403 = new AtomicInteger();
    private final AtomicInteger count429 = new AtomicInteger();
    private volatile boolean running;
    private volatile boolean runningJob;
    private volatile boolean skipQueue;
    private volatile String errorMsg = "";
    private volatile String jobHash = "";
    private volatile long startTime;
    private volatile long startTimeJob;
    private long total;
    /** Serializes the dispatch loop's input iteration against an interactive restart. */
    private final Object inputLock = new Object();
    private final Object calibLock = new Object();
    /** Guards paused; the pause gate is closed while paused is set and checkpoints wait on this monitor. */
    private final Object pauseState = new Object();
    private boolean paused;
    private Consumer<String> debugLog = s -> { };
    private AuditLogger auditLogger;
    private Scraper scraper;
    private SimpleRunner replayRunner;
    private final Calibration calibration;
    private final RateThrottle rate;
    private Flights flights;

    /** A job of the queue: its URL, its recursion depth and its base request. */
    record QueueJob(String url, int depth, Request req) {
    }

    /** The per queue job values a worker reads, passed by value: the base request and its recursion depth. */
    record JobContext(Request basereq, int depth) {
    }

    /** Creates a job for the configuration, reading payloads from input and sending requests with runner. */
    public Job(Config conf, MainInputProvider input, SimpleRunner runner, Stdoutput output) {
        this.config = conf;
        this.input = input;
        this.runner = runner;
        this.output = output;
        this.rate = new RateThrottle(conf);
        conf.rateLimitFunc = rate::take;
        this.flights = new Flights(conf, runner, s -> { });
        this.calibration = new Calibration(conf, runner, output, this::isMatch, this::incError);
    }

    /** Sets the scraper applied to every response. */
    public void setScraper(Scraper s) {
        this.scraper = s;
    }

    /** Sets the audit logger that records the configuration, requests and responses. */
    public void setAuditLogger(AuditLogger a) {
        this.auditLogger = a;
    }

    /** The runner that re-sends matched requests through the -replay-proxy. */
    public void setReplayRunner(SimpleRunner r) {
        this.replayRunner = r;
    }

    /** Sets the debug logger. */
    public void setDebugLog(Consumer<String> log) {
        this.debugLog = log;
        this.flights = new Flights(config, runner, log);
    }

    /** {@return the job's configuration} */
    public Config config() {
        return config;
    }

    /** {@return the output the job reports to} */
    public Stdoutput output() {
        return output;
    }

    /** {@return the request rate limiter} */
    public RateThrottle rate() {
        return rate;
    }

    /** {@return the audit logger, or null when -audit-log is not set} */
    public AuditLogger auditLogger() {
        return auditLogger;
    }

    /** {@return the number of requests of the whole run: the input total, times the number of sniper positions} */
    public long total() {
        return total;
    }

    void setRunning(boolean v) {
        running = v;
    }

    boolean isRunning() {
        return running;
    }

    private void incError() {
        errorCounter.incrementAndGet();
        spuriousErrorCounter.incrementAndGet();
    }

    private void audit(Object data, String kind) {
        if (auditLogger == null) {
            return;
        }
        try {
            auditLogger.write(data);
        } catch (IOException e) {
            output.error("Encountered error while writing " + kind + " audit log: " + e.getMessage() + "\n");
        }
    }

    /** Deletes a queued job by its index in the queue listing (0 is the active job). */
    public void deleteQueueItem(int index) {
        queue.removeAt(index);
    }

    /** {@return the URLs of the active job and of the jobs still queued} */
    public List<String> queuedJobs() {
        List<String> urls = new ArrayList<>();
        for (QueueJob j : queue.remaining()) {
            urls.add(j.url);
        }
        return urls;
    }

    /** Runs the job and every queued recursion or scraper job until the queue is empty or the run stops. */
    public void start() {
        if (startTime == 0) {
            startTime = System.nanoTime();
        }
        recursion = new RecursionManager(config, queue, output);
        Request base = Request.base(config);
        if (config.inputMode.equals("sniper")) {
            List<Request> reqs = io.github.ffuf.pkg.ffuf.Sniper.requests(base, config.inputProviders.get(0).template);
            for (Request r : reqs) {
                queue.push(new QueueJob(config.url, 0, r));
            }
            total = input.total() * reqs.size();
        } else {
            queue.push(new QueueJob(config.url, 0, Request.base(config)));
            total = input.total();
        }
        try {
            running = true;
            runningJob = true;
            if (!config.quiet) {
                output.banner();
            }
            interruptMonitor();
            while (queue.hasNext()) {
                JobContext ctx = prepareQueueJob();
                reset(true);
                runningJob = true;
                startExecution(ctx);
            }
            output.finalizeOutput();
        } finally {
            stop();
        }
    }

    /** Resets the counters and the wordlist position; cycle keeps the current results, otherwise they are dropped. */
    public void reset(boolean cycle) {
        synchronized (inputLock) {
            input.reset();
        }
        counter.set(0);
        skipQueue = false;
        startTimeJob = System.nanoTime();
        if (cycle) {
            output.cycle();
        } else {
            output.reset();
        }
    }

    private JobContext prepareQueueJob() {
        QueueJob job = queue.advance();
        config.url = job.url;
        List<String> found = new ArrayList<>();
        for (String k : input.keywords()) {
            if (Util.requestContainsKeyword(job.req, k)) {
                found.add(k);
            }
        }
        input.activateKeywords(found);
        jobHash = writeHistoryEntry();
        return new JobContext(job.req, job.depth);
    }

    /** Skips the rest of the active job and advances to the next queued one. */
    public void skipQueue() {
        skipQueue = true;
    }

    private void sleepIfNeeded() {
        if (!config.delayHasDelay) {
            return;
        }
        double secs = config.delayIsRange ? config.delayMin + Math.random() * (config.delayMax - config.delayMin) : config.delayMin;
        // time.Duration(secs*1000) * time.Millisecond: a saturating conversion, then a wrapping multiplication,
        // so an infinite or huge delay ends up negative and does not sleep
        long nanos = (long) (secs * 1000) * 1_000_000L;
        config.context.sleep(nanos);
    }

    /**
     * Closes the pause gate: workers then wait at their next checkpoint. The flag flip and the gate are one step under
     * pauseState, and pausing is refused once the job is stopping, so a stop always leaves the gate open.
     */
    public void pause() {
        synchronized (pauseState) {
            if (!running) {
                return;
            }
            if (!paused) {
                paused = true;
                output.info("------ PAUSING ------");
            }
        }
    }

    /** Reopens the pause gate. */
    public void resume() {
        synchronized (pauseState) {
            if (paused) {
                paused = false;
                pauseState.notifyAll();
                output.info("------ RESUMING -----");
            }
        }
    }

    /** Waits while the job is paused; returns at once otherwise. */
    void pauseCheckpoint() {
        synchronized (pauseState) {
            while (paused) {
                try {
                    pauseState.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void startExecution(JobContext ctx) {
        Thread background = new Thread(this::runBackgroundTasks, "ffuf-progress");
        background.setDaemon(true);
        background.start();
        if (queue.position() > 1) {
            if (config.inputMode.equals("sniper")) {
                output.info("Starting queued sniper job (" + queue.position() + " of " + queue.total() + ") on target: " + config.url);
            } else {
                output.info("Starting queued job on target: " + config.url);
            }
        }
        int threads = (int) Math.min(Math.max(1, config.threads), Integer.MAX_VALUE);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Semaphore limiter = new Semaphore(threads);
        String warning = null;
        try {
            while (true) {
                boolean hasNext;
                synchronized (inputLock) {
                    hasNext = input.next();
                }
                if (!hasNext || skipQueue) {
                    break;
                }
                checkStop();
                if (!running) {
                    warning = errorMsg;
                    break;
                }
                pauseCheckpoint();
                try {
                    limiter.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                rate.take();
                Map<String, byte[]> next;
                int pos;
                synchronized (inputLock) {
                    next = input.value();
                    pos = input.position();
                }
                next.put("FFUFHASH", ffufHash(pos));
                counter.incrementAndGet();
                pool.submit(() -> {
                    try {
                        runTask(ctx, next, pos, false);
                        sleepIfNeeded();
                        rate.tick(Instant.now());
                    } finally {
                        limiter.release();
                    }
                });
                if (!runningJob) {
                    warning = errorMsg;
                    break;
                }
            }
        } finally {
            pool.shutdown();
        }
        try {
            pool.awaitTermination(365, TimeUnit.DAYS);
            background.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        updateProgress();
        if (warning != null) {
            output.warning(warning);
        }
    }

    /** Stops the run on SIGINT or SIGTERM: the dispatch loop ends, the workers finish and the output is written. */
    private void interruptMonitor() {
        for (String name : new String[] {"INT", "TERM"}) {
            try {
                sun.misc.Signal.handle(new sun.misc.Signal(name), sig -> {
                    errorMsg = "Caught keyboard interrupt (Ctrl-C)\n";
                    stop();
                });
            } catch (IllegalArgumentException e) {
                debugLog.accept("Could not handle SIG" + name + ": " + e.getMessage());
            }
        }
    }

    /** Redraws the progress line every progress period until the job ends, is skipped or the run stops. */
    private void runBackgroundTasks() {
        long totalProgress = input.total();
        while (counter.get() <= totalProgress && !skipQueue) {
            pauseCheckpoint();
            if (!running) {
                break;
            }
            updateProgress();
            if (counter.get() == totalProgress || !runningJob) {
                return;
            }
            try {
                Thread.sleep(config.progressFrequency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void updateProgress() {
        output.progress(new Progress(startTimeJob, counter.get(), input.total(), rate.currentRate(), queue.position(), queue.total(), errorCounter.get()));
    }

    /** Stops the job when a stop condition is met: the 403, 429 or error share, or the time limits. */
    public void checkStop() {
        int c = counter.get();
        if (c > 50) {
            if ((config.stopOn403 || config.stopOnAll) && (double) count403.get() / c > 0.95) {
                errorMsg = "Getting an unusual amount of 403 responses, exiting.";
                stop();
            }
            if ((config.stopOnErrors || config.stopOnAll) && spuriousErrorCounter.get() > config.threads * 2) {
                errorMsg = "Receiving spurious errors, exiting.";
                stop();
            }
            if (config.stopOnAll && (double) count429.get() / c > 0.2) {
                errorMsg = "Getting an unusual amount of 429 responses, exiting.";
                stop();
            }
        }
        if (config.maxTime > 0 && (System.nanoTime() - startTime) / 1_000_000_000L >= config.maxTime) {
            errorMsg = "Maximum running time for entire process reached, exiting.";
            stop();
        }
        if (config.maxTimeJob > 0 && (System.nanoTime() - startTimeJob) / 1_000_000_000L >= config.maxTimeJob) {
            errorMsg = "Maximum running time for this job reached, continuing with next job if one exists.";
            next();
        }
    }

    /** Stops the run: cancels in-flight requests and reopens the pause gate so no worker stays blocked. */
    public void stop() {
        running = false;
        config.context.cancel();
        resume();
    }

    /** Stops the active job and moves on to the next queued one. */
    public void next() {
        runningJob = false;
    }

    private byte[] ffufHash(int pos) {
        String h = jobHash.length() > 5 ? jobHash.substring(0, 5) : "";
        return (h + Integer.toHexString(pos)).getBytes(StandardCharsets.UTF_8);
    }

    private void runTask(JobContext ctx, Map<String, byte[]> in, int pos, boolean retried) {
        Request req = runner.prepare(in, ctx.basereq());
        req.position = pos;
        req.timestamp = Instant.now();
        Response resp;
        try {
            resp = flights.execute(req);
            audit(req, "request");
            audit(resp, "response");
        } catch (SimpleRunner.RequestException e) {
            req.error = e.getMessage();
            audit(req, "request");
            if (!retried) {
                runTask(ctx, in, pos, true);
                return;
            }
            incError();
            debugLog.accept(e.getMessage());
            if (e.timeout) {
                String inputmsg = timeoutInputs(in);
                if (config.matcherManager.getMatchers().containsKey("time")) {
                    output.info("Timeout while 'time' matcher is active: " + inputmsg);
                } else if (config.matcherManager.getFilters().containsKey("time")) {
                    output.info("Timeout while 'time' filter is active: " + inputmsg);
                }
            }
            return;
        }
        spuriousErrorCounter.set(0);
        if ((config.stopOn403 || config.stopOnAll) && resp.statusCode == 403) {
            count403.incrementAndGet();
        }
        if (config.stopOnAll && resp.statusCode == 429) {
            count429.incrementAndGet();
        }
        pauseCheckpoint();
        synchronized (calibLock) {
            calibration.calibrateIfNeeded(Util.hostUrlFromRequest(req), in);
        }
        if (scraper != null) {
            resp.scraperData.putAll(scraper.execute(resp, isMatch(resp)));
        }
        if (isMatch(resp)) {
            if (replayRunner != null) {
                Request replayreq = replayRunner.prepare(in, ctx.basereq());
                replayreq.position = pos;
                sendReplay(replayreq);
            }
            output.result(resp);
            updateProgress();
            if (config.recursion && config.recursionStrategy.equals("greedy")) {
                recursion.handleGreedy(ctx, resp);
            }
        } else if (!resp.scraperData.isEmpty()) {
            output.result(resp);
        }
        if (config.recursion && config.recursionStrategy.equals("default") && !resp.redirectLocation().isEmpty()) {
            recursion.handleDefault(ctx, resp);
        }
    }

    private static String timeoutInputs(Map<String, byte[]> in) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, byte[]> e : in.entrySet()) {
            sb.append(e.getKey()).append(" : ").append(new String(e.getValue(), StandardCharsets.UTF_8)).append("  // ");
        }
        return sb.toString();
    }

    private boolean isMatch(Response resp) {
        return config.matcherManager.matches(resp, config.autoCalibrationPerHost, config.matcherMode, config.filterMode, Util.hostUrlFromRequest(resp.request));
    }

    private String writeHistoryEntry() {
        String time = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", "Z");
        String json = historyOptions(config).toJson(time);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
        Path dir = Paths.get(Util.historyDir(), hash);
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("options"), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            debugLog.accept("Could not write history entry: " + e.getMessage());
        }
        return hash;
    }

    /** The options a history entry records: the parse-time options with the live URL, matchers and filters. */
    static ConfigOptions historyOptions(Config config) {
        ConfigOptions o = config.options.copy();
        o.http.url = config.url;
        o.filter.mode = config.filterMode;
        o.filter.lines = "";
        o.filter.regexp = "";
        o.filter.size = "";
        o.filter.status = "";
        o.filter.time = "";
        o.filter.words = "";
        applyReprs(o.filter, config.matcherManager.getFilters());
        o.matcher.mode = config.matcherMode;
        o.matcher.lines = "";
        o.matcher.regexp = "";
        o.matcher.size = "";
        o.matcher.status = "";
        o.matcher.time = "";
        o.matcher.words = "";
        applyReprs(o.matcher, config.matcherManager.getMatchers());
        return o;
    }

    private static void applyReprs(ConfigOptions.MatchOptions m, Map<String, FilterProvider> fs) {
        Map<String, FilterProvider> copy = new LinkedHashMap<>(fs);
        for (Map.Entry<String, FilterProvider> e : copy.entrySet()) {
            String r = e.getValue().repr();
            switch (e.getKey()) {
                case "line" -> m.lines = r;
                case "regexp" -> m.regexp = r;
                case "size" -> m.size = r;
                case "status" -> m.status = r;
                case "time" -> m.time = r;
                case "words" -> m.words = r;
                default -> {
                    // there is no case for "word", so word filters never reach the history entry
                }
            }
        }
    }

    /**
     * Sends the copy of a matched request to the replay proxy. The replay only mirrors the traffic, so its outcome does
     * not change the result.
     *
     * @return true when the replay request completed, false when it failed
     */
    private boolean sendReplay(Request replayreq) {
        try {
            replayRunner.execute(replayreq);
            return true;
        } catch (SimpleRunner.RequestException e) {
            return false;
        }
    }
}
