package io.github.ffuf.pkg.ffuf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The command line flags and a parser that follows the syntax rules of Go's flag package: single or double dash,
 * "-flag=value", boolean flags without a value, parsing stops at the first non-flag argument or at "--".
 */
public class Flags {
    public static final String SECTION_HTTP = "http";
    public static final String SECTION_GENERAL = "general";
    public static final String SECTION_COMPAT = "compat";
    public static final String SECTION_MATCHER = "matcher";
    public static final String SECTION_FILTER = "filter";
    public static final String SECTION_INPUT = "input";
    public static final String SECTION_OUTPUT = "output";

    /** Thrown when parsing fails; the message is printed on standard error before the usage screen. */
    public static class FlagException extends Exception {
        /** Whether -h or -help asked for the usage text. */
        public final boolean help;

        /** Creates the exception; help is true when -h or -help asked for the usage text. */
        public FlagException(String message, boolean help) {
            super(message);
            this.help = help;
        }
    }

    /** One registered flag. */
    public static class Flag {
        public final String name;
        public final String section;
        public final String usage;
        public final String defValue;
        public final boolean isBool;
        final Function<String, String> setter;

        Flag(String name, String section, String usage, String defValue, boolean isBool, Function<String, String> setter) {
            this.name = name;
            this.section = section;
            this.usage = usage;
            this.defValue = defValue;
            this.isBool = isBool;
            this.setter = setter;
        }
    }

    private final TreeMap<String, Flag> flags = new TreeMap<>();
    private final Set<String> actual = new HashSet<>();

