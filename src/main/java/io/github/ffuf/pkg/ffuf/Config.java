package io.github.ffuf.pkg.ffuf;

import io.github.ffuf.pkg.filter.MatcherManager;
import io.github.ffuf.pkg.goregexp.GoRegexp;
import io.github.ffuf.pkg.runner.GoText;
import io.github.ffuf.pkg.runner.GoUrl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The validated runtime configuration, built from ConfigOptions. */
public class Config {
    public String auditLog = "";
    public boolean autoCalibration;
    public String autoCalibrationKeyword = "FUZZ";
    public boolean autoCalibrationPerHost;
    public List<String> autoCalibrationStrategies = new ArrayList<>();
    public List<String> autoCalibrationStrings = new ArrayList<>();
    public boolean colors;
    public List<String> commandKeywords = new ArrayList<>();
    public String commandLine = "";
    public String data = "";
    public boolean delayHasDelay;
    public boolean delayIsRange;
    public double delayMin;
    public double delayMax;
    public boolean dirSearchCompat;
    public List<String> extensions = new ArrayList<>();
    public String filterMode = "or";
    public boolean followRedirects;
    public Map<String, String> headers = new LinkedHashMap<>();
    public boolean ignoreBody;
    public boolean ignoreWordlistComments;
    public String inputMode = "clusterbomb";
    public long inputNum;
    public String inputShell = "";
    public List<InputProviderConfig> inputProviders = new ArrayList<>();
    public String matcherMode = "or";
    public long maxTime;
    public long maxTimeJob;
    public String method = "";
    public boolean noninteractive;
    public String outputDirectory = "";
    public String outputFile = "";
    public String outputFormat = "";
    public boolean outputSkipEmptyFile;
    public String proxyUrl = "";
    public boolean quiet;
    public long rate;
    public boolean raw;
    public boolean recursion;
    public long recursionDepth;
    public String recursionStrategy = "default";
    public String replayProxyUrl = "";
    public String requestFile = "";
    public String requestProto = "https";
    public String scraperFile = "";
    public String scrapers = "all";
    public String sni = "";
    public boolean stopOn403;
    public boolean stopOnAll;
    public boolean stopOnErrors;
    public long threads;
    public long timeout;
    public String url = "";
    public boolean verbose;
    public boolean json;
    public List<String> wordlists = new ArrayList<>();
    public boolean http2;
    public String clientCert = "";
    public String clientKey = "";
    public String preflightMode = "per-request";
    public String preflightError = "abort";
    public boolean preflightAnyHost;
    public List<PreflightConfig> preflights = new ArrayList<>();
    public List<PreflightConfig> postflights = new ArrayList<>();
    public MatcherManager matcherManager;
    public ConfigOptions options;
    /** Waits for the rate limiter; flight requests call it, the job installs the throttle. */
    public Runnable rateLimitFunc = () -> { };
    /** Cancelled when the run stops. */
    public Context context = new Context();
    /** Milliseconds between two redraws of the progress line. */
    public int progressFrequency = 125;

    /** Thrown with the combined report of every validation error. */
    public static class ConfigException extends Exception {
        /** The configuration as far as it was built, returned next to the error. */
        public final transient Config partial;

        /** Creates the exception with the combined error report and the partially built configuration. */
        public ConfigException(String message, Config partial) {
            super(message);
            this.partial = partial;
        }
    }

