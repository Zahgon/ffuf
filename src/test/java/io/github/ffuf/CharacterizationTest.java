package io.github.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.ffuf.Flags;
import io.github.ffuf.pkg.ffuf.TomlConfig;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.filter.FilterProvider;
import io.github.ffuf.pkg.output.Stdoutput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden master tests for the CLI flag and configuration pipeline: the help screen and the Config each argv builds,
 * compared with the recorded goldens in testdata/.
 */
class CharacterizationTest {
    @TempDir
    Path tmp;

    /** Runs fn with System.out redirected and returns what it wrote. */
    static String captureStdout(Runnable fn) {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            fn.run();
        } finally {
            System.setOut(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void compareGolden(String path, String got) throws IOException {
        String want = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        assertEquals(want, got, "characterization drift in " + path);
    }

    @Test
    void testCharacterization_HelpGolden() throws IOException {
        String out = captureStdout(() -> Help.usage(new Flags(new ConfigOptions())));
        out = out.replaceFirst("(?m)^Fuzz Faster U Fool - .*$", "Fuzz Faster U Fool - <VERSION>");
        compareGolden("testdata/help.golden", out);
    }

    private record ConfigCase(String name, List<String> args, String toml, String requestBody) {
        ConfigCase(String name, String... args) {
            this(name, List.of(args), "", "");
        }
    }

    @Test
    void testCharacterization_ConfigGolden() throws Exception {
        String cwd = Util.workingDir();
        List<ConfigCase> cases = List.of(
                new ConfigCase("basic", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt"),
                new ConfigCase("alias_data_binary", "-u", "https://example.org/", "-w", "/tmp/wl.txt", "-X", "POST", "-data-binary", "name=FUZZ"),
                new ConfigCase("alias_cookie", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-cookie", "SESSION=abc"),
                new ConfigCase("matchers_filters", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-mc", "200,301", "-fc", "404", "-fs", "42", "-ml", "5"),
                new ConfigCase("multi_wordlist_pitchfork", "-u", "https://example.org/W1/W2", "-w", "/tmp/a.txt:W1", "-w", "/tmp/b.txt:W2", "-mode", "pitchfork"),
                new ConfigCase("headers_multi", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-H", "X-A: 1", "-H", "X-B: 2"),
                new ConfigCase("delay_range", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-p", "0.1-0.8"),
                new ConfigCase("extensions", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-e", ".php,.bak"),
                new ConfigCase("encoders", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt:FUZZ", "-enc", "FUZZ:b64encode"),
                new ConfigCase("autocalibration", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-ac", "-acc", "custom1", "-acc", "custom2"),
                new ConfigCase("acs_strategies", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-acs", "advanced,greedy", "-acs", "custom"),
                new ConfigCase("compat_data_alias", "-u", "https://example.org/", "-w", "/tmp/wl.txt", "-data", "x=FUZZ"),
                new ConfigCase("config_ach_no_autoenable", List.of("-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt"), "[general]\nautocalibration = true\n", ""),
                new ConfigCase("config_client_cert", List.of("-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt"),
                        "[http]\nclientcert = \"/tmp/cert.pem\"\nclientkey = \"/tmp/key.pem\"\n", ""),
                new ConfigCase("many_flags", "-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-x", "http://127.0.0.1:8080", "-replay-proxy",
                        "http://127.0.0.1:9090", "-sni", "example.com", "-timeout", "15", "-rate", "50", "-recursion", "-recursion-depth", "3",
                        "-recursion-strategy", "greedy", "-of", "json", "-od", "/tmp/out", "-maxtime", "60", "-json", "-r", "-raw", "-http2", "-ic", "-D",
                        "-sf"),
                new ConfigCase("raw_request", List.of("-w", "/tmp/wl.txt"), "",
                        "POST /submit HTTP/1.1\nHost: example.org\nContent-Type: application/json\n\n{\"q\":\"FUZZ\"}\n"),
                new ConfigCase("precedence_cli_overrides_file", List.of("-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt", "-t", "5"), "[general]\nthreads = 99\n", ""),
                new ConfigCase("precedence_file_used", List.of("-u", "https://example.org/FUZZ", "-w", "/tmp/wl.txt"), "[general]\nthreads = 99\n", ""),
                new ConfigCase("error_missing_url", "-w", "/tmp/wl.txt"),
                new ConfigCase("error_missing_wordlist", "-u", "https://example.org/FUZZ"));
        for (ConfigCase c : cases) {
            Path dir = Files.createTempDirectory(tmp, c.name);
            List<String> args = new ArrayList<>(c.args);
            String reqPath = "";
            if (!c.requestBody.isEmpty()) {
                reqPath = dir.resolve("request.txt").toString();
                Files.write(Paths.get(reqPath), c.requestBody.getBytes(StandardCharsets.UTF_8));
                args.add("-request");
                args.add(reqPath);
            }
            ConfigOptions opts = new ConfigOptions();
            if (!c.toml.isEmpty()) {
                Path f = dir.resolve("ffufrc");
                Files.write(f, c.toml.getBytes(StandardCharsets.UTF_8));
                opts = TomlConfig.readConfig(f.toString());
            }
            Flags flags = new Flags(opts);
            flags.parse(args);
            List<String> argv = new ArrayList<>();
            argv.add("ffuf");
            argv.addAll(args);
            StringBuilder snap = new StringBuilder();
            Config conf;
            String err = null;
            try {
                conf = Config.fromOptions(opts, argv);
            } catch (Config.ConfigException e) {
                err = e.getMessage();
                conf = e.partial;
                snap.append("error: ").append(err).append('\n');
            }
            snap.append(indentJson(conf.toJson().toString()).replace(cwd, "$CWD")).append('\n');
            if (err == null) {
                Config setup = conf;
                ConfigOptions setupOpts = opts;
                captureStdout(() -> Main.setupFilters(setupOpts, setup, flags));
                snap.append("\n--- matchers after SetupFilters ---\n").append(matcherSnapshot(conf));
            }
            String got = snap.toString();
            if (!reqPath.isEmpty()) {
                got = got.replace(reqPath, "$REQFILE");
            }
            compareGolden("testdata/config_" + c.name + ".golden", got);
        }
    }

    private static String matcherSnapshot(Config conf) {
        StringBuilder b = new StringBuilder();
        dump(b, "matchers", conf.matcherManager.getMatchers());
        dump(b, "filters", conf.matcherManager.getFilters());
        return b.toString();
    }

    private static void dump(StringBuilder b, String title, Map<String, FilterProvider> providers) {
        TreeSet<String> reprs = new TreeSet<>();
        for (Map.Entry<String, FilterProvider> e : providers.entrySet()) {
            reprs.add("  " + e.getKey() + " = " + e.getValue().repr());
        }
        b.append(title).append(":\n").append(String.join("\n", reprs)).append('\n');
    }

    /** Re-indents compact JSON the way encoding/json's MarshalIndent with a two-space indent lays it out. */
    static String indentJson(String compact) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\') {
                    out.append(compact.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    char close = c == '{' ? '}' : ']';
                    if (compact.charAt(i + 1) == close) {
                        out.append(c).append(close);
                        i++;
                    } else {
                        depth++;
                        out.append(c).append('\n').append("  ".repeat(depth));
                    }
                }
                case '}', ']' -> {
                    depth--;
                    out.append('\n').append("  ".repeat(depth)).append(c);
                }
                case ',' -> out.append(",\n").append("  ".repeat(depth));
                case ':' -> out.append(": ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Output far larger than any pipe buffer is captured whole. */
    @Test
    void testCaptureStdoutHandlesOutputLargerThanThePipeBuffer() {
        String payload = "ffuf".repeat(1 << 18);
        String got = captureStdout(() -> Stdoutput.OUT.print(payload));
        assertEquals(payload.length(), got.length(), "captured length");
        assertEquals(payload, got, "captured output");
    }
}
