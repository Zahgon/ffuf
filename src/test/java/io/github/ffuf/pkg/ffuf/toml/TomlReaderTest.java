package io.github.ffuf.pkg.ffuf.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.ffuf.TomlConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Expected texts are the go-toml v1.9.5 decoding errors for the same documents. */
class TomlReaderTest {
    @TempDir
    Path dir;

    private ConfigOptions load(String doc) throws Exception {
        return load(doc.getBytes(StandardCharsets.UTF_8));
    }

    private ConfigOptions load(byte[] doc) throws Exception {
        Path f = dir.resolve("c.toml");
        Files.write(f, doc);
        return TomlConfig.readConfig(f.toString());
    }

    private String error(String doc) throws IOException {
        Path f = dir.resolve("e.toml");
        Files.writeString(f, doc);
        ConfigOptions conf = new ConfigOptions();
        return assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.readInto(conf, f.toString()))
                .getMessage();
    }

    @Test
    void readsEverySectionOfTheExample() throws Exception {
        ConfigOptions c = load(Files.readString(Path.of("ffufrc.example")));
        assertEquals(List.of("cookiename=cookievalue"), c.http.cookies);
        assertEquals(List.of("X-Header-Name: value", "X-Another-Header: value"), c.http.headers);
        assertEquals("https://example.org/FUZZ", c.http.url);
        assertEquals("http://127.0.0.1:8080", c.http.replayProxyUrl);
        assertEquals(List.of("randomtest", "admin"), c.general.autoCalibrationStrings);
        assertEquals(40, c.general.threads);
        assertEquals(List.of("seq 1 100:CUSTOMKEYWORD"), c.input.inputCommands);
        assertEquals(List.of("/path/to/wordlist:FUZZ", "/path/to/hostlist:HOST"), c.input.wordlists);
        assertEquals("debug.log", c.output.debugLog);
        assertEquals("200,204,301,302,307,401,403,405,500", c.matcher.status);
        assertEquals("or", c.filter.mode);
    }

    @Test
    void matchesKeysUnderGoTomlsFourSpellingsOnly() throws Exception {
        ConfigOptions c = load("[HTTP]\nuRL = \"a\"\nPROXYURL = \"p\"\n[general]\nautoCalibrationKeyword = \"K\"\n"
                + "AutoCalibrationPerHost = true\n[iNPUT]\nextensions = \".x\"\n");
        assertEquals("a", c.http.url);
        assertEquals("p", c.http.proxyUrl);
        assertEquals("K", c.general.autoCalibrationKeyword);
        assertTrue(c.general.autoCalibrationPerHost);
        assertEquals("", c.input.extensions);
    }

    @Test
    void prefersTheGoFieldNameOverTheLowercaseSpelling() throws Exception {
        assertEquals("upper", load("[http]\nURL = \"upper\"\nurl = \"lower\"\n").http.url);
    }

    @Test
    void readsPreflightTablesAndInlineForms() throws Exception {
        ConfigOptions c = load("[[http.preflights]]\nrequest_file = \"login.txt\"\n[[http.preflights.vars]]\n"
                + "name = \"T\"\nregex = \"csrf=(.*)\"\n[http]\npostflights = [{request_file = \"out\", "
                + "vars = [{name = \"S\", source = \"cookie\", key = \"sid\"}]}]\n");
        assertEquals("login.txt", c.http.preflights.get(0).requestFile);
        assertEquals("csrf=(.*)", c.http.preflights.get(0).vars.get(0).regex);
        assertEquals("sid", c.http.postflights.get(0).vars.get(0).key);
    }

    @Test
    void decodesStringsNumbersAndBomLikeGo() throws Exception {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = ("[http]\r\nurl = \"a\\u00e9\\U0001F600\\uD800\"\r\ndata = 'lit\\n'\r\nmethod = \"\"\"\nx\\\n"
                + "   y\"\"\"\r\ntimeout = 0x_1F\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] doc = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, doc, 0, bom.length);
        System.arraycopy(body, 0, doc, bom.length, body.length);
        assertEquals("(7, 11): invalid use of _ in hex number", assertThrows(TomlConfig.TomlException.class,
                () -> load(doc)).getMessage());
        ConfigOptions c = load(new String(doc, StandardCharsets.UTF_8).replace("0x_1F", "0x1F")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("aé😀�", c.http.url);
        assertEquals("lit\\n", c.http.data);
        assertEquals("xy", c.http.method);
        assertEquals(31, c.http.timeout);
    }

    @Test
    void keepsGoTomlsUnsignedConversions() throws Exception {
        assertEquals("�", load("[http]\nurl = 18446744073709551615\n").http.url);
        assertEquals(-1, load("[http]\ntimeout = 18446744073709551615\n").http.timeout);
    }

    @Test
    void leavesFieldsDecodedBeforeAnErrorInPlace() throws IOException {
        Path f = dir.resolve("p.toml");
        Files.writeString(f, "[general]\nthreads = 3\n[http]\nurl = \"kept\"\nmethod = 1\ndata = \"skipped\"\n"
                + "[input]\ninputnum = 9\n");
        ConfigOptions conf = new ConfigOptions();
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.readInto(conf, f.toString()));
        assertEquals(3, conf.general.threads);
        assertEquals("skipped", conf.http.data);
        assertEquals("", conf.http.url);
        assertEquals(100, conf.input.inputNum);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "[http]\\nurl = \"abc\\n | (2, 8): unescaped control character U+000A",
        "[http]\\nurl = \"\\\\%\"\\n | (2, 8): invalid escape sequence: \\%!(NOVERB)",
        "[http]\\nurl = \"a\"\\nurl = \"b\"\\n | (3, 1): The following key was defined twice: http.url",
        "[http]\\n[http]\\n | (2, 2): duplicated tables",
        "[http]\\ntimeout = 012\\n | invalid base",
        "[http]\\nheaders = [\"a\",,\"b\"]\\n | unhandled token: \",\"",
        "[http]\\ntimeout = 18446744073709551616\\n | (2, 11): strconv.ParseUint: parsing \"18446744073709551616\": "
                + "value out of range",
        "[http]\\ntimeout = +99999999999999999999\\n | (2, 11): strconv.ParseUint: parsing "
                + "\"+99999999999999999999\": invalid syntax",
        "[http]\\nurl = 1e6\\n | (2, 1): Can't convert 1e+06(float64) to string",
        "[http]\\nurl = 1979-02-30\\n | (2, 7): parsing time \"1979-02-30\": day out of range",
        "[http]\\nurl = 1979-05-27T25:32:00\\n | (2, 7): parsing time \"1979-05-27T25:32:00\" as "
                + "\"2006-01-02t15:04:05.999999999\": cannot parse \"T25:32:00\" as \"t\"",
        "[http]\\nurl = 1979-05-27T00:32:00.5-07:00\\n | (2, 1): Can't convert 1979-05-27 00:32:00.5 -0700 -0700"
                + "(time.Time) to string",
        "[http]\\nurl = 1979-05-27\\n | (2, 17): parsing error: incorrect date/time separation character: \\n",
        "http = {url = \"a\", timeout = \"b\"}\\n | (2, 0): Can't convert b(string) to int",
        "[http]\\nheaders = \"A: b\"\\n | (2, 1): Can't convert A: b(string) to []string(slice)",
        "http = 5\\n | (1, 1): Can't convert 5(int64) to ffuf.HTTPOptions",
        "[[http.preflights]]\\n[[http.preflights.vars]]\\nname = 1\\n | (3, 1): Can't convert 1(int64) to string",
        "[http]\\nu rl = 1\\n | (2, 3): was expecting token =, but got \"rl\" instead",
        "[a]\\n[[a]]\\n | (2, 3): key \"a\" is already assigned and not of type table array",
        "[http]\\nurl = [nan, -inf, 0.1, 1e-05]\\n | (2, 1): Can't convert [NaN -Inf 0.1 1e-05]([]interface {}) "
                + "to a slice",
        "a = 1\\n[a.b]\\n | (2, 2): unknown type for path a.b (a): *toml.tomlValue (&toml.tomlValue{value:1, "
                + "comment:\"\", commented:false, multiline:false, literal:false, position:toml.Position{Line:1, Col:1}})",
    })
    void reportsGoTomlErrors(String doc, String expected) throws IOException {
        assertEquals(unescape(expected.strip()), error(unescape(doc.strip())));
    }

    @Test
    void rendersTreesInConversionErrorsAsGoTomlWritesThem() throws IOException {
        assertEquals("(0, 0): Can't convert a = 1\nb = \"x\"\nc = [1, 2]\n\n[d]\n  e = 1.5\n(*toml.Tree) to a tree",
                error("[http]\ntimeout = {a = 1, b = \"x\", c = [1, 2], d = {e = 1.5}}\n"));
        assertEquals("(0, 0): Can't convert [a = 1\n b = \"x\"\n]([]*toml.Tree) to trees",
                error("[http]\nheaders = [{a = 1}, {b = \"x\"}]\n"));
    }

    @Test
    void reportsUnreadableFiles() {
        String missing = dir.resolve("missing").toString();
        assertEquals("open " + missing + ": no such file or directory",
                assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.readConfig(missing)).getMessage());
        assertEquals("read " + dir + ": is a directory",
                assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.readConfig(dir.toString())).getMessage());
    }

    @Test
    void parsesTablesArraysAndInlineTablesIntoTheTree() throws TomlException {
        TomlTree t = Toml.parse("a.b = 1\n[[c]]\nd = [1, 'x', 2.5]\n[[c]]\ne = {f = true}\n"
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(t.get("a") instanceof TomlTree);
        TomlTree.TreeArray c = (TomlTree.TreeArray) t.get("c");
        assertEquals(2, c.trees().size());
        assertEquals(List.of(1L, "x", 2.5), c.trees().get(0).get("d"));
        assertFalse(c.trees().get(1).has("d"));
        assertEquals(new Position(4, 1), t.positionOf("c"));
    }

    private static String unescape(String s) {
        return s.replace("\\\\", "\u0000").replace("\\n", "\n").replace("\u0000", "\\");
    }
}
