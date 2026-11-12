package io.github.ffuf.pkg.runner;

import java.nio.charset.StandardCharsets;

/**
 * Go string helpers for the HTTP code. A "byte string" is a Java String that holds one byte per char
 * (ISO-8859-1), which is how this package represents Go's byte-oriented strings.
 */
public final class GoText {
    private GoText() {
    }

    /** {@return the UTF-8 bytes of s as a byte string} */
    public static String bin(String s) {
        return new String(io.github.ffuf.pkg.ffuf.GoString.encode(s), StandardCharsets.ISO_8859_1);
    }

    /** {@return the bytes of a byte string} */
    public static byte[] bytes(String bin) {
        return bin.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** {@return a byte string as a Java string that keeps its invalid UTF-8 bytes (see GoString)} */
    public static String text(String bin) {
        return io.github.ffuf.pkg.ffuf.GoString.decode(bytes(bin));
    }

    static String asciiLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return sb.toString();
    }

    /**
     * Decodes the rune at i of a byte string like utf8.DecodeRuneInString: returns rune and width packed as
     * {@code rune << 3 | width}, with rune U+FFFD and width 1 for an invalid encoding.
     */
    static long decodeRune(String s, int i) {
        int b0 = s.charAt(i);
        if (b0 < 0x80) {
            return (long) b0 << 3 | 1;
        }
        int n;
        int r;
        int lo = 0x80;
        int hi = 0xbf;
        if (b0 >= 0xc2 && b0 <= 0xdf) {
            n = 2;
            r = b0 & 0x1f;
        } else if (b0 >= 0xe0 && b0 <= 0xef) {
            n = 3;
            r = b0 & 0x0f;
            if (b0 == 0xe0) {
                lo = 0xa0;
            } else if (b0 == 0xed) {
                hi = 0x9f;
            }
        } else if (b0 >= 0xf0 && b0 <= 0xf4) {
            n = 4;
            r = b0 & 0x07;
            if (b0 == 0xf0) {
                lo = 0x90;
            } else if (b0 == 0xf4) {
                hi = 0x8f;
            }
        } else {
            return 0xfffdL << 3 | 1;
        }
        if (i + n > s.length()) {
            return 0xfffdL << 3 | 1;
        }
        for (int k = 1; k < n; k++) {
            int b = s.charAt(i + k);
            int min = k == 1 ? lo : 0x80;
            int max = k == 1 ? hi : 0xbf;
            if (b < min || b > max) {
                return 0xfffdL << 3 | 1;
            }
            r = r << 6 | (b & 0x3f);
        }
        return (long) r << 3 | n;
    }

    /** {@return whether r is printable under Go's unicode.IsPrint} */
    static boolean isPrint(int r) {
        if (r == ' ') {
            return true;
        }
        switch (Character.getType(r)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
                    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    /** {@return a byte string quoted with the rules of Go's strconv.Quote, as text} */
    public static String quote(String bin) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < bin.length();) {
            long d = decodeRune(bin, i);
            int r = (int) (d >> 3);
            int w = (int) (d & 7);
            if (w == 1 && r == 0xfffd) {
                sb.append("\\x").append(String.format("%02x", (int) bin.charAt(i)));
                i++;
                continue;
            }
            i += w;
            if (r == '"' || r == '\\') {
                sb.append('\\').append((char) r);
            } else if (isPrint(r)) {
                sb.appendCodePoint(r);
            } else {
                switch (r) {
                    case 7 -> sb.append("\\a");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case 11 -> sb.append("\\v");
                    default -> {
                        if (r < ' ' || r == 0x7f) {
                            sb.append("\\x").append(String.format("%02x", r));
                        } else if (r < 0x10000) {
                            sb.append("\\u").append(String.format("%04x", r));
                        } else {
                            sb.append("\\U").append(String.format("%08x", r));
                        }
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
