package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class FlagsTest {
    /**
     * The full expected flag surface, hidden compat aliases and dummies included. A net-zero add and remove, a rename
     * or a duplicate fails here; update it deliberately when the CLI changes.
     */
    @Test
    void testRegisterFlags_Wellformed() {
        Flags flags = new Flags(new ConfigOptions());
        Set<String> validSection = Set.of(Flags.SECTION_HTTP, Flags.SECTION_GENERAL, Flags.SECTION_COMPAT, Flags.SECTION_MATCHER,
                Flags.SECTION_FILTER, Flags.SECTION_INPUT, Flags.SECTION_OUTPUT);
        Set<String> expected = new TreeSet<>(Set.of(
                "H", "X", "b", "cc", "ck", "d", "http2", "ignore-body", "r", "raw", "recursion", "recursion-depth", "recursion-strategy",
                "replay-proxy", "sni", "timeout", "u", "x", "preflight-mode", "preflight-error", "preflight-anyhost", "preflight",
                "preflight-var", "postflight", "postflight-var", "preflight-var-auto", "postflight-var-auto",
                "V", "ac", "acc", "ach", "ack", "acs", "c", "config", "json", "maxtime", "maxtime-job", "noninteractive", "p", "rate", "s",
                "sa", "scraperfile", "scrapers", "se", "search", "sf", "t", "v",
                "mc", "ml", "mmode", "mr", "ms", "mt", "mw",
                "fc", "fl", "fmode", "fr", "fs", "ft", "fw",
                "D", "e", "enc", "ic", "input-cmd", "input-num", "input-shell", "mode", "request", "request-proto", "w",
                "audit-log", "debug-log", "o", "od", "of", "or",
                "cookie", "data", "data-ascii", "data-binary",
                "compressed", "i", "k"));
        Set<String> registered = new TreeSet<>();
        for (Flags.Flag f : flags.all()) {
            assertTrue(registered.add(f.name), "flag -" + f.name + " registered twice");
            assertFalse(f.usage.isEmpty(), "flag -" + f.name + " has an empty usage string");
            assertTrue(validSection.contains(f.section), "flag -" + f.name + " has unknown section " + f.section);
        }
        assertEquals(expected, registered, "registered flags");
    }
}
