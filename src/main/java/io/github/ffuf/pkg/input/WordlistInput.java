package io.github.ffuf.pkg.input;

import io.github.ffuf.pkg.ffuf.Config;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A wordlist file (or standard input) as a list of payloads. */
public class WordlistInput implements InternalInputProvider {
    private static final Pattern EXT = Pattern.compile("(?i)%ext%");
    private final String keyword;
    private final List<byte[]> data = new ArrayList<>();
    private int position;
    private boolean active = true;

    /**
     * Reads the wordlist file, or stdin for "-".
     *
     * @throws IOException when the file cannot be read
     */
    public WordlistInput(String keyword, String value, Config conf) throws IOException {
        this.keyword = keyword;
        byte[] content;
        if (value.equals("-")) {
            content = readAll(System.in);
        } else {
            Path p = Paths.get(value);
            if (!Files.exists(p)) {
                throw new NoSuchFileException("stat " + value + ": no such file or directory");
            }
            try (InputStream in = Files.newInputStream(p)) {
                content = readAll(in);
            } catch (IOException e) {
                throw new IOException(Files.isDirectory(p) ? "read " + value + ": is a directory" : "open " + value + ": permission denied", e);
            }
        }
        for (String text : scanLines(content)) {
            if (conf.dirSearchCompat && !conf.extensions.isEmpty() && EXT.matcher(text).find()) {
                for (String ext : conf.extensions) {
                    data.add(EXT.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(ext)).getBytes(StandardCharsets.ISO_8859_1));
                }
                continue;
            }
            if (conf.ignoreWordlistComments) {
                if (isCommentLine(text)) {
                    continue;
                }
                text = stripComments(text);
            }
            data.add(text.getBytes(StandardCharsets.ISO_8859_1));
            if (!conf.dirSearchCompat && keyword.equals("FUZZ") && !conf.extensions.isEmpty()) {
                for (String ext : conf.extensions) {
                    data.add((text + new String(ext.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)).getBytes(StandardCharsets.ISO_8859_1));
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        in.transferTo(bos);
        return bos.toByteArray();
    }

    /**
     * Splits the data into lines as Go's bufio.ScanLines does: split on \n, drop one trailing \r, no empty final
     * token. Bytes are kept as Latin-1 chars.
     */
    static List<String> scanLines(byte[] content) {
        String all = new String(content, StandardCharsets.ISO_8859_1);
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < all.length()) {
            int nl = all.indexOf('\n', start);
            String line = nl < 0 ? all.substring(start) : all.substring(start, nl);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return lines;
    }

    /** Returns true when the line, ignoring leading spaces, starts with '#'. */
    static boolean isCommentLine(String text) {
        return text.replaceFirst("^ +", "").startsWith("#");
    }

    /** Returns the text before the first " #", or an empty string for a comment line. */
    static String stripComments(String text) {
        if (isCommentLine(text)) {
            return "";
        }
        int index = text.indexOf(" #");
        return index == -1 ? text : text.substring(0, index);
    }

    @Override
    public String keyword() {
        return keyword;
    }

    @Override
    public boolean next() {
        return position < data.size();
    }

    @Override
    public void incrementPosition() {
        position++;
    }

    @Override
    public byte[] value() {
        return data.get(position);
    }

    @Override
    public long total() {
        return data.size();
    }

    @Override
    public void resetPosition() {
        position = 0;
    }

    @Override
    public void setPosition(int pos) {
        position = pos;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public void disable() {
        active = false;
    }
}
