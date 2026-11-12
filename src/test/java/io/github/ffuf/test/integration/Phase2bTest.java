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

class Phase2bTest {
    @TempDir
    Path tmp;

    /** Sniper fuzzes one marked position at a time and holds the others at their literal text. */
    @Test
    void testInputModeSniper() throws Exception {
        try (Target target = Target.start()) {
            runScan(tmp, target.url() + "/reflect/§a§/§b§", List.of("x", "y"), o -> o.input.inputMode = "sniper", mm -> mm.addMatcher("status", "all"));
            assertSet(List.of("/reflect/x/b", "/reflect/y/b", "/reflect/a/x", "/reflect/a/y"), recordedPaths(target.requests()));
            assertEquals(4, target.count(), "sniper requests (2 positions x 2 words)");
        }
    }

    @Test
    void testMatcherModeAnd() throws Exception {
        try (Target target = Target.start()) {
            List<String> got = runScan(tmp, target.url() + "/reflect/FUZZ", List.of("keep", "drop"), o -> o.matcher.mode = "and", mm -> {
                mm.addMatcher("status", "200");
                mm.addMatcher("regexp", "keep");
            });
            assertSet(List.of("keep"), got);
        }
    }

    @Test
    void testFilterModeAnd() throws Exception {
        try (Target target = Target.start()) {
            List<String> got = runScan(tmp, target.url() + "/reflect/FUZZ", List.of("secret", "public"), o -> o.filter.mode = "and", mm -> {
                mm.addMatcher("status", "all");
                mm.addFilter("status", "200", false);
                mm.addFilter("regexp", "secret", false);
            });
            assertSet(List.of("public"), got);
        }
    }
}
