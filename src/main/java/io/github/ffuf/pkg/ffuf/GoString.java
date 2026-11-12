package io.github.ffuf.pkg.ffuf;

import java.io.ByteArrayOutputStream;

/**
 * Go strings are byte sequences that usually, but not always, hold UTF-8. This class maps them losslessly to Java
 * strings: valid UTF-8 is decoded as usual and every byte that Go's utf8.DecodeRune rejects becomes the unpaired
 * surrogate {@code U+DC80 + (byte - 0x80)}, which {@link #encode} turns back into the same byte. Such a string can be
 * searched, replaced and concatenated like any other, and still produces the original bytes on output.
 */
public final class GoString {
    private static final int ESCAPE_BASE = 0xDC00;

    private GoString() {
    }

    /** {@return the text of b, keeping each byte of an invalid UTF-8 sequence as an escape char} */
    public static String decode(byte[] b) {
        return decode(b, 0, b.length);
    }

    /** {@return the text of {@code len} bytes from {@code off}, decoded as {@link #decode(byte[])} decodes} */
    public static String decode(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        int end = off + len;
        int i = off;
        while (i < end) {
            int c = b[i] & 0xff;
            if (c < 0x80) {
                sb.append((char) c);
                i++;
                continue;
            }
            int size = validSequenceLength(b, i, end);
            if (size == 0) {
                sb.append((char) (ESCAPE_BASE + c));
                i++;
                continue;
            }
            int cp = switch (size) {
                case 2 -> (c & 0x1f) << 6 | (b[i + 1] & 0x3f);
                case 3 -> (c & 0x0f) << 12 | (b[i + 1] & 0x3f) << 6 | (b[i + 2] & 0x3f);
                default -> (c & 0x07) << 18 | (b[i + 1] & 0x3f) << 12 | (b[i + 2] & 0x3f) << 6 | (b[i + 3] & 0x3f);
            };
            sb.appendCodePoint(cp);
            i += size;
        }
        return sb.toString();
    }

    /** The length of the valid multi-byte UTF-8 sequence at {@code i}, or 0 when Go would decode RuneError there. */
    private static int validSequenceLength(byte[] b, int i, int end) {
        int c = b[i] & 0xff;
        int size;
        int lo = 0x80;
        int hi = 0xbf;
        if (c >= 0xc2 && c <= 0xdf) {
            size = 2;
        } else if (c >= 0xe0 && c <= 0xef) {
            size = 3;
            if (c == 0xe0) {
                lo = 0xa0;
            } else if (c == 0xed) {
                hi = 0x9f;
            }
        } else if (c >= 0xf0 && c <= 0xf4) {
            size = 4;
            if (c == 0xf0) {
                lo = 0x90;
            } else if (c == 0xf4) {
                hi = 0x8f;
            }
        } else {
            return 0;
        }
        if (i + size > end) {
            return 0;
        }
        int c1 = b[i + 1] & 0xff;
        if (c1 < lo || c1 > hi) {
            return 0;
        }
        for (int k = 2; k < size; k++) {
            int ck = b[i + k] & 0xff;
            if (ck < 0x80 || ck > 0xbf) {
                return 0;
            }
        }
        return size;
    }

    /** {@return whether {@code c} stands for a raw byte of invalid UTF-8} */
    public static boolean isEscape(char c) {
        return c >= ESCAPE_BASE + 0x80 && c <= ESCAPE_BASE + 0xff;
    }

    /**
     * Encodes a string to raw bytes: UTF-8, escape chars as their raw byte, and any other unpaired surrogate as
     * U+FFFD, as Go's string conversion of a surrogate rune produces.
     *
     * @return the encoded bytes
     */
    public static byte[] encode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                out.write(c);
            } else if (c < 0x800) {
                out.write(0xc0 | c >> 6);
                out.write(0x80 | c & 0x3f);
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                out.write(0xf0 | cp >> 18);
                out.write(0x80 | cp >> 12 & 0x3f);
                out.write(0x80 | cp >> 6 & 0x3f);
                out.write(0x80 | cp & 0x3f);
            } else if (isEscape(c)) {
                out.write(c - ESCAPE_BASE);
            } else {
                int cp = Character.isSurrogate(c) ? 0xfffd : c;
                out.write(0xe0 | cp >> 12);
                out.write(0x80 | cp >> 6 & 0x3f);
                out.write(0x80 | cp & 0x3f);
            }
        }
        return out.toByteArray();
    }

    /** {@return s with each escape char replaced by U+FFFD, as Go converts invalid UTF-8 to runes} */
    public static String toValidUtf8(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isEscape(c)) {
                if (sb == null) {
                    sb = new StringBuilder(s.substring(0, i));
                }
                sb.append('�');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    /** {@return the number of runes in s; every escape char counts as one, as each invalid byte does in Go} */
    public static int runeCount(String s) {
        return s.codePointCount(0, s.length());
    }

    /** {@return s padded with spaces on the right to {@code width} runes, as fmt's {@code %-Ns} pads} */
    public static String padRight(String s, int width) {
        int n = runeCount(s);
        return n >= width ? s : s + " ".repeat(width - n);
    }
}
