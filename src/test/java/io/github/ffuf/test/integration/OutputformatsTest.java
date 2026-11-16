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
                    String[] rows = text.split("\n");
                    assertEquals(3, rows.length, format + ": rows (header + 2 results)");
                    int col = List.of(rows[0].split(",", -1)).indexOf("status_code");
                    assertTrue(col >= 0, format + ": no status_code column in header " + rows[0]);
                    assertSet(List.of("200", "201"), List.of(rows[1].split(",", -1)[col], rows[2].split(",", -1)[col]));
                }
                default -> {
                    for (String in : List.of("200", "201")) {
                        assertTrue(text.contains(in), format + ": output missing status " + in);
                    }
                }
            }
        }
    }
}
