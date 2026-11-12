package io.github.ffuf.pkg.ffuf;

import java.util.ArrayList;
import java.util.List;

/** Every user-settable option, grouped by section. */
public class ConfigOptions {

    /** HTTP options: target, headers, proxies, timeouts and flights. */
    public static class Http {
        public List<String> cookies = new ArrayList<>();
        public String data = "";
        public boolean followRedirects = false;
        public List<String> headers = null;
        public boolean ignoreBody = false;
        public String method = "";
        public String proxyUrl = "";
        public boolean raw = false;
        public boolean recursion = false;
        public long recursionDepth = 0;
        public String recursionStrategy = "default";
        public String replayProxyUrl = "";
        public String sni = "";
        public long timeout = 10;
        public String url = "";
        public boolean http2 = false;
        public String clientCert = "";
        public String clientKey = "";
        public List<PreflightConfig> preflights = new ArrayList<>();
        public List<PreflightConfig> postflights = new ArrayList<>();
        public String preflightMode = "per-request";
        public String preflightError = "abort";
        public boolean preflightAnyHost = false;
    }

    /** General options: calibration, threads, rate, stop conditions and the run mode. */
    public static class General {
        public boolean autoCalibration = false;
        public String autoCalibrationKeyword = "FUZZ";
        public boolean autoCalibrationPerHost = false;
        public List<String> autoCalibrationStrategies = new ArrayList<>(List.of("basic"));
        public List<String> autoCalibrationStrings = null;
        public boolean colors = false;
        public String configFile = "";
        public String delay = "";
        public boolean json = false;
        public long maxTime = 0;
        public long maxTimeJob = 0;
        public boolean noninteractive = false;
        public boolean quiet = false;
        public long rate = 0;
        public String scraperFile = "";
        public String scrapers = "all";
        public String searchhash = "";
        public boolean showVersion = false;
        public boolean stopOn403 = false;
        public boolean stopOnAll = false;
        public boolean stopOnErrors = false;
        public long threads = 40;
        public boolean verbose = false;
    }

    /** Input options: wordlists, commands, encoders, extensions and request files. */
    public static class Input {
        public boolean dirSearchCompat = false;
        public List<String> encoders = null;
        public String extensions = "";
        public boolean ignoreWordlistComments = false;
        public String inputMode = "clusterbomb";
        public long inputNum = 100;
        public String inputShell = "";
        public List<String> inputCommands = null;
        public String request = "";
        public String requestProto = "https";
        public List<String> wordlists = null;
    }

    /** Output options: debug and audit logs, output files and formats. */
    public static class Output {
        public String auditLog = "";
        public String debugLog = "";
        public String outputDirectory = "";
        public String outputFile = "";
        public String outputFormat = "json";
        public boolean outputSkipEmptyFile = false;
    }

    /** Matcher and filter options, one value per -m and -f flag. */
    public static class MatchOptions {
        public String mode = "or";
        public String lines = "";
        public String regexp = "";
        public String size = "";
        public String status = "";
        public String time = "";
        public String words = "";
    }

    public final Http http = new Http();
    public final General general = new General();
    public final Input input = new Input();
    public final Output output = new Output();
    public final MatchOptions filter = new MatchOptions();
    public final MatchOptions matcher = new MatchOptions();

    /** Creates the options with their command line defaults. */
    public ConfigOptions() {
        matcher.status = "200-299,301,302,307,401,403,405,500";
    }

