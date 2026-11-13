package io.github.ffuf;

import io.github.ffuf.pkg.assembly.Assembly;
import io.github.ffuf.pkg.engine.Job;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.ffuf.Flags;
import io.github.ffuf.pkg.ffuf.MultiError;
import io.github.ffuf.pkg.ffuf.TomlConfig;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.filter.MatcherManager;
import io.github.ffuf.pkg.interactive.Termhandler;
import io.github.ffuf.pkg.output.GoPrintStream;
import io.github.ffuf.pkg.output.Stdoutput;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Command line entry point: reads the configuration, builds the job and runs the scan. */
public final class Main {
    private static final PrintStream OUT = Stdoutput.OUT;
    private static final PrintStream ERR = Stdoutput.ERR;
    private static Flags flags;

    private Main() {
    }

    /** Runs ffuf with the command line arguments and exits with its status code. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static ConfigOptions parseFlags(ConfigOptions opts, String[] args) throws Flags.FlagException {
        flags = new Flags(opts);
        flags.parse(List.of(args));
        return opts;
    }

    /** Parses the command line; returns null when the program must exit with the code in exitHolder. */
    private static ConfigOptions parseOrExit(ConfigOptions opts, String[] args, int[] exitHolder) {
        try {
            return parseFlags(opts, args);
        } catch (Flags.FlagException e) {
            if (!e.help) {
                ERR.print(e.getMessage() + "\n");
            }
            Help.usage(flags);
            exitHolder[0] = e.help ? 0 : 2;
            return null;
        }
    }

    static int run(String[] args) {
        String optsErr = null;
        ConfigOptions opts;
        // When a configuration folder cannot be created, the run goes on without the default config file.
        Util.checkOrCreateConfigDir();
        String confFile = Util.configDir() + "/ffufrc";
        if (!Files.exists(Paths.get(confFile))) {
            confFile = Util.homeDir() + "/.ffufrc";
        }
        opts = new ConfigOptions();
        try {
            TomlConfig.readInto(opts, confFile);
        } catch (TomlConfig.TomlException e) {
            optsErr = e.getMessage();
        }
        int[] exit = {0};
        opts = parseOrExit(opts, args, exit);
        if (opts == null) {
            return exit[0];
        }
        if (!opts.general.searchhash.isEmpty()) {
            List<String> sargv = new ArrayList<>();
            sargv.add("ffuf");
            sargv.addAll(List.of(args));
            return History.search(opts.general.searchhash, sargv);
        }
        if (opts.general.showVersion) {
            OUT.print("ffuf version: " + Util.version() + "\n");
            return 0;
        }
        PrintStream debugStream = null;
        if (!opts.output.debugLog.isEmpty()) {
            try {
                debugStream = new GoPrintStream(new FileOutputStream(opts.output.debugLog, true));
            } catch (IOException e) {
                ERR.print("Disabling logging, encountered error(s): open " + opts.output.debugLog + ": no such file or directory\n");
            }
        }
        try {
            return runLogged(opts, args, optsErr, debugLogger(debugStream));
        } finally {
            if (debugStream != null) {
                debugStream.close();
            }
        }
    }

    /** Returns a logger that writes timestamped lines to the stream, or one that drops them when the stream is null. */
    private static Consumer<String> debugLogger(PrintStream ps) {
        if (ps == null) {
            return s -> { };
        }
        return s -> {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
            synchronized (ps) {
                ps.print(ts + " " + s + (s.endsWith("\n") ? "" : "\n"));
            }
        };
    }

    /** Runs the scan once the debug log is set up; returns the exit code. */
    private static int runLogged(ConfigOptions opts, String[] args, String optsErr, Consumer<String> debugLog) {
        int[] exit = {0};
        if (optsErr != null) {
            debugLog.accept("Error while opening default config file: " + optsErr);
        }
        if (!opts.general.configFile.isEmpty()) {
            try {
                opts = TomlConfig.readConfig(opts.general.configFile);
            } catch (TomlConfig.TomlException e) {
                ERR.print("Encoutered error(s): " + e.getMessage() + "\n");
                Help.usage(flags);
                ERR.print("Encoutered error(s): " + e.getMessage() + "\n");
                return 1;
            }
            opts = parseOrExit(opts, args, exit);
            if (opts == null) {
                return exit[0];
            }
        }
        List<String> argv = new ArrayList<>();
        argv.add("ffuf");
        argv.addAll(List.of(args));
        Config conf;
        try {
            conf = Config.fromOptions(opts, argv);
        } catch (Config.ConfigException e) {
            return fail(e.getMessage());
        }
        MultiError buildErrs = new MultiError();
        Job job = Assembly.buildJob(conf, buildErrs);
        job.setDebugLog(debugLog);
        try {
            if (!buildErrs.isEmpty()) {
                return fail(buildErrs.errorOrNull());
            }
            String filterErr = setupFilters(opts, conf, flags);
            if (filterErr != null) {
                return fail(filterErr);
            }
            if (!conf.noninteractive) {
                Consumer<String> log = debugLog;
                Thread console = new Thread(() -> {
                    try {
                        Termhandler.handle(job);
                    } catch (IOException e) {
                        log.accept("Error while trying to initialize interactive session: " + e.getMessage());
                    }
                }, "ffuf-interactive");
                console.setDaemon(true);
                console.start();
            }
            job.start();
            return 0;
        } finally {
            if (job.auditLogger() != null) {
                try {
                    job.auditLogger().close();
                } catch (IOException e) {
                    // The scan already finished, so the exit code stays; the lost audit tail is reported.
                    System.err.print("Error closing the audit log: " + e.getMessage() + "\n");
                }
            }
        }
    }

    /**
     * Installs the matchers and filters: the default status matcher applies unless only non-status matchers were
     * given on the command line. Returns the combined error report, or null.
     */
    static String setupFilters(ConfigOptions opts, Config conf, Flags parsed) {
        boolean statusSet = parsed.wasSet("mc");
        boolean matcherSet = parsed.wasSet("ms") || parsed.wasSet("ml") || parsed.wasSet("mw") || parsed.wasSet("mr") || parsed.wasSet("mt");
        boolean responseMatcherSet = parsed.wasSet("ms") || parsed.wasSet("ml") || parsed.wasSet("mw");
        MultiError filterErrs = new MultiError();
        conf.matcherManager = MatcherManager.fromConfig(opts, statusSet || !matcherSet, filterErrs);
        boolean warnIgnoreBody = responseMatcherSet || !opts.filter.size.isEmpty() || !opts.filter.lines.isEmpty() || !opts.filter.words.isEmpty();
        if (conf.ignoreBody && warnIgnoreBody) {
            OUT.print("*** Warning: possible undesired combination of -ignore-body and the response options: fl,fs,fw,ml,ms and mw.\n");
        }
        return filterErrs.errorOrNull();
    }

    private static int fail(String err) {
        ERR.print("Encountered error(s): " + err + "\n");
        Help.usage(flags);
        ERR.print("Encountered error(s): " + err + "\n");
        return 1;
    }
}
