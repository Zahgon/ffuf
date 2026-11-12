package io.github.ffuf.pkg.goregexp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A regular expression with the syntax and semantics of Go's regexp package: RE2 syntax with the Perl flags of
 * regexp.Compile, leftmost-first matching, and input read as UTF-8 where every invalid byte is one U+FFFD rune.
 * Matching runs on bytes; the String methods match the UTF-8 encoding of their argument. Instances are immutable and
 * safe to share between threads.
 */
public final class GoRegexp {
    /** A syntax error; {@link #getMessage()} is the text of Go's {@code err.Error()}. */
    public static final class SyntaxException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String expr;

        SyntaxException(String code, String expr) {
            super("error parsing regexp: " + code + ": `" + expr + "`", null, false, false);
            this.code = code;
            this.expr = expr;
        }

        /** {@return the error code text, such as {@code missing closing )}} */
        public String code() {
            return code;
        }

        /** {@return the part of the expression the error is about} */
        public String expr() {
            return expr;
        }
    }

    private final String expr;
    private final Prog prog;
    private final int numSubexp;
    private final int cond;

    private GoRegexp(String expr, Prog prog, int numSubexp) {
        this.expr = expr;
        this.prog = prog;
        this.numSubexp = numSubexp;
        this.cond = prog.startCond();
    }

    /**
     * Compiles the UTF-8 encoding of {@code expr}.
     *
     * @param expr the expression in RE2 syntax
     * @return the compiled expression
     * @throws SyntaxException when the expression is invalid; its message is the error text Go's regexp.Compile reports
     */
    public static GoRegexp compile(String expr) throws SyntaxException {
        return compile(expr.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Compiles the raw bytes of an expression, which may hold invalid UTF-8.
     *
     * @param expr the expression in RE2 syntax
     * @return the compiled expression
     * @throws SyntaxException when the expression is invalid; its message is the error text Go's regexp.Compile reports
     */
    public static GoRegexp compile(byte[] expr) throws SyntaxException {
        Node re = Parser.parse(expr);
        int maxCap = re.maxCap();
        Prog prog = Prog.compile(re.simplify());
        return new GoRegexp(new String(expr, StandardCharsets.UTF_8), prog, maxCap);
    }

    /** {@return the number of parenthesized subexpressions} */
    public int numSubexp() {
        return numSubexp;
    }

    /** {@return whether {@code b} contains a match} */
    public boolean match(byte[] b) {
        return find(b, 0, 0) != null;
    }

    /** {@return whether {@code s} contains a match} */
    public boolean matchString(String s) {
        return match(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Finds the leftmost match in b.
     *
     * @return the byte offsets of the match and its subexpressions as [start, end] pairs, -1 for a group that did not
     *     take part, or null when there is no match
     */
    public int[] findSubmatchIndex(byte[] b) {
        return pad(find(b, 0, prog.numCap));
    }

    /** {@return the leftmost match and its subexpressions, null entries for groups that did not take part, or null when there is no match} */
    public byte[][] findSubmatch(byte[] b) {
        return slices(b, findSubmatchIndex(b));
    }

    /** {@return the leftmost match and its subexpressions, "" for groups that did not take part, or null when there is no match} */
    public String[] findStringSubmatch(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return strings(b, findSubmatchIndex(b));
    }

    /**
     * Finds successive non-overlapping matches.
     *
     * @param n the largest number of matches to return; n < 0 means all
     * @return the matches as {@link #findSubmatchIndex} returns them, empty when there is none
     */
    public List<int[]> findAllSubmatchIndex(byte[] b, int n) {
        List<int[]> out = new ArrayList<>();
        if (n == 0) {
            return out;
        }
        int end = b.length;
        int prevMatchEnd = -1;
        for (int pos = 0; pos <= end;) {
            int[] m = find(b, pos, prog.numCap);
            if (m == null) {
                break;
            }
            boolean accept = true;
            if (m[1] == pos) {
                if (m[0] == prevMatchEnd) {
                    accept = false;
                }
                int width = Utf8.decode(b, pos, end) & 7;
                pos = width > 0 ? pos + width : end + 1;
            } else {
                pos = m[1];
            }
            prevMatchEnd = m[1];
            if (accept) {
                out.add(pad(m));
                if (n > 0 && out.size() == n) {
                    break;
                }
            }
        }
        return out;
    }

    /** {@return up to n successive matches as {@link #findSubmatch} returns them (n < 0 means all), empty when there is none} */
    public List<byte[][]> findAllSubmatch(byte[] b, int n) {
        List<byte[][]> out = new ArrayList<>();
        for (int[] m : findAllSubmatchIndex(b, n)) {
            out.add(slices(b, m));
        }
        return out;
    }

    /** {@return up to n successive matches as {@link #findStringSubmatch} returns them (n < 0 means all), empty when there is none} */
    public List<String[]> findAllStringSubmatch(String s, int n) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        List<String[]> out = new ArrayList<>();
        for (int[] m : findAllSubmatchIndex(b, n)) {
            out.add(strings(b, m));
        }
        return out;
    }

    /** {@return {@code s} with every regular expression metacharacter escaped} */
    public static String quoteMeta(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (special(c)) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** {@return the raw bytes {@code s} with every regular expression metacharacter escaped} */
    public static byte[] quoteMeta(byte[] s) {
        byte[] out = new byte[2 * s.length];
        int j = 0;
        for (byte c : s) {
            if (special(c)) {
                out[j++] = '\\';
            }
            out[j++] = c;
        }
        return Arrays.copyOf(out, j);
    }

    private static boolean special(int c) {
        return c >= 0 && c < 0x80 && "\\.+*?()|[]{}^$".indexOf(c) >= 0;
    }

    /** The source text of the expression. */
    @Override
    public String toString() {
        return expr;
    }

    private int[] pad(int[] a) {
        if (a == null) {
            return null;
        }
        int n = (1 + numSubexp) * 2;
        if (a.length >= n) {
            return a;
        }
        int[] out = Arrays.copyOf(a, n);
        Arrays.fill(out, a.length, n, -1);
        return out;
    }

    private static byte[][] slices(byte[] b, int[] m) {
        if (m == null) {
            return null;
        }
        byte[][] out = new byte[m.length / 2][];
        for (int i = 0; i < out.length; i++) {
            if (m[2 * i] >= 0) {
                out[i] = Arrays.copyOfRange(b, m[2 * i], m[2 * i + 1]);
            }
        }
        return out;
    }

    private static String[] strings(byte[] b, int[] m) {
        if (m == null) {
            return null;
        }
        String[] out = new String[m.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = m[2 * i] >= 0 ? new String(b, m[2 * i], m[2 * i + 1] - m[2 * i], StandardCharsets.UTF_8) : "";
        }
        return out;
    }

    private int[] find(byte[] b, int pos, int ncap) {
        if (cond == -1) {
            return null;
        }
        Machine m = new Machine(prog, b, ncap, cond);
        if (!m.match(pos)) {
            return null;
        }
        return m.matchcap;
    }
}