    /** Declares every flag, bound to the fields of o. */
    public Flags(ConfigOptions o) {
        ConfigOptions.Http h = o.http;
        ConfigOptions.General g = o.general;
        ConfigOptions.Input in = o.input;
        ConfigOptions.Output out = o.output;
        multi("b", SECTION_HTTP, "Cookie data \"NAME1=VALUE1; NAME2=VALUE2\" for copy as curl functionality.", () -> h.cookies, v -> h.cookies = v, "cookie");
        str("d", SECTION_HTTP, "POST data", () -> h.data, v -> h.data = v, "data", "data-ascii", "data-binary");
        bool("r", SECTION_HTTP, "Follow redirects", h.followRedirects, v -> h.followRedirects = v);
        multi("H", SECTION_HTTP, "Header \"Name: Value\", separated by colon. Multiple -H flags are accepted.", () -> h.headers, v -> h.headers = v);
        bool("ignore-body", SECTION_HTTP, "Do not fetch the response content.", h.ignoreBody, v -> h.ignoreBody = v);
        str("X", SECTION_HTTP, "HTTP method to use", () -> h.method, v -> h.method = v);
        str("x", SECTION_HTTP, "Proxy URL (SOCKS5 or HTTP). For example: http://127.0.0.1:8080 or socks5://127.0.0.1:8080", () -> h.proxyUrl, v -> h.proxyUrl = v);
        bool("raw", SECTION_HTTP, "Do not encode URI", h.raw, v -> h.raw = v);
        bool("recursion", SECTION_HTTP, "Scan recursively. Only FUZZ keyword is supported, and URL (-u) has to end in it.", h.recursion, v -> h.recursion = v);
        integer("recursion-depth", SECTION_HTTP, "Maximum recursion depth.", h.recursionDepth, v -> h.recursionDepth = v);
        str("recursion-strategy", SECTION_HTTP, "Recursion strategy: \"default\" for a redirect based, and \"greedy\" to recurse on all matches", () -> h.recursionStrategy, v -> h.recursionStrategy = v);
        str("replay-proxy", SECTION_HTTP, "Replay matched requests using this proxy.", () -> h.replayProxyUrl, v -> h.replayProxyUrl = v);
        str("sni", SECTION_HTTP, "Target TLS SNI, does not support FUZZ keyword", () -> h.sni, v -> h.sni = v);
        integer("timeout", SECTION_HTTP, "HTTP request timeout in seconds.", h.timeout, v -> h.timeout = v);
        str("u", SECTION_HTTP, "Target URL", () -> h.url, v -> h.url = v);
        bool("http2", SECTION_HTTP, "Use HTTP2 protocol", h.http2, v -> h.http2 = v);
        str("cc", SECTION_HTTP, "Client cert for authentication. Client key needs to be defined as well for this to work", () -> h.clientCert, v -> h.clientCert = v);
        str("ck", SECTION_HTTP, "Client key for authentication. Client certificate needs to be defined as well for this to work", () -> h.clientKey, v -> h.clientKey = v);
        str("preflight-mode", SECTION_HTTP, "Preflight execution mode: \"per-request\" or \"per-thread\"", () -> h.preflightMode, v -> h.preflightMode = v);
        str("preflight-error", SECTION_HTTP, "Preflight error handling: \"abort\" or \"ignore\"", () -> h.preflightError, v -> h.preflightError = v);
        bool("preflight-anyhost", SECTION_HTTP, "Allow a -preflight-var value to change a flight request's scheme, host or port. Off by default: those values come from the scanned target.", h.preflightAnyHost, v -> h.preflightAnyHost = v);

        bool("ac", SECTION_GENERAL, "Automatically calibrate filtering options", g.autoCalibration, v -> g.autoCalibration = v);
        str("ack", SECTION_GENERAL, "Autocalibration keyword", () -> g.autoCalibrationKeyword, v -> g.autoCalibrationKeyword = v);
        bool("ach", SECTION_GENERAL, "Per host autocalibration", g.autoCalibrationPerHost, v -> g.autoCalibrationPerHost = v);
        boolean[] acsSet = {false};
        add(new Flag("acs", SECTION_GENERAL, "Custom auto-calibration strategies. Can be used multiple times. Implies -ac", "", false, v -> {
            if (!acsSet[0]) {
                g.autoCalibrationStrategies = new ArrayList<>();
                acsSet[0] = true;
            }
            g.autoCalibrationStrategies.addAll(List.of(v.split(",", -1)));
            return null;
        }));
        multi("acc", SECTION_GENERAL, "Custom auto-calibration string. Can be used multiple times. Implies -ac", () -> g.autoCalibrationStrings, v -> g.autoCalibrationStrings = v);
        bool("c", SECTION_GENERAL, "Colorize output.", g.colors, v -> g.colors = v);
        str("config", SECTION_GENERAL, "Load configuration from a file", () -> g.configFile, v -> g.configFile = v);
        str("p", SECTION_GENERAL, "Seconds of delay between requests, or a range of random delay. For example \"0.1\" or \"0.1-2.0\"", () -> g.delay, v -> g.delay = v);
        bool("json", SECTION_GENERAL, "JSON output, printing newline-delimited JSON records", g.json, v -> g.json = v);
        integer("maxtime", SECTION_GENERAL, "Maximum running time in seconds for entire process.", g.maxTime, v -> g.maxTime = v);
        integer("maxtime-job", SECTION_GENERAL, "Maximum running time in seconds per job.", g.maxTimeJob, v -> g.maxTimeJob = v);
        bool("noninteractive", SECTION_GENERAL, "Disable the interactive console functionality", g.noninteractive, v -> g.noninteractive = v);
        bool("s", SECTION_GENERAL, "Do not print additional information (silent mode)", g.quiet, v -> g.quiet = v);
        integer("rate", SECTION_GENERAL, "Rate of requests per second", g.rate, v -> g.rate = v);
        str("scraperfile", SECTION_GENERAL, "Custom scraper file path", () -> g.scraperFile, v -> g.scraperFile = v);
        str("scrapers", SECTION_GENERAL, "Active scraper groups", () -> g.scrapers, v -> g.scrapers = v);
        str("search", SECTION_GENERAL, "Search for a FFUFHASH payload from ffuf history", () -> g.searchhash, v -> g.searchhash = v);
        bool("V", SECTION_GENERAL, "Show version information.", g.showVersion, v -> g.showVersion = v);
        bool("sf", SECTION_GENERAL, "Stop when > 95% of responses return 403 Forbidden", g.stopOn403, v -> g.stopOn403 = v);
        bool("sa", SECTION_GENERAL, "Stop on all error cases. Implies -sf and -se.", g.stopOnAll, v -> g.stopOnAll = v);
        bool("se", SECTION_GENERAL, "Stop on spurious errors", g.stopOnErrors, v -> g.stopOnErrors = v);
        integer("t", SECTION_GENERAL, "Number of concurrent threads.", g.threads, v -> g.threads = v);
        bool("v", SECTION_GENERAL, "Verbose output, printing full URL and redirect location (if any) with the results.", g.verbose, v -> g.verbose = v);

        bool("D", SECTION_INPUT, "DirSearch wordlist compatibility mode. Used in conjunction with -e flag.", in.dirSearchCompat, v -> in.dirSearchCompat = v);
        wordlist("enc", SECTION_INPUT, "Encoders for keywords, eg. 'FUZZ:urlencode b64encode'", () -> in.encoders, v -> in.encoders = v);
        str("e", SECTION_INPUT, "Comma separated list of extensions. Extends FUZZ keyword.", () -> in.extensions, v -> in.extensions = v);
        bool("ic", SECTION_INPUT, "Ignore wordlist comments", in.ignoreWordlistComments, v -> in.ignoreWordlistComments = v);
        str("mode", SECTION_INPUT, "Multi-wordlist operation mode. Available modes: clusterbomb, pitchfork, sniper", () -> in.inputMode, v -> in.inputMode = v);
        integer("input-num", SECTION_INPUT, "Number of inputs to test. Used in conjunction with --input-cmd.", in.inputNum, v -> in.inputNum = v);
        str("input-shell", SECTION_INPUT, "Shell to be used for running command", () -> in.inputShell, v -> in.inputShell = v);
        multi("input-cmd", SECTION_INPUT, "Command producing the input. --input-num is required when using this input method. Overrides -w.", () -> in.inputCommands, v -> in.inputCommands = v);
        str("request", SECTION_INPUT, "File containing the raw http request", () -> in.request, v -> in.request = v);
        str("request-proto", SECTION_INPUT, "Protocol to use along with raw request", () -> in.requestProto, v -> in.requestProto = v);
        wordlist("w", SECTION_INPUT, "Wordlist file path and (optional) keyword separated by colon. eg. '/path/to/wordlist:KEYWORD'", () -> in.wordlists, v -> in.wordlists = v);

        str("audit-log", SECTION_OUTPUT, "Write audit log containing all requests, responses and config", () -> out.auditLog, v -> out.auditLog = v);
        str("debug-log", SECTION_OUTPUT, "Write all of the internal logging to the specified file.", () -> out.debugLog, v -> out.debugLog = v);
        str("od", SECTION_OUTPUT, "Directory path to store matched results to.", () -> out.outputDirectory, v -> out.outputDirectory = v);
        str("o", SECTION_OUTPUT, "Write output to file", () -> out.outputFile, v -> out.outputFile = v);
        str("of", SECTION_OUTPUT, "Output file format. Available formats: json, ejson, html, md, csv, ecsv (or, 'all' for all formats)", () -> out.outputFormat, v -> out.outputFormat = v);
        bool("or", SECTION_OUTPUT, "Don't create the output file if we don't have results", out.outputSkipEmptyFile, v -> out.outputSkipEmptyFile = v);

        matchFlags("f", SECTION_FILTER, o.filter, true);
        matchFlags("m", SECTION_MATCHER, o.matcher, false);

        add(new Flag("compressed", SECTION_COMPAT, "Dummy flag for copy as curl functionality (ignored)", "true", true, v -> GoStrconv.parseBool(v) == null ? "parse error" : null));
        add(new Flag("i", SECTION_COMPAT, "Dummy flag for copy as curl functionality (ignored)", "true", true, v -> GoStrconv.parseBool(v) == null ? "parse error" : null));
        add(new Flag("k", SECTION_COMPAT, "Dummy flag for backwards compatibility", "false", true, v -> GoStrconv.parseBool(v) == null ? "parse error" : null));
        add(new Flag("preflight", SECTION_HTTP, "Raw HTTP request file to run before each fuzzing request (repeatable, order matters). Inherits main config headers, including -H auth and -b cookies.", "", false, v -> {
            if (v.isEmpty()) {
                return "-preflight requires a request file path";
            }
            h.preflights.add(new PreflightConfig(v));
            return null;
        }));
        add(new Flag("preflight-var", SECTION_HTTP, "Extract a variable from the preceding -preflight response: \"NAME:regex\", matched against the body, then the headers (repeatable)", "", false,
                v -> FlightVars.appendVar(h.preflights, "-preflight-var", "-preflight", v)));
        add(new Flag("postflight", SECTION_HTTP, "Raw HTTP request file to run after each fuzzing request (repeatable, order matters). Inherits main config headers, including -H auth and -b cookies.", "", false, v -> {
            if (v.isEmpty()) {
                return "-postflight requires a request file path";
            }
            h.postflights.add(new PreflightConfig(v));
            return null;
        }));
        add(new Flag("postflight-var", SECTION_HTTP, "Extract a variable from the preceding -postflight response: \"NAME:regex\", matched against the body, then the headers (repeatable)", "", false,
                v -> FlightVars.appendVar(h.postflights, "-postflight-var", "-postflight", v)));
        add(new Flag("preflight-var-auto", SECTION_HTTP, "Extract a variable from the preceding -preflight response by name: \"NAME:key\" tries form field, meta tag, cookie and header; \"NAME:[form|meta|cookie|header]key\" pins the source (repeatable)", "", false,
                v -> FlightVars.appendVarAuto(h.preflights, "-preflight-var-auto", "-preflight", v)));
        add(new Flag("postflight-var-auto", SECTION_HTTP, "Extract a variable from the preceding -postflight response by name, like -preflight-var-auto (repeatable)", "", false,
                v -> FlightVars.appendVarAuto(h.postflights, "-postflight-var-auto", "-postflight", v)));
    }

