package io.github.ffuf.pkg.runner;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.FlightVars;
import io.github.ffuf.pkg.ffuf.Flags;
import io.github.ffuf.pkg.ffuf.PreflightConfig;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.ffuf.VarExtract;
import io.github.ffuf.pkg.goregexp.GoRegexp;
import io.github.ffuf.pkg.goquery.Document;
import io.github.ffuf.pkg.goquery.GoStrings;
import io.github.ffuf.pkg.goquery.Selection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Preflight and postflight request chains and their variable extraction. */
public class Flights {
    private final Config config;
    private final SimpleRunner runner;
    private final Consumer<String> log;
    private Map<String, String> laneVars;
    final Map<VarExtract, String> autoPins = new IdentityHashMap<>();

    /**
     * Creates the flight runner for the configured preflight and postflight requests.
     *
     * @param runner sends the flight requests
     * @param log receives the debug log lines
     */
    public Flights(Config config, SimpleRunner runner, Consumer<String> log) {
        this.config = config;
        this.runner = runner;
        this.log = log;
    }

    /**
     * Runs the preflights for one request and applies their variables.
     *
     * @return the variables used
     * @throws SimpleRunner.RequestException when a preflight request fails
     */
    public synchronized Map<String, String> runPreflights(Request req) throws SimpleRunner.RequestException {
        if (config.preflights.isEmpty()) {
            return Map.of();
        }
        String targetHost = Util.urlHost(req.url);
        Map<String, String> applied;
        if (config.preflightMode.equals("per-thread")) {
            if (laneVars == null) {
                laneVars = runChain(config.preflights, Map.of(), "preflight");
            }
            applied = laneVars;
        } else {
            applied = runChain(config.preflights, Map.of(), "preflight");
        }
        applyVars(req, applied);
        if (!applied.isEmpty() && !Util.urlHost(req.url).equals(targetHost)) {
            throw new SimpleRunner.RequestException("preflight variable changed the request host from \"" + Flags.goQuote(targetHost) + "\" to \""
                    + Flags.goQuote(Util.urlHost(req.url)) + "\"; refusing to send (a captured value must not alter the target host)", false);
        }
        return applied;
    }

    /** Substitutes extracted variables into one request's URL, headers and body. */
    static void applyVars(Request req, Map<String, String> vars) {
        if (vars.isEmpty()) {
            return;
        }
        req.url = replace(req.url, vars);
        req.data = replace(new String(req.data, StandardCharsets.UTF_8), vars).getBytes(StandardCharsets.UTF_8);
        req.headers.replaceAll((k, v) -> replace(v, vars));
    }

    /** Runs the postflight chain with the seed variables; a failure is logged, never thrown. */
    public synchronized void runPostflights(Map<String, String> seed) {
        if (config.postflights.isEmpty()) {
            return;
        }
        try {
            runChain(config.postflights, seed, "postflight");
        } catch (SimpleRunner.RequestException e) {
            log.accept("postflight error (result still recorded): " + e.getMessage());
        }
    }

