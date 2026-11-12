package io.github.ffuf.pkg.ffuf;

/**
 * Character classes and formatting of Go's unicode and strconv packages, over strings that may hold the raw-byte
 * escapes of {@link GoString}.
 */
public final class GoUnicode {
    private GoUnicode() {
    }

    /** {@return whether r is white space under Go's unicode.IsSpace} */
    public static boolean isSpace(int r) {
        if (r < 0x100) {
            return r == '\t' || r == '\n' || r == 0x0b || r == '\f' || r == '\r' || r == ' ' || r == 0x85 || r == 0xa0;
        }
        return r == 0x1680 || r >= 0x2000 && r <= 0x200a || r == 0x2028 || r == 0x2029 || r == 0x202f || r == 0x205f || r == 0x3000;
    }

    /** {@return whether r is printable under Go's strconv.IsPrint: letters, marks, numbers, punctuation, symbols and the ASCII space} */
    public static boolean isPrint(int r) {
        if (r < 0x100) {
            return r >= 0x20 && r <= 0x7e || r >= 0xa1 && r != 0xad;
        }
        if (r > 0x10ffff || r >= 0xd800 && r <= 0xdfff) {
            return false;
        }
        return switch (Character.getType(r)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
                    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    /** {@return s quoted as Go's strconv.Quote does, without the surrounding quotes: invalid bytes as \xHH, other non-printable runes escaped} */
    public static String quoteBody(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); ) {
            int r = s.codePointAt(i);
            i += Character.charCount(r);
            if (r <= 0xffff && GoString.isEscape((char) r)) {
                sb.append(String.format("\\x%02x", r - 0xdc00));
            } else if (r == '"' || r == '\\') {
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
                    case 0x0b -> sb.append("\\v");
                    default -> {
                        if (r < ' ' || r == 0x7f) {
                            sb.append(String.format("\\x%02x", r));
                        } else if (r < 0x10000) {
                            sb.append(String.format("\\u%04x", Character.isSurrogate((char) r) ? 0xfffd : r));
                        } else {
                            sb.append(String.format("\\U%08x", r));
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /** {@return r formatted as fmt's {@code %#U}: "U+0078 'x'", without the quoted character when it is not printable} */
    public static String formatSharpU(int r) {
        String s = String.format("U+%04X", r);
        return isPrint(r) ? s + " '" + new String(Character.toChars(r)) + "'" : s;
    }

    /**
     * Maps each rune of s on its own (no context or length changes), as Go's strings.ToUpper and strings.ToLower
     * do; every invalid byte becomes U+FFFD.
     *
     * @return the mapped string
     */
    public static String mapRunes(String s, java.util.function.IntUnaryOperator mapping) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int r = s.codePointAt(i);
            i += Character.charCount(r);
            if (r <= 0xffff && GoString.isEscape((char) r)) {
                r = 0xfffd;
            }
            sb.appendCodePoint(mapping.applyAsInt(r));
        }
        return sb.toString();
    }
}
