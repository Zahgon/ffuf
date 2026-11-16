package io.github.ffuf.test.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ffuf.pkg.testtarget.Target;
import io.github.ffuf.test.integration.HarnessTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The black-box layer: runs the real program (io.github.ffuf.Main in a separate JVM on the build's class path) against
 * the mock target over real TCP and asserts on its output. It is the only layer that covers argv, flag parsing, the
 * engine and the formatted output together. Each run gets its own configuration folder via XDG_CONFIG_HOME.
 */
class E2eTest {
    @TempDir
    Path tmp;
    private Target target;

    @BeforeEach
    void start() throws Exception {
        target = Target.start();
    }

    @AfterEach
    void stop() throws Exception {
        target.close();
    }

    /** Runs ffuf with a hard timeout and returns its standard output; a failing or hung run fails the test. */
    private String runFfuf(Path configHome, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("io.github.ffuf.Main");
        cmd.addAll(List.of(args));
        Path out = Files.createTempFile(tmp, "stdout", ".txt");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("XDG_CONFIG_HOME", configHome.toString());
        Process p = pb.start();
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                fail("ffuf timed out; args: " + List.of(args));
            }
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        assertEquals(0, p.exitValue(), "ffuf exit code; args: " + List.of(args));
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    private String runFfuf(String... args) throws IOException, InterruptedException {
        return runFfuf(Files.createTempDirectory(tmp, "config"), args);
    }

    private String wordlist(String... words) throws IOException {
        return HarnessTest.writeWordlist(tmp, List.of(words));
    }

    /** The -json result lines: input values decoded from base64, and the URL. */
    private record JsonResult(JsonNode input, String url) {
        String input(String key) {
            return new String(Base64.getDecoder().decode(input.path(key).asText()), StandardCharsets.UTF_8);
        }
    }

    private static List<JsonResult> parseResults(String stdout) throws IOException {
        List<JsonResult> out = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String line : stdout.split("\n")) {
            line = line.strip();
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode n = mapper.readTree(line);
            if (!n.path("url").asText().isEmpty()) {
                out.add(new JsonResult(n.path("input"), n.path("url").asText()));
            }
        }
        return out;
    }

    private static String base(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** The sorted set of last path segments of the matched URLs. */
    private static List<String> matchedPayloads(String stdout) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        for (JsonResult r : parseResults(stdout)) {
            out.add(base(r.url()));
        }
        return new ArrayList<>(out);
    }

    /** The FFUFHASH of the result whose URL ends in payload. */
    private static String hashFor(String stdout, String payload) throws IOException {
        for (JsonResult r : parseResults(stdout)) {
            if (base(r.url()).equals(payload) && !r.input("FFUFHASH").isEmpty()) {
                return r.input("FFUFHASH");
            }
        }
        return fail("no result for payload " + payload + " with a FFUFHASH in:\n" + stdout);
    }

    @Test
    void testE2E_JSONOutput() throws Exception {
        String out = runFfuf("-u", target.url() + "/status/FUZZ", "-w", wordlist("200", "201", "404"), "-mc", "200,201", "-json", "-t", "1", "-noninteractive");
        assertEquals(List.of("200", "201"), matchedPayloads(out), "matched payloads");
    }

    /** Without -mc the default status set applies, so 404 is left out. */
    @Test
    void testE2E_DefaultMatcher() throws Exception {
        String out = runFfuf("-u", target.url() + "/status/FUZZ", "-w", wordlist("200", "403", "404", "500"), "-json", "-t", "1", "-noninteractive");
        assertEquals(List.of("200", "403", "500"), matchedPayloads(out), "matched payloads");
    }

    /** A non-status matcher without -mc suppresses the default status matcher. */
    @Test
    void testE2E_MatcherSuppressesDefault() throws Exception {
        String out = runFfuf("-u", target.url() + "/reflect/FUZZ", "-w", wordlist("keep", "drop"), "-mr", "keep", "-json", "-t", "1", "-noninteractive");
        assertEquals(List.of("keep"), matchedPayloads(out), "matched payloads");
    }

    /** A FFUFHASH from a scan, fed to -search, reconstructs the original request from the history entry. */
    @Test
    void testE2E_FFUFHashRoundTrip() throws Exception {
        Path cfgHome = Files.createTempDirectory(tmp, "config");
        String out1 = runFfuf(cfgHome, "-u", target.url() + "/status/FUZZ", "-w", wordlist("200", "201"), "-mc", "200,201", "-json", "-t", "1", "-noninteractive");
        String hash = hashFor(out1, "200");
        assertTrue(Files.isDirectory(cfgHome.resolve("ffuf/history")), "history was written under the isolated config folder");
        String out2 = runFfuf(cfgHome, "-search", hash, "-noninteractive");
        assertTrue(out2.contains("Request candidate"), "-search found no candidate for " + hash + ":\n" + out2);
        assertTrue(out2.contains("/status/200"), "-search did not reconstruct /status/200:\n" + out2);
    }

    /** A recursed result's history carries the recursed URL, so -search reconstructs /admin/secret. */
    @Test
    void testE2E_FFUFHashRecursion() throws Exception {
        Path cfgHome = Files.createTempDirectory(tmp, "config");
        String out1 = runFfuf(cfgHome, "-u", target.url() + "/FUZZ", "-w", wordlist("admin", "secret"), "-mc", "200", "-recursion", "-recursion-strategy",
                "greedy", "-recursion-depth", "1", "-json", "-t", "1", "-noninteractive");
        assertTrue(matchedPayloads(out1).contains("secret"), "recursion did not find 'secret':\n" + out1);
        String hash = hashFor(out1, "secret");
        String out2 = runFfuf(cfgHome, "-search", hash, "-noninteractive");
        assertTrue(out2.contains("/admin/secret"), "recursed hash did not reconstruct /admin/secret:\n" + out2);
    }

    /** Headers, method and body survive the history round trip and appear in the dumped request. */
    @Test
    void testE2E_FFUFHashRequestOptions() throws Exception {
        Path cfgHome = Files.createTempDirectory(tmp, "config");
        String out1 = runFfuf(cfgHome, "-u", target.url() + "/status/FUZZ", "-w", wordlist("200"), "-X", "POST", "-H", "X-Test: roundtrip", "-d", "body=FUZZ",
                "-mc", "200", "-json", "-t", "1", "-noninteractive");
        String hash = hashFor(out1, "200");
        String out2 = runFfuf(cfgHome, "-search", hash, "-noninteractive");
        for (String want : List.of("POST", "X-Test: roundtrip", "body=200")) {
            assertTrue(out2.contains(want), "-search reconstruction missing " + want + ":\n" + out2);
        }
    }
}
