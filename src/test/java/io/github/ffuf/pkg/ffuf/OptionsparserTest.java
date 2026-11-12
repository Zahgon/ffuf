package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OptionsparserTest {
    private static Config config(String url, String method, Map<String, String> headers, String data) {
        Config c = new Config();
        c.url = url;
        c.method = method;
        c.headers = new LinkedHashMap<>(headers);
        c.data = data;
        return c;
    }

    @Test
    void testTemplatePresent() {
        String template = "§";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("foo", "§bar§");
        headers.put("omg", "bbq");
        headers.put("§world§", "Ooo");

        Config goodConf = config("https://example.com/fooo/bar?test=§value§&order[§0§]=§foo§", "PO§ST§", headers,
                "line=Can we pull back the §veil§ of §static§ and reach in to the source of §all§ being?&commit=true");
        assertTrue(goodConf.templatePresent(template), "expected-good config failed validation");

        Config badConfMethod = config("https://example.com/fooo/bar?test=§value§&order[§0§]=§foo§", "POST§", headers,
                "line=Can we pull back the §veil§ of §static§ and reach in to the source of §all§ being?&commit=§true§");
        assertFalse(badConfMethod.templatePresent(template), "expected-bad config (Method) passed validation");

        Config badConfUrl = config("https://example.com/fooo/bar?test=§value§&order[0§]=§foo§", "§POST§", headers,
                "line=Can we pull back the §veil§ of §static§ and reach in to the source of §all§ being?&commit=§true§");
        assertFalse(badConfUrl.templatePresent(template), "expected-bad config (URL) passed validation");

        Config badConfData = config("https://example.com/fooo/bar?test=§value§&order[§0§]=§foo§", "§POST§", headers,
                "line=Can we pull back the §veil of §static§ and reach in to the source of §all§ being?&commit=§true§");
        assertFalse(badConfData.templatePresent(template), "expected-bad config (Data) passed validation");

        headers.put("kingdom", "§candy");
        Config badConfHeaderValue = config("https://example.com/fooo/bar?test=§value§&order[§0§]=§foo§", "PO§ST§", headers,
                "line=Can we pull back the §veil§ of §static§ and reach in to the source of §all§ being?&commit=true");
        assertFalse(badConfHeaderValue.templatePresent(template), "expected-bad config (Header value) passed validation");

        headers.put("kingdom", "candy");
        headers.put("§kingdom", "candy");
        Config badConfHeaderKey = config("https://example.com/fooo/bar?test=§value§&order[§0§]=§foo§", "PO§ST§", headers,
                "line=Can we pull back the §veil§ of §static§ and reach in to the source of §all§ being?&commit=true");
        assertFalse(badConfHeaderKey.templatePresent(template), "expected-bad config (Header key) passed validation");
    }

    /** The configuration error text for the given options; the options never validate (no URL, no wordlist). */
    private static String errorFor(ConfigOptions o) {
        try {
            Config.fromOptions(o, List.of("ffuf"));
        } catch (Config.ConfigException e) {
            return e.getMessage();
        }
        throw new AssertionError("options without -u and -w validated, want an error");
    }

    private static void checkProxy(boolean replay, String errorString) {
        Object[][] cases = {
            {"http://127.0.0.1:8080", false},
            {"https://127.0.0.1", false},
            {"socks5://127.0.0.1", false},
            {"Y0 y0 it's GREASE", true},
            {"http:sixhours@dungeon", true},
            {"imap://127.0.0.1", true},
        };
        ConfigOptions o = new ConfigOptions();
        for (Object[] c : cases) {
            if (replay) {
                o.http.replayProxyUrl = (String) c[0];
            } else {
                o.http.proxyUrl = (String) c[0];
            }
            assertEquals(c[1], errorFor(o).contains(errorString), "proxy " + c[0] + " rejected");
        }
    }

    @Test
    void testProxyParsing() {
        checkProxy(false, "Bad proxy url (-x) format. Expected http, https or socks5 url");
    }

    @Test
    void testReplayProxyParsing() {
        checkProxy(true, "Bad replay-proxy url (-replay-proxy) format. Expected http, https or socks5 url");
    }
}
