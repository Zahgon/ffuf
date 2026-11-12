package io.github.ffuf.pkg.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.filter.MatcherManager;
import io.github.ffuf.pkg.runner.SimpleRunner;
import io.github.ffuf.test.integration.HarnessTest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutocalibrationMutationTest {
    /** A runner whose requests fail: enough to drive calibration without HTTP. */
    private static final class StubRunner extends SimpleRunner {
        StubRunner(Config conf) {
            super(conf);
        }

        @Override
        public Request prepare(Map<String, byte[]> input, Request base) {
            Request r = base.copy();
            r.input = input;
            return r;
        }

        @Override
        public Response execute(Request req) throws RequestException {
            throw new RequestException("stub", false);
        }
    }

    /**
     * The runner hands the caller's input map on to the response, so calibration must copy it before overwriting the
     * keyword with each calibration string; otherwise a result reported after calibration shows the probe instead
     * of the payload.
     */
    @Test
    void testCalibrate_DoesNotMutateInput() {
        Config conf = new Config();
        conf.url = "http://localhost/FUZZ";
        conf.method = "GET";
        conf.autoCalibration = true;
        conf.autoCalibrationKeyword = "FUZZ";
        conf.autoCalibrationStrings = List.of("probe-a", "probe-b");
        conf.matcherManager = new MatcherManager();
        Calibration c = new Calibration(conf, new StubRunner(conf), new HarnessTest.Capture(conf), r -> true, () -> { });
        Map<String, byte[]> input = new LinkedHashMap<>();
        input.put("FUZZ", "realpayload".getBytes(StandardCharsets.UTF_8));
        c.calibrateIfNeeded("http://localhost", input);
        assertEquals("realpayload", new String(input.get("FUZZ"), StandardCharsets.UTF_8), "FUZZ after calibration");
    }
}