    /**
     * Builds a Config, collecting every validation problem into one combined report.
     *
     * @param o the parsed options
     * @param argv the command line, kept for the output files
     * @return the configuration
     * @throws ConfigException with the combined report when any option is invalid
     */
    public static Config fromOptions(ConfigOptions o, List<String> argv) throws ConfigException {
        MultiError errs = new MultiError();
        Config conf = new Config();
        if (o.http.url.isEmpty() && o.input.request.isEmpty()) {
            errs.add("-u flag or -request flag is required");
        }
        if (!o.input.extensions.isEmpty()) {
            conf.extensions = List.of(o.input.extensions.split(",", -1));
        }
        List<String> effectiveHeaders = cloneStrings(o.http.headers);
        if (!o.http.cookies.isEmpty()) {
            if (effectiveHeaders == null) {
                effectiveHeaders = new ArrayList<>();
            }
            effectiveHeaders.add("Cookie: " + String.join("; ", o.http.cookies));
        }
        conf.inputMode = o.input.inputMode;
        if (!List.of("clusterbomb", "pitchfork", "sniper").contains(conf.inputMode)) {
            errs.add("Input mode (-mode) " + conf.inputMode + " not recognized");
        }
        String template = "";
        List<String> wordlists = o.input.wordlists == null ? List.of() : o.input.wordlists;
        List<String> commands = o.input.inputCommands == null ? List.of() : o.input.inputCommands;
        if (conf.inputMode.equals("sniper")) {
            template = "§";
            if (wordlists.size() > 1) {
                errs.add("sniper mode only supports one wordlist");
            }
            if (commands.size() > 1) {
                errs.add("sniper mode only supports one input command");
            }
        }
        Map<String, String> tmpEncoders = new LinkedHashMap<>();
        if (o.input.encoders != null) {
            for (String e : o.input.encoders) {
                if (e.contains(":")) {
                    String[] p = e.split(":", -1);
                    tmpEncoders.put(p[0], p[1]);
                }
            }
        }
        for (String v : wordlists) {
            String[] wl = v.split(":", 2);
            if (!wl[0].equals("-")) {
                wl[0] = absPath(wl[0]);
            }
            if (wl.length == 2) {
                if (conf.inputMode.equals("sniper")) {
                    errs.add("sniper mode does not support wordlist keywords");
                } else {
                    InputProviderConfig p = new InputProviderConfig("wordlist", wl[0], wl[1], "");
                    p.encoders = tmpEncoders.getOrDefault(wl[1], "");
                    conf.inputProviders.add(p);
                }
            } else {
                InputProviderConfig p = new InputProviderConfig("wordlist", wl[0], "FUZZ", template);
                p.encoders = tmpEncoders.getOrDefault("FUZZ", "");
                conf.inputProviders.add(p);
            }
            conf.wordlists.add(String.join(":", wl));
        }
        for (String v : commands) {
            String[] ic = v.split(":", 2);
            if (ic.length == 2) {
                if (conf.inputMode.equals("sniper")) {
                    errs.add("sniper mode does not support command keywords");
                } else {
                    InputProviderConfig p = new InputProviderConfig("command", ic[0], ic[1], "");
                    p.encoders = tmpEncoders.getOrDefault(ic[1], "");
                    conf.inputProviders.add(p);
                    conf.commandKeywords.add(ic[0]);
                }
            } else {
                InputProviderConfig p = new InputProviderConfig("command", ic[0], "FUZZ", template);
                p.encoders = tmpEncoders.getOrDefault("FUZZ", "");
                conf.inputProviders.add(p);
                conf.commandKeywords.add("FUZZ");
            }
        }
        if (conf.inputProviders.isEmpty()) {
            errs.add("Either -w or --input-cmd flag is required");
        }
        if (!o.input.request.isEmpty()) {
            String err = parseRawRequest(o, conf);
            if (err != null) {
                errs.add("Could not parse raw request: " + err);
            }
        }
        if (!o.http.url.isEmpty()) {
            conf.url = o.http.url;
        }
        conf.sni = o.http.sni;
        conf.clientCert = o.http.clientCert;
        conf.clientKey = o.http.clientKey;
        if (effectiveHeaders != null) {
            for (String v : effectiveHeaders) {
                String[] hs = v.split(":", 2);
                if (hs.length == 2) {
                    boolean canonical = true;
                    for (String a : conf.commandKeywords) {
                        if (hs[0].contains(a)) {
                            canonical = false;
                        }
                    }
                    for (InputProviderConfig b : conf.inputProviders) {
                        if (hs[0].contains(b.keyword)) {
                            canonical = false;
                        }
                    }
                    String key = canonical ? Util.canonicalHeaderKey(Util.goTrimSpace(hs[0])) : Util.goTrimSpace(hs[0]);
                    conf.headers.put(key, Util.goTrimSpace(hs[1]));
                } else {
                    errs.add("Header defined by -H needs to have a value. \":\" should be used as a separator");
                }
            }
        }
        String[] d = o.general.delay.split("-", -1);
        if (d.length > 2) {
            errs.add("Delay needs to be either a single float: \"0.1\" or a range of floats, delimited by dash: \"0.1-0.8\"");
        } else if (d.length == 2) {
            conf.delayIsRange = true;
            conf.delayHasDelay = true;
            Double a = Util.parseGoFloat(d[0]);
            Double b = Util.parseGoFloat(d[1]);
            conf.delayMin = a == null ? 0 : a;
            conf.delayMax = b == null ? 0 : b;
            if (a == null || b == null) {
                errs.add("Delay range min and max values need to be valid floats. For example: 0.1-0.5");
            }
        } else if (!o.general.delay.isEmpty()) {
            conf.delayHasDelay = true;
            Double a = Util.parseGoFloat(o.general.delay);
            conf.delayMin = a == null ? 0 : a;
            if (a == null) {
                errs.add("Delay needs to be either a single float: \"0.1\" or a range of floats, delimited by dash: \"0.1-0.8\"");
            }
        }
        if (!o.http.proxyUrl.isEmpty()) {
            if (!validProxyUrl(o.http.proxyUrl, List.of("http", "https", "socks5"))) {
                errs.add("Bad proxy url (-x) format. Expected http, https or socks5 url");
            } else {
                conf.proxyUrl = o.http.proxyUrl;
            }
        }
        if (!o.http.replayProxyUrl.isEmpty()) {
            if (!validProxyUrl(o.http.replayProxyUrl, List.of("http", "https", "socks5", "socks5h"))) {
                errs.add("Bad replay-proxy url (-replay-proxy) format. Expected http, https or socks5 url");
            } else {
                conf.replayProxyUrl = o.http.replayProxyUrl;
            }
        }
        if (!o.output.outputFile.isEmpty()) {
            if (List.of("all", "json", "ejson", "html", "md", "csv", "ecsv").contains(o.output.outputFormat)) {
                conf.outputFormat = o.output.outputFormat;
            } else {
                errs.add("Unknown output file format (-of): " + o.output.outputFormat);
            }
        }
        if (o.general.autoCalibrationStrings != null && !o.general.autoCalibrationStrings.isEmpty()) {
            conf.autoCalibrationStrings = o.general.autoCalibrationStrings;
        }
        conf.rate = Math.max(0, o.general.rate);
        if (conf.method.isEmpty()) {
            conf.method = o.http.method.isEmpty() ? "GET" : o.http.method;
        } else if (!o.http.method.isEmpty()) {
            conf.method = o.http.method;
        }
        if (!o.http.data.isEmpty()) {
            conf.data = o.http.data;
        }
        conf.ignoreWordlistComments = o.input.ignoreWordlistComments;
        conf.dirSearchCompat = o.input.dirSearchCompat;
        conf.colors = o.general.colors;
        conf.inputNum = o.input.inputNum;
        conf.inputShell = o.input.inputShell;
        conf.auditLog = o.output.auditLog;
        conf.outputFile = o.output.outputFile;
        conf.outputDirectory = o.output.outputDirectory;
        conf.outputSkipEmptyFile = o.output.outputSkipEmptyFile;
        conf.ignoreBody = o.http.ignoreBody;
        conf.quiet = o.general.quiet;
        conf.scraperFile = o.general.scraperFile;
        conf.scrapers = o.general.scrapers;
        conf.stopOn403 = o.general.stopOn403;
        conf.stopOnAll = o.general.stopOnAll;
        conf.stopOnErrors = o.general.stopOnErrors;
        conf.followRedirects = o.http.followRedirects;
        conf.raw = o.http.raw;
        conf.recursion = o.http.recursion;
        conf.recursionDepth = o.http.recursionDepth;
        conf.recursionStrategy = o.http.recursionStrategy;
        conf.autoCalibration = o.general.autoCalibration;
        conf.autoCalibrationPerHost = o.general.autoCalibrationPerHost;
        conf.autoCalibrationStrategies = o.general.autoCalibrationStrategies;
        conf.threads = o.general.threads;
        conf.timeout = o.http.timeout;
        conf.maxTime = o.general.maxTime;
        conf.maxTimeJob = o.general.maxTimeJob;
        conf.noninteractive = o.general.noninteractive;
        conf.verbose = o.general.verbose;
        conf.json = o.general.json;
        conf.http2 = o.http.http2;
        conf.preflightAnyHost = o.http.preflightAnyHost;
        conf.preflights = o.http.preflights;
        conf.postflights = o.http.postflights;
        switch (o.http.preflightMode) {
            case "", "per-request" -> conf.preflightMode = "per-request";
            case "per-thread" -> conf.preflightMode = "per-thread";
            default -> errs.add("-preflight-mode must be \"per-request\" or \"per-thread\", got \"" + Flags.goQuote(o.http.preflightMode) + "\"");
        }
        switch (o.http.preflightError) {
            case "", "abort" -> conf.preflightError = "abort";
            case "ignore" -> conf.preflightError = "ignore";
            default -> errs.add("-preflight-error must be \"abort\" or \"ignore\", got \"" + Flags.goQuote(o.http.preflightError) + "\"");
        }
        compileFlights("preflight", conf.preflights, errs);
        compileFlights("postflight", conf.postflights, errs);
        boolean fmodeFound = List.of("and", "or").contains(o.filter.mode);
        boolean mmodeFound = List.of("and", "or").contains(o.matcher.mode);
        if (!fmodeFound) {
            errs.add("Unrecognized value for parameter fmode: " + o.filter.mode + ", valid values are: and, or");
        }
        if (!mmodeFound) {
            errs.add("Unrecognized value for parameter mmode: " + o.matcher.mode + ", valid values are: and, or");
        }
        conf.filterMode = o.filter.mode;
        conf.matcherMode = o.matcher.mode;
        if (conf.autoCalibrationPerHost) {
            conf.autoCalibration = true;
        }
        if (!conf.data.isEmpty() && conf.method.equals("GET") && o.input.request.isEmpty()) {
            conf.method = "POST";
        }
        conf.commandLine = String.join(" ", argv);
        List<InputProviderConfig> kept = new ArrayList<>();
        for (InputProviderConfig p : conf.inputProviders) {
            if (!p.template.isEmpty()) {
                if (!conf.templatePresent(p.template)) {
                    errs.add("Template " + p.template + " defined, but not found in pairs in headers, method, URL or POST data.");
                } else {
                    kept.add(p);
                }
            } else {
                if (!conf.keywordPresent(p.keyword)) {
                    System.err.print("Keyword " + p.keyword + " defined, but not found in headers, method, URL or POST data.\n");
                } else {
                    kept.add(p);
                }
            }
        }
        conf.inputProviders = kept;
        if (conf.inputMode.equals("sniper") && conf.keywordPresent("FUZZ")) {
            errs.add("FUZZ keyword defined, but we are using sniper mode.");
        }
        if (o.http.recursion && !conf.url.endsWith("FUZZ")) {
            errs.add("When using -recursion the URL (-u) must end with FUZZ keyword.");
        }
        if (o.general.verbose && o.general.json) {
            errs.add("Cannot have -json and -v");
        }
        ConfigOptions snapshot = o.copy();
        snapshot.http.headers = effectiveHeaders;
        snapshot.input.wordlists = cloneStrings(o.input.wordlists);
        snapshot.input.encoders = cloneStrings(o.input.encoders);
        snapshot.input.inputCommands = cloneStrings(o.input.inputCommands);
        snapshot.general.autoCalibrationStrings = cloneStrings(o.general.autoCalibrationStrings);
        snapshot.general.autoCalibrationStrategies = cloneStrings(o.general.autoCalibrationStrategies);
        conf.options = snapshot;
        String err = errs.errorOrNull();
        if (err != null) {
            throw new ConfigException(err, conf);
        }
        return conf;
    }

