package io.github.ffuf.pkg.output;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.GoString;
import io.github.ffuf.pkg.ffuf.InputProviderConfig;
import io.github.ffuf.pkg.ffuf.Json;
import io.github.ffuf.pkg.ffuf.Progress;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.filter.FilterProvider;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Terminal output: banner, result lines, information, warnings and errors, and the output files at the end. */
public class Stdoutput {
    public static final String BANNER_HEADER = "\n"
            + "        /'___\\  /'___\\           /'___\\       \n"
            + "       /\\ \\__/ /\\ \\__/  __  __  /\\ \\__/       \n"
            + "       \\ \\ ,__\\\\ \\ ,__\\/\\ \\/\\ \\ \\ \\ ,__\\      \n"
            + "        \\ \\ \\_/ \\ \\ \\_/\\ \\ \\_\\ \\ \\ \\ \\_/      \n"
            + "         \\ \\_\\   \\ \\_\\  \\ \\____/  \\ \\_\\       \n"
            + "          \\/_/    \\/_/   \\/___/    \\/_/       \n";
    public static final String BANNER_SEP = "________________________________________________";
    /** Windows consoles get no ANSI escapes. */
    static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    /** Returns the cursor to the start of the line and erases it, to redraw the progress line on a terminal. */
    public static final String TERMINAL_CLEAR_LINE = WINDOWS ? "\r\r" : "\r\u001b[2K";
    static final String ANSI_CLEAR = WINDOWS ? "" : "\u001b[0m";
    static final String ANSI_RED = WINDOWS ? "" : "\u001b[31m";
    static final String ANSI_GREEN = WINDOWS ? "" : "\u001b[32m";
    static final String ANSI_BLUE = WINDOWS ? "" : "\u001b[34m";
    static final String ANSI_YELLOW = WINDOWS ? "" : "\u001b[33m";

    private final Config config;
    private final List<String> fuzzKeywords = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private List<Result> currentResults = new ArrayList<>();
    boolean stdoutIsTerminal;
    boolean stderrIsTerminal;
    private boolean paused;
    public static final PrintStream OUT = new GoPrintStream(new Forward(() -> System.out));
    public static final PrintStream ERR = new GoPrintStream(new Forward(() -> System.err));

    /** Writes to whatever stream System.out or System.err is at the time of the write, so a redirect is honoured. */
    private static final class Forward extends java.io.OutputStream {
        private final java.util.function.Supplier<PrintStream> target;

        Forward(java.util.function.Supplier<PrintStream> target) {
            this.target = target;
        }

        @Override
        public void write(int b) {
            target.get().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            target.get().write(b, off, len);
        }

        @Override
        public void flush() {
            target.get().flush();
        }
    }

    /** Creates the output for the configuration. */
    public Stdoutput(Config conf) {
        this.config = conf;
        for (InputProviderConfig ip : conf.inputProviders) {
            fuzzKeywords.add(ip.keyword);
        }
        Collections.sort(fuzzKeywords);
        stdoutIsTerminal = isTerminal(1);
        stderrIsTerminal = isTerminal(2);
    }

