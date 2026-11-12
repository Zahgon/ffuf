package io.github.ffuf.pkg.goquery;

import java.nio.charset.StandardCharsets;

/**
 * Go string semantics over byte strings (Java strings whose chars are the bytes 0-255 of UTF-8 text). Runes are
 * decoded the way Go's range loop does: an invalid byte reads as U+FFFD with width 1.
 */
public final class GoStrings {
    private static final int RUNE_ERROR = 0xFFFD;

    private GoStrings() {
    }

    /** {@return the byte string of raw bytes} */
    public static String fromBytes(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    /** {@return the byte string of Java text, encoded as UTF-8} */
    public static String encode(String s) {
        return fromBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /** {@return the Java text of a byte string, decoding its UTF-8 so that each invalid byte is U+FFFD} */
    public static String decode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length();) {
            char c = s.charAt(i);
            if (c < 0x80) {
                sb.append(c);
                i++;
                continue;
            }
            int[] rw = decodeRune(s, i);
            sb.appendCodePoint(rw[0]);
            i += rw[1];
        }
        return sb.toString();
    }

    /** Appends the UTF-8 bytes of a code point. */
    static void appendRune(StringBuilder sb, int r) {
        if (r < 0 || r > 0x10FFFF || (r >= 0xD800 && r <= 0xDFFF)) {
            r = RUNE_ERROR;
        }
        if (r < 0x80) {
            sb.append((char) r);
        } else if (r < 0x800) {
            sb.append((char) (0xC0 | (r >> 6))).append((char) (0x80 | (r & 0x3F)));
        } else if (r < 0x10000) {
            sb.append((char) (0xE0 | (r >> 12))).append((char) (0x80 | ((r >> 6) & 0x3F)))
                    .append((char) (0x80 | (r & 0x3F)));
        } else {
            sb.append((char) (0xF0 | (r >> 18))).append((char) (0x80 | ((r >> 12) & 0x3F)))
                    .append((char) (0x80 | ((r >> 6) & 0x3F))).append((char) (0x80 | (r & 0x3F)));
        }
    }

    /** Decodes the rune at i; returns {rune, width} like utf8.DecodeRuneInString. */
    static int[] decodeRune(String s, int i) {
        int n = s.length() - i;
        int b0 = s.charAt(i);
        if (b0 < 0x80) {
            return new int[] {b0, 1};
        }
        int need;
        int min;
        int r;
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            need = 1;
            min = 0x80;
            r = b0 & 0x1F;
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            need = 2;
            min = 0x800;
            r = b0 & 0x0F;
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            need = 3;
            min = 0x10000;
            r = b0 & 0x07;
        } else {
            return new int[] {RUNE_ERROR, 1};
        }
        if (n <= need) {
            return new int[] {RUNE_ERROR, 1};
        }
        for (int k = 1; k <= need; k++) {
            int b = s.charAt(i + k);
            if ((b & 0xC0) != 0x80) {
                return new int[] {RUNE_ERROR, 1};
            }
            r = (r << 6) | (b & 0x3F);
        }
        if (r < min || r > 0x10FFFF || (r >= 0xD800 && r <= 0xDFFF)) {
            return new int[] {RUNE_ERROR, 1};
        }
        return new int[] {r, need + 1};
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /** Lower-cases s as Go's strings.ToLower does: ASCII fast path, otherwise a rune map that turns invalid bytes into U+FFFD. */
    static String toLower(String s) {
        if (isAscii(s)) {
            return toLowerAscii(s);
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length();) {
            int[] rw = decodeRune(s, i);
            appendRune(sb, rw[0] == RUNE_ERROR ? RUNE_ERROR : Character.toLowerCase(rw[0]));
            i += rw[1];
        }
        return sb.toString();
    }

    /** ASCII-only lower casing, as cascadia's toLowerASCII and the tokenizer's lower. */
    static String toLowerAscii(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (sb == null) {
                    sb = new StringBuilder(s);
                }
                sb.setCharAt(i, (char) (c + 32));
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static boolean foldEquals(int a, int b) {
        if (a == b) {
            return true;
        }
        return Character.toLowerCase(a) == Character.toLowerCase(b) || Character.toUpperCase(a) == Character.toUpperCase(b);
    }

    /** {@return whether s and t are equal under Unicode simple case folding} */
    public static boolean equalFold(String s, String t) {
        int i = 0;
        int j = 0;
        while (i < s.length() && j < t.length()) {
            int[] a = decodeRune(s, i);
            int[] b = decodeRune(t, j);
            if (!foldEquals(a[0], b[0])) {
                return false;
            }
            i += a[1];
            j += b[1];
        }
        return i == s.length() && j == t.length();
    }

    /** {@return whether r is white space under Go's unicode.IsSpace} */
    private static boolean isSpace(int r) {
        switch (r) {
            case '\t', '\n', 0x0B, '\f', '\r', ' ', 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000:
                return true;
            default:
                return r >= 0x2000 && r <= 0x200A;
        }
    }

    /** Whether strings.TrimSpace(s) is empty. */
    static boolean isBlank(String s) {
        for (int i = 0; i < s.length();) {
            int[] rw = decodeRune(s, i);
            if (!isSpace(rw[0])) {
                return false;
            }
            i += rw[1];
        }
        return true;
    }

    /** {@return s without leading and trailing Unicode white space} */
    public static String trimSpace(String s) {
        int start = 0;
        while (start < s.length()) {
            int[] rw = decodeRune(s, start);
            if (!isSpace(rw[0])) {
                break;
            }
            start += rw[1];
        }
        int end = s.length();
        while (end > start) {
            int k = end - 1;
            while (k > start && (s.charAt(k) & 0xC0) == 0x80 && end - k < 4) {
                k--;
            }
            int[] rw = decodeRune(s, k);
            if (k + rw[1] != end) {
                k = end - 1;
                rw = new int[] {RUNE_ERROR, 1};
            }
            if (!isSpace(rw[0])) {
                break;
            }
            end = k;
        }
        return s.substring(start, end);
    }

    /** strings.TrimLeft with an ASCII cutset. */
    static String trimLeft(String s, String cutset) {
        int i = 0;
        while (i < s.length() && cutset.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }

    /** strings.Trim with an ASCII cutset. */
    static String trim(String s, String cutset) {
        int i = 0;
        int j = s.length();
        while (i < j && cutset.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        while (j > i && cutset.indexOf(s.charAt(j - 1)) >= 0) {
            j--;
        }
        return s.substring(i, j);
    }
}
