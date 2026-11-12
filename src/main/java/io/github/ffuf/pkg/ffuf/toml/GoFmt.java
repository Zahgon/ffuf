package io.github.ffuf.pkg.ffuf.toml;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.StringJoiner;

/** Go's {@code fmt} and {@code strconv} renderings of decoded TOML values, as go-toml's messages print them. */
final class GoFmt {
    private GoFmt() {
    }

    /** Go's {@code %v} of a value held by the tree. */
    static String v(Object o) {
        if (o instanceof String s) {
            return s;
        }
        if (o instanceof Double d) {
            return formatG(d);
        }
        if (o instanceof GoUint64 u) {
            return Long.toUnsignedString(u.bits());
        }
        if (o instanceof TomlDateTime t) {
            return t.goString();
        }
        if (o instanceof TomlTree t) {
            return t.toTomlString();
        }
        if (o instanceof TomlTree.TreeArray a) {
            StringJoiner j = new StringJoiner(" ", "[", "]");
            for (TomlTree t : a.trees()) {
                j.add(t.toTomlString());
            }
            return j.toString();
        }
        if (o instanceof List<?> l) {
            StringJoiner j = new StringJoiner(" ", "[", "]");
            for (Object e : l) {
                j.add(v(e));
            }
            return j.toString();
        }
        return String.valueOf(o);
    }

    /** Go's {@code %#v} of a value held by the tree. */
    static String sharpV(Object o) {
        if (o instanceof String s) {
            return quote(s);
        }
        if (o instanceof GoUint64 u) {
            return "0x" + Long.toHexString(u.bits());
        }
        if (o instanceof TomlDateTime t) {
            return t.goSyntax();
        }
        if (o instanceof List<?> l) {
            if (l.isEmpty()) {
                return "[]interface {}(nil)";
            }
            StringJoiner j = new StringJoiner(", ", "[]interface {}{", "}");
            for (Object e : l) {
                j.add(sharpV(e));
            }
            return j.toString();
        }
        if (o instanceof TomlTree || o instanceof TomlTree.TreeArray) {
            return "(" + typeName(o) + ")(" + v(o) + ")";
        }
        return v(o);
    }

    /** Go's {@code %T} of a value held by the tree. */
    static String typeName(Object o) {
        if (o instanceof String) {
            return "string";
        }
        if (o instanceof Long) {
            return "int64";
        }
        if (o instanceof GoUint64) {
            return "uint64";
        }
        if (o instanceof Double) {
            return "float64";
        }
        if (o instanceof Boolean) {
            return "bool";
        }
        if (o instanceof TomlDateTime t) {
            return t.goType();
        }
        if (o instanceof TomlTree) {
            return "*toml.Tree";
        }
        if (o instanceof TomlTree.TreeArray) {
            return "[]*toml.Tree";
        }
        return "[]interface {}";
    }

    /** Go's {@code strconv.Quote}. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        s.codePoints().forEach(r -> {
            switch (r) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case 0x07 -> sb.append("\\a");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case 0x0B -> sb.append("\\v");
                default -> {
                    if (isPrint(r)) {
                        sb.appendCodePoint(r);
                    } else if (r < ' ' || r == 0x7F) {
                        sb.append(String.format("\\x%02x", r));
                    } else if (r < 0x10000) {
                        sb.append(String.format("\\u%04x", r));
                    } else {
                        sb.append(String.format("\\U%08x", r));
                    }
                }
            }
        });
        return sb.append('"').toString();
    }

    private static boolean isPrint(int r) {
        if (r == ' ') {
            return true;
        }
        return switch (Character.getType(r)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                    Character.UNASSIGNED, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }

    /** Go's {@code fmt.Sprintf(format)} with no arguments, as go-toml applies it to some lexer messages. */
    static String sprintfNoArgs(String format) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%') {
                sb.append(c);
            } else if (i + 1 >= format.length()) {
                sb.append("%!(NOVERB)");
            } else if (format.charAt(i + 1) == '%') {
                sb.append('%');
                i++;
            } else {
                sb.append("%!").append(format.charAt(i + 1)).append("(MISSING)");
                i++;
            }
        }
        return sb.toString();
    }

    /** The shortest decimal digits that read back as {@code abs}, and the decimal point position. */
    private record Digits(String digits, int dp) {
    }

    private static Digits shortest(double abs, boolean float32) {
        if (abs == 0) {
            return new Digits("0", 1);
        }
        BigDecimal exact = new BigDecimal(abs);
        for (int p = 1; p <= 17; p++) {
            BigDecimal r = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
            boolean same = float32 ? r.floatValue() == (float) abs : r.doubleValue() == abs;
            if (same) {
                BigDecimal stripped = r.stripTrailingZeros();
                String digits = stripped.unscaledValue().toString();
                return new Digits(digits, digits.length() - stripped.scale());
            }
        }
        BigDecimal stripped = exact.stripTrailingZeros();
        String digits = stripped.unscaledValue().toString();
        return new Digits(digits, digits.length() - stripped.scale());
    }

    /** Go's {@code %v} of a float64: shortest digits, exponent form below 1e-4 and from 1e6. */
    static String formatG(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "+Inf" : "-Inf";
        }
        String sign = (d < 0 || (d == 0 && 1 / d < 0)) ? "-" : "";
        Digits dg = shortest(Math.abs(d), false);
        int exp = dg.dp() - 1;
        if (exp < -4 || exp >= 6) {
            String m = dg.digits().length() > 1 ? dg.digits().charAt(0) + "." + dg.digits().substring(1)
                    : dg.digits();
            String es = exp < 0 ? "-" : "+";
            int ae = Math.abs(exp);
            return sign + m + "e" + es + (ae < 10 ? "0" : "") + ae;
        }
        return sign + fixed(dg);
    }

    private static String fixed(Digits dg) {
        String digits = dg.digits();
        int dp = dg.dp();
        if (dp <= 0) {
            return "0." + "0".repeat(-dp) + digits;
        }
        if (dp >= digits.length()) {
            return digits + "0".repeat(dp - digits.length());
        }
        return digits.substring(0, dp) + "." + digits.substring(dp);
    }

    /** Go's {@code strconv.FormatFloat(d, 'f', -1, bits)}: shortest digits for that size, never an exponent. */
    static String formatShortestFixed(double d, boolean float32) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "+Inf" : "-Inf";
        }
        String sign = (d < 0 || (d == 0 && 1 / d < 0)) ? "-" : "";
        return sign + fixed(shortest(Math.abs(d), float32));
    }

    /** Go's {@code strconv.FormatFloat(d, 'f', 1, 64)} for an integral or infinite value. */
    static String formatOneDecimal(double d) {
        if (Double.isInfinite(d)) {
            return d > 0 ? "+Inf" : "-Inf";
        }
        String sign = (d < 0 || (d == 0 && 1 / d < 0)) ? "-" : "";
        return sign + new BigDecimal(Math.abs(d)).setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }
}
