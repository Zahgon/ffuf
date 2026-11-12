package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreflightFlagsTest {
    @TempDir
    Path tmp;

    static void parse(ConfigOptions o, String... args) throws Flags.FlagException {
        new Flags(o).parse(List.of(args));
    }

    /** One flight as "file[name=regex|name=[source]key, ...]", so a whole chain compares as one string. */
    static String describe(List<PreflightConfig> flights) {
        List<String> out = new ArrayList<>();
        for (PreflightConfig pf : flights) {
            List<String> vars = new ArrayList<>();
            for (VarExtract v : pf.vars) {
                vars.add(v.name + "=" + (v.source.isEmpty() ? v.regex : "[" + v.source + "]" + v.key) + (v.source.isEmpty() || v.regex.isEmpty() ? "" : "|" + v.regex));
            }
            out.add(pf.requestFile + vars);
        }
        return out.toString();
    }

    static ConfigOptions loadToml(Path dir, String toml) throws IOException, TomlConfig.TomlException {
        Path f = dir.resolve("ffufrc");
        Files.write(f, toml.getBytes(StandardCharsets.UTF_8));
        ConfigOptions o = new ConfigOptions();
        TomlConfig.readInto(o, f.toString());
        return o;
    }

    @Test
    void testPreflightFlagsPositionalBinding() throws Flags.FlagException {
        ConfigOptions o = new ConfigOptions();
        parse(o, "-preflight", "login.txt",
                "-preflight-var", "TOKEN:csrf=([a-f0-9]+)",
                "-preflight-var", "SESSION:sid=(\\w+)",
                "-preflight", "second.txt",
                "-postflight", "logout.txt",
                "-postflight-var", "OUT:done:(\\d+)");
        assertEquals("[login.txt[TOKEN=csrf=([a-f0-9]+), SESSION=sid=(\\w+)], second.txt[]]", describe(o.http.preflights), "preflights");
        assertEquals("[logout.txt[OUT=done:(\\d+)]]", describe(o.http.postflights), "postflights");
    }

    @Test
    void testPreflightConfigFilePreserved() throws Flags.FlagException {
        ConfigOptions o = new ConfigOptions();
        o.http.preflights.add(new PreflightConfig("from-config.txt"));
        parse(o, "-preflight", "from-cli.txt");
        assertEquals(2, o.http.preflights.size(), "preflights, the config value must survive");
        assertEquals("from-config.txt", o.http.preflights.get(0).requestFile, "config-file preflight");
        assertEquals("from-cli.txt", o.http.preflights.get(1).requestFile, "appended CLI preflight");
    }

    @Test
    void testPreflightVarSpecValidation() {
        for (String bad : new String[] {"NOCOLON", ":emptyname", "EMPTY:"}) {
            assertThrows(Flags.FlagException.class, () -> parse(new ConfigOptions(), "-preflight", "f.txt", "-preflight-var", bad), "var spec " + bad);
        }
        assertThrows(Flags.FlagException.class, () -> parse(new ConfigOptions(), "-preflight-var", "X:y"), "orphan -preflight-var");
        assertThrows(Flags.FlagException.class, () -> parse(new ConfigOptions(), "-preflight", ""), "empty -preflight value");
    }

    @Test
    void testPreflightTOMLLoads() throws Exception {
        ConfigOptions o = loadToml(tmp, "\n[http]\n[[http.preflights]]\nrequest_file = \"login.txt\"\n[[http.preflights.vars]]\nname = \"TOKEN\"\n"
                + "regex = \"csrf=(\\\\w+)\"\n");
        assertEquals("[login.txt[TOKEN=csrf=(\\w+)]]", describe(o.http.preflights), "preflights loaded from TOML");
    }
}