    private static void compileFlights(String kind, List<PreflightConfig> flights, MultiError errs) {
        for (int i = 0; i < flights.size(); i++) {
            PreflightConfig pf = flights.get(i);
            if (pf.requestFile.isEmpty() || !Files.exists(Paths.get(pf.requestFile))) {
                errs.add(kind + " request file #" + (i + 1) + " \"" + Flags.goQuote(pf.requestFile) + "\": stat " + pf.requestFile + ": no such file or directory");
            }
            for (VarExtract ve : pf.vars) {
                if (!ve.source.isEmpty()) {
                    if (!ve.regex.isEmpty()) {
                        errs.add(kind + " #" + (i + 1) + " var \"" + Flags.goQuote(ve.name) + "\": set either regex or source, not both");
                    } else {
                        String verr = FlightVars.validateSource(ve.source, ve.key);
                        if (verr != null) {
                            errs.add(kind + " #" + (i + 1) + " var \"" + Flags.goQuote(ve.name) + "\": " + verr);
                        }
                    }
                    continue;
                }
                if (ve.regex.isEmpty()) {
                    errs.add(kind + " #" + (i + 1) + " var \"" + Flags.goQuote(ve.name) + "\": needs a regex or a source");
                    continue;
                }
                try {
                    ve.compiled = GoRegexp.compile(ve.regex);
                } catch (GoRegexp.SyntaxException e) {
                    errs.add(kind + " #" + (i + 1) + " var \"" + Flags.goQuote(ve.name) + "\": invalid regex \""
                            + Flags.goQuote(ve.regex) + "\": " + e.getMessage());
                }
            }
        }
    }