    /**
     * Whether file descriptor fd is an interactive terminal rather than a regular file or a pipe. Like Go's terminal
     * check, this treats every character device (also /dev/null) as a terminal, which only decides whether a few
     * control bytes are written.
     */
    static boolean isTerminal(int fd) {
        try {
            int mode = (Integer) Files.getAttribute(Paths.get("/dev/fd/" + fd), "unix:mode");
            return (mode & 0170000) == 0020000;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    /** The control sequence that redraws the current stderr line, or "" when stderr is not a terminal. */
    private String stderrClear() {
        return stderrIsTerminal ? TERMINAL_CLEAR_LINE : "";
    }

    /** Clears the progress line on stdout, like stderrClear does on stderr, for results that overwrite it. */
    private String stdoutClear() {
        return stdoutIsTerminal ? TERMINAL_CLEAR_LINE : "";
    }

    /** Prints the banner with the configuration summary. */
    public void banner() {
        String version = Util.formattedVersion();
        if (config.colors) {
            version = version.replace("<3", ANSI_RED + "<3" + ANSI_CLEAR);
        }
        ERR.print(BANNER_HEADER + "\n       " + version + "\n" + BANNER_SEP + "\n\n");
        printOption("Method", config.method);
        printOption("URL", config.url);
        for (InputProviderConfig p : config.inputProviders) {
            if (p.name.equals("wordlist")) {
                printOption("Wordlist", p.keyword + ": " + p.value);
            }
        }
        for (Map.Entry<String, String> h : config.headers.entrySet()) {
            printOption("Header", h.getKey() + ": " + h.getValue());
        }
        if (!config.data.isEmpty()) {
            printOption("Data", config.data);
        }
        if (!config.extensions.isEmpty()) {
            StringBuilder exts = new StringBuilder();
            for (String e : config.extensions) {
                exts.append(e).append(' ');
            }
            printOption("Extensions", exts.toString());
        }
        if (!config.outputFile.isEmpty()) {
            String f = config.outputFile;
            if (config.outputFormat.equals("all")) {
                f += ".{json,ejson,html,md,csv,ecsv}";
            }
            printOption("Output file", f);
            printOption("File format", config.outputFormat);
        }
        printOption("Follow redirects", Boolean.toString(config.followRedirects));
        printOption("Calibration", Boolean.toString(config.autoCalibration));
        if (!config.proxyUrl.isEmpty()) {
            printOption("Proxy", config.proxyUrl);
        }
        if (!config.replayProxyUrl.isEmpty()) {
            printOption("ReplayProxy", config.replayProxyUrl);
        }
        printOption("Timeout", Long.toString(config.timeout));
        printOption("Threads", Long.toString(config.threads));
        if (config.delayHasDelay) {
            String delay = config.delayIsRange
                    ? String.format("%.2f - %.2f seconds", config.delayMin, config.delayMax)
                    : String.format("%.2f seconds", config.delayMin);
            printOption("Delay", delay);
        }
        for (FilterProvider f : config.matcherManager.getMatchers().values()) {
            printOption("Matcher", f.reprVerbose());
        }
        for (FilterProvider f : config.matcherManager.getFilters().values()) {
            printOption("Filter", f.reprVerbose());
        }
        ERR.print(BANNER_SEP + "\n\n");
    }

    private static void printOption(String name, String value) {
        ERR.print(" :: " + GoString.padRight(name, 16) + " : " + value + "\n");
    }

    /** Moves the current results to the results of the whole run. */
    public synchronized void cycle() {
        results.addAll(currentResults);
        currentResults = new ArrayList<>();
    }

    /** Drops the current results. */
    public synchronized void reset() {
        currentResults = new ArrayList<>();
    }

    /** {@return a copy of the results of the current job} */
    public synchronized List<Result> getCurrentResults() {
        return new ArrayList<>(currentResults);
    }

    /** Replaces the results of the current job. */
    public synchronized void setCurrentResults(List<Result> res) {
        currentResults = res;
    }

    /** Keeps only the current results keep accepts, in one step, so a result recorded meanwhile is not lost. */
    public synchronized void filterCurrentResults(Predicate<Result> keep) {
        List<Result> filtered = new ArrayList<>();
        for (Result r : currentResults) {
            if (keep.test(r)) {
                filtered.add(r);
            }
        }
        currentResults = filtered;
    }

    /** Prints the progress line. */
    public void progress(Progress status) {
        if (config.quiet) {
            return;
        }
        long dur = System.nanoTime() - status.startedAtNanos();
        long secsTotal = dur / 1_000_000_000L;
        long rate = secsTotal > 0 ? status.reqSec() : 0;
        long hours = secsTotal / 3600;
        long mins = (secsTotal % 3600) / 60;
        long secs = secsTotal % 60;
        ERR.print(String.format("%s:: Progress: [%d/%d] :: Job [%d/%d] :: %d req/sec :: Duration: [%d:%02d:%02d] :: Errors: %d ::", stderrClear(),
                status.reqCount(), status.reqTotal(), status.queuePos(), status.queueTotal(), rate, hours, mins, secs, status.errorCount()));
    }

    /** Prints an informational message. */
    public void info(String s) {
        if (config.quiet) {
            ERR.print(s);
        } else if (!config.colors) {
            ERR.print(stderrClear() + "[INFO] " + s + "\n\n");
        } else {
            ERR.print(stderrClear() + "[" + ANSI_BLUE + "INFO" + ANSI_CLEAR + "] " + s + "\n\n");
        }
    }

    /** Prints an error message. */
    public void error(String s) {
        if (config.quiet) {
            ERR.print(s);
        } else if (!config.colors) {
            ERR.print(stderrClear() + "[ERR]\u00a0" + s + "\n");
        } else {
            ERR.print(stderrClear() + "[" + ANSI_RED + "ERR" + ANSI_CLEAR + "] " + s + "\n");
        }
    }

    /** Prints a warning. */
    public void warning(String s) {
        if (config.quiet) {
            ERR.print(s);
        } else if (!config.colors) {
            ERR.print(stderrClear() + "[WARN] " + s + "\n");
        } else {
            ERR.print(stderrClear() + "[" + ANSI_RED + "WARN" + ANSI_CLEAR + "] " + s + "\n");
        }
    }

    /** Writes output to stderr as it is, after clearing the progress line on a terminal. */
    public void raw(String output) {
        ERR.print(stderrClear() + output);
    }

    /** Writes the output file of every format with the -o name as the base name, reporting each failure. */
    private void writeToAll(List<Result> res) {
        String base = config.outputFile;
        for (String format : new String[] {"json", "ejson", "html", "md", "csv", "ecsv"}) {
            config.outputFile = base + "." + format;
            try {
                FileOutput.write(config.outputFile, format, config, res);
            } catch (IOException e) {
                error(FileOutput.goError(e));
            }
        }
    }

    /**
     * Saves the results of the run so far to filename in format.
     *
     * @return the error text, or null when the file was written
     */
    public String saveFile(String filename, String format) {
        List<Result> all;
        synchronized (this) {
            all = new ArrayList<>(results);
            all.addAll(currentResults);
        }
        if (config.outputSkipEmptyFile && all.isEmpty()) {
            info("No results and -or defined, output file not written.");
            return null;
        }
        if (format.equals("all")) {
            writeToAll(all);
            return null;
        }
        try {
            FileOutput.write(filename, format, config, all);
        } catch (IOException e) {
            return FileOutput.goError(e);
        }
        return null;
    }

    /** Runs after all the jobs: writes the -o output file. */
    public void finalizeOutput() {
        if (!config.outputFile.isEmpty()) {
            String err = saveFile(config.outputFile, config.outputFormat);
            if (err != null) {
                error(err);
            }
        }
        if (!config.quiet) {
            ERR.print("\n");
        }
    }

    /** Records a matched response and prints it. */
    public void result(Response resp) {
        if (!config.outputDirectory.isEmpty()) {
            resp.resultFile = writeResultToFile(resp);
        }
        Result r = new Result();
        r.input = new java.util.LinkedHashMap<>(resp.request.input);
        r.position = resp.request.position;
        r.statusCode = resp.statusCode;
        r.contentLength = resp.contentLength;
        r.contentWords = resp.contentWords;
        r.contentLines = resp.contentLines;
        r.contentType = resp.contentType;
        r.redirectLocation = resp.redirectLocation();
        r.scraperData = resp.scraperData;
        r.url = resp.request.url;
        r.durationNanos = resp.durationNanos;
        r.resultFile = resp.resultFile;
        r.host = resp.request.host;
        boolean held;
        synchronized (this) {
            held = paused;
            r.printed = !held;
            currentResults.add(r);
        }
        if (!held) {
            printResult(r);
        }
    }

    /**
     * Sets whether result() streams matches to the terminal. While paused (the interactive console is open) matches
     * are recorded but not printed; leaving a pause marks every held result as shown.
     */
    public synchronized void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            for (Result r : currentResults) {
                r.printed = true;
            }
        }
    }

