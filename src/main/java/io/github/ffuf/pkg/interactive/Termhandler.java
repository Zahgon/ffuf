package io.github.ffuf.pkg.interactive;

import io.github.ffuf.pkg.engine.Job;
import io.github.ffuf.pkg.ffuf.GoStrconv;
import io.github.ffuf.pkg.ffuf.GoString;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.filter.FilterProvider;
import io.github.ffuf.pkg.output.Stdoutput;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The interactive console: reads commands from the controlling terminal while a job runs. ENTER pauses the job and
 * opens the console, where the filters, the rate and the job queue can be changed and the results shown or saved.
 */
public final class Termhandler {
    /** The longest line the console reads (bufio.MaxScanTokenSize); a longer one ends the console. */
    private static final int MAX_LINE = 64 * 1024;
    private static final String HELP = "\n"
            + "available commands:\n"
            + " afc  [value]             - append to status code filter %s\n"
            + " fc   [value]             - (re)configure status code filter %s\n"
            + " afl  [value]             - append to line count filter %s\n"
            + " fl   [value]             - (re)configure line count filter %s\n"
            + " afw  [value]             - append to word count filter %s\n"
            + " fw   [value]             - (re)configure word count filter %s\n"
            + " afs  [value]             - append to size filter %s\n"
            + " fs   [value]             - (re)configure size filter %s\n"
            + " aft  [value]             - append to time filter %s\n"
            + " ft   [value]             - (re)configure time filter %s\n"
            + " rate [value]             - adjust rate of requests per second %s\n"
            + " queueshow                - show job queue\n"
            + " queuedel [number]        - delete a job in the queue\n"
            + " queueskip                - advance to the next queued job\n"
            + " restart                  - restart and resume the current ffuf job\n"
            + " resume                   - resume current ffuf job (or: ENTER) \n"
            + " show                     - show results for the current job\n"
            + " savejson [filename]      - save current matches to a file\n"
            + " help                     - you are looking at it\n";

    private final Job job;
    private final Stdoutput output;
    private boolean paused;

    Termhandler(Job job) {
        this.job = job;
        this.output = job.output();
    }

    /**
     * Runs the console on the controlling terminal until it closes.
     *
     * @throws IOException when the terminal cannot be opened, for example when the process has no controlling terminal
     */
    public static void handle(Job job) throws IOException {
        Termhandler i = new Termhandler(job);
        try (InputStream tty = termHandle()) {
            byte[] line;
            while ((line = readLine(tty)) != null) {
                i.handleInput(line);
            }
        }
    }

    /** Opens the terminal: /dev/tty, or CONIN$ on Windows. */
    static InputStream termHandle() throws IOException {
        String name = System.getProperty("os.name", "").startsWith("Windows") ? "CONIN$" : "/dev/tty";
        try {
            return new FileInputStream(name);
        } catch (FileNotFoundException e) {
            throw new IOException("open " + name + ": " + reason(e.getMessage(), name), e);
        }
    }

    /** The lower-case reason of a failed open, as Go spells the error: "/dev/tty (Device not configured)" gives "device not configured". */
    private static String reason(String message, String name) {
        String m = message == null ? "" : message;
        if (m.startsWith(name + " (") && m.endsWith(")")) {
            m = m.substring(name.length() + 2, m.length() - 1);
        }
        return m.isEmpty() ? m : Character.toLowerCase(m.charAt(0)) + m.substring(1);
    }