    /**
     * Serialises the options with ffuf's JSON field names and order.
     *
     * @param timeValue the JSON text written for the time fields
     * @return the options as a JSON object
     */
    public String toJson(String timeValue) {
        Json.Obj root = new Json.Obj();
        root.put("filters", matchJson(filter));
        Json.Obj g = new Json.Obj();
        g.put("autocalibration", general.autoCalibration);
        g.put("autocalibration_keyword", general.autoCalibrationKeyword);
        g.put("autocalibration_per_host", general.autoCalibrationPerHost);
        g.put("autocalibration_strategies", general.autoCalibrationStrategies);
        g.put("autocalibration_strings", general.autoCalibrationStrings);
        g.put("colors", general.colors);
        g.put("config_file", general.configFile);
        g.put("delay", general.delay);
        g.put("json", general.json);
        g.put("maxtime", general.maxTime);
        g.put("maxtime_job", general.maxTimeJob);
        g.put("noninteractive", general.noninteractive);
        g.put("quiet", general.quiet);
        g.put("rate", general.rate);
        g.put("scraperfile", general.scraperFile);
        g.put("scrapers", general.scrapers);
        g.put("stop_on_403", general.stopOn403);
        g.put("stop_on_all", general.stopOnAll);
        g.put("stop_on_errors", general.stopOnErrors);
        g.put("threads", general.threads);
        g.put("verbose", general.verbose);
        root.put("general", g);
        Json.Obj h = new Json.Obj();
        h.put("data", http.data);
        h.put("follow_redirects", http.followRedirects);
        h.put("headers", http.headers);
        h.put("ignore_body", http.ignoreBody);
        h.put("method", http.method);
        h.put("proxy_url", http.proxyUrl);
        h.put("raw", http.raw);
        h.put("recursion", http.recursion);
        h.put("recursion_depth", http.recursionDepth);
        h.put("recursion_strategy", http.recursionStrategy);
        h.put("replay_proxy_url", http.replayProxyUrl);
        h.put("sni", http.sni);
        h.put("timeout", http.timeout);
        h.put("url", http.url);
        h.put("http2", http.http2);
        h.put("client-cert", http.clientCert);
        h.put("client-key", http.clientKey);
        h.put("preflights", flightsJson(http.preflights));
        h.put("postflights", flightsJson(http.postflights));
        h.put("preflight_mode", http.preflightMode);
        h.put("preflight_error", http.preflightError);
        h.put("preflight_anyhost", http.preflightAnyHost);
        root.put("http", h);
        Json.Obj in = new Json.Obj();
        in.put("dirsearch_compat", input.dirSearchCompat);
        in.put("encoders", input.encoders);
        in.put("extensions", input.extensions);
        in.put("ignore_wordlist_comments", input.ignoreWordlistComments);
        in.put("input_mode", input.inputMode);
        in.put("input_num", input.inputNum);
        in.put("input_shell", input.inputShell);
        in.put("input_commands", input.inputCommands);
        in.put("request_file", input.request);
        in.put("request_proto", input.requestProto);
        in.put("wordlists", input.wordlists);
        root.put("input", in);
        root.put("matchers", matchJson(matcher));
        Json.Obj o = new Json.Obj();
        o.put("audit_log", output.auditLog);
        o.put("debug_log", output.debugLog);
        o.put("output_directory", output.outputDirectory);
        o.put("output_file", output.outputFile);
        o.put("output_format", output.outputFormat);
        o.put("output_skip_empty", output.outputSkipEmptyFile);
        root.put("output", o);
        if (timeValue != null) {
            root.put("time", timeValue);
        }
        return root.toString();
    }

    private static Json.Obj matchJson(MatchOptions m) {
        Json.Obj f = new Json.Obj();
        f.put("mode", m.mode);
        f.put("lines", m.lines);
        f.put("regexp", m.regexp);
        f.put("size", m.size);
        f.put("status", m.status);
        f.put("time", m.time);
        f.put("words", m.words);
        return f;
    }

    static List<Object> flightsJson(List<PreflightConfig> flights) {
        if (flights == null) {
            return null;
        }
        List<Object> out = new ArrayList<>();
        for (PreflightConfig pf : flights) {
            Json.Obj o = new Json.Obj();
            o.put("request_file", pf.requestFile);
            List<Object> vars = new ArrayList<>();
            for (VarExtract v : pf.vars) {
                Json.Obj vo = new Json.Obj();
                vo.put("name", v.name);
                vo.put("regex", v.regex);
                if (!v.source.isEmpty()) {
                    vo.put("source", v.source);
                }
                if (!v.key.isEmpty()) {
                    vo.put("key", v.key);
                }
                vars.add(vo);
            }
            o.put("vars", pf.vars.isEmpty() ? null : vars);
            out.add(o);
        }
        return out;
    }

