package io.github.ffuf.pkg.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ffuf.pkg.ffuf.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCsvTest {
    @TempDir
    Path tmp;

    @Test
    void testToCSV() throws Exception {
        Stdoutput.Result result = new Stdoutput.Result();
        Map<String, byte[]> input = new LinkedHashMap<>();
        input.put("x", new byte[] {66});
        input.put("FFUFHASH", new byte[] {65});
        result.input = input;
        result.position = 1;
        result.statusCode = 200;
        result.contentLength = 3;
        result.contentWords = 4;
        result.contentLines = 5;
        result.contentType = "application/json";
        result.redirectLocation = "http://no.pe";
        result.url = "http://as.df";
        result.durationNanos = 123;
        result.resultFile = "resultfile";
        result.host = "host";

        Path file = tmp.resolve("out.csv");
        FileOutput.writeCsv(file.toString(), new Config(), List.of(result), false);
        String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
        assertEquals(List.of("B", "http://as.df", "http://no.pe", "1", "200", "3", "4", "5", "application/json", "123ns", "resultfile", "A"),
                List.of(lines[1].split(",", -1)), "CSV row");
    }
}
