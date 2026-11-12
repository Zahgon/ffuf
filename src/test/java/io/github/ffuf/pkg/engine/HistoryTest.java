package io.github.ffuf.pkg.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.filter.MatcherManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryTest {
    @TempDir
    Path tmp;

    /** The options a config retains, written as a history entry and read back, rebuild an equivalent request. */
    @Test
    void testConfigOptions_SerializationRoundTrip() throws Exception {
        ConfigOptions opts = new ConfigOptions();
        opts.http.url = "https://example.org/FUZZ";
        opts.http.method = "POST";
        opts.http.data = "name=FUZZ";
        opts.http.headers = List.of("X-Test: FUZZ");
        opts.input.wordlists = List.of("/tmp/wl.txt");
        opts.general.threads = 7;
        opts.general.delay = "0.1-0.8";

        Config conf = Config.fromOptions(opts, List.of("ffuf"));
        assertNotNull(conf.options, "conf.options was not retained by fromOptions");
        Files.write(tmp.resolve("options"), conf.options.toJson(null).getBytes(StandardCharsets.UTF_8));
        ConfigOptions restored = HistoryEntry.read(tmp).options;
        Config conf2 = Config.fromOptions(restored, List.of("ffuf"));

        assertEquals(conf.url, conf2.url, "url across round-trip");
        assertEquals(conf.method, conf2.method, "method across round-trip");
        assertEquals(conf.data, conf2.data, "data across round-trip");
        assertEquals(conf.threads, conf2.threads, "threads across round-trip");
        assertEquals(conf.delayMin, conf2.delayMin, "delay min across round-trip");
        assertEquals(conf.delayMax, conf2.delayMax, "delay max across round-trip");
        assertEquals(conf.headers.get("X-Test"), conf2.headers.get("X-Test"), "header across round-trip");
    }

    /** A history entry records the live URL of a recursion job, not the base URL of the parsed options. */
    @Test
    void testHistoryOptions_ReflectsRecursedURL() {
        ConfigOptions opts = new ConfigOptions();
        opts.http.url = "https://example.org/FUZZ";
        Config conf = new Config();
        conf.options = opts;
        conf.url = "https://example.org/admin/FUZZ";
        conf.matcherManager = new MatcherManager();
        assertEquals("https://example.org/admin/FUZZ", Job.historyOptions(conf).http.url, "history URL");
    }

    /** Filters installed at run time, such as by -ac, are in the history entry. */
    @Test
    void testHistoryOptions_ReflectsRuntimeFilters() {
        ConfigOptions opts = new ConfigOptions();
        opts.http.url = "https://example.org/FUZZ";
        Config conf = new Config();
        conf.options = opts;
        conf.url = "https://example.org/FUZZ";
        conf.matcherManager = new MatcherManager();
        conf.matcherManager.addFilter("size", "4242", false);
        assertEquals("4242", Job.historyOptions(conf).filter.size, "history filter size");
    }
}