    private void matchFlags(String p, String section, ConfigOptions.MatchOptions m, boolean filter) {
        if (filter) {
            str(p + "mode", section, "Filter set operator. Either of: and, or", () -> m.mode, v -> m.mode = v);
            str(p + "l", section, "Filter by amount of lines in response. Comma separated list of line counts and ranges", () -> m.lines, v -> m.lines = v);
            str(p + "r", section, "Filter regexp", () -> m.regexp, v -> m.regexp = v);
            str(p + "s", section, "Filter HTTP response size. Comma separated list of sizes and ranges", () -> m.size, v -> m.size = v);
            str(p + "c", section, "Filter HTTP status codes from response. Comma separated list of codes and ranges", () -> m.status, v -> m.status = v);
            str(p + "t", section, "Filter by number of milliseconds to the first response byte, either greater or less than. EG: >100 or <100", () -> m.time, v -> m.time = v);
            str(p + "w", section, "Filter by amount of words in response. Comma separated list of word counts and ranges", () -> m.words, v -> m.words = v);
        } else {
            str(p + "mode", section, "Matcher set operator. Either of: and, or", () -> m.mode, v -> m.mode = v);
            str(p + "l", section, "Match amount of lines in response", () -> m.lines, v -> m.lines = v);
            str(p + "r", section, "Match regexp", () -> m.regexp, v -> m.regexp = v);
            str(p + "s", section, "Match HTTP response size", () -> m.size, v -> m.size = v);
            str(p + "c", section, "Match HTTP status codes, or \"all\" for everything.", () -> m.status, v -> m.status = v);
            str(p + "t", section, "Match how many milliseconds to the first response byte, either greater or less than. EG: >100 or <100", () -> m.time, v -> m.time = v);
            str(p + "w", section, "Match amount of words in response", () -> m.words, v -> m.words = v);
        }
    }