    private static String absPath(String p) {
        Path path = Paths.get(p);
        if (path.isAbsolute()) {
            return Util.cleanPath(p);
        }
        return Util.cleanPath(Paths.get(Util.workingDir()).resolve(p).toString());
    }

    private static String parseRawRequest(ConfigOptions o, Config conf) {
        conf.requestFile = o.input.request;
        conf.requestProto = o.input.requestProto;
        byte[] content;
        try (InputStream in = Files.newInputStream(Paths.get(o.input.request))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            content = bos.toByteArray();
        } catch (IOException e) {
            return "could not open request file: open " + o.input.request + ": no such file or directory";
        }
        String all = new String(content, StandardCharsets.ISO_8859_1);
        int nl = all.indexOf('\n');
        if (nl < 0) {
            return "could not read request: EOF";
        }
        String first = all.substring(0, nl + 1);
        String[] parts = first.split(" ", -1);
        if (parts.length < 3) {
            return "malformed request supplied";
        }
        conf.method = Util.latin1ToUtf8(parts[0]);
        int pos = nl + 1;
        while (true) {
            int next = all.indexOf('\n', pos);
            String line;
            boolean eof = next < 0;
            if (eof) {
                line = all.substring(pos);
                pos = all.length();
            } else {
                line = all.substring(pos, next + 1);
                pos = next + 1;
            }
            line = Util.goTrimSpace(line);
            if (eof || line.isEmpty()) {
                break;
            }
            String[] p = line.split(":", 2);
            if (p.length != 2) {
                continue;
            }
            if (p[0].equalsIgnoreCase("content-length")) {
                continue;
            }
            conf.headers.put(Util.latin1ToUtf8(Util.goTrimSpace(p[0])), Util.latin1ToUtf8(Util.goTrimSpace(p[1])));
        }
        String target = Util.latin1ToUtf8(parts[1]);
        if (target.startsWith("http")) {
            conf.url = target;
            conf.headers.put("Host", Util.urlHost(target));
        } else {
            conf.url = o.input.requestProto + "://" + conf.headers.getOrDefault("Host", "") + target;
        }
        String body = Util.latin1ToUtf8(all.substring(pos));
        if (body.endsWith("\r\n")) {
            body = body.substring(0, body.length() - 2);
        } else if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        conf.data = body;
        return null;
    }

