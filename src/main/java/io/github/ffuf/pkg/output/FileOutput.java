package io.github.ffuf.pkg.output;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.GoString;
import io.github.ffuf.pkg.ffuf.GoUnicode;
import io.github.ffuf.pkg.ffuf.InputProviderConfig;
import io.github.ffuf.pkg.ffuf.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Writes the -o output file in the -of format. */
public final class FileOutput {
    private static final List<String> STATIC_HEADERS = List.of("url", "redirectlocation", "position", "status_code", "content_length",
            "content_words", "content_lines", "content_type", "duration", "resultfile", "Ffufhash");

    private FileOutput() {
    }

    /**
     * Writes res to filename in one of the single-file formats; an unknown format writes nothing.
     *
     * @param format one of json, ejson, html, md, csv or ecsv
     * @throws IOException when the file cannot be written
     */
    public static void write(String filename, String format, Config config, List<Stdoutput.Result> res) throws IOException {
        switch (format) {
            case "json" -> writeJson(filename, config, res);
            case "ejson" -> writeEjson(filename, config, res);
            case "md" -> writeMarkdown(filename, config, res);
            case "html" -> HtmlReport.write(filename, config, res, now());
            case "csv" -> writeCsv(filename, config, res, false);
            case "ecsv" -> writeCsv(filename, config, res, true);
            default -> {
                // other formats have no file writer, so nothing is written
            }
        }
    }

    /** The text of a file error as Go's *PathError reports it. */
    static String goError(IOException e) {
        if (e instanceof java.nio.file.NoSuchFileException f) {
            return "open " + f.getFile() + ": no such file or directory";
        }
        if (e instanceof java.nio.file.AccessDeniedException f) {
            return "open " + f.getFile() + ": permission denied";
        }
        if (e instanceof java.nio.file.FileSystemException f) {
            return "open " + f.getFile() + ": " + (java.nio.file.Files.isDirectory(java.nio.file.Paths.get(f.getFile())) ? "is a directory" : "input/output error");
        }
        return e.getMessage();
    }

    static String now() {
        return java.time.OffsetDateTime.now().withNano(0).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", "Z");
    }

    static void writeJson(String filename, Config config, List<Stdoutput.Result> res) throws IOException {
        List<Object> rows = new ArrayList<>();
        for (Stdoutput.Result r : res) {
            Map<String, Object> in = new java.util.TreeMap<>();
            for (Map.Entry<String, byte[]> e : r.input.entrySet()) {
                in.put(e.getKey(), GoString.decode(e.getValue()));
            }
            rows.add(new Json.Obj().put("input", in).put("position", r.position).put("status", r.statusCode).put("length", r.contentLength)
                    .put("words", r.contentWords).put("lines", r.contentLines).put("content-type", r.contentType)
                    .put("redirectlocation", r.redirectLocation).put("scraper", r.scraperData).put("duration", r.durationNanos)
                    .put("resultfile", r.resultFile).put("url", r.url).put("host", r.host));
        }
        Json.Obj out = new Json.Obj().put("commandline", config.commandLine).put("time", now()).put("results", rows).put("config", config.toJson());
        Files.write(Paths.get(filename), GoString.encode(out.toString()));
    }

    static void writeEjson(String filename, Config config, List<Stdoutput.Result> res) throws IOException {
        StringBuilder sb = new StringBuilder("{\"commandline\":");
        Json.writeString(sb, config.commandLine);
        sb.append(",\"time\":");
        Json.writeString(sb, now());
        sb.append(",\"results\":[");
        for (int i = 0; i < res.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(res.get(i).toJson());
        }
        sb.append("],\"config\":null}");
        Files.write(Paths.get(filename), GoString.encode(sb.toString()));
    }

