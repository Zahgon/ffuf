package io.github.ffuf.test.integration;

import static io.github.ffuf.test.integration.HarnessTest.assertSet;
import static io.github.ffuf.test.integration.HarnessTest.recordedPaths;
import static io.github.ffuf.test.integration.HarnessTest.runScan;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ffuf.pkg.testtarget.Target;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncodersTest {
    @TempDir
    Path tmp;

    /** With FUZZ:b64encode, "hello" goes on the wire as its base64 form. */
    @Test
    void testEncoderApplied() throws Exception {
        try (Target target = Target.start()) {
            runScan(tmp, target.url() + "/reflect/FUZZ", List.of("hello"), o -> o.input.encoders = List.of("FUZZ:b64encode"),
                    mm -> mm.addMatcher("status", "all"));
            assertSet(List.of("/reflect/aGVsbG8="), recordedPaths(target.requests()));
            assertEquals(1, target.count(), "encoder scan requests");
        }
    }
}
