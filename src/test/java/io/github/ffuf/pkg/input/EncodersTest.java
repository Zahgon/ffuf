package io.github.ffuf.pkg.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks the -enc encoders against the output and error texts of github.com/ffuf/pencode built with Go 1.27. */
class EncodersTest {
    private static byte[] run(String encoder, byte[] in) {
        return new Encoders(List.of(encoder.split(" "))).encode(in);
    }

    private static String error(String encoder, byte[] in) {
        return assertThrows(Encoders.EncodeException.class, () -> run(encoder, in)).getMessage();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "b64decode|YQ==YQ==|illegal base64 data at input byte 4",
        "b64decode|Y Q ==|illegal base64 data at input byte 1",
        "b64decode|a<b>c</b>d|illegal base64 data at input byte 1",
        "hexdecode|4142x|encoding/hex: invalid byte: U+0078 'x'",
        "hexdecode|\\u00zz|encoding/hex: invalid byte: U+005C '\\'",
        "jsonunescape|\"\\n\"|invalid character '\\\\' after object key:value pair",
        "jsonunescape|\\u00zz|invalid character 'z' in \\u hexadecimal character escape",
        "jsonunescape|\\ud83d\\ude00\\q|invalid character 'q' in string escape code",
        "jsonunescape|\\u|invalid character '\"' in \\u hexadecimal character escape",
        "jsonunescape|a\",\"input\":1,\"x\":\"|json: cannot unmarshal number into Go struct field JSONInput.input of type string",
        "jsonunescape|a\",\"x\":tru|invalid character '\"' in literal true (expecting 'e')",
        "xmlunescape|&amp;&lt;&eacute;|XML syntax error on line 1: invalid character entity &eacute;",
        "xmlunescape|&#x;&#150;|XML syntax error on line 1: invalid character entity &#x;",
        "xmlunescape|&lt|XML syntax error on line 1: invalid character entity &lt (no semicolon)",
        "urldecode|%e4%b8%ad%zz|invalid URL escape \"%zz\"",
    })
    void reportsGoErrors(String encoder, String in, String want) {
        assertEquals(want, error(encoder, in.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "htmlunescape|&amp;&lt;&eacute;&#x41;&#65;&bogus;&notit;|263cc3a9414126626f6775733bc2ac69743b",
        "htmlunescape|&#x;&#150;&ampx;&#4294967361;|efbfbde2809326783b41",
        "htmlunescape|&lt|3c",
        "xmlunescape|a<b>c</b>d|6164",
        "xmlunescape|<![CDATA[x<y]]>|783c79",
        "jsonunescape|\\ud800\\u0041|efbfbd41",
        "lower|\u00df\u0130\u0131\u03c2\u1e9e|c39f69c4b1cf82c39f",
        "upper|\u00df\u0130\u0131\u03c2\u1e9e|c39fc4b049cea3e1ba9e",
        "upper|\ufb00\u01f0|efac80c7b0",
        "unicodedecode|\\ud83d\\ude00\\q|f09f9880",
    })
    void encodesLikePencode(String encoder, String in, String wantHex) {
        assertArrayEquals(HexFormat.of().parseHex(wantHex), run(encoder, in.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "hexdecode|0163746c|encoding/hex: invalid byte: U+0001",
        "jsonunescape|0163746c|invalid character '\\x01' in string literal",
        "xmlunescape|0163746c|XML syntax error on line 1: illegal character code U+0001",
        "xmlunescape|ff|XML syntax error on line 1: invalid UTF-8",
        "hexdecode|ff|encoding/hex: invalid byte: U+00EF '\u00ef'",
    })
    void reportsGoErrorsForRawBytes(String encoder, String inHex, String want) {
        assertEquals(want, error(encoder, HexFormat.of().parseHex(inHex)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "xmlescape|0163746c3c|efbfbd63746c266c743b",
        "jsonescape|080c09e280a83c|5c625c665c745c75323032383c",
        "lower|ff|efbfbd",
        "utf16|e4b8ad80|2d4efdff",
        "unicodeencodeall|ff61|5c75666666645c7530303631",
        "jsonunescape|eda080|efbfbdefbfbdefbfbd",
        "htmlescape|ff3c|ff266c743b",
    })
    void encodesRawBytesLikePencode(String encoder, String inHex, String wantHex) {
        assertArrayEquals(HexFormat.of().parseHex(wantHex), run(encoder, HexFormat.of().parseHex(inHex)));
    }
}
