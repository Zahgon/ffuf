package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class UtilTest {
    @Test
    void testRandomString() {
        int length = 1 + new Random().nextInt(65535);
        assertEquals(length, Util.randomString(length).length(), "random string length");
    }

    @Test
    void testUniqStringSlice() {
        List<String> slice = List.of("foo", "foo", "bar", "baz", "baz", "foo", "baz", "baz", "foo");
        assertEquals(3, Util.uniqStringSlice(slice).size(), "Length of slice");
    }

    @Test
    void testMultierrorFormat() {
        MultiError m = new MultiError();
        assertNull(m.errorOrNull());
        m.add("first");
        m.add("second");
        assertEquals("2 errors occured.\n\t* first\n\t* second\n", m.errorOrNull());
    }

    @Test
    void testFlagParsing() throws Flags.FlagException {
        ConfigOptions o = new ConfigOptions();
        Flags f = new Flags(o);
        List<String> rest = f.parse(List.of("--u=http://x/FUZZ", "-t", "0x10", "-c", "-w", "a.txt,b.txt", "rest", "-v"));
        assertEquals("http://x/FUZZ", o.http.url);
        assertEquals(16, o.general.threads);
        assertEquals(true, o.general.colors);
        assertEquals(List.of("a.txt", "b.txt"), o.input.wordlists);
        assertEquals(List.of("rest", "-v"), rest);
    }
}
