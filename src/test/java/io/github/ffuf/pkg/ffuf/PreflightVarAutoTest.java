package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreflightVarAutoTest {
    @TempDir
    Path tmp;

    @Test
    void testPreflightVarAutoFlags() throws Flags.FlagException {
        ConfigOptions o = new ConfigOptions();
        PreflightFlagsTest.parse(o, "-preflight", "login.txt",
                "-preflight-var-auto", "CSRF:csrf_token",
                "-preflight-var-auto", "FT:[form]data[_Token][key]",
                "-preflight-var-auto", "JSF:[form]loginForm:token",
                "-preflight-var-auto", "META:[meta]csrf-token",
                "-preflight-var", "RE:tok=(\\w+)",
                "-postflight", "logout.txt",
                "-postflight-var-auto", "SESS:[cookie]sessionid",
                "-postflight-var-auto", "HDR:[header]X-Csrf-Token");
        assertEquals("[login.txt[CSRF=[auto]csrf_token, FT=[form]data[_Token][key], JSF=[form]loginForm:token, META=[meta]csrf-token, RE=tok=(\\w+)]]",
                PreflightFlagsTest.describe(o.http.preflights), "preflights");
        assertEquals("[logout.txt[SESS=[cookie]sessionid, HDR=[header]X-Csrf-Token]]", PreflightFlagsTest.describe(o.http.postflights), "postflights");
    }

    @Test
    void testPreflightVarAutoFlagErrors() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("NOCOLON", "must be");
        cases.put(":key", "must be");
        cases.put("X:", "must be");
        cases.put("X:[body]foo", "unknown source");
        cases.put("X:[]foo", "unknown source");
        cases.put("X:[form", "unterminated");
        cases.put("X:[header]", "non-empty key");
        cases.put("X:[auto]csrf", "");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            try {
                PreflightFlagsTest.parse(new ConfigOptions(), "-preflight", "f.txt", "-preflight-var-auto", c.getKey());
                if (!c.getValue().isEmpty()) {
                    fail(c.getKey() + ": no error, want one mentioning " + c.getValue());
                }
            } catch (Flags.FlagException e) {
                if (c.getValue().isEmpty()) {
                    fail(c.getKey() + ": unexpected error " + e.getMessage());
                }
                assertTrue(e.getMessage().contains(c.getValue()), c.getKey() + ": error " + e.getMessage() + ", want it to mention " + c.getValue());
            }
        }
        assertThrows(Flags.FlagException.class, () -> PreflightFlagsTest.parse(new ConfigOptions(), "-preflight-var-auto", "X:y"),
                "orphan -preflight-var-auto");
    }

    @Test
    void testPreflightVarAutoTOML() throws Exception {
        ConfigOptions o = PreflightFlagsTest.loadToml(tmp, "\n[http]\n[[http.preflights]]\nrequest_file = \"login.txt\"\n[[http.preflights.vars]]\n"
                + "name = \"CSRF\"\nsource = \"cookie\"\nkey = \"XSRF-TOKEN\"\n");
        assertEquals("[login.txt[CSRF=[cookie]XSRF-TOKEN]]", PreflightFlagsTest.describe(o.http.preflights), "vars loaded from TOML");
    }

    @Test
    void testPreflightVarConfigValidation() {
        Object[][] cases = {
            {var("A", "body", "x", ""), "unknown source \"body\""},
            {var("B", "form", "", ""), "non-empty key"},
            {var("C", "form", "x", "(x)"), "not both"},
            {var("D", "", "", ""), "needs a regex or a source"},
        };
        for (Object[] c : cases) {
            ConfigOptions o = new ConfigOptions();
            PreflightConfig pf = new PreflightConfig("f.txt");
            pf.vars.add((VarExtract) c[0]);
            o.http.preflights = List.of(pf);
            Config.ConfigException e = assertThrows(Config.ConfigException.class, () -> Config.fromOptions(o, List.of("ffuf")));
            assertTrue(e.getMessage().contains((String) c[1]), "error " + e.getMessage() + ", want it to mention " + c[1]);
        }
    }

    private static VarExtract var(String name, String source, String key, String regex) {
        VarExtract v = new VarExtract();
        v.name = name;
        v.source = source;
        v.key = key;
        v.regex = regex;
        return v;
    }
}