    /**
     * Reads one line without its "\n" and one trailing "\r" (bufio.ScanLines); returns null at the end of input or
     * when a line is longer than the console reads.
     */
    static byte[] readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            buf.write(b);
            if (buf.size() > MAX_LINE) {
                return null;
            }
        }
        if (b < 0 && buf.size() == 0) {
            return null;
        }
        byte[] line = buf.toByteArray();
        if (line.length > 0 && line[line.length - 1] == '\r') {
            return java.util.Arrays.copyOf(line, line.length - 1);
        }
        return line;
    }

    void handleInput(byte[] in) {
        String[] args = Util.goTrimSpace(GoString.decode(in)).split(" ", -1);
        if (args.length == 1 && args[0].isEmpty()) {
            paused = !paused;
            if (paused) {
                output.setPaused(true);
                job.pause();
                printBanner();
            } else {
                resumeJob();
            }
        } else {
            command(args);
        }
        if (paused) {
            printPrompt();
        }
    }

    private void command(String[] args) {
        switch (args[0]) {
            case "?", "help" -> printHelp();
            case "resume" -> {
                paused = false;
                resumeJob();
            }
            case "restart" -> {
                job.reset(false);
                paused = false;
                output.setPaused(false);
                output.info("Restarting the current ffuf job!");
                job.resume();
            }
            case "show" -> {
                for (Stdoutput.Result r : output.getCurrentResults()) {
                    output.printResult(r);
                }
            }
            case "savejson" -> {
                if (args.length < 2) {
                    output.error("Please define the filename");
                } else if (args.length > 2) {
                    output.error("Too many arguments for \"savejson\"");
                } else {
                    String err = output.saveFile(args[1], "json");
                    if (err != null) {
                        output.error(err);
                    } else {
                        output.info("Output file successfully saved!");
                    }
                }
            }
            case "fc" -> filterCommand(args, "status", "status code", true);
            case "afc" -> filterCommand(args, "status", "status code", false);
            case "fl" -> filterCommand(args, "line", "line count", true);
            case "afl" -> filterCommand(args, "line", "line count", false);
            case "fw" -> filterCommand(args, "word", "word count", true);
            case "afw" -> filterCommand(args, "word", "word count", false);
            case "fs" -> filterCommand(args, "size", "response size", true);
            case "afs" -> filterCommand(args, "size", "response size", false);
            case "ft" -> filterCommand(args, "time", "response time", true);
            case "aft" -> filterCommand(args, "time", "response time", false);
            case "queueshow" -> printQueue();
            case "queuedel" -> {
                if (args.length < 2) {
                    output.error("Please define the index of a queued job to remove. Use \"queueshow\" for listing of jobs.");
                } else if (args.length > 2) {
                    output.error("Too many arguments for \"queuedel\"");
                } else {
                    deleteQueue(args[1]);
                }
            }
            case "queueskip" -> {
                job.skipQueue();
                output.info("Skipping to the next queued job");
            }
            case "rate" -> {
                if (args.length < 2) {
                    output.error("Please define the new rate");
                } else if (args.length > 2) {
                    output.error("Too many arguments for \"rate\"");
                } else {
                    String err = atoiError(args[1]);
                    if (err != null) {
                        output.error("Could not adjust rate: " + err);
                    } else {
                        job.rate().changeRate(GoStrconv.parseInt(args[1], 10).value());
                    }
                }
            }
            default -> {
                if (paused) {
                    output.warning("Unknown command: \"" + args[0] + "\". Enter \"help\" for a list of available commands");
                } else {
                    output.error("NOPE");
                }
            }
        }
    }

    /**
     * A filter command: fc, fl, fw, fs and ft (re)configure a filter ("none" removes it), afc, afl, afw, afs and aft
     * append to it. The messages name the filter by label; the append prompts of the size filter name it "size".
     */
    private void filterCommand(String[] args, String name, String label, boolean replace) {
        String cmd = args[0];
        if (args.length < 2) {
            if (replace) {
                output.error("Please define a value for " + label + " filter, or \"none\" for removing it");
            } else {
                output.error("Please define a value to append to " + (name.equals("size") ? "size" : label) + " filter");
            }
        } else if (args.length > 2) {
            output.error("Too many arguments for \"" + cmd + "\"");
        } else {
            updateFilter(name, args[1], replace);
            output.info("New " + label + " filter value set");
        }
    }

    /** strconv.Atoi's error text for s, or null when s is a decimal int. */
    static String atoiError(String s) {
        GoStrconv.IntResult r = GoStrconv.parseInt(s, 10);
        if (r.error() == null) {
            return null;
        }
        String kind = r.error() == GoStrconv.Error.SYNTAX ? "invalid syntax" : "value out of range";
        return "strconv.Atoi: parsing \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(s) + "\": " + kind;
    }

    /** The minimal response that re-runs the active filters on a collected result: its status and its counts. */
    static Response resultProbe(Stdoutput.Result res) {
        Response r = new Response();
        r.statusCode = res.statusCode;
        r.contentLines = res.contentLines;
        r.contentWords = res.contentWords;
        r.contentLength = res.contentLength;
        return r;
    }

    /** Keeps only the current results that pass every active filter, in one step under the output's lock. */
    private void refreshResults() {
        Map<String, FilterProvider> filters = job.config().matcherManager.getFilters();
        output.filterCurrentResults(res -> {
            for (FilterProvider f : filters.values()) {
                if (f.filter(resultProbe(res))) {
                    return false;
                }
            }
            return true;
        });
    }

    private void updateFilter(String name, String value, boolean replace) {
        if (value.equals("none")) {
            job.config().matcherManager.removeFilter(name);
        } else {
            try {
                job.config().matcherManager.addFilter(name, value, replace);
            } catch (IllegalArgumentException e) {
                // an invalid value is ignored and the filter stays as it was
            }
        }
        refreshResults();
    }

    private void printQueue() {
        List<String> queued = job.queuedJobs();
        if (queued.isEmpty()) {
            output.info("Job queue is empty");
            return;
        }
        output.raw("Queued jobs:\n");
        for (int index = 0; index < queued.size(); index++) {
            output.raw(" [" + index + "] : " + queued.get(index) + (index == 0 ? " (active job)" : "") + "\n");
        }
    }

    private void deleteQueue(String in) {
        if (atoiError(in) != null) {
            output.warning("Not a number: " + in);
            return;
        }
        long index = GoStrconv.parseInt(in, 10).value();
        if (index < 0 || index > job.queuedJobs().size() - 1) {
            output.warning("No such queued job. Use \"queueshow\" to list the jobs in queue");
        } else if (index == 0) {
            output.warning("Cannot delete the currently running job. Use \"queueskip\" to advance to the next one");
        } else {
            job.deleteQueueItem((int) index);
            output.info("Job successfully deleted!");
        }
    }

    /** Restores live result printing and resumes the job, first reporting the matches held while paused. */
    private void resumeJob() {
        int n = output.pendingResults();
        if (n > 0) {
            output.info(n + " new match(es) found while paused. Type \"show\" to display them.");
        }
        output.setPaused(false);
        job.resume();
    }

    private void printBanner() {
        output.raw("entering interactive mode\ntype \"help\" for a list of commands, or ENTER to resume.\n");
    }

    private void printPrompt() {
        int n = output.pendingResults();
        output.raw(n > 0 ? "[" + n + " new] > " : "> ");
    }

    private void printHelp() {
        String fc = "";
        String fl = "";
        String fs = "";
        String ft = "";
        String fw = "";
        for (Map.Entry<String, FilterProvider> e : job.config().matcherManager.getFilters().entrySet()) {
            String active = "(active: " + e.getValue().repr() + ")";
            switch (e.getKey()) {
                case "status" -> fc = active;
                case "line" -> fl = active;
                case "word" -> fw = active;
                case "size" -> fs = active;
                case "time" -> ft = active;
                default -> {
                    // the help lists only the filters the console can change
                }
            }
        }
        String rate = "(active: " + job.rate().currentConfiguredRate() + ")";
        output.raw(String.format(HELP, fc, fc, fl, fl, fw, fw, fs, fs, ft, ft, rate));
    }
}