    /** {@return the number of held matches not yet shown: recorded while paused and still among the current results} */
    public synchronized int pendingResults() {
        int n = 0;
        for (Result r : currentResults) {
            if (!r.printed) {
                n++;
            }
        }
        return n;
    }

    private String writeResultToFile(Response resp) {
        try {
            Files.createDirectories(Paths.get(config.outputDirectory));
        } catch (IOException e) {
            error(e.getMessage());
            return "";
        }
        String content = resp.request.raw + "\n---- \u2191 Request ---- Response \u2193 ----\n\n" + resp.raw;
        byte[] bytes = GoString.encode(content);
        String name;
        try {
            name = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is part of every Java runtime", e);
        }
        Path p = Paths.get(config.outputDirectory, name);
        try {
            Files.write(p, bytes);
        } catch (IOException e) {
            error(e.getMessage());
        }
        return name;
    }

    /** Prints one result in the configured format. */
    public synchronized void printResult(Result r) {
        if (config.json) {
            ERR.print(stderrClear());
            OUT.print(r.toJson() + "\n");
        } else if (config.quiet) {
            OUT.print(inputsOneLine(r) + "\n");
        } else if (fuzzKeywords.size() > 1 || config.verbose || !config.outputDirectory.isEmpty() || !r.scraperData.isEmpty()) {
            resultMultiline(r);
        } else {
            OUT.print(String.format("%s%s%s [Status: %d, Size: %d, Words: %d, Lines: %d, Duration: %dms]%s\n", stdoutClear(), colorize(r.statusCode),
                    GoString.padRight(inputsOneLine(r), 23), r.statusCode, r.contentLength, r.contentWords, r.contentLines, r.durationNanos / 1_000_000L, ansiClear()));
        }
    }