    static String htmlEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("&#34;");
                case '\'' -> sb.append("&#39;");
                case '&' -> sb.append("&amp;");
                case '+' -> sb.append("&#43;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case 0 -> sb.append("\uFFFD");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static void writeMarkdown(String filename, Config config, List<Stdoutput.Result> res) throws IOException {
        StringBuilder sb = new StringBuilder("# FFUF Report\n\n  Command line : `");
        sb.append(htmlEscape(config.commandLine)).append("`\n  Time: ").append(htmlEscape(now())).append("\n\n  ");
        for (InputProviderConfig p : config.inputProviders) {
            sb.append("| ").append(htmlEscape(p.keyword)).append(' ');
        }
        sb.append("| URL | Redirectlocation | Position | Status Code | Content Length | Content Words | Content Lines | Content Type | Duration | ResultFile | ScraperData | Ffufhash\n  ");
        for (int i = 0; i < config.inputProviders.size(); i++) {
            sb.append("| :- ");
        }
        sb.append("| :-- | :--------------- | :---- | :------- | :---------- | :------------- | :------------ | :--------- | :----------- | :------------ | :-------- |\n  ");
        String hash = "";
        for (Stdoutput.Result r : res) {
            Map<String, String> in = new java.util.TreeMap<>();
            for (Map.Entry<String, byte[]> e : r.input.entrySet()) {
                if (e.getKey().equals("FFUFHASH")) {
                    hash = GoString.decode(e.getValue());
                } else {
                    in.put(e.getKey(), GoString.decode(e.getValue()));
                }
            }
            for (String v : in.values()) {
                sb.append("| ").append(htmlEscape(v)).append(' ');
            }
            sb.append("| ").append(htmlEscape(r.url)).append(" | ").append(htmlEscape(r.redirectLocation)).append(" | ").append(r.position)
                    .append(" | ").append(r.statusCode).append(" | ").append(r.contentLength).append(" | ").append(r.contentWords)
                    .append(" | ").append(r.contentLines).append(" | ").append(htmlEscape(r.contentType)).append(" | ")
                    .append(goDuration(r.durationNanos)).append(" | ").append(htmlEscape(r.resultFile)).append(" |  | ")
                    .append(htmlEscape(hash)).append("\n  ");
        }
        Files.write(Paths.get(filename), GoString.encode(sb.toString()));
    }

    static void writeCsv(String filename, Config config, List<Stdoutput.Result> res, boolean encode) throws IOException {
        StringBuilder sb = new StringBuilder();
        List<String> header = new ArrayList<>();
        for (InputProviderConfig p : config.inputProviders) {
            header.add(p.keyword);
        }
        header.addAll(STATIC_HEADERS);
        csvLine(sb, header);
        for (Stdoutput.Result r : res) {
            List<String> row = new ArrayList<>();
            String hash = "";
            for (Map.Entry<String, byte[]> e : r.input.entrySet()) {
                String v = encode ? Base64.getEncoder().encodeToString(e.getValue()) : GoString.decode(e.getValue());
                if (e.getKey().equals("FFUFHASH")) {
                    hash = v;
                } else {
                    row.add(v);
                }
            }
            row.add(r.url);
            row.add(r.redirectLocation);
            row.add(Integer.toString(r.position));
            row.add(Long.toString(r.statusCode));
            row.add(Long.toString(r.contentLength));
            row.add(Long.toString(r.contentWords));
            row.add(Long.toString(r.contentLines));
            row.add(r.contentType);
            row.add(goDuration(r.durationNanos));
            row.add(r.resultFile);
            row.add(hash);
            csvLine(sb, row);
        }
        Files.write(Paths.get(filename), GoString.encode(sb.toString()));
    }

    private static void csvLine(StringBuilder sb, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String f = fields.get(i);
            boolean quote = !f.isEmpty() && (f.contains(",") || f.contains("\"") || f.contains("\r") || f.contains("\n")
                    || GoUnicode.isSpace(f.codePointAt(0)) || f.equals("\\."));
            if (quote) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(f);
            }
        }
        sb.append('\n');
    }

    /** {@return a duration in nanoseconds as text such as "1.5s" or "250ms", with the rules of Go's time.Duration.String} */
    public static String goDuration(long ns) {
        if (ns == 0) {
            return "0s";
        }
        if (ns < 1000) {
            return ns + "ns";
        }
        if (ns < 1_000_000) {
            return trimFloat(ns / 1000.0) + "µs";
        }
        if (ns < 1_000_000_000) {
            return trimFloat(ns / 1_000_000.0) + "ms";
        }
        return trimFloat(ns / 1_000_000_000.0) + "s";
    }

    private static String trimFloat(double d) {
        String s = new java.math.BigDecimal(d).setScale(9, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        return s;
    }
}
