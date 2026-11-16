package io.github.ffuf.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.assembly.Assembly;
import io.github.ffuf.pkg.engine.Job;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.ffuf.MultiError;
import io.github.ffuf.pkg.ffuf.Progress;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.filter.MatcherManager;
import io.github.ffuf.pkg.output.Stdoutput;
import io.github.ffuf.pkg.testtarget.Target;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Runs the real ffuf engine end to end against the mock target: a Job assembled as Main assembles it (real input
 * provider, HTTP runner, matchers and filters), with a capturing output in place of the terminal. The configuration
 * folder is isolated per test run (XDG_CONFIG_HOME under target/, set by the build), so history entries and scraper
 * rules never touch the user's own folder.
 */
public final class HarnessTest {
    private HarnessTest() {
    }

    /** An output that records matched responses and the information and warning lines instead of printing them. */
    public static class Capture extends Stdoutput {
        private final List<Response> responses = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();

        /** Creates a capturing output for the configuration conf. */
        public Capture(Config conf) {
            super(conf);
        }

        @Override
        public synchronized void result(Response resp) {
            responses.add(resp);
        }

        @Override
        public void banner() {
        }

        @Override
        public void progress(Progress status) {
        }

        @Override
        public synchronized void info(String s) {
            messages.add("[INFO] " + s);
        }

        @Override
        public synchronized void warning(String s) {
            messages.add("[WARN] " + s);
        }

        @Override
        public synchronized void error(String s) {
            messages.add("[ERR] " + s);
        }

        @Override
        public void finalizeOutput() {
        }

        /** {@return a copy of the matched responses, in the order they were reported} */
        public synchronized List<Response> responses() {
            return new ArrayList<>(responses);
        }

        /** {@return a copy of the information, warning and error lines, each with its [INFO], [WARN] or [ERR] prefix} */
        public synchronized List<String> messages() {
            return new ArrayList<>(messages);
        }

        /** The sorted, de-duplicated FUZZ payloads that matched. */
        public synchronized List<String> inputs() {
            TreeSet<String> out = new TreeSet<>();
            for (Response r : responses) {
                byte[] v = r.request.input.get("FUZZ");
                out.add(v == null ? "" : new String(v, StandardCharsets.UTF_8));
            }
            return new ArrayList<>(out);
        }
    }

    /**
     * Writes words, one per line, to a new file under dir.
     *
     * @return the path of the wordlist file
     * @throws IOException when the file cannot be created or written
     */
    public static String writeWordlist(Path dir, List<String> words) throws IOException {
        Path f = Files.createTempFile(dir, "wordlist-", ".txt");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            sb.append(w).append('\n');
        }
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f.toString();
    }

    /**
     * The options every scan starts from: GET on url with the words as the FUZZ wordlist, quiet.
     *
     * @throws IOException when the wordlist file cannot be written
     */
    public static ConfigOptions options(Path dir, String url, List<String> words) throws IOException {
        ConfigOptions opts = new ConfigOptions();
        opts.http.url = url;
        opts.http.method = "GET";
        opts.input.wordlists = new ArrayList<>(List.of(writeWordlist(dir, words)));
        opts.general.quiet = true;
        return opts;
    }

    /**
     * Builds the Job as Main does, with the given matchers and filters and the given output.
     *
     * @throws Config.ConfigException when the options do not form a valid configuration
     * @throws IOException when a configuration or input file cannot be read
     */
    public static Job buildJob(ConfigOptions opts, Consumer<MatcherManager> setup, java.util.function.Function<Config, Stdoutput> output)
            throws Config.ConfigException, IOException {
        Util.checkOrCreateConfigDir();
        Config conf = Config.fromOptions(opts, List.of("ffuf"));
        conf.matcherManager = new MatcherManager();
        if (setup != null) {
            setup.accept(conf.matcherManager);
        }
        MultiError errs = new MultiError();
        Job job = Assembly.buildJob(conf, errs, output);
        assertTrue(errs.isEmpty(), "building the job: " + errs.errorOrNull());
        return job;
    }

    /**
     * Runs a full scan and returns the capturing output.
     *
     * @throws Config.ConfigException when the options do not form a valid configuration
     * @throws IOException when a configuration or input file cannot be read
     */
    public static Capture scan(ConfigOptions opts, Consumer<MatcherManager> setup) throws Config.ConfigException, IOException {
        Capture[] out = new Capture[1];
        Job job = buildJob(opts, setup, conf -> out[0] = new Capture(conf));
        job.start();
        return out[0];
    }

    /**
     * Runs a full scan of url with words and returns the sorted set of FUZZ inputs that matched.
     *
     * @throws IOException when the wordlist or a configuration file cannot be written or read
     * @throws Config.ConfigException when the options do not form a valid configuration
     */
    public static List<String> runScan(Path dir, String url, List<String> words, Consumer<ConfigOptions> configure, Consumer<MatcherManager> setup)
            throws IOException, Config.ConfigException {
        ConfigOptions opts = options(dir, url, words);
        if (configure != null) {
            configure.accept(opts);
        }
        return scan(opts, setup).inputs();
    }

    /** Asserts that got holds the same inputs as want, in any order: both are sorted and compared element by element. */
    public static void assertSet(List<String> want, List<String> got) {
        List<String> w = new ArrayList<>(want);
        List<String> g = new ArrayList<>(got);
        Collections.sort(w);
        Collections.sort(g);
        assertEquals(w, g, "matched set");
    }

    /** The sorted, de-duplicated request paths the target received. */
    public static List<String> recordedPaths(List<Target.Recorded> reqs) {
        TreeSet<String> out = new TreeSet<>();
        for (Target.Recorded r : reqs) {
            out.add(r.path());
        }
        return new ArrayList<>(out);
    }
}