    private void add(Flag f) {
        flags.put(f.name, f);
    }

    private void str(String name, String section, String usage, Supplier<String> get, Consumer<String> set, String... aliases) {
        Function<String, String> setter = v -> {
            set.accept(v);
            return null;
        };
        add(new Flag(name, section, usage, get.get(), false, setter));
        for (String a : aliases) {
            add(new Flag(a, SECTION_COMPAT, usage, get.get(), false, setter));
        }
    }

    private void bool(String name, String section, String usage, boolean current, Consumer<Boolean> set) {
        add(new Flag(name, section, usage, Boolean.toString(current), true, v -> {
            Boolean b = GoStrconv.parseBool(v);
            if (b == null) {
                return "parse error";
            }
            set.accept(b);
            return null;
        }));
    }

    private void integer(String name, String section, String usage, long current, Consumer<Long> set) {
        add(new Flag(name, section, usage, Long.toString(current), false, v -> {
            GoStrconv.IntResult n = GoStrconv.parseInt(v, 0);
            if (n.error() != null) {
                return n.error() == GoStrconv.Error.RANGE ? "value out of range" : "parse error";
            }
            set.accept(n.value());
            return null;
        }));
    }

    private void multi(String name, String section, String usage, Supplier<List<String>> get, Consumer<List<String>> set, String... aliases) {
        Function<String, String> setter = v -> {
            List<String> l = get.get() == null ? new ArrayList<>() : get.get();
            l.add(v);
            set.accept(l);
            return null;
        };
        add(new Flag(name, section, usage, "", false, setter));
        for (String a : aliases) {
            add(new Flag(a, SECTION_COMPAT, usage, "", false, setter));
        }
    }

