package io.github.ffuf.test.integration;

import static io.github.ffuf.test.integration.HarnessTest.assertSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.engine.Job;
import io.github.ffuf.pkg.output.Stdoutput;
import io.github.ffuf.pkg.testtarget.Target;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputformatsTest {
    @TempDir
    Path tmp;

    /**
     * Runs a scan with the real terminal output, which accumulates the results and writes them to the -o file at the
     * end. The terminal lines are captured and returned so they stay out of the test log.
     */
    private String runScanStdout(String url, List<String> words, Consumer<ConfigOptions> configure) throws Exception {
        ConfigOptions opts = HarnessTest.options(tmp, url, words);
        configure.accept(opts);
        Job job = HarnessTest.buildJob(opts, mm -> mm.addMatcher("status", "all"), Stdoutput::new);
        PrintStream oldOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            job.start();
        } finally {
            System.setOut(oldOut);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** Every match reported at the default concurrency is accumulated; none is lost to concurrent appends. */
    @Test
    void testOutputConcurrentResultAccumulation() throws Exception {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            words.add("w" + i);
        }
        Path out = tmp.resolve("out.json");
        try (Target target = Target.start()) {
            runScanStdout(target.url() + "/reflect/FUZZ", words, o -> {
                o.output.outputFile = out.toString();
                o.output.outputFormat = "json";
            });
        }
        JsonNode doc = new ObjectMapper().readTree(Files.readAllBytes(out));
        assertEquals(60, doc.path("results").size(), "accumulated results at high concurrency");
    }

    /** Writes each supported file format and checks it carries the result fields, status and input. */
    @Test
    void testOutputFormats() throws Exception {
        Path base = tmp.resolve("out");
        try (Target target = Target.start()) {
            runScanStdout(target.url() + "/status/FUZZ", List.of("200", "201"), o -> {
                o.general.threads = 1;
                o.matcher.status = "200,201";
                o.output.outputFile = base.toString();
                o.output.outputFormat = "all";
            });
        }
        ObjectMapper mapper = new ObjectMapper();
        for (String format : new String[] {"json", "ejson", "csv", "ecsv", "md", "html"}) {
            Path path = tmp.resolve("out." + format);
            byte[] b = Files.readAllBytes(path);
            assertTrue(b.length > 0, format + ": wrote an empty file");
            String text = new String(b, StandardCharsets.UTF_8);
            switch (format) {
                case "json", "ejson" -> {
                    List<String> statuses = new ArrayList<>();
                    List<String> inputs = new ArrayList<>();
                    for (JsonNode r : mapper.readTree(b).path("results")) {
                        statuses.add(r.path("status").asText());
                        String in = r.path("input").path("FUZZ").asText();
                        inputs.add(format.equals("ejson") ? new String(Base64.getDecoder().decode(in), StandardCharsets.UTF_8) : in);
                    }
                    assertSet(List.of("200", "201"), statuses);
                    assertSet(List.of("200", "201"), inputs);
                }
                case "csv", "ecsv" -> {
                    List<List<String>> rows = readCsv(text, format);
                    assertEquals(3, rows.size(), format + ": rows (header + 2 results)");
                    int col = rows.get(0).indexOf("status_code");
                    assertTrue(col >= 0, format + ": no status_code column in header " + rows.get(0));
                    assertSet(List.of("200", "201"), List.of(rows.get(1).get(col), rows.get(2).get(col)));
                }
                default -> {
                    for (String in : List.of("200", "201")) {
                        assertTrue(text.contains(in), format + ": output missing status " + in);
                    }
                }
            }
        }
    }

    /**
     * Reads text as CSV the way Go's encoding/csv ReadAll does: comma-separated fields, double-quoted fields with ""
     * for a quote, records ended by \n or \r\n, and every record with as many fields as the first. Fails the test
     * when the text is not valid CSV.
     */
    private static List<List<String>> readCsv(String text, String format) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int i = 0;
        boolean atFieldStart = true;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (atFieldStart && c == '"') {
                i++;
                while (true) {
                    assertTrue(i < text.length(), format + ": not valid CSV: extraneous or missing \" in quoted-field");
                    char q = text.charAt(i);
                    if (q == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else if (q == '"') {
                        i++;
                        break;
                    } else {
                        field.append(q);
                        i++;
                    }
                }
                assertTrue(i == text.length() || text.charAt(i) == ',' || text.charAt(i) == '\n' || text.charAt(i) == '\r',
                        format + ": not valid CSV: extraneous \" in field");
                atFieldStart = false;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                atFieldStart = true;
                i++;
            } else if (c == '\n' || (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n')) {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                atFieldStart = true;
                i += c == '\r' ? 2 : 1;
            } else {
                assertTrue(c != '"', format + ": not valid CSV: bare \" in non-quoted-field");
                field.append(c);
                atFieldStart = false;
                i++;
            }
        }
        if (!atFieldStart || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        for (List<String> r : rows) {
            assertEquals(rows.get(0).size(), r.size(), format + ": not valid CSV: wrong number of fields");
        }
        return rows;
    }
}
