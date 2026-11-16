package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.ConfigOptions;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Property tests for the range and value parsers behind every numeric matcher and filter, and for the -p delay
 * parser. Like Go's testing/quick runs they use a fixed seed and 1000 samples.
 */
class ValueRangeTest {
    private static final int SAMPLES = 1000;

    @Test
    void testValueRange_RangeInvariant() {
        Random r = new Random(1);
        for (int i = 0; i < SAMPLES; i++) {
            long lo = r.nextInt(1 << 16);
            long hi = r.nextInt(1 << 16);
            long[] vr = RangeFilter.valueRange(lo + "-" + hi);
            if (lo < hi) {
                assertArrayEquals(new long[] {lo, hi}, vr, "range " + lo + "-" + hi);
            } else {
                assertNull(vr, "range " + lo + "-" + hi + " has min >= max, want an error");
            }
        }
    }

    @Test
    void testValueRange_SingleValueInvariant() {
        Random r = new Random(1);
        for (int i = 0; i < SAMPLES; i++) {
            long n = Integer.toUnsignedLong(r.nextInt());
            assertArrayEquals(new long[] {n, n}, RangeFilter.valueRange(Long.toString(n)), "single value " + n);
        }
    }

    @Test
    void testValueRange_NeverPanics() {
        Random r = new Random(1);
        for (int i = 0; i < SAMPLES; i++) {
            String s = arbitraryString(r);
            long[] vr = RangeFilter.valueRange(s);
            assertTrue(vr == null || vr[0] <= vr[1], "value range of \"" + s + "\" must be well-formed (min <= max)");
        }
    }

    @Test
    void testOptRange_SingleFloatInvariant() throws Config.ConfigException {
        Random r = new Random(1);
        for (int i = 0; i < SAMPLES; i++) {
            String val = String.format(Locale.ROOT, "%d.%02d", r.nextInt(1 << 16), r.nextInt(256) % 100);
            ConfigOptions o = new ConfigOptions();
            o.http.url = "http://127.0.0.1/FUZZ";
            o.input.wordlists = List.of("/tmp/wl.txt");
            o.general.delay = val;
            Config c = Config.fromOptions(o, List.of("ffuf"));
            assertTrue(c.delayHasDelay, "delay " + val + " has a delay");
            assertTrue(!c.delayIsRange, "delay " + val + " is not a range");
            assertEquals(Double.parseDouble(val), c.delayMin, "delay " + val + " min");
        }
    }

    @Test
    void testOptRange_NeverPanics() {
        Random r = new Random(1);
        for (int i = 0; i < SAMPLES; i++) {
            String s = arbitraryString(r);
            ConfigOptions o = new ConfigOptions();
            o.http.url = "http://127.0.0.1/FUZZ";
            o.input.wordlists = List.of("/tmp/wl.txt");
            o.general.delay = s;
            try {
                Config c = Config.fromOptions(o, List.of("ffuf"));
                assertTrue(s.isEmpty() || c.delayHasDelay, "delay \"" + s + "\" parsed without error, want HasDelay set");
            } catch (Config.ConfigException e) {
                assertTrue(e.getMessage().contains("Delay"), "delay \"" + s + "\" error mentions Delay: " + e.getMessage());
            }
        }
    }

    /**
     * An arbitrary string as testing/quick draws one for a string argument: fewer than 50 runes, each uniform below
     * U+10FFFF; a surrogate code point becomes U+FFFD, as Go's string conversion of that rune does.
     */
    private static String arbitraryString(Random r) {
        StringBuilder sb = new StringBuilder();
        int len = r.nextInt(50);
        for (int k = 0; k < len; k++) {
            int cp = r.nextInt(0x10ffff);
            sb.appendCodePoint(cp >= 0xd800 && cp <= 0xdfff ? 0xfffd : cp);
        }
        return sb.toString();
    }
}
