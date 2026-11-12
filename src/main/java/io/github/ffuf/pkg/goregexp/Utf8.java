package io.github.ffuf.pkg.goregexp;

/** UTF-8 decoding with the rules of Go's unicode/utf8: every byte of an invalid sequence is one U+FFFD. */
final class Utf8 {
    static final int RUNE_ERROR = 0xFFFD;
    static final int MAX_RUNE = 0x10FFFF;
    static final int RUNE_SELF = 0x80;

    private Utf8() {
    }

    /**
     * Decodes the rune starting at {@code pos}, like utf8.DecodeRune on {@code b[pos:end]}. The result packs the rune
     * and its width as {@code rune << 3 | width}; the width is 0 at the end of the input.
     */
    static int decode(byte[] b, int pos, int end) {
        if (pos >= end) {
            return RUNE_ERROR << 3;
        }
        int b0 = b[pos] & 0xFF;
        if (b0 < 0x80) {
            return b0 << 3 | 1;
        }
        int invalid = RUNE_ERROR << 3 | 1;
        int lo = 0x80;
        int hi = 0xBF;
        int n;
        int r;
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            n = 2;
            r = b0 & 0x1F;
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            n = 3;
            r = b0 & 0x0F;
            if (b0 == 0xE0) {
                lo = 0xA0;
            } else if (b0 == 0xED) {
                hi = 0x9F;
            }
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            n = 4;
            r = b0 & 0x07;
            if (b0 == 0xF0) {
                lo = 0x90;
            } else if (b0 == 0xF4) {
                hi = 0x8F;
            }
        } else {
            return invalid;
        }
        for (int i = 1; i < n; i++) {
            if (pos + i >= end) {
                return invalid;
            }
            int c = b[pos + i] & 0xFF;
            if (i == 1 ? c < lo || c > hi : c < 0x80 || c > 0xBF) {
                return invalid;
            }
            r = r << 6 | (c & 0x3F);
        }
        return r << 3 | n;
    }

    /** The rune ending just before {@code pos}, like utf8.DecodeLastRune on {@code b[:pos]}. */
    static int decodeLastRune(byte[] b, int pos) {
        if (pos == 0) {
            return RUNE_ERROR;
        }
        int start = pos - 1;
        int r = b[start] & 0xFF;
        if (r < RUNE_SELF) {
            return r;
        }
        int lim = Math.max(0, pos - 4);
        for (start--; start >= lim; start--) {
            if ((b[start] & 0xC0) != 0x80) {
                break;
            }
        }
        start = Math.max(start, 0);
        int d = decode(b, start, pos);
        if (start + (d & 7) != pos) {
            return RUNE_ERROR;
        }
        return d >> 3;
    }
}
