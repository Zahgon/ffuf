package io.github.ffuf.pkg.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ffuf.pkg.ffuf.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WordlistTest {
    @TempDir
    Path tmp;

    @Test
    void testStripCommentsIgnoresCommentLines() {
        assertEquals("", WordlistInput.stripComments("# text"), "comment line");
    }

    @Test
    void testStripCommentsStripsCommentAfterText() {
        assertEquals("text", WordlistInput.stripComments("text # comment"), "text before the comment");
    }

    /** A single 448 KiB entry, as in issue #567, is read whole. */
    @Test
    void testWordlistAcceptsLinesLargerThanScannerDefault() throws Exception {
        String payload = "a".repeat(448 * 1024);
        Path wordlist = tmp.resolve("large-wordlist.txt");
        Files.write(wordlist, (payload + "\n").getBytes(StandardCharsets.UTF_8));
        WordlistInput input = new WordlistInput("FUZZ", wordlist.toString(), new Config());
        assertEquals(1, input.total(), "entries");
        assertEquals(payload.length(), input.value().length, "payload length");
        assertEquals(payload, new String(input.value(), StandardCharsets.UTF_8), "payload");
    }
}