    private static String replace(String s, Map<String, String> vars) {
        List<String> names = new ArrayList<>(vars.keySet());
        names.sort((a, b) -> a.length() != b.length() ? b.length() - a.length() : a.compareTo(b));
        StringBuilder out = new StringBuilder();
        int i = 0;
        outer:
        while (i < s.length()) {
            for (String n : names) {
                if (!n.isEmpty() && s.startsWith(n, i)) {
                    out.append(vars.get(n));
                    i += n.length();
                    continue outer;
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    Map<String, String> runChain(List<PreflightConfig> chain, Map<String, String> inherit, String kind)
            throws SimpleRunner.RequestException {
        Map<String, String> vars = new LinkedHashMap<>(inherit);
        boolean ignore = config.preflightError.equals("ignore");
        for (PreflightConfig pf : chain) {
            Request req;
            try {
                req = build(pf.requestFile, vars);
            } catch (SimpleRunner.RequestException e) {
                if (ignore) {
                    log.accept("preflight ignored error building request from \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\": " + e.getMessage());
                    return vars;
                }
                throw e;
            }
            Response resp;
            try {
                config.rateLimitFunc.run();
                resp = runner.execute(req);
            } catch (SimpleRunner.RequestException e) {
                if (ignore) {
                    log.accept("preflight ignored error executing \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\": " + e.getMessage());
                    return vars;
                }
                throw new SimpleRunner.RequestException("preflight: request \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\" failed: " + e.getMessage(), e.timeout);
            }
            String body = new String(resp.data, StandardCharsets.UTF_8);
            StringBuilder headerBlock = new StringBuilder();
            for (Map.Entry<String, List<String>> h : new java.util.TreeMap<>(resp.headers).entrySet()) {
                for (String v : h.getValue()) {
                    headerBlock.append(h.getKey()).append(": ").append(v).append("\r\n");
                }
            }
            FlightResponse fr = new FlightResponse(resp);
            for (VarExtract ve : pf.vars) {
                String val;
                if (!ve.source.isEmpty()) {
                    try {
                        val = extractByName(ve, fr);
                    } catch (VarNotFoundException e) {
                        if (ignore) {
                            log.accept("preflight: var " + ve.name + " not extracted from \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\": " + e.getMessage());
                            continue;
                        }
                        throw new SimpleRunner.RequestException("preflight: var " + ve.name + " not extracted from response of \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\": " + e.getMessage(), false);
                    }
                } else {
                    GoRegexp re = ve.compiled;
                    if (re == null) {
                        try {
                            re = GoRegexp.compile(ve.regex);
                        } catch (GoRegexp.SyntaxException e) {
                            if (ignore) {
                                log.accept("preflight ignored invalid regex \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(ve.regex) + "\" for var " + ve.name + ": " + e.getMessage());
                                continue;
                            }
                            throw new SimpleRunner.RequestException("preflight: invalid regex \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(ve.regex) + "\" for var " + ve.name + ": " + e.getMessage(), false);
                        }
                    }
                    byte[][] matches = re.findSubmatch(resp.data);
                    if (matches == null || matches.length < 2) {
                        matches = re.findSubmatch(headerBlock.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    if (matches == null || matches.length < 2) {
                        if (ignore) {
                            log.accept("preflight: regex \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(ve.regex) + "\" did not match var " + ve.name + " in the body or headers of the response from \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\"");
                            continue;
                        }
                        throw new SimpleRunner.RequestException("preflight: regex \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(ve.regex) + "\" did not capture var " + ve.name + " from the body or headers of the response of \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\"", false);
                    }
                    val = matches[1] == null ? "" : new String(matches[1], StandardCharsets.UTF_8);
                }
                if (val.contains("\r") || val.contains("\n") || val.contains("\u0000")) {
                    if (ignore) {
                        log.accept("preflight: var " + ve.name + " from \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\" contains a control character, skipping");
                        continue;
                    }
                    throw new SimpleRunner.RequestException("preflight: var " + ve.name + " captured from \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\" contains a control character; refusing to use it", false);
                }
                vars.put(ve.name, val);
                log.accept("preflight: extracted " + ve.name + " from \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(pf.requestFile) + "\"");
            }
        }
        return vars;
    }

    /** A -preflight-var-auto key that no source resolves; the message is ffuf's error text. */
    static final class VarNotFoundException extends Exception {
        private static final long serialVersionUID = 1L;

        VarNotFoundException(String msg) {
            super(msg);
        }
    }

    /** One flight response as variables are read from it; the HTML is parsed at most once, on first use. */
    static final class FlightResponse {
        private final Response resp;
        private Selection doc;
        private boolean docDone;

        FlightResponse(Response resp) {
            this.resp = resp;
        }

        private Selection document() {
            if (!docDone) {
                doc = Document.parse(resp.data);
                docDone = true;
            }
            return doc;
        }

        /** The value of key in one source, or null when that source does not have it. */
        String lookup(String source, String key) {
            switch (source) {
                case "form" -> {
                    Selection d = document();
                    return d == null ? null : formValue(d, key);
                }
                case "meta" -> {
                    Selection d = document();
                    return d == null ? null : metaValue(d, key);
                }
                case "cookie" -> {
                    return cookieValue(resp.headers.getOrDefault("Set-Cookie", List.of()), key);
                }
                case "header" -> {
                    List<String> vals = resp.headers.get(Util.canonicalHeaderKey(key));
                    return vals == null || vals.isEmpty() ? null : vals.get(0);
                }
                default -> {
                    return null;
                }
            }
        }
    }

    /**
     * The value a browser would submit for the first control named key: an input's value (skipping unchecked radios
     * and checkboxes), a textarea's text, or a select's selected, else first, option.
     */
    private static String formValue(Selection doc, String key) {
        Selection controls = doc.find("input, textarea, select");
        for (int i = 0; i < controls.length(); i++) {
            Selection s = controls.eq(i);
            if (!s.attrOr("name", "").equals(key)) {
                continue;
            }
            switch (s.nodeName()) {
                case "input" -> {
                    String typ = s.attrOr("type", "").toLowerCase(Locale.ROOT);
                    if ((typ.equals("radio") || typ.equals("checkbox")) && s.attr("checked").isEmpty()) {
                        continue;
                    }
                    return s.attrOr("value", "");
                }
                case "textarea" -> {
                    return s.text();
                }
                case "select" -> {
                    Selection opt = s.find("option[selected]").first();
                    if (opt.length() == 0) {
                        opt = s.find("option").first();
                    }
                    Optional<String> v = opt.attr("value");
                    return v.isPresent() ? v.get() : GoStrings.decode(GoStrings.trimSpace(GoStrings.encode(opt.text())));
                }
                default -> {
                    return "";
                }
            }
        }
        return null;
    }

    /** The content of the first meta tag whose name equals key, ignoring case. */
    private static String metaValue(Selection doc, String key) {
        Selection metas = doc.find("meta[name]");
        for (int i = 0; i < metas.length(); i++) {
            Selection s = metas.eq(i);
            if (GoStrings.equalFold(GoStrings.encode(s.attrOr("name", "")), GoStrings.encode(key))) {
                return s.attrOr("content", "");
            }
        }
        return null;
    }

    private static final String TOKEN_CHARS = "!#$%&'*+-.^_`|~";

    private static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (!(c < 0x80 && (Character.isLetterOrDigit(c) || TOKEN_CHARS.indexOf(c) >= 0))) {
                return false;
            }
        }
        return true;
    }

    /** {@return s without leading and trailing ASCII space, tab, CR and LF} */
    private static String trimAscii(String s) {
        int i = 0;
        int j = s.length();
        while (i < j && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        while (j > i && " \t\r\n".indexOf(s.charAt(j - 1)) >= 0) {
            j--;
        }
        return s.substring(i, j);
    }

    /** Strips one pair of double quotes when allowed; null when a byte is not allowed. */
    private static String parseCookieValue(String raw, boolean allowDoubleQuote) {
        if (allowDoubleQuote && raw.length() > 1 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            raw = raw.substring(1, raw.length() - 1);
        }
        for (char c : raw.toCharArray()) {
            if (c < 0x20 || c >= 0x7f || c == '"' || c == ';' || c == '\\') {
                return null;
            }
        }
        return raw;
    }

    /**
     * The value of cookie name across every Set-Cookie line, parsed as net/http does: the last one wins and a cookie
     * being deleted (Max-Age zero or negative) counts as absent.
     */
    private static String cookieValue(List<String> lines, String name) {
        String val = null;
        for (String line : lines) {
            String[] parts = trimAscii(line).split(";", -1);
            if (parts.length == 1 && parts[0].isEmpty()) {
                continue;
            }
            String first = trimAscii(parts[0]);
            int eq = first.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String cname = trimAscii(first.substring(0, eq));
            String value = parseCookieValue(first.substring(eq + 1), true);
            if (!isToken(cname) || value == null) {
                continue;
            }
            int maxAge = 0;
            for (int i = 1; i < parts.length; i++) {
                String part = trimAscii(parts[i]);
                int e = part.indexOf('=');
                String attr = e < 0 ? part : part.substring(0, e);
                String av = parseCookieValue(e < 0 ? "" : part.substring(e + 1), false);
                if (part.isEmpty() || av == null || !attr.chars().allMatch(c -> c < 0x80)
                        || !attr.toLowerCase(Locale.ROOT).equals("max-age")) {
                    continue;
                }
                Integer secs = goAtoi(av);
                if (secs == null || (secs != 0 && av.charAt(0) == '0')) {
                    continue;
                }
                maxAge = secs <= 0 ? -1 : secs;
            }
            if (cname.equals(name)) {
                val = maxAge >= 0 ? value : null;
            }
        }
        return val;
    }

    /** Parses a decimal int as Go's strconv.Atoi does: optional sign and decimal digits. */
    private static Integer goAtoi(String s) {
        if (!s.matches("[+-]?[0-9]+")) {
            return null;
        }
        try {
            long v = Long.parseLong(s.startsWith("+") ? s.substring(1) : s);
            return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : v < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolves a -preflight-var-auto variable. A pinned source is read directly; "auto" tries every source in order,
     * pins the first that resolves the key for the rest of the run, and refuses a key two sources disagree on.
     */
    synchronized String extractByName(VarExtract ve, FlightResponse fr) throws VarNotFoundException {
        String source = ve.source;
        if (source.equals(FlightVars.SOURCE_AUTO) && autoPins.containsKey(ve)) {
            source = autoPins.get(ve);
        }
        if (!source.equals(FlightVars.SOURCE_AUTO)) {
            String val = fr.lookup(source, ve.key);
            if (val != null) {
                return val;
            }
            throw new VarNotFoundException("[" + source + "]" + ve.key + " not found");
        }
        String picked = null;
        String val = null;
        for (String s : FlightVars.SOURCES) {
            String v = fr.lookup(s, ve.key);
            if (v == null) {
                continue;
            }
            if (picked == null) {
                picked = s;
                val = v;
                continue;
            }
            if (!v.equals(val)) {
                throw new VarNotFoundException("\"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(ve.key) + "\" is in both [" + picked + "] and [" + s
                        + "] with different values; pin one with " + ve.name + ":[" + picked + "]" + ve.key);
            }
        }
        if (picked == null) {
            throw new VarNotFoundException("\"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(ve.key) + "\" not found in any of form, meta, cookie or header");
        }
        autoPins.put(ve, picked);
        log.accept("preflight: " + ve.name + " resolved to [" + picked + "]" + ve.key);
        return val;
    }

    /** The scheme://host a flight request goes to, given its request-line path and Host header, like the URL built below. */
    private String flightAuthority(String path, String host) {
        if (path.startsWith("http")) {
            String scheme = Util.urlScheme(path);
            return (scheme == null ? "" : scheme) + "://" + Util.urlHost(path);
        }
        String scheme = Util.urlScheme(config.url);
        if (scheme == null) {
            return "https://" + host;
        }
        return scheme + "://" + (host.isEmpty() ? Util.urlHost(config.url) : host);
    }

    Request build(String filename, Map<String, String> vars) throws SimpleRunner.RequestException {
        String content;
        try {
            content = new String(Files.readAllBytes(Paths.get(filename)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SimpleRunner.RequestException("preflight: could not open \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(filename) + "\": open " + filename + ": no such file or directory", false);
        }
        String[] lines = content.split("\n", -1);
        String[] parts = lines[0].strip().split(" ", 3);
        if (parts.length < 2) {
            throw new SimpleRunner.RequestException("preflight: malformed request line in \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(filename) + "\"", false);
        }
        String method = parts[0];
        String path = parts[1];
        Map<String, String> headers = new LinkedHashMap<>(config.headers);
        String host = "";
        int i = 1;
        for (; i < lines.length; i++) {
            String line = lines[i].replaceAll("[\r\n]+$", "");
            if (line.isEmpty()) {
                i++;
                break;
            }
            String[] p = line.split(":", 2);
            if (p.length == 2) {
                String k = p[0].strip();
                String v = p[1].strip();
                if (k.equalsIgnoreCase("content-length")) {
                    continue;
                } else if (k.equalsIgnoreCase("host")) {
                    host = v;
                } else {
                    headers.put(Util.canonicalHeaderKey(k), v);
                }
            }
        }
        StringBuilder body = new StringBuilder();
        for (int j = i; j < lines.length; j++) {
            if (j > i) {
                body.append('\n');
            }
            body.append(lines[j]);
        }
        String b = body.toString().replaceAll("[\r\n]+$", "");
        String authored = flightAuthority(path, host);
        if (!vars.isEmpty()) {
            method = replace(method, vars);
            path = replace(path, vars);
            b = replace(b, vars);
            host = replace(host, vars);
            headers.replaceAll((k, v) -> replace(v, vars));
        }
        if (!vars.isEmpty() && !config.preflightAnyHost) {
            String got = flightAuthority(path, host);
            if (!got.equals(authored)) {
                throw new SimpleRunner.RequestException("preflight \"" + Flags.goQuote(filename) + "\": a captured variable changed the request destination from "
                        + authored + " to " + got + "; captured values come from the scanned target, so this is refused. Pass -preflight-anyhost if the target is meant to choose the destination", false);
            }
        }
        String url;
        if (path.startsWith("http")) {
            url = path;
        } else {
            String scheme = Util.urlScheme(config.url);
            if (scheme == null) {
                scheme = "https";
            }
            if (host.isEmpty()) {
                host = Util.urlHost(config.url);
            }
            for (io.github.ffuf.pkg.ffuf.InputProviderConfig ip : config.inputProviders) {
                if (!ip.keyword.isEmpty() && host.contains(ip.keyword)) {
                    throw new SimpleRunner.RequestException("preflight \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(filename) + "\": derived target host \"" + io.github.ffuf.pkg.ffuf.Flags.goQuote(host) + "\" still contains fuzz keyword \""
                            + ip.keyword + "\"; give the preflight file an absolute URL or a Host header", false);
                }
            }
            url = scheme + "://" + host + path;
        }
        Request r = new Request();
        r.method = method;
        r.url = url;
        r.headers = headers;
        if (!host.isEmpty()) {
            r.headers.put("Host", host);
        }
        r.data = b.getBytes(StandardCharsets.UTF_8);
        return r;
    }
}
