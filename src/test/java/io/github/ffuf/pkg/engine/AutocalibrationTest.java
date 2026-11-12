package io.github.ffuf.pkg.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.test.integration.HarnessTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutocalibrationTest {
    private static Map<String, List<String>> strings(String strategy) {
        Config conf = new Config();
        conf.autoCalibrationStrategies = List.of(strategy);
        return new Calibration(conf, null, new HarnessTest.Capture(conf), r -> true, () -> { }).strings();
    }

    /** Strategy files live in the autocalibration folder of the configuration folder, isolated for the test run. */
    @Test
    void testAutoCalibrationStrings() throws Exception {
        Util.checkOrCreateConfigDir();
        Path dir = Paths.get(Util.configDir(), "autocalibration");
        Path strategyFile = dir.resolve("test.json");
        Path malformedFile = dir.resolve("malformed.json");
        try {
            Files.write(strategyFile, "{\"test\":[\"foo\",\"bar\"]}".getBytes(StandardCharsets.UTF_8));
            Map<String, List<String>> inputs = strings("test");
            assertFalse(inputs.containsKey("custom"), "custom strategy should be empty, got " + inputs.get("custom"));
            assertEquals(List.of("foo", "bar"), inputs.get("test"), "test strategy inputs");

            assertTrue(strings("missing").isEmpty(), "missing strategy should be skipped");

            Files.write(malformedFile, "{\"test\": \"foo\"}".getBytes(StandardCharsets.UTF_8));
            assertTrue(strings("malformed").isEmpty(), "malformed strategy should be skipped");
        } finally {
            Files.deleteIfExists(strategyFile);
            Files.deleteIfExists(malformedFile);
        }
    }
}