    private void wordlist(String name, String section, String usage, Supplier<List<String>> get, Consumer<List<String>> set) {
        add(new Flag(name, section, usage, "", false, v -> {
            List<String> l = get.get() == null ? new ArrayList<>() : get.get();
            l.addAll(List.of(v.split(",", -1)));
            set.accept(l);
            return null;
        }));
    }

    /**
     * Parses args and sets the flags they name.
     *
     * @return the remaining non-flag arguments
     * @throws FlagException for an unknown flag, a missing value or a value the flag rejects
     */
    public List<String> parse(List<String> args) throws FlagException {
        int i = 0;
        while (i < args.size()) {
            String s = args.get(i);
            if (s.length() < 2 || s.charAt(0) != '-') {
                break;
            }
            int minuses = 1;
            if (s.charAt(1) == '-') {
                minuses++;
                if (s.length() == 2) {
                    i++;
                    break;
                }
            }
            String name = s.substring(minuses);
            if (name.isEmpty() || name.charAt(0) == '-' || name.charAt(0) == '=') {
                throw new FlagException("bad flag syntax: " + s, false);
            }
            i++;
            boolean hasValue = false;
            String value = "";
            int eq = name.indexOf('=', 1);
            if (eq >= 1) {
                value = name.substring(eq + 1);
                hasValue = true;
                name = name.substring(0, eq);
            }
            Flag f = flags.get(name);
            if (f == null) {
                if (name.equals("help") || name.equals("h")) {
                    throw new FlagException(null, true);
                }
                throw new FlagException("flag provided but not defined: -" + name, false);
            }
            if (f.isBool) {
                if (hasValue) {
                    String err = f.setter.apply(value);
                    if (err != null) {
                        throw new FlagException("invalid boolean value \"" + goQuote(value) + "\" for -" + name + ": " + err, false);
                    }
                } else {
                    f.setter.apply("true");
                }
            } else {
                if (!hasValue && i < args.size()) {
                    hasValue = true;
                    value = args.get(i);
                    i++;
                }
                if (!hasValue) {
                    throw new FlagException("flag needs an argument: -" + name, false);
                }
                String err = f.setter.apply(value);
                if (err != null) {
                    throw new FlagException("invalid value \"" + goQuote(value) + "\" for flag -" + name + ": " + err, false);
                }
            }
            actual.add(name);
        }
        return new ArrayList<>(args.subList(i, args.size()));
    }

    /** {@return s escaped as Go's %q verb escapes it, without the surrounding quotes} */
    public static String goQuote(String s) {
        return GoUnicode.quoteBody(s);
    }

    /** {@return true when the flag was given on the command line} */
    public boolean wasSet(String name) {
        return actual.contains(name);
    }

    /** {@return every declared flag in declaration order} */
    public Iterable<Flag> all() {
        return flags.values();
    }
}
