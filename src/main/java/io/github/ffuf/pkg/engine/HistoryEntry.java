package io.github.ffuf.pkg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.ffuf.PreflightConfig;
import io.github.ffuf.pkg.ffuf.VarExtract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** A history folder's saved options, read back for -search. */
public final class HistoryEntry {
    public final ConfigOptions options;
    public final String time;

    private HistoryEntry(ConfigOptions options, String time) {
        this.options = options;
        this.time = time;
    }

    /**
     * Reads the history entry stored in a history directory.
     *
     * @param dir the entry's directory
     * @return the entry with its options and time
     * @throws IOException when the options file cannot be read or parsed
     */
    public static HistoryEntry read(Path dir) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(dir.resolve("options")));
        ConfigOptions o = new ConfigOptions();
        JsonNode f = field(root, "filters");
        JsonNode g = field(root, "general");
        JsonNode h = field(root, "http");
        JsonNode in = field(root, "input");
        JsonNode m = field(root, "matchers");
        JsonNode out = field(root, "output");
        match(f, o.filter);
        match(m, o.matcher);
        o.general.autoCalibration = bool(g, "autocalibration", o.general.autoCalibration);
        o.general.autoCalibrationKeyword = str(g, "autocalibration_keyword", o.general.autoCalibrationKeyword);
        o.general.autoCalibrationPerHost = bool(g, "autocalibration_per_host", o.general.autoCalibrationPerHost);
        o.general.autoCalibrationStrategies = list(g, "autocalibration_strategies", o.general.autoCalibrationStrategies);
        o.general.autoCalibrationStrings = list(g, "autocalibration_strings", o.general.autoCalibrationStrings);
        o.general.colors = bool(g, "colors", o.general.colors);
        o.general.configFile = str(g, "config_file", o.general.configFile);
        o.general.delay = str(g, "delay", o.general.delay);
        o.general.json = bool(g, "json", o.general.json);
        o.general.maxTime = integer(g, "maxtime", o.general.maxTime);
        o.general.maxTimeJob = integer(g, "maxtime_job", o.general.maxTimeJob);
        o.general.noninteractive = bool(g, "noninteractive", o.general.noninteractive);
        o.general.quiet = bool(g, "quiet", o.general.quiet);
        o.general.rate = integer(g, "rate", o.general.rate);
        o.general.scraperFile = str(g, "scraperfile", o.general.scraperFile);
        o.general.scrapers = str(g, "scrapers", o.general.scrapers);
        o.general.stopOn403 = bool(g, "stop_on_403", o.general.stopOn403);
        o.general.stopOnAll = bool(g, "stop_on_all", o.general.stopOnAll);
        o.general.stopOnErrors = bool(g, "stop_on_errors", o.general.stopOnErrors);
        o.general.threads = integer(g, "threads", o.general.threads);
        o.general.verbose = bool(g, "verbose", o.general.verbose);
        o.http.data = str(h, "data", o.http.data);
        o.http.followRedirects = bool(h, "follow_redirects", o.http.followRedirects);
        o.http.headers = list(h, "headers", o.http.headers);
        o.http.ignoreBody = bool(h, "ignore_body", o.http.ignoreBody);
        o.http.method = str(h, "method", o.http.method);
        o.http.proxyUrl = str(h, "proxy_url", o.http.proxyUrl);
        o.http.raw = bool(h, "raw", o.http.raw);
        o.http.recursion = bool(h, "recursion", o.http.recursion);
        o.http.recursionDepth = integer(h, "recursion_depth", o.http.recursionDepth);
        o.http.recursionStrategy = str(h, "recursion_strategy", o.http.recursionStrategy);
        o.http.replayProxyUrl = str(h, "replay_proxy_url", o.http.replayProxyUrl);
        o.http.sni = str(h, "sni", o.http.sni);
        o.http.timeout = integer(h, "timeout", o.http.timeout);
        o.http.url = str(h, "url", o.http.url);
        o.http.http2 = bool(h, "http2", o.http.http2);
        o.http.clientCert = str(h, "client-cert", o.http.clientCert);
        o.http.clientKey = str(h, "client-key", o.http.clientKey);
        o.http.preflights = flights(h, "preflights");
        o.http.postflights = flights(h, "postflights");
        o.http.preflightMode = str(h, "preflight_mode", o.http.preflightMode);
        o.http.preflightError = str(h, "preflight_error", o.http.preflightError);
        o.http.preflightAnyHost = bool(h, "preflight_anyhost", o.http.preflightAnyHost);
        o.input.dirSearchCompat = bool(in, "dirsearch_compat", o.input.dirSearchCompat);
        o.input.encoders = list(in, "encoders", o.input.encoders);
        o.input.extensions = str(in, "extensions", o.input.extensions);
        o.input.ignoreWordlistComments = bool(in, "ignore_wordlist_comments", o.input.ignoreWordlistComments);
        o.input.inputMode = str(in, "input_mode", o.input.inputMode);
        o.input.inputNum = integer(in, "input_num", o.input.inputNum);
        o.input.inputShell = str(in, "input_shell", o.input.inputShell);
        o.input.inputCommands = list(in, "input_commands", o.input.inputCommands);
        o.input.request = str(in, "request_file", o.input.request);
        o.input.requestProto = str(in, "request_proto", o.input.requestProto);
        o.input.wordlists = list(in, "wordlists", o.input.wordlists);
        o.output.auditLog = str(out, "audit_log", o.output.auditLog);
        o.output.debugLog = str(out, "debug_log", o.output.debugLog);
        o.output.outputDirectory = str(out, "output_directory", o.output.outputDirectory);
        o.output.outputFile = str(out, "output_file", o.output.outputFile);
        o.output.outputFormat = str(out, "output_format", o.output.outputFormat);
        o.output.outputSkipEmptyFile = bool(out, "output_skip_empty", o.output.outputSkipEmptyFile);
        return new HistoryEntry(o, str(root, "time", ""));
    }

    /** Looks a key up case-insensitively, as encoding/json does. */
    private static JsonNode field(JsonNode n, String name) {
        if (n == null || !n.isObject()) {
            return null;
        }
        if (n.has(name)) {
            return n.get(name);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static void match(JsonNode n, ConfigOptions.MatchOptions m) {
        m.mode = str(n, "mode", m.mode);
        m.lines = str(n, "lines", m.lines);
        m.regexp = str(n, "regexp", m.regexp);
        m.size = str(n, "size", m.size);
        m.status = str(n, "status", m.status);
        m.time = str(n, "time", m.time);
        m.words = str(n, "words", m.words);
    }

    private static String str(JsonNode n, String k, String def) {
        JsonNode v = field(n, k);
        return v == null || v.isNull() ? def : v.asText();
    }

    private static boolean bool(JsonNode n, String k, boolean def) {
        JsonNode v = field(n, k);
        return v == null || v.isNull() ? def : v.asBoolean();
    }

    private static long integer(JsonNode n, String k, long def) {
        JsonNode v = field(n, k);
        return v == null || v.isNull() ? def : v.asLong();
    }

    private static List<String> list(JsonNode n, String k, List<String> def) {
        JsonNode v = field(n, k);
        if (v == null) {
            return def;
        }
        if (v.isNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        v.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static List<PreflightConfig> flights(JsonNode n, String k) {
        List<PreflightConfig> out = new ArrayList<>();
        JsonNode v = field(n, k);
        if (v == null || !v.isArray()) {
            return out;
        }
        for (JsonNode f : v) {
            PreflightConfig pf = new PreflightConfig(str(f, "request_file", ""));
            JsonNode vars = field(f, "vars");
            if (vars != null && vars.isArray()) {
                for (JsonNode x : vars) {
                    VarExtract ve = new VarExtract();
                    ve.name = str(x, "name", "");
                    ve.regex = str(x, "regex", "");
                    ve.source = str(x, "source", "");
                    ve.key = str(x, "key", "");
                    pf.vars.add(ve);
                }
            }
            out.add(pf);
        }
        return out;
    }
}
