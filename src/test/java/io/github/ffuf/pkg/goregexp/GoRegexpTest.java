package io.github.ffuf.pkg.goregexp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks GoRegexp against results and error texts of Go's regexp package. */
class GoRegexpTest {
    private static GoRegexp re(String expr) throws GoRegexp.SyntaxException {
        return GoRegexp.compile(expr);
    }

    private static String error(String expr) {
        return assertThrows(GoRegexp.SyntaxException.class, () -> GoRegexp.compile(expr)).getMessage();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
        "(a|missing closing ): `(a`",
        "a)|unexpected ): `a)`",
        "a{1001}|invalid repeat count: `{1001}`",
        "a{3,2}|invalid repeat count: `{3,2}`",
        "((a{10}){10}){11}|invalid repeat count: `{11}`",
        "a**|invalid nested repetition operator: `**`",
        "a{2}{3}|invalid nested repetition operator: `{2}{3}`",
        "*a|missing argument to repetition operator: `*`",
        "(?i)+|missing argument to repetition operator: `+`",
        "\\Z|invalid escape sequence: `\\Z`",
        "\\1|invalid escape sequence: `\\1`",
        "\\h|invalid escape sequence: `\\h`",
        "\\R|invalid escape sequence: `\\R`",
        "\\e|invalid escape sequence: `\\e`",
        "\\cA|invalid escape sequence: `\\c`",
        "\\C|invalid escape sequence: `\\C`",
        "\\x{110000}|invalid escape sequence: `\\x{110000`",
        "(?=a)|invalid or unsupported Perl syntax: `(?=`",
        "(?!a)|invalid or unsupported Perl syntax: `(?!`",
        "(?<=a)b|invalid named capture: `(?<=a)b`",
        "(?i|invalid or unsupported Perl syntax: `(?i`",
        "(?x)a|invalid or unsupported Perl syntax: `(?x`",
        "[z-a]|invalid character class range: `z-a`",
        "[[:foo:]]|invalid character class range: `[:foo:]`",
        "\\p{Foo}|invalid character class range: `\\p{Foo}`",
        "[a|missing closing ]: `[a`",
        "[]|missing closing ]: `[]`",
        "(?P<>a)|invalid named capture: `(?P<>`",
        "(?P<a-b>x)|invalid named capture: `(?P<a-b>`",
        "a\\|trailing backslash at end of expression: ``",
    })
    void errorsHaveGoText(String expr, String message) {
        assertEquals("error parsing regexp: " + message, error(expr));
    }

    @Test
    void invalidUtf8InExpression() {
        GoRegexp.SyntaxException e = assertThrows(GoRegexp.SyntaxException.class,
                () -> GoRegexp.compile(new byte[] {'a', (byte) 0xff, 'b'}));
        assertEquals("invalid UTF-8", e.code());
    }

    @Test
    void acceptsGoOnlySyntax() throws Exception {
        assertTrue(re("a{,3}").matchString("xa{,3}"));
        assertFalse(re("a{,3}").matchString("aaa"));
        assertTrue(re("^[a-z&&b]+$").matchString("a&&b"));
        assertEquals("a.*", re("\\Qa.*\\E").findStringSubmatch("xa.*y")[0]);
        assertEquals("a", re("(?U)a+").findStringSubmatch("aaa")[0]);
        assertEquals("aaa", re("(?U)a+?").findStringSubmatch("aaa")[0]);
        assertEquals("b", re("(?P<n>b)").findStringSubmatch("abc")[1]);
        assertEquals("b", re("(?<n>b)").findStringSubmatch("abc")[1]);
        assertTrue(re("\\x{10FFFF}").matchString(new String(Character.toChars(0x10FFFF))));
        assertTrue(re("^\\101$").matchString("A"));
        assertTrue(re("^\\v$").matchString("\u000b"));
        assertFalse(re("\\v").matchString("\n"));
    }

    @Test
    void perlClassesAreAscii() throws Exception {
        assertFalse(re("\\d").matchString("\u0663"));
        assertFalse(re("\\w").matchString("\u00e9"));
        assertFalse(re("\\s").matchString("\u00a0"));
        assertFalse(re("\\s").matchString("\u000b"));
        assertTrue(re("[[:space:]]").matchString("\u000b"));
        assertTrue(re("\\bcaf").matchString("caf\u00e9"));
        assertTrue(re("caf\\b").matchString("caf\u00e9"));
    }

    @Test
    void unicodeClassesAndFolding() throws Exception {
        assertTrue(re("^\\p{Greek}+$").matchString("\u03b1\u03b2\u03b3"));
        assertTrue(re("^\\pL+$").matchString("\u00e9\u65e5"));
        assertTrue(re("^\\p{Letter}$").matchString("x"));
        assertTrue(re("(?i)^k$").matchString("\u212a"));
        assertTrue(re("(?i)^s$").matchString("\u017f"));
        assertTrue(re("(?i)^\u03c3+$").matchString("\u03a3\u03c2\u03c3"));
        assertFalse(re("^k$").matchString("K"));
    }

    @Test
    void invalidBytesAreOneRuneEach() throws Exception {
        byte[] in = {'a', (byte) 0xe2, (byte) 0x82, 'b'};
        assertArrayEquals(new int[] {0, 4}, re("^a..b$").findSubmatchIndex(in));
        assertNull(re("^a.b$").findSubmatchIndex(in));
        assertTrue(re("^a\\x{fffd}{2}b$").match(in));
    }

    @Test
    void leftmostFirstSubmatches() throws Exception {
        assertArrayEquals(new String[] {"abcd", "a", "bcd", ""}, re("(a|ab)(c|bcd)(d*)").findStringSubmatch("abcd"));
        assertArrayEquals(new String[] {"a", "a", ""}, re("(a)|(b)").findStringSubmatch("a"));
        assertArrayEquals(new String[] {"", ""}, re("(a*)*").findStringSubmatch("b"));
        assertArrayEquals(new String[] {"b", ""}, re("(a){0}b").findStringSubmatch("b"));
        assertNull(re("x").findStringSubmatch("abc"));
    }

    @Test
    void findAllSkipsEmptyMatchAfterMatch() throws Exception {
        List<String[]> all = re("a*").findAllStringSubmatch("baaab", -1);
        assertEquals(3, all.size());
        assertEquals("", all.get(0)[0]);
        assertEquals("aaa", all.get(1)[0]);
        assertEquals("", all.get(2)[0]);
        assertEquals(2, re("a*").findAllStringSubmatch("baaab", 2).size());
        assertEquals(0, re("a").findAllStringSubmatch("aaa", 0).size());
    }

    @Test
    void anchorsAndMultiline() throws Exception {
        assertEquals(1, re("^\\w+").findAllStringSubmatch("ab\ncd", -1).size());
        assertEquals(2, re("(?m)^\\w+").findAllStringSubmatch("ab\ncd", -1).size());
        assertFalse(re("a$").matchString("a\n"));
        assertTrue(re("(?m)a$").matchString("a\n"));
        assertFalse(re(".").matchString("\n"));
        assertTrue(re("(?s).").matchString("\n"));
        assertTrue(re("[^a]").matchString("\n"));
    }

    @Test
    void quoteMeta() throws Exception {
        String s = "a.b*c\\d[e]{f}|^$+?()";
        assertEquals("a\\.b\\*c\\\\d\\[e\\]\\{f\\}\\|\\^\\$\\+\\?\\(\\)", GoRegexp.quoteMeta(s));
        assertTrue(re("^" + GoRegexp.quoteMeta(s) + "$").matchString(s));
        assertArrayEquals("\\.\u00e9".getBytes(StandardCharsets.UTF_8),
                GoRegexp.quoteMeta(".\u00e9".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void limits() {
        assertEquals("expression nests too deeply", assertThrows(GoRegexp.SyntaxException.class,
                () -> GoRegexp.compile("(".repeat(1001) + "a" + ")".repeat(1001))).code());
    }
}
