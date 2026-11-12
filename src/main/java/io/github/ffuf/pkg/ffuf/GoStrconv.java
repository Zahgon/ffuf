package io.github.ffuf.pkg.ffuf;

/**
 * Number and boolean parsing with the rules of Go's strconv package, as the flag package applies them to command line
 * values: {@code ParseInt(s, 0, 64)} with base prefixes, leading-zero octal and underscores, and {@code ParseBool}.
 */
public final class GoStrconv {
    /** Why a value was rejected, with the meaning of strconv.ErrSyntax and strconv.ErrRange. */
    public enum Error {
        SYNTAX, RANGE
    }

    /** The result of a parse: the value, or the error kind when it failed. */
    public record IntResult(long value, Error error) {
    }

    private GoStrconv() {
    }

    private static int lower(char c) {
        return c | 0x20;
    }

    /**
     * Parses an unsigned 64-bit number with the rules of Go's strconv.ParseUint(s, base, 64).
     *
     * @return the value, held unsigned in a long, or the reason it was rejected
     */
    public static IntResult parseUint(String s, int base) {
        if (s.isEmpty()) {
            return new IntResult(0, Error.SYNTAX);
        }
        boolean base0 = base == 0;
        String s0 = s;
        if (base0) {
            base = 10;
            if (s.charAt(0) == '0') {
                if (s.length() >= 3 && lower(s.charAt(1)) == 'b') {
                    base = 2;
                    s = s.substring(2);
                } else if (s.length() >= 3 && lower(s.charAt(1)) == 'o') {
                    base = 8;
                    s = s.substring(2);
                } else if (s.length() >= 3 && lower(s.charAt(1)) == 'x') {
                    base = 16;
                    s = s.substring(2);
                } else {
                    base = 8;
                    s = s.substring(1);
                }
            }
        }
        long cutoff = Long.divideUnsigned(-1L, base) + 1;
        boolean underscores = false;
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d;
            if (c == '_' && base0) {
                underscores = true;
                continue;
            } else if (c >= '0' && c <= '9') {
                d = c - '0';
            } else if (c < 0x80 && lower(c) >= 'a' && lower(c) <= 'z') {
                d = lower(c) - 'a' + 10;
            } else {
                return new IntResult(0, Error.SYNTAX);
            }
            if (d >= base) {
                return new IntResult(0, Error.SYNTAX);
            }
            if (Long.compareUnsigned(n, cutoff) >= 0) {
                return new IntResult(-1L, Error.RANGE);
            }
            n *= base;
            long n1 = n + d;
            if (Long.compareUnsigned(n1, n) < 0) {
                return new IntResult(-1L, Error.RANGE);
            }
            n = n1;
        }
        if (underscores && !underscoreOk(s0)) {
            return new IntResult(0, Error.SYNTAX);
        }
        return new IntResult(n, null);
    }

    /**
     * Parses a signed 64-bit number with the rules of Go's strconv.ParseInt(s, base, 64).
     *
     * @return the value, or the reason it was rejected
     */
    public static IntResult parseInt(String s, int base) {
        if (s.isEmpty()) {
            return new IntResult(0, Error.SYNTAX);
        }
        boolean neg = false;
        String t = s;
        if (t.charAt(0) == '+') {
            t = t.substring(1);
        } else if (t.charAt(0) == '-') {
            neg = true;
            t = t.substring(1);
        }
        IntResult u = parseUint(t, base);
        if (u.error() == Error.SYNTAX) {
            return u;
        }
        long un = u.value();
        if (!neg && Long.compareUnsigned(un, Long.MIN_VALUE) >= 0) {
            return new IntResult(Long.MAX_VALUE, Error.RANGE);
        }
        if (neg && Long.compareUnsigned(un, Long.MIN_VALUE) > 0) {
            return new IntResult(Long.MIN_VALUE, Error.RANGE);
        }
        return new IntResult(neg ? -un : un, null);
    }

    /** Reports whether the underscores in a base-0 literal only separate digits, as Go's literal syntax requires. */
    static boolean underscoreOk(String s) {
        char saw = '^';
        int i = 0;
        if (!s.isEmpty() && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            s = s.substring(1);
        }
        boolean hex = false;
        if (s.length() >= 2 && s.charAt(0) == '0' && (lower(s.charAt(1)) == 'b' || lower(s.charAt(1)) == 'o' || lower(s.charAt(1)) == 'x')) {
            i = 2;
            saw = '0';
            hex = lower(s.charAt(1)) == 'x';
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9' || hex && lower(c) >= 'a' && lower(c) <= 'f') {
                saw = '0';
                continue;
            }
            if (c == '_') {
                if (saw != '0') {
                    return false;
                }
                saw = '_';
                continue;
            }
            if (saw == '_') {
                return false;
            }
            saw = '!';
        }
        return saw != '_';
    }

    /** {@return the boolean v spells under Go's strconv.ParseBool rules, or null for a value it rejects} */
    public static Boolean parseBool(String v) {
        return switch (v) {
            case "1", "t", "T", "TRUE", "true", "True" -> true;
            case "0", "f", "F", "FALSE", "false", "False" -> false;
            default -> null;
        };
    }
}
