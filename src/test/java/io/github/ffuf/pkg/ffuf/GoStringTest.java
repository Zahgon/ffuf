package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks the lossless byte-string mapping and the rune counting, padding and quoting Go applies to it. */
class GoStringTest {
    private static byte[] hex(String h) {
        return java.util.HexFormat.of().parseHex(h);
    }

    @ParameterizedTest
    @CsvSource({"61ff62", "eda080", "e4b878", "c0af", "f4908080", "f09f988078", "efbfbd", "636166e9"})
    void roundTripsAnyBytes(String h) {
        assertArrayEquals(hex(h), GoString.encode(GoString.decode(hex(h))));
    }

    @ParameterizedTest
    @CsvSource({"61ff62, 3", "eda080, 3", "e4b878, 3", "f09f988078, 2", "f4908080, 4"})
    void countsRunesLikeUtf8RuneCount(String h, int runes) {
        assertEquals(runes, GoString.runeCount(GoString.decode(hex(h))));
    }

    @Test
    void padsByRunes() {
        assertEquals("\ud83d\ude00x    |", GoString.padRight("\ud83d\ude00x", 6) + "|");
        assertArrayEquals(hex("61ff62202020"), GoString.encode(GoString.padRight(GoString.decode(hex("61ff62")), 6)));
    }

    @Test
    void replacesInvalidBytesForJson() {
        assertEquals("caf\ufffd\ufffd\ufffd\ufffd", GoString.toValidUtf8(GoString.decode(hex("636166e9eda080"))));
    }

    @Test
    void quotesLikeStrconvQuote() {
        String s = GoString.decode("a\u00ad\u200b\u2028\ufeff\u0001\u007f".getBytes(StandardCharsets.UTF_8)) + GoString.decode(hex("ff"))
                + "\ud83d\ude00\"\\";
        assertEquals("a\\u00ad\\u200b\\u2028\\ufeff\\x01\\x7f\\xff\ud83d\ude00\\\"\\\\", GoUnicode.quoteBody(s));
    }

    @Test
    void formatsSharpU() {
        assertEquals("U+0078 'x'", GoUnicode.formatSharpU('x'));
        assertEquals("U+0001", GoUnicode.formatSharpU(1));
        assertEquals("U+00E2 '\u00e2'", GoUnicode.formatSharpU(0xe2));
        assertEquals("U+00AD", GoUnicode.formatSharpU(0xad));
    }
}