    private String inputValue(Result r, String k) {
        if (config.commandKeywords.contains(k)) {
            return Integer.toString(r.position);
        }
        byte[] v = r.input.get(k);
        return v == null ? "" : GoString.decode(v);
    }

    private String inputsOneLine(Result r) {
        StringBuilder sb = new StringBuilder();
        if (fuzzKeywords.size() > 1) {
            for (String k : fuzzKeywords) {
                sb.append(k).append(" : ").append(inputValue(r, k)).append(' ');
            }
            return sb.toString();
        }
        String out = "";
        for (String k : fuzzKeywords) {
            out = inputValue(r, k);
        }
        return out;
    }

    private void resultMultiline(Result r) {
        String hdr = String.format("%s%s[Status: %d, Size: %d, Words: %d, Lines: %d, Duration: %dms]%s", stdoutClear(), colorize(r.statusCode), r.statusCode,
                r.contentLength, r.contentWords, r.contentLines, r.durationNanos / 1_000_000L, ansiClear());
        StringBuilder lines = new StringBuilder();
        if (config.verbose) {
            lines.append(stdoutClear()).append("| URL | ").append(r.url).append('\n');
            if (!r.redirectLocation.isEmpty()) {
                lines.append(stdoutClear()).append("| --> | ").append(r.redirectLocation).append('\n');
            }
        }
        if (!r.resultFile.isEmpty()) {
            lines.append(stdoutClear()).append("| RES | ").append(r.resultFile).append('\n');
        }
        for (String k : fuzzKeywords) {
            lines.append(stdoutClear()).append("    * ").append(k).append(": ").append(inputValue(r, k)).append('\n');
        }
        if (!r.scraperData.isEmpty()) {
            lines.append(stdoutClear()).append("| SCR |\n");
            for (Map.Entry<String, List<String>> e : r.scraperData.entrySet()) {
                for (String v : e.getValue()) {
                    lines.append(stdoutClear()).append("    * ").append(e.getKey()).append(": ").append(v).append('\n');
                }
            }
        }
        OUT.print(hdr + "\n" + lines + "\n");
    }

    private String ansiClear() {
        return config.colors ? ANSI_CLEAR : "";
    }

    private String colorize(long status) {
        if (!config.colors) {
            return "";
        }
        if (status >= 200 && status < 300) {
            return ANSI_GREEN;
        }
        if (status >= 300 && status < 400) {
            return ANSI_BLUE;
        }
        if (status >= 400 && status < 500) {
            return ANSI_YELLOW;
        }
        if (status >= 500 && status < 600) {
            return ANSI_RED;
        }
        return ANSI_CLEAR;
    }

    /** One reported result. */
    public static class Result {
        public Map<String, byte[]> input = new java.util.LinkedHashMap<>();
        public int position;
        public long statusCode;
        public long contentLength;
        public long contentWords;
        public long contentLines;
        public String contentType = "";
        public String redirectLocation = "";
        public String url = "";
        public long durationNanos;
        public Map<String, List<String>> scraperData = new java.util.LinkedHashMap<>();
        public String resultFile = "";
        public String host = "";
        /** Whether the result was shown to the user (streamed, or counted on resume); never serialized. */
        public boolean printed;

        /** {@return the result as it is written into JSON output} */
        public String toJson() {
            Json.Obj o = new Json.Obj();
            o.put("input", input);
            o.put("position", position);
            o.put("status", statusCode);
            o.put("length", contentLength);
            o.put("words", contentWords);
            o.put("lines", contentLines);
            o.put("content-type", contentType);
            o.put("redirectlocation", redirectLocation);
            o.put("url", url);
            o.put("duration", durationNanos);
            o.put("scraper", scraperData);
            o.put("resultfile", resultFile);
            o.put("host", host);
            return o.toString();
        }
    }
}
