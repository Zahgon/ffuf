package io.github.ffuf.pkg.input;

import io.github.ffuf.pkg.goquery.Entities;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** HTML escaping and unescaping over raw bytes, with the rules of Go's html.EscapeString and html.UnescapeString. */
final class GoHtml {
    /** Entities that may appear without a trailing ';' are at most this long. */
    private static final int LONGEST_ENTITY_WITHOUT_SEMICOLON = 6;

    /** Windows-1252 code points that numeric references in 0x80..0x9F stand for. */
    private static final int[] REPLACEMENT_TABLE = {
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    };

    private GoHtml() {
    }

    static byte[] escapeString(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length + 16);
        for (byte b : in) {
            switch (b) {
                case '&' -> out.writeBytes("&amp;".getBytes(StandardCharsets.US_ASCII));
                case '\'' -> out.writeBytes("&#39;".getBytes(StandardCharsets.US_ASCII));
                case '<' -> out.writeBytes("&lt;".getBytes(StandardCharsets.US_ASCII));
                case '>' -> out.writeBytes("&gt;".getBytes(StandardCharsets.US_ASCII));
                case '"' -> out.writeBytes("&#34;".getBytes(StandardCharsets.US_ASCII));
                default -> out.write(b);
            }
        }
        return out.toByteArray();
    }

    static byte[] unescapeString(byte[] s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length);
        int src = 0;
        while (src < s.length) {
            if (s[src] != '&') {
                out.write(s[src++]);
                continue;
            }
            src = unescapeEntity(s, src, out);
        }
        return out.toByteArray();
    }

    private static boolean isAlnum(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    private static void writeRune(ByteArrayOutputStream out, int r) {
        if (r < 0 || r > 0x10ffff || r >= 0xd800 && r <= 0xdfff) {
            r = 0xfffd;
        }
        out.writeBytes(new String(Character.toChars(r)).getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes the reference at s[src] == '&' into out and returns the index after what it consumed. */
    private static int unescapeEntity(byte[] all, int src, ByteArrayOutputStream out) {
        int len = all.length - src;
        int i = 1;
        if (len <= 1) {
            out.write('&');
            return src + 1;
        }
        if (all[src + i] == '#') {
            if (len <= 3) {
                out.write('&');
                return src + 1;
            }
            i++;
            int c = all[src + i] & 0xff;
            boolean hex = false;
            if (c == 'x' || c == 'X') {
                hex = true;
                i++;
            }
            int x = 0;
            while (i < len) {
                c = all[src + i] & 0xff;
                i++;
                if (hex) {
                    if (c >= '0' && c <= '9') {
                        x = 16 * x + c - '0';
                        continue;
                    } else if (c >= 'a' && c <= 'f') {
                        x = 16 * x + c - 'a' + 10;
                        continue;
                    } else if (c >= 'A' && c <= 'F') {
                        x = 16 * x + c - 'A' + 10;
                        continue;
                    }
                } else if (c >= '0' && c <= '9') {
                    x = 10 * x + c - '0';
                    continue;
                }
                if (c != ';') {
                    i--;
                }
                break;
            }
            if (i <= 3) {
                out.write('&');
                return src + 1;
            }
            if (x >= 0x80 && x <= 0x9f) {
                x = REPLACEMENT_TABLE[x - 0x80];
            } else if (x == 0 || x >= 0xd800 && x <= 0xdfff || x > 0x10ffff) {
                x = 0xfffd;
            }
            writeRune(out, x);
            return src + i;
        }
        while (i < len) {
            int c = all[src + i] & 0xff;
            i++;
            if (isAlnum(c)) {
                continue;
            }
            if (c != ';') {
                i--;
            }
            break;
        }
        String name = new String(all, src + 1, i - 1, StandardCharsets.ISO_8859_1);
        if (!name.isEmpty()) {
            int[] v = Entities.lookup(name);
            if (v != null) {
                for (int r : v) {
                    writeRune(out, r);
                }
                return src + i;
            }
            int maxLen = Math.min(name.length() - 1, LONGEST_ENTITY_WITHOUT_SEMICOLON);
            for (int j = maxLen; j > 1; j--) {
                int[] w = Entities.lookup(name.substring(0, j));
                if (w != null && w.length == 1) {
                    writeRune(out, w[0]);
                    return src + j + 1;
                }
            }
        }
        out.write(all, src, i);
        return src + i;
    }
}
