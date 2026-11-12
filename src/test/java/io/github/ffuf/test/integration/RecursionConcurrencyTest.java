package io.github.ffuf.test.integration;

import static io.github.ffuf.test.integration.HarnessTest.runScan;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ffuf.pkg.testtarget.Target;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecursionConcurrencyTest {
    @TempDir
    Path tmp;

    /**
     * Greedy recursion on /reflect/FUZZ makes every base match queue a recursion job at nearly the same instant from
     * the default 40 workers. Each queued depth-1 job runs the whole wordlist again, so a lost append shows as a
     * request count short of 25 + 25 * 25.
     */
    @Test
    void testRecursionConcurrentQueue() throws Exception {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            words.add("d" + i);
        }
        try (Target target = Target.start()) {
            runScan(tmp, target.url() + "/reflect/FUZZ", words, o -> {
                o.http.recursion = true;
                o.http.recursionStrategy = "greedy";
                o.http.recursionDepth = 1;
            }, mm -> mm.addMatcher("status", "200"));
            assertEquals(words.size() + words.size() * words.size(), target.count(), "requests with every queued recursion job processed");
        }
    }
}