    /** {@return a deep copy, so a config snapshot never shares lists with the parsed options} */
    public ConfigOptions copy() {
        ConfigOptions c = new ConfigOptions();
        Http s = http;
        Http d = c.http;
        d.cookies = new ArrayList<>(s.cookies);
        d.data = s.data;
        d.followRedirects = s.followRedirects;
        d.headers = s.headers == null ? null : new ArrayList<>(s.headers);
        d.ignoreBody = s.ignoreBody;
        d.method = s.method;
        d.proxyUrl = s.proxyUrl;
        d.raw = s.raw;
        d.recursion = s.recursion;
        d.recursionDepth = s.recursionDepth;
        d.recursionStrategy = s.recursionStrategy;
        d.replayProxyUrl = s.replayProxyUrl;
        d.sni = s.sni;
        d.timeout = s.timeout;
        d.url = s.url;
        d.http2 = s.http2;
        d.clientCert = s.clientCert;
        d.clientKey = s.clientKey;
        d.preflights = new ArrayList<>(s.preflights);
        d.postflights = new ArrayList<>(s.postflights);
        d.preflightMode = s.preflightMode;
        d.preflightError = s.preflightError;
        d.preflightAnyHost = s.preflightAnyHost;
        General gs = general;
        General gd = c.general;
        gd.autoCalibration = gs.autoCalibration;
        gd.autoCalibrationKeyword = gs.autoCalibrationKeyword;
        gd.autoCalibrationPerHost = gs.autoCalibrationPerHost;
        gd.autoCalibrationStrategies = gs.autoCalibrationStrategies == null ? null : new ArrayList<>(gs.autoCalibrationStrategies);
        gd.autoCalibrationStrings = gs.autoCalibrationStrings == null ? null : new ArrayList<>(gs.autoCalibrationStrings);
        gd.colors = gs.colors;
        gd.configFile = gs.configFile;
        gd.delay = gs.delay;
        gd.json = gs.json;
        gd.maxTime = gs.maxTime;
        gd.maxTimeJob = gs.maxTimeJob;
        gd.noninteractive = gs.noninteractive;
        gd.quiet = gs.quiet;
        gd.rate = gs.rate;
        gd.scraperFile = gs.scraperFile;
        gd.scrapers = gs.scrapers;
        gd.searchhash = gs.searchhash;
        gd.showVersion = gs.showVersion;
        gd.stopOn403 = gs.stopOn403;
        gd.stopOnAll = gs.stopOnAll;
        gd.stopOnErrors = gs.stopOnErrors;
        gd.threads = gs.threads;
        gd.verbose = gs.verbose;
        Input is = input;
        Input id = c.input;
        id.dirSearchCompat = is.dirSearchCompat;
        id.encoders = is.encoders == null ? null : new ArrayList<>(is.encoders);
        id.extensions = is.extensions;
        id.ignoreWordlistComments = is.ignoreWordlistComments;
        id.inputMode = is.inputMode;
        id.inputNum = is.inputNum;
        id.inputShell = is.inputShell;
        id.inputCommands = is.inputCommands == null ? null : new ArrayList<>(is.inputCommands);
        id.request = is.request;
        id.requestProto = is.requestProto;
        id.wordlists = is.wordlists == null ? null : new ArrayList<>(is.wordlists);
        c.output.auditLog = output.auditLog;
        c.output.debugLog = output.debugLog;
        c.output.outputDirectory = output.outputDirectory;
        c.output.outputFile = output.outputFile;
        c.output.outputFormat = output.outputFormat;
        c.output.outputSkipEmptyFile = output.outputSkipEmptyFile;
        copyMatch(filter, c.filter);
        copyMatch(matcher, c.matcher);
        return c;
    }

    private static void copyMatch(MatchOptions s, MatchOptions d) {
        d.mode = s.mode;
        d.lines = s.lines;
        d.regexp = s.regexp;
        d.size = s.size;
        d.status = s.status;
        d.time = s.time;
        d.words = s.words;
    }
}
