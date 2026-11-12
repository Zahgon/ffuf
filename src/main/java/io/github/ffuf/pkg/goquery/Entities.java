package io.github.ffuf.pkg.goquery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Character reference decoding over byte strings, with the rules of golang.org/x/net/html. */
public final class Entities {
    /** All entities without a trailing ';' are at most this long. */
    private static final int LONGEST_ENTITY_WITHOUT_SEMICOLON = 6;

    private static final int[] REPLACEMENT_TABLE = {
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    };

    private static final Map<String, int[]> TABLE = load();

    private Entities() {
    }

    private static Map<String, int[]> load() {
        Map<String, int[]> m = new HashMap<>(4096);
        try (InputStream in = Entities.class.getResourceAsStream("entities.txt");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(" ");
                int[] v = new int[f.length - 1];
                for (int i = 1; i < f.length; i++) {
                    v[i - 1] = Integer.parseInt(f[i], 16);
                }
                m.put(f[0], v);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return m;
    }

    /**
     * Looks up a named character reference, with its trailing ';' when it has one ("amp;").
     *
     * @return the code points of the reference, or null when there is no such entity
     */
    public static int[] lookup(String name) {
        return TABLE.get(name);
    }

    /** Decodes the reference at s[start]=='&'; returns {r1, r2, consumed}. */
    private static int[] unescapeEntity(String s, int start, boolean attribute) {
        int len = s.length() - start;
        int i = 1;
        if (len <= 1) {
            return new int[] {'&', 0, 1};
        }
        if (s.charAt(start + i) == '#') {
            if (len <= 2) {
                return new int[] {'&', 0, 1};
            }
            i++;
            char c = s.charAt(start + i);
            boolean hex = false;
            if (c == 'x' || c == 'X') {
                hex = true;
                i++;
            }
            int i0 = i;
            int x = 0;
            while (i < len) {
                c = s.charAt(start + i);
                int d;
                int mult;
                if (hex) {
                    mult = 16;
                    if (c >= '0' && c <= '9') {
                        d = c - '0';
                    } else if (c >= 'a' && c <= 'f') {
                        d = c - 'a' + 10;
                    } else if (c >= 'A' && c <= 'F') {
                        d = c - 'A' + 10;
                    } else {
                        break;
                    }
                } else {
                    mult = 10;
                    if (c >= '0' && c <= '9') {
                        d = c - '0';
                    } else {
                        break;
                    }
                }
                if (x <= 0x10FFFF) {
                    x = mult * x + d;
                }
                i++;
            }
            if (i == i0) {
                return new int[] {'&', 0, 1};
            }
            if (i < len && s.charAt(start + i) == ';') {
                i++;
            }
            if (x >= 0x80 && x <= 0x9F) {
                x = REPLACEMENT_TABLE[x - 0x80];
            } else if (x == 0 || (x >= 0xD800 && x <= 0xDFFF) || x > 0x10FFFF) {
                x = 0xFFFD;
            }
            return new int[] {x, 0, i};
        }
        while (i < len) {
            char c = s.charAt(start + i);
            i++;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                continue;
            }
            if (c != ';') {
                i--;
            }
            break;
        }
        String name = s.substring(start + 1, start + i);
        if (name.isEmpty()) {
            return new int[] {'&', 0, 1};
        }
        if (attribute && name.charAt(name.length() - 1) != ';' && len > i && s.charAt(start + i) == '=') {
            return new int[] {'&', 0, 1};
        }
        int[] v = TABLE.get(name);
        if (v != null) {
            return new int[] {v[0], v.length > 1 ? v[1] : 0, i};
        }
        if (!attribute) {
            int maxLen = Math.min(name.length() - 1, LONGEST_ENTITY_WITHOUT_SEMICOLON);
            for (int j = maxLen; j > 1; j--) {
                int[] w = TABLE.get(name.substring(0, j));
                if (w != null && w.length == 1) {
                    return new int[] {w[0], 0, j + 1};
                }
            }
        }
        return new int[] {'&', 0, 1};
    }

    /** Replaces every character reference of a byte string with its UTF-8 bytes. */
    static String unescape(String b, boolean attribute) {
        int amp = b.indexOf('&');
        if (amp < 0) {
            return b;
        }
        StringBuilder out = new StringBuilder(b.length());
        out.append(b, 0, amp);
        int src = amp;
        while (src < b.length()) {
            char c = b.charAt(src);
            if (c != '&') {
                out.append(c);
                src++;
                continue;
            }
            int[] r = unescapeEntity(b, src, attribute);
            if (r[2] == 1 && r[0] == '&') {
                out.append('&');
                src++;
                continue;
            }
            GoStrings.appendRune(out, r[0]);
            if (r[1] != 0) {
                GoStrings.appendRune(out, r[1]);
            }
            src += r[2];
        }
        return out.toString();
    }
}
