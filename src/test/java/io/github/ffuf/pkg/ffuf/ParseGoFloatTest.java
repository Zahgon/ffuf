package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Expected values come from Go 1.27's strconv.ParseFloat(s, 64). */
class ParseGoFloatTest {
    @Test
    void testValid() {
        assertEquals(Double.POSITIVE_INFINITY, Util.parseGoFloat("Inf"));
        assertEquals(10.0, Util.parseGoFloat("1_0"));
        assertEquals(0.25, Util.parseGoFloat("0x1p-2"));
        assertEquals(0.5, Util.parseGoFloat(".5"));
        assertEquals(1.0, Util.parseGoFloat("1."));
    }

    @Test
    void testInvalid() {
        for (String s : new String[] {" 0.1", "1e400", "+nan", "infin", "1_", "0x10", "1d", ""}) {
            assertNull(Util.parseGoFloat(s), s);
        }
    }
}
