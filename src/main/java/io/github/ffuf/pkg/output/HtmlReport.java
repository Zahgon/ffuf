package io.github.ffuf.pkg.output;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.GoString;
import io.github.ffuf.pkg.ffuf.InputProviderConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders the HTML report from ffuf's report template, escaping values as Go's html/template does. */
final class HtmlReport {
    private static final String RESULTS_START = "{{range $result := .Results}}";
    private static final String HEAD_KEYS = "{{ range $keyword := .Keys }}|{{ $keyword | printf \"%s\" }}{{ end }}";
    private static final String TH_KEYS = "{{ range .Keys }}              <th>{{ . }}</th>{{ end }}";
    private static final String RAW_INPUTS = "{{ range $keyword, $value := $result.Input }}|{{ $value | printf \"%s\" }}{{ end }}";
    private static final Pattern TD_INPUTS = Pattern.compile("\\{\\{ range \\$keyword, \\$value := \\$result.Input \\}\\}(.*?)\\{\\{ end \\}\\}", Pattern.DOTALL);

    private HtmlReport() {
    }

    static void write(String filename, Config config, List<Stdoutput.Result> res, String time) throws IOException {
        String tmpl;
        try (InputStream in = HtmlReport.class.getResourceAsStream("report.html.tmpl")) {
            if (in == null) {
                throw new IOException("HTML report template missing from the jar");
            }
            tmpl = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        }
        int start = tmpl.indexOf(RESULTS_START);
        int end = tmpl.lastIndexOf("{{ end }}");
        String head = tmpl.substring(0, start);
        String row = tmpl.substring(start + RESULTS_START.length(), end);
        String foot = tmpl.substring(end + "{{ end }}".length());
        StringBuilder keysRaw = new StringBuilder();
        StringBuilder keysTh = new StringBuilder();
        for (InputProviderConfig p : config.inputProviders) {
            keysRaw.append('|').append(FileOutput.htmlEscape(p.keyword));
            keysTh.append("              <th>").append(FileOutput.htmlEscape(p.keyword)).append("</th>");
        }
        head = head.replace("{{ .CommandLine }}", FileOutput.htmlEscape(config.commandLine)).replace("{{ .Time }}", FileOutput.htmlEscape(time))
                .replace(HEAD_KEYS, keysRaw.toString()).replace(TH_KEYS, keysTh.toString());
        StringBuilder sb = new StringBuilder(head);
        String hash = "";
        for (Stdoutput.Result r : res) {
            Map<String, String> inputs = new TreeMap<>();
            for (Map.Entry<String, byte[]> e : r.input.entrySet()) {
                String v = GoString.decode(e.getValue());
                if (e.getKey().equals("FFUFHASH")) {
                    hash = v;
                } else {
                    inputs.put(e.getKey(), v);
                }
            }
            StringBuilder raw = new StringBuilder();
            for (String v : inputs.values()) {
                raw.append('|').append(FileOutput.htmlEscape(v));
            }
            String out = row.replace(RAW_INPUTS, raw.toString());
            Matcher m = TD_INPUTS.matcher(out);
            if (m.find()) {
                StringBuilder tds = new StringBuilder();
                for (String v : inputs.values()) {
                    tds.append(m.group(1).replace("{{ $value | printf \"%s\" }}", FileOutput.htmlEscape(v)));
                }
                out = out.substring(0, m.start()) + tds + out.substring(m.end());
            }
            out = out.replace("href=\"{{ $result.Url }}\"", "href=\"" + urlAttr(r.url) + "\"")
                    .replace("href=\"{{ $result.RedirectLocation }}\"", "href=\"" + urlAttr(r.redirectLocation) + "\"")
                    .replace("{{ $result.StatusCode }}", Long.toString(r.statusCode))
                    .replace("{{ $result.HTMLColor }}", color(r.statusCode))
                    .replace("{{ $result.Url }}", FileOutput.htmlEscape(r.url))
                    .replace("{{ $result.RedirectLocation }}", FileOutput.htmlEscape(r.redirectLocation))
                    .replace("{{ $result.Position }}", Integer.toString(r.position))
                    .replace("{{ $result.ContentLength }}", Long.toString(r.contentLength))
                    .replace("{{ $result.ContentWords }}", Long.toString(r.contentWords))
                    .replace("{{ $result.ContentLines }}", Long.toString(r.contentLines))
                    .replace("{{ $result.ContentType }}", FileOutput.htmlEscape(r.contentType))
                    .replace("{{ $result.Duration }}", FileOutput.goDuration(r.durationNanos))
                    .replace("{{ $result.ResultFile }}", FileOutput.htmlEscape(r.resultFile))
                    .replace("{{ $result.ScraperData }}", "")
                    .replace("{{ $result.FfufHash }}", FileOutput.htmlEscape(hash));
            sb.append(out);
        }
        sb.append(foot);
        Files.write(Paths.get(filename), GoString.encode(sb.toString()));
    }

    private static String color(long s) {
        if (s >= 200 && s <= 299) {
            return "#adea9e";
        }
        if (s >= 300 && s <= 399) {
            return "#bbbbe6";
        }
        if (s >= 400 && s <= 499) {
            return "#d2cb7e";
        }
        if (s >= 500 && s <= 599) {
            return "#de8dc1";
        }
        return "black";
    }

    /** html/template's URL normaliser followed by attribute escaping. */
    private static String urlAttr(String url) {
        StringBuilder sb = new StringBuilder();
        for (byte b : GoString.encode(url)) {
            int c = b & 0xff;
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$&*+,-./:;=?@[]_~%".indexOf(c) >= 0;
            if (keep) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02x", c));
            }
        }
        return FileOutput.htmlEscape(sb.toString());
    }
}
