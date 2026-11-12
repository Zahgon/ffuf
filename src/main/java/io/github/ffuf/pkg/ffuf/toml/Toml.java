package io.github.ffuf.pkg.ffuf.toml;

/**
 * Entry points of the TOML reader, which accepts documents as go-toml v1.9.5 does: loading bytes (byte order marks,
 * lexer, parser) and decoding the tree into an options object.
 */
public final class Toml {
    private Toml() {
    }

    /**
     * Parses a document into its tree.
     *
     * @return the root table
     * @throws TomlException with go-toml's positioned error text when the document is malformed
     */
    public static TomlTree parse(byte[] data) throws TomlException {
        return Parser.parse(Lexer.lex(goRunes(data, bomLength(data))));
    }

    /**
     * Parses a document and decodes it into {@code target}, an instance of the struct {@code type} describes. Fields
     * decoded before an error keep their new values.
     *
     * @param data the raw document
     * @param type the struct type of target
     * @param target the instance the values are written into
     * @throws TomlException when the document is malformed or a value does not fit its field
     */
    public static void unmarshal(byte[] data, GoType type, Object target) throws TomlException {
        Decoder.valueFromTree(type, parse(data), target);
    }

    private static int bomLength(byte[] b) {
        if (b.length >= 4 && (b[0] == 0 && b[1] == 0 && (b[2] & 0xFF) == 0xFE && (b[3] & 0xFF) == 0xFF
                || (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE && b[2] == 0 && b[3] == 0)) {
            return 4;
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return 3;
        }
        if (b.length >= 2 && ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF
                || (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)) {
            return 2;
        }
        return 0;
    }

    /** Go's {@code bytes.Runes}: UTF-8 decoding where every byte of an invalid sequence becomes U+FFFD. */
    static int[] goRunes(byte[] b, int start) {
        int[] out = new int[b.length - start];
        int n = 0;
        int i = start;
        while (i < b.length) {
            int c0 = b[i] & 0xFF;
            if (c0 < 0x80) {
                out[n++] = c0;
                i++;
                continue;
            }
            int size;
            int lo = 0x80;
            int hi = 0xBF;
            if (c0 >= 0xC2 && c0 <= 0xDF) {
                size = 2;
            } else if (c0 >= 0xE0 && c0 <= 0xEF) {
                size = 3;
                if (c0 == 0xE0) {
                    lo = 0xA0;
                } else if (c0 == 0xED) {
                    hi = 0x9F;
                }
            } else if (c0 >= 0xF0 && c0 <= 0xF4) {
                size = 4;
                if (c0 == 0xF0) {
                    lo = 0x90;
                } else if (c0 == 0xF4) {
                    hi = 0x8F;
                }
            } else {
                out[n++] = 0xFFFD;
                i++;
                continue;
            }
            boolean ok = i + size <= b.length;
            for (int k = 1; ok && k < size; k++) {
                int c = b[i + k] & 0xFF;
                ok = k == 1 ? c >= lo && c <= hi : c >= 0x80 && c <= 0xBF;
            }
            if (!ok) {
                out[n++] = 0xFFFD;
                i++;
                continue;
            }
            int r = c0 & (size == 2 ? 0x1F : size == 3 ? 0x0F : 0x07);
            for (int k = 1; k < size; k++) {
                r = (r << 6) | (b[i + k] & 0x3F);
            }
            out[n++] = r;
            i += size;
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