    /** The proxy URL check: url.Parse succeeds, the URL is not opaque and the scheme is allowed. */
    private static boolean validProxyUrl(String proxyUrl, List<String> schemes) {
        try {
            GoUrl u = GoUrl.parse(GoText.bin(proxyUrl));
            return u.opaque.isEmpty() && schemes.contains(u.scheme);
        } catch (GoUrl.UrlException e) {
            return false;
        }
    }

    /** {@return the configuration as written into JSON output files and the audit log} */
    public Json.Obj toJson() {
        Json.Obj o = new Json.Obj();
        o.put("auditlog", auditLog);
        o.put("autocalibration", autoCalibration);
        o.put("autocalibration_keyword", autoCalibrationKeyword);
        o.put("autocalibration_perhost", autoCalibrationPerHost);
        o.put("autocalibration_strategies", autoCalibrationStrategies);
        o.put("autocalibration_strings", autoCalibrationStrings);
        o.put("colors", colors);
        o.put("cmdline", commandLine);
        o.put("configfile", "");
        o.put("postdata", data);
        o.put("debuglog", "");
        String delay = delayMin == delayMax ? String.format("%.2f", delayMin) : String.format("%.2f-%.2f", delayMin, delayMax);
        o.put("delay", new Json.Obj().put("value", delay));
        o.put("dirsearch_compatibility", dirSearchCompat);
        o.put("encoders", new ArrayList<String>());
        o.put("extensions", extensions);
        o.put("fmode", filterMode);
        o.put("follow_redirects", followRedirects);
        o.put("headers", headers);
        o.put("ignorebody", ignoreBody);
        o.put("ignore_wordlist_comments", ignoreWordlistComments);
        o.put("inputmode", inputMode);
        o.put("cmd_inputnum", inputNum);
        List<Object> providers = new ArrayList<>();
        for (InputProviderConfig p : inputProviders) {
            providers.add(new Json.Obj().put("name", p.name).put("keyword", p.keyword).put("value", p.value)
                    .put("encoders", p.encoders).put("template", p.template));
        }
        o.put("inputproviders", providers);
        o.put("inputshell", inputShell);
        o.put("json", json);
        o.put("matchers", matcherManager == null ? null : matcherManager.toJson());
        o.put("mmode", matcherMode);
        o.put("maxtime", maxTime);
        o.put("maxtime_job", maxTimeJob);
        o.put("method", method);
        o.put("noninteractive", noninteractive);
        o.put("outputdirectory", outputDirectory);
        o.put("outputfile", outputFile);
        o.put("outputformat", outputFormat);
        o.put("OutputSkipEmptyFile", outputSkipEmptyFile);
        o.put("proxyurl", proxyUrl);
        o.put("quiet", quiet);
        o.put("rate", rate);
        o.put("raw", raw);
        o.put("recursion", recursion);
        o.put("recursion_depth", recursionDepth);
        o.put("recursion_strategy", recursionStrategy);
        o.put("replayproxyurl", replayProxyUrl);
        o.put("requestfile", requestFile);
        o.put("requestproto", requestProto);
        o.put("scraperfile", scraperFile);
        o.put("scrapers", scrapers);
        o.put("sni", sni);
        o.put("stop_403", stopOn403);
        o.put("stop_all", stopOnAll);
        o.put("stop_errors", stopOnErrors);
        o.put("threads", threads);
        o.put("timeout", timeout);
        o.put("url", url);
        o.put("verbose", verbose);
        o.put("wordlists", wordlists);
        o.put("http2", http2);
        o.put("client-cert", clientCert);
        o.put("client-key", clientKey);
        o.put("preflights", ConfigOptions.flightsJson(preflights));
        o.put("postflights", ConfigOptions.flightsJson(postflights));
        o.put("preflight_mode", preflightMode);
        o.put("preflight_error", preflightError);
        o.put("preflight_anyhost", preflightAnyHost);
        return o;
    }

    /** {@return true when the method, URL, body or a header contains the keyword} */
    public boolean keywordPresent(String keyword) {
        if (method.contains(keyword) || url.contains(keyword) || data.contains(keyword)) {
            return true;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().contains(keyword) || e.getValue().contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** {@return true when the template marker appears in the method, URL, body or headers, always in pairs} */
    public boolean templatePresent(String template) {
        boolean sane = false;
        List<String> fields = new ArrayList<>(List.of(method, url, data));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            fields.add(e.getKey());
            fields.add(e.getValue());
        }
        for (String f : fields) {
            int c = Util.count(f, template);
            if (c > 0) {
                if (c % 2 != 0) {
                    return false;
                }
                sane = true;
            }
        }
        return sane;
    }

    /** Go's {@code append([]string(nil), s...)}: a copy that is nil when the source is nil or empty. */
    private static List<String> cloneStrings(List<String> s) {
        return s == null || s.isEmpty() ? null : new ArrayList<>(s);
    }
}
