package io.github.ffuf.pkg.goregexp;

import static io.github.ffuf.pkg.goregexp.Node.CLASS_NL;
import static io.github.ffuf.pkg.goregexp.Node.DOT_NL;
import static io.github.ffuf.pkg.goregexp.Node.FOLD_CASE;
import static io.github.ffuf.pkg.goregexp.Node.NON_GREEDY;
import static io.github.ffuf.pkg.goregexp.Node.ONE_LINE;
import static io.github.ffuf.pkg.goregexp.Node.OP_ALTERNATE;
import static io.github.ffuf.pkg.goregexp.Node.OP_ANY_CHAR;
import static io.github.ffuf.pkg.goregexp.Node.OP_ANY_CHAR_NOT_NL;
import static io.github.ffuf.pkg.goregexp.Node.OP_BEGIN_LINE;
import static io.github.ffuf.pkg.goregexp.Node.OP_BEGIN_TEXT;
import static io.github.ffuf.pkg.goregexp.Node.OP_CAPTURE;
import static io.github.ffuf.pkg.goregexp.Node.OP_CHAR_CLASS;
import static io.github.ffuf.pkg.goregexp.Node.OP_CONCAT;
import static io.github.ffuf.pkg.goregexp.Node.OP_EMPTY_MATCH;
import static io.github.ffuf.pkg.goregexp.Node.OP_END_LINE;
import static io.github.ffuf.pkg.goregexp.Node.OP_END_TEXT;
import static io.github.ffuf.pkg.goregexp.Node.OP_LEFT_PAREN;
import static io.github.ffuf.pkg.goregexp.Node.OP_LITERAL;
import static io.github.ffuf.pkg.goregexp.Node.OP_NO_MATCH;
import static io.github.ffuf.pkg.goregexp.Node.OP_NO_WORD_BOUNDARY;
import static io.github.ffuf.pkg.goregexp.Node.OP_PLUS;
import static io.github.ffuf.pkg.goregexp.Node.OP_PSEUDO;
import static io.github.ffuf.pkg.goregexp.Node.OP_QUEST;
import static io.github.ffuf.pkg.goregexp.Node.OP_REPEAT;
import static io.github.ffuf.pkg.goregexp.Node.OP_STAR;
import static io.github.ffuf.pkg.goregexp.Node.OP_VERTICAL_BAR;
import static io.github.ffuf.pkg.goregexp.Node.OP_WORD_BOUNDARY;
import static io.github.ffuf.pkg.goregexp.Node.PERL;
import static io.github.ffuf.pkg.goregexp.Node.PERL_X;
import static io.github.ffuf.pkg.goregexp.Node.UNICODE_GROUPS;
import static io.github.ffuf.pkg.goregexp.Node.WAS_DOLLAR;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a regular expression into a {@link Node} tree with the RE2 syntax and Perl flags of Go's regexp.Compile. It works on the
 * UTF-8 bytes of the expression so that positions and error texts are those of Go.
 */
final class Parser {
    static final String ERR_INVALID_CHAR_CLASS = "invalid character class";
    static final String ERR_INVALID_CHAR_RANGE = "invalid character class range";
    static final String ERR_INVALID_ESCAPE = "invalid escape sequence";
    static final String ERR_INVALID_NAMED_CAPTURE = "invalid named capture";
    static final String ERR_INVALID_PERL_OP = "invalid or unsupported Perl syntax";
    static final String ERR_INVALID_REPEAT_OP = "invalid nested repetition operator";
    static final String ERR_INVALID_REPEAT_SIZE = "invalid repeat count";
    static final String ERR_INVALID_UTF8 = "invalid UTF-8";
    static final String ERR_MISSING_BRACKET = "missing closing ]";
    static final String ERR_MISSING_PAREN = "missing closing )";
    static final String ERR_MISSING_REPEAT_ARGUMENT = "missing argument to repetition operator";
    static final String ERR_TRAILING_BACKSLASH = "trailing backslash at end of expression";
    static final String ERR_UNEXPECTED_PAREN = "unexpected )";
    static final String ERR_NESTING_DEPTH = "expression nests too deeply";
    static final String ERR_LARGE = "expression too large";

    private static final int MAX_HEIGHT = 1000;
    private static final long MAX_SIZE = (128L << 20) / 40;
    private static final long MAX_RUNES = (128L << 20) / 4;

    private static final Map<String, Group> PERL_GROUPS = new HashMap<>();
    private static final Map<String, Group> POSIX_GROUPS = new HashMap<>();
    private static final Map<String, String> SCRIPT_ALIASES = new HashMap<>();
    private static final Map<String, String> CATEGORY_ALIASES = new HashMap<>();

    static {
        int[] digit = {0x30, 0x39};
        int[] space = {0x9, 0xa, 0xc, 0xd, 0x20, 0x20};
        int[] word = {0x30, 0x39, 0x41, 0x5a, 0x5f, 0x5f, 0x61, 0x7a};
        perl("d", digit);
        perl("s", space);
        perl("w", word);
        posix("alnum", 0x30, 0x39, 0x41, 0x5a, 0x61, 0x7a);
        posix("alpha", 0x41, 0x5a, 0x61, 0x7a);
        posix("ascii", 0x0, 0x7f);
        posix("blank", 0x9, 0x9, 0x20, 0x20);
        posix("cntrl", 0x0, 0x1f, 0x7f, 0x7f);
        posix("digit", 0x30, 0x39);
        posix("graph", 0x21, 0x7e);
        posix("lower", 0x61, 0x7a);
        posix("print", 0x20, 0x7e);
        posix("punct", 0x21, 0x2f, 0x3a, 0x40, 0x5b, 0x60, 0x7b, 0x7e);
        posix("space", 0x9, 0xd, 0x20, 0x20);
        posix("upper", 0x41, 0x5a);
        posix("word", 0x30, 0x39, 0x41, 0x5a, 0x5f, 0x5f, 0x61, 0x7a);
        posix("xdigit", 0x30, 0x39, 0x41, 0x46, 0x61, 0x66);
        for (Map.Entry<String, String> e : UnicodeTables.CATEGORY_ALIASES.entrySet()) {
            CATEGORY_ALIASES.put(canonicalName(e.getKey()), e.getValue());
        }
        for (String name : UnicodeTables.SCRIPTS.keySet()) {
            SCRIPT_ALIASES.put(canonicalName(name), name);
        }
    }

    /** A Perl or POSIX class: its ranges and whether it is negated. */
    private record Group(int sign, int[] cls) {
    }

    private static void perl(String c, int[] cls) {
        PERL_GROUPS.put("\\" + c, new Group(1, cls));
        PERL_GROUPS.put("\\" + Character.toUpperCase(c.charAt(0)), new Group(-1, cls));
    }

    private static void posix(String name, int... cls) {
        POSIX_GROUPS.put("[:" + name + ":]", new Group(1, cls));
        POSIX_GROUPS.put("[:^" + name + ":]", new Group(-1, cls));
    }

    /** A resource limit hit while parsing; reported with the whole expression. */
    private static final class LimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        LimitException(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    private final byte[] s;
    private final int end;
    private int flags;
    private final List<Node> stack = new ArrayList<>();
    private int numCap;
    private int numRegexp;
    private int free;
    private long numRunes;
    private long repeats;
    private Map<Node, Integer> height;
    private Map<Node, Long> size;
    private final RuneList tmpClass = new RuneList();

    private Parser(byte[] s, int flags) {
        this.s = s;
        this.end = s.length;
        this.flags = flags;
    }

    /**
     * Parses {@code expr} with the Perl flags of regexp.Compile.
     *
     * @throws GoRegexp.SyntaxException when the expression is not valid regular expression syntax
     */
    static Node parse(byte[] expr) throws GoRegexp.SyntaxException {
        Parser p = new Parser(expr, PERL);
        try {
            return p.run();
        } catch (LimitException e) {
            throw p.error(e.code, 0, expr.length);
        }
    }

    private GoRegexp.SyntaxException error(String code, int from, int to) {
        return new GoRegexp.SyntaxException(code, new String(s, from, to - from, StandardCharsets.UTF_8));
    }

    private Node newRegexp(int op) {
        if (free > 0) {
            free--;
        } else {
            numRegexp++;
        }
        return new Node(op);
    }

    private void reuse(Node re) {
        if (height != null) {
            height.remove(re);
        }
        free++;
    }

    private void checkLimits(Node re) {
        if (numRunes > MAX_RUNES) {
            throw new LimitException(ERR_LARGE);
        }
        checkSize(re);
        checkHeight(re);
    }

    private void checkSize(Node re) {
        if (size == null) {
            if (repeats == 0) {
                repeats = 1;
            }
            if (re.op == OP_REPEAT) {
                int n = re.max;
                if (n == -1) {
                    n = re.min;
                }
                if (n <= 0) {
                    n = 1;
                }
                if (n > MAX_SIZE / repeats) {
                    repeats = MAX_SIZE;
                } else {
                    repeats *= n;
                }
            }
            if (numRegexp < MAX_SIZE / repeats) {
                return;
            }
            size = new IdentityHashMap<>();
            for (Node n : stack) {
                checkSize(n);
            }
        }
        if (calcSize(re, true) > MAX_SIZE) {
            throw new LimitException(ERR_LARGE);
        }
    }

    private long calcSize(Node re, boolean force) {
        if (!force) {
            Long cached = size.get(re);
            if (cached != null) {
                return cached;
            }
        }
        long sz = 0;
        switch (re.op) {
            case OP_LITERAL -> sz = re.rune.size();
            case OP_CAPTURE, OP_STAR -> sz = 2 + calcSize(re.sub.get(0), false);
            case OP_PLUS, OP_QUEST -> sz = 1 + calcSize(re.sub.get(0), false);
            case OP_CONCAT -> {
                for (Node sub : re.sub) {
                    sz += calcSize(sub, false);
                }
            }
            case OP_ALTERNATE -> {
                for (Node sub : re.sub) {
                    sz += calcSize(sub, false);
                }
                if (re.sub.size() > 1) {
                    sz += re.sub.size() - 1;
                }
            }
            case OP_REPEAT -> {
                long sub = calcSize(re.sub.get(0), false);
                if (re.max == -1) {
                    sz = re.min == 0 ? 2 + sub : 1 + (long) re.min * sub;
                } else {
                    sz = (long) re.max * sub + (re.max - re.min);
                }
            }
            default -> {
            }
        }
        sz = Math.max(1, sz);
        size.put(re, sz);
        return sz;
    }

    private void checkHeight(Node re) {
        if (numRegexp < MAX_HEIGHT) {
            return;
        }
        if (height == null) {
            height = new IdentityHashMap<>();
            for (Node n : stack) {
                checkHeight(n);
            }
        }
        if (calcHeight(re, true) > MAX_HEIGHT) {
            throw new LimitException(ERR_NESTING_DEPTH);
        }
    }

    private int calcHeight(Node re, boolean force) {
        if (!force) {
            Integer h = height.get(re);
            if (h != null) {
                return h;
            }
        }
        int h = 1;
        for (Node sub : re.sub) {
            h = Math.max(h, 1 + calcHeight(sub, false));
        }
        height.put(re, h);
        return h;
    }

    private Node push(Node re) {
        numRunes += re.rune.size();
        RuneList r = re.rune;
        if (re.op == OP_CHAR_CLASS && r.size() == 2 && r.get(0) == r.get(1)) {
            if (maybeConcat(r.get(0), flags & ~FOLD_CASE)) {
                return null;
            }
            re.op = OP_LITERAL;
            r.truncate(1);
            re.flags = flags & ~FOLD_CASE;
        } else if (re.op == OP_CHAR_CLASS && r.size() == 4 && r.get(0) == r.get(1) && r.get(2) == r.get(3)
                && UnicodeTables.simpleFold(r.get(0)) == r.get(2) && UnicodeTables.simpleFold(r.get(2)) == r.get(0)
                || re.op == OP_CHAR_CLASS && r.size() == 2 && r.get(0) + 1 == r.get(1)
                && UnicodeTables.simpleFold(r.get(0)) == r.get(1) && UnicodeTables.simpleFold(r.get(1)) == r.get(0)) {
            if (maybeConcat(r.get(0), flags | FOLD_CASE)) {
                return null;
            }
            re.op = OP_LITERAL;
            r.truncate(1);
            re.flags = flags | FOLD_CASE;
        } else {
            maybeConcat(-1, 0);
        }
        stack.add(re);
        checkLimits(re);
        return re;
    }

    private boolean maybeConcat(int r, int fl) {
        int n = stack.size();
        if (n < 2) {
            return false;
        }
        Node re1 = stack.get(n - 1);
        Node re2 = stack.get(n - 2);
        if (re1.op != OP_LITERAL || re2.op != OP_LITERAL || (re1.flags & FOLD_CASE) != (re2.flags & FOLD_CASE)) {
            return false;
        }
        re2.rune.addAll(re1.rune);
        if (r >= 0) {
            re1.rune = new RuneList(r);
            re1.flags = fl;
            return true;
        }
        stack.remove(n - 1);
        reuse(re1);
        return false;
    }

    private void literal(int r) {
        Node re = newRegexp(OP_LITERAL);
        re.flags = flags;
        if ((flags & FOLD_CASE) != 0) {
            r = minFoldRune(r);
        }
        re.rune = new RuneList(r);
        push(re);
    }

    private static int minFoldRune(int r) {
        if (r < CharClass.MIN_FOLD || r > CharClass.MAX_FOLD) {
            return r;
        }
        int m = r;
        int r0 = r;
        for (r = UnicodeTables.simpleFold(r); r != r0; r = UnicodeTables.simpleFold(r)) {
            m = Math.min(m, r);
        }
        return m;
    }

    private Node op(int op) {
        Node re = newRegexp(op);
        re.flags = flags;
        return push(re);
    }

    private int repeat(int op, int min, int max, int before, int after, int lastRepeat)
            throws GoRegexp.SyntaxException {
        int fl = flags;
        if ((flags & PERL_X) != 0) {
            if (after < end && s[after] == '?') {
                after++;
                fl ^= NON_GREEDY;
            }
            if (lastRepeat >= 0) {
                throw error(ERR_INVALID_REPEAT_OP, lastRepeat, after);
            }
        }
        int n = stack.size();
        if (n == 0) {
            throw error(ERR_MISSING_REPEAT_ARGUMENT, before, after);
        }
        Node sub = stack.get(n - 1);
        if (sub.op >= OP_PSEUDO) {
            throw error(ERR_MISSING_REPEAT_ARGUMENT, before, after);
        }
        Node re = newRegexp(op);
        re.min = min;
        re.max = max;
        re.flags = fl;
        re.sub.add(sub);
        stack.set(n - 1, re);
        checkLimits(re);
        if (op == OP_REPEAT && (min >= 2 || max >= 2) && !repeatIsValid(re, 1000)) {
            throw error(ERR_INVALID_REPEAT_SIZE, before, after);
        }
        return after;
    }

    private static boolean repeatIsValid(Node re, int n) {
        if (re.op == OP_REPEAT) {
            int m = re.max;
            if (m == 0) {
                return true;
            }
            if (m < 0) {
                m = re.min;
            }
            if (m > n) {
                return false;
            }
            if (m > 0) {
                n /= m;
            }
        }
        for (Node sub : re.sub) {
            if (!repeatIsValid(sub, n)) {
                return false;
            }
        }
        return true;
    }

    private List<Node> popOperands() {
        int i = stack.size();
        while (i > 0 && stack.get(i - 1).op < OP_PSEUDO) {
            i--;
        }
        List<Node> subs = new ArrayList<>(stack.subList(i, stack.size()));
        stack.subList(i, stack.size()).clear();
        return subs;
    }

    private Node concat() {
        maybeConcat(-1, 0);
        List<Node> subs = popOperands();
        if (subs.isEmpty()) {
            return push(newRegexp(OP_EMPTY_MATCH));
        }
        return push(collapse(subs, OP_CONCAT));
    }

    private Node alternate() {
        List<Node> subs = popOperands();
        if (!subs.isEmpty()) {
            cleanAlt(subs.get(subs.size() - 1));
        }
        if (subs.isEmpty()) {
            return push(newRegexp(OP_NO_MATCH));
        }
        return push(collapse(subs, OP_ALTERNATE));
    }

    private static void cleanAlt(Node re) {
        if (re.op == OP_CHAR_CLASS) {
            CharClass.clean(re.rune);
            RuneList r = re.rune;
            if (r.size() == 2 && r.get(0) == 0 && r.get(1) == Utf8.MAX_RUNE) {
                re.rune = new RuneList();
                re.op = OP_ANY_CHAR;
                return;
            }
            if (r.size() == 4 && r.get(0) == 0 && r.get(1) == '\n' - 1 && r.get(2) == '\n' + 1
                    && r.get(3) == Utf8.MAX_RUNE) {
                re.rune = new RuneList();
                re.op = OP_ANY_CHAR_NOT_NL;
            }
        }
    }

    private Node collapse(List<Node> subs, int op) {
        if (subs.size() == 1) {
            return subs.get(0);
        }
        Node re = newRegexp(op);
        for (Node sub : subs) {
            if (sub.op == op) {
                re.sub.addAll(sub.sub);
                reuse(sub);
            } else {
                re.sub.add(sub);
            }
        }
        if (op == OP_ALTERNATE) {
            re.sub = factor(re.sub);
            if (re.sub.size() == 1) {
                Node old = re;
                re = re.sub.get(0);
                reuse(old);
            }
        }
        return re;
    }

    private List<Node> factor(List<Node> sub) {
        if (sub.size() < 2) {
            return sub;
        }
        sub = new ArrayList<>(sub);

        RuneList str = null;
        int strLen = 0;
        int strflags = 0;
        int start = 0;
        List<Node> out = new ArrayList<>();
        for (int i = 0; i <= sub.size(); i++) {
            RuneList istr = null;
            int iflags = 0;
            if (i < sub.size()) {
                Node lead = leadingString(sub.get(i));
                if (lead != null) {
                    istr = lead.rune;
                    iflags = lead.flags & FOLD_CASE;
                }
                if (iflags == strflags) {
                    int same = 0;
                    int ilen = istr == null ? 0 : istr.size();
                    while (same < strLen && same < ilen && str.get(same) == istr.get(same)) {
                        same++;
                    }
                    if (same > 0) {
                        strLen = same;
                        continue;
                    }
                }
            }
            if (i == start + 1) {
                out.add(sub.get(start));
            } else if (i > start + 1) {
                Node prefix = newRegexp(OP_LITERAL);
                prefix.flags = strflags;
                prefix.rune = new RuneList(Arrays.copyOf(str.a, strLen));
                for (int j = start; j < i; j++) {
                    sub.set(j, removeLeadingString(sub.get(j), strLen));
                    checkLimits(sub.get(j));
                }
                Node suffix = collapse(new ArrayList<>(sub.subList(start, i)), OP_ALTERNATE);
                Node re = newRegexp(OP_CONCAT);
                re.sub.add(prefix);
                re.sub.add(suffix);
                out.add(re);
            }
            start = i;
            str = istr;
            strLen = istr == null ? 0 : istr.size();
            strflags = iflags;
        }
        sub = out;

        start = 0;
        out = new ArrayList<>();
        Node first = null;
        for (int i = 0; i <= sub.size(); i++) {
            Node ifirst = null;
            if (i < sub.size()) {
                ifirst = leadingRegexp(sub.get(i));
                if (first != null && first.equal(ifirst) && (isCharClass(first)
                        || first.op == OP_REPEAT && first.min == first.max && isCharClass(first.sub.get(0)))) {
                    continue;
                }
            }
            if (i == start + 1) {
                out.add(sub.get(start));
            } else if (i > start + 1) {
                Node prefix = first;
                for (int j = start; j < i; j++) {
                    boolean reuse = j != start;
                    sub.set(j, removeLeadingRegexp(sub.get(j), reuse));
                    checkLimits(sub.get(j));
                }
                Node suffix = collapse(new ArrayList<>(sub.subList(start, i)), OP_ALTERNATE);
                Node re = newRegexp(OP_CONCAT);
                re.sub.add(prefix);
                re.sub.add(suffix);
                out.add(re);
            }
            start = i;
            first = ifirst;
        }
        sub = out;

        start = 0;
        out = new ArrayList<>();
        for (int i = 0; i <= sub.size(); i++) {
            if (i < sub.size() && isCharClass(sub.get(i))) {
                continue;
            }
            if (i == start + 1) {
                out.add(sub.get(start));
            } else if (i > start + 1) {
                int max = start;
                for (int j = start + 1; j < i; j++) {
                    Node a = sub.get(max);
                    Node b = sub.get(j);
                    if (a.op < b.op || a.op == b.op && a.rune.size() < b.rune.size()) {
                        max = j;
                    }
                }
                Node tmp = sub.get(start);
                sub.set(start, sub.get(max));
                sub.set(max, tmp);
                for (int j = start + 1; j < i; j++) {
                    mergeCharClass(sub.get(start), sub.get(j));
                    reuse(sub.get(j));
                }
                cleanAlt(sub.get(start));
                out.add(sub.get(start));
            }
            if (i < sub.size()) {
                out.add(sub.get(i));
            }
            start = i + 1;
        }
        sub = out;

        out = new ArrayList<>();
        for (int i = 0; i < sub.size(); i++) {
            if (i + 1 < sub.size() && sub.get(i).op == OP_EMPTY_MATCH && sub.get(i + 1).op == OP_EMPTY_MATCH) {
                continue;
            }
            out.add(sub.get(i));
        }
        return out;
    }

    /** The literal that starts {@code re}, or null. */
    private static Node leadingString(Node re) {
        if (re.op == OP_CONCAT && !re.sub.isEmpty()) {
            re = re.sub.get(0);
        }
        return re.op == OP_LITERAL ? re : null;
    }

    private Node removeLeadingString(Node re, int n) {
        if (re.op == OP_CONCAT && !re.sub.isEmpty()) {
            Node sub = removeLeadingString(re.sub.get(0), n);
            re.sub.set(0, sub);
            if (sub.op == OP_EMPTY_MATCH) {
                reuse(sub);
                switch (re.sub.size()) {
                    case 0, 1 -> {
                        re.op = OP_EMPTY_MATCH;
                        re.sub = new ArrayList<>();
                    }
                    case 2 -> {
                        Node old = re;
                        re = re.sub.get(1);
                        reuse(old);
                    }
                    default -> re.sub.remove(0);
                }
            }
            return re;
        }
        if (re.op == OP_LITERAL) {
            RuneList r = re.rune;
            int len = r.size() - n;
            System.arraycopy(r.a, n, r.a, 0, len);
            r.truncate(len);
            if (len == 0) {
                re.op = OP_EMPTY_MATCH;
            }
        }
        return re;
    }

    private static Node leadingRegexp(Node re) {
        if (re.op == OP_EMPTY_MATCH) {
            return null;
        }
        if (re.op == OP_CONCAT && !re.sub.isEmpty()) {
            Node sub = re.sub.get(0);
            return sub.op == OP_EMPTY_MATCH ? null : sub;
        }
        return re;
    }

    private Node removeLeadingRegexp(Node re, boolean reuse) {
        if (re.op == OP_CONCAT && !re.sub.isEmpty()) {
            if (reuse) {
                reuse(re.sub.get(0));
            }
            re.sub.remove(0);
            switch (re.sub.size()) {
                case 0 -> {
                    re.op = OP_EMPTY_MATCH;
                    re.sub = new ArrayList<>();
                }
                case 1 -> {
                    Node old = re;
                    re = re.sub.get(0);
                    reuse(old);
                }
                default -> {
                }
            }
            return re;
        }
        if (reuse) {
            reuse(re);
        }
        return newRegexp(OP_EMPTY_MATCH);
    }

    private static boolean isCharClass(Node re) {
        return re.op == OP_LITERAL && re.rune.size() == 1 || re.op == OP_CHAR_CLASS || re.op == OP_ANY_CHAR_NOT_NL
                || re.op == OP_ANY_CHAR;
    }

    private static boolean matchRune(Node re, int r) {
        switch (re.op) {
            case OP_LITERAL:
                return re.rune.size() == 1 && re.rune.get(0) == r;
            case OP_CHAR_CLASS:
                for (int i = 0; i < re.rune.size(); i += 2) {
                    if (re.rune.get(i) <= r && r <= re.rune.get(i + 1)) {
                        return true;
                    }
                }
                return false;
            case OP_ANY_CHAR_NOT_NL:
                return r != '\n';
            case OP_ANY_CHAR:
                return true;
            default:
                return false;
        }
    }

    private void parseVerticalBar() {
        concat();
        if (!swapVerticalBar()) {
            op(OP_VERTICAL_BAR);
        }
    }

    private static void mergeCharClass(Node dst, Node src) {
        switch (dst.op) {
            case OP_ANY_CHAR_NOT_NL -> {
                if (matchRune(src, '\n')) {
                    dst.op = OP_ANY_CHAR;
                }
            }
            case OP_CHAR_CLASS -> {
                if (src.op == OP_LITERAL) {
                    CharClass.appendLiteral(dst.rune, src.rune.get(0), src.flags);
                } else {
                    CharClass.appendClass(dst.rune, src.rune.a, src.rune.size());
                }
            }
            case OP_LITERAL -> {
                if (src.rune.get(0) == dst.rune.get(0) && src.flags == dst.flags) {
                    break;
                }
                int r0 = dst.rune.get(0);
                dst.op = OP_CHAR_CLASS;
                dst.rune = new RuneList();
                CharClass.appendLiteral(dst.rune, r0, dst.flags);
                CharClass.appendLiteral(dst.rune, src.rune.get(0), src.flags);
            }
            default -> {
            }
        }
    }

    private boolean swapVerticalBar() {
        int n = stack.size();
        if (n >= 3 && stack.get(n - 2).op == OP_VERTICAL_BAR && isCharClass(stack.get(n - 1))
                && isCharClass(stack.get(n - 3))) {
            Node re1 = stack.get(n - 1);
            Node re3 = stack.get(n - 3);
            if (re1.op > re3.op) {
                Node t = re1;
                re1 = re3;
                re3 = t;
                stack.set(n - 3, re3);
            }
            mergeCharClass(re3, re1);
            reuse(re1);
            stack.remove(n - 1);
            return true;
        }
        if (n >= 2) {
            Node re1 = stack.get(n - 1);
            Node re2 = stack.get(n - 2);
            if (re2.op == OP_VERTICAL_BAR) {
                if (n >= 3) {
                    cleanAlt(stack.get(n - 3));
                }
                stack.set(n - 2, re1);
                stack.set(n - 1, re2);
                return true;
            }
        }
        return false;
    }

    private void parseRightParen() throws GoRegexp.SyntaxException {
        concat();
        if (swapVerticalBar()) {
            stack.remove(stack.size() - 1);
        }
        alternate();
        int n = stack.size();
        if (n < 2) {
            throw error(ERR_UNEXPECTED_PAREN, 0, end);
        }
        Node re1 = stack.get(n - 1);
        Node re2 = stack.get(n - 2);
        stack.subList(n - 2, n).clear();
        if (re2.op != OP_LEFT_PAREN) {
            throw error(ERR_UNEXPECTED_PAREN, 0, end);
        }
        flags = re2.flags;
        if (re2.cap == 0) {
            push(re1);
        } else {
            re2.op = OP_CAPTURE;
            re2.sub = new ArrayList<>();
            re2.sub.add(re1);
            push(re2);
        }
    }

    private Node run() throws GoRegexp.SyntaxException {
        int t = 0;
        int lastRepeat = -1;
        while (t < end) {
            int repeat = -1;
            switch (s[t]) {
                case '(' -> {
                    if ((flags & PERL_X) != 0 && t + 1 < end && s[t + 1] == '?') {
                        t = parsePerlFlags(t);
                    } else {
                        numCap++;
                        op(OP_LEFT_PAREN).cap = numCap;
                        t++;
                    }
                }
                case '|' -> {
                    parseVerticalBar();
                    t++;
                }
                case ')' -> {
                    parseRightParen();
                    t++;
                }
                case '^' -> {
                    op((flags & ONE_LINE) != 0 ? OP_BEGIN_TEXT : OP_BEGIN_LINE);
                    t++;
                }
                case '$' -> {
                    if ((flags & ONE_LINE) != 0) {
                        op(OP_END_TEXT).flags |= WAS_DOLLAR;
                    } else {
                        op(OP_END_LINE);
                    }
                    t++;
                }
                case '.' -> {
                    op((flags & DOT_NL) != 0 ? OP_ANY_CHAR : OP_ANY_CHAR_NOT_NL);
                    t++;
                }
                case '[' -> t = parseClass(t);
                case '*', '+', '?' -> {
                    int op = s[t] == '*' ? OP_STAR : s[t] == '+' ? OP_PLUS : OP_QUEST;
                    int before = t;
                    t = repeat(op, 0, 0, before, t + 1, lastRepeat);
                    repeat = before;
                }
                case '{' -> {
                    int before = t;
                    int[] rep = parseRepeat(t);
                    if (rep == null) {
                        literal('{');
                        t++;
                        break;
                    }
                    int min = rep[0];
                    int max = rep[1];
                    int after = rep[2];
                    if (min < 0 || min > 1000 || max > 1000 || max >= 0 && min > max) {
                        throw error(ERR_INVALID_REPEAT_SIZE, before, after);
                    }
                    t = repeat(OP_REPEAT, min, max, before, after, lastRepeat);
                    repeat = before;
                }
                case '\\' -> t = parseBackslash(t);
                default -> {
                    int d = nextRune(t, end);
                    literal(d >> 3);
                    t += d & 7;
                }
            }
            lastRepeat = repeat;
        }
        concat();
        if (swapVerticalBar()) {
            stack.remove(stack.size() - 1);
        }
        alternate();
        if (stack.size() != 1) {
            throw error(ERR_MISSING_PAREN, 0, end);
        }
        return stack.get(0);
    }

    private int parseBackslash(int t) throws GoRegexp.SyntaxException {
        if ((flags & PERL_X) != 0 && t + 1 < end) {
            switch (s[t + 1]) {
                case 'A':
                    op(OP_BEGIN_TEXT);
                    return t + 2;
                case 'b':
                    op(OP_WORD_BOUNDARY);
                    return t + 2;
                case 'B':
                    op(OP_NO_WORD_BOUNDARY);
                    return t + 2;
                case 'C':
                    throw error(ERR_INVALID_ESCAPE, t, t + 2);
                case 'Q': {
                    int litEnd = indexOf(t + 2, (byte) '\\', (byte) 'E');
                    int rest = litEnd < 0 ? end : litEnd + 2;
                    if (litEnd < 0) {
                        litEnd = end;
                    }
                    for (int lit = t + 2; lit < litEnd;) {
                        int d = nextRune(lit, litEnd);
                        literal(d >> 3);
                        lit += d & 7;
                    }
                    return rest;
                }
                case 'z':
                    op(OP_END_TEXT);
                    return t + 2;
                default:
                    break;
            }
        }
        Node re = newRegexp(OP_CHAR_CLASS);
        re.flags = flags;
        if (t + 1 < end && (s[t + 1] == 'p' || s[t + 1] == 'P')) {
            int rest = parseUnicodeClass(t, re.rune);
            if (rest >= 0) {
                push(re);
                return rest;
            }
        }
        int rest = parsePerlClassEscape(t, re.rune);
        if (rest >= 0) {
            push(re);
            return rest;
        }
        reuse(re);
        long e = parseEscape(t);
        literal((int) (e >> 32));
        return (int) e;
    }

    private int indexOf(int from, byte a, byte b) {
        for (int i = from; i + 1 < end; i++) {
            if (s[i] == a && s[i + 1] == b) {
                return i;
            }
        }
        return -1;
    }

    /** The {min, max, rest} of a {n}, {n,} or {n,m} at {@code t}, or null when it is a literal brace. */
    private int[] parseRepeat(int t) {
        if (t >= end || s[t] != '{') {
            return null;
        }
        t++;
        long r = parseInt(t);
        if (r == Long.MIN_VALUE) {
            return null;
        }
        int min = (int) (r >> 32);
        t = (int) r;
        int max;
        if (t >= end) {
            return null;
        }
        if (s[t] != ',') {
            max = min;
        } else {
            t++;
            if (t >= end) {
                return null;
            }
            if (s[t] == '}') {
                max = -1;
            } else {
                r = parseInt(t);
                if (r == Long.MIN_VALUE) {
                    return null;
                }
                max = (int) (r >> 32);
                t = (int) r;
                if (max < 0) {
                    min = -1;
                }
            }
        }
        if (t >= end || s[t] != '}') {
            return null;
        }
        return new int[] {min, max, t + 1};
    }

    /** The number at {@code t} and the position after it packed as {@code n << 32 | rest}. */
    private long parseInt(int t) {
        if (t >= end || s[t] < '0' || s[t] > '9') {
            return Long.MIN_VALUE;
        }
        if (t + 1 < end && s[t] == '0' && s[t + 1] >= '0' && s[t + 1] <= '9') {
            return Long.MIN_VALUE;
        }
        int start = t;
        while (t < end && s[t] >= '0' && s[t] <= '9') {
            t++;
        }
        int n = 0;
        for (int i = start; i < t; i++) {
            if (n >= 100_000_000) {
                n = -1;
                break;
            }
            n = n * 10 + s[i] - '0';
        }
        return (long) n << 32 | t;
    }

    private int parsePerlFlags(int t) throws GoRegexp.SyntaxException {
        int start = t;
        int len = end - t;
        boolean startsWithP = len > 4 && s[t + 2] == 'P' && s[t + 3] == '<';
        boolean startsWithName = len > 3 && s[t + 2] == '<';
        if (startsWithP || startsWithName) {
            int exprStart = startsWithName ? 3 : 4;
            int close = -1;
            for (int i = t; i < end; i++) {
                if (s[i] == '>') {
                    close = i;
                    break;
                }
            }
            if (close < 0) {
                checkUTF8(t, end);
                throw error(ERR_INVALID_NAMED_CAPTURE, start, end);
            }
            checkUTF8(t + exprStart, close);
            if (!isValidCaptureName(t + exprStart, close)) {
                throw error(ERR_INVALID_NAMED_CAPTURE, t, close + 1);
            }
            numCap++;
            Node re = op(OP_LEFT_PAREN);
            re.cap = numCap;
            re.name = new String(s, t + exprStart, close - t - exprStart, StandardCharsets.UTF_8);
            return close + 1;
        }
        t += 2;
        int fl = flags;
        int sign = 1;
        boolean sawFlag = false;
        loop:
        while (t < end) {
            int d = nextRune(t, end);
            int c = d >> 3;
            t += d & 7;
            switch (c) {
                case 'i' -> {
                    fl |= FOLD_CASE;
                    sawFlag = true;
                }
                case 'm' -> {
                    fl &= ~ONE_LINE;
                    sawFlag = true;
                }
                case 's' -> {
                    fl |= DOT_NL;
                    sawFlag = true;
                }
                case 'U' -> {
                    fl |= NON_GREEDY;
                    sawFlag = true;
                }
                case '-' -> {
                    if (sign < 0) {
                        break loop;
                    }
                    sign = -1;
                    fl = ~fl;
                    sawFlag = false;
                }
                case ':', ')' -> {
                    if (sign < 0) {
                        if (!sawFlag) {
                            break loop;
                        }
                        fl = ~fl;
                    }
                    if (c == ':') {
                        op(OP_LEFT_PAREN);
                    }
                    flags = fl;
                    return t;
                }
                default -> {
                    break loop;
                }
            }
        }
        throw error(ERR_INVALID_PERL_OP, start, t);
    }

    private boolean isValidCaptureName(int from, int to) {
        if (from == to) {
            return false;
        }
        for (int i = from; i < to; i++) {
            int c = s[i];
            if (c != '_' && !isalnum(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The rune of the escape at {@code t} and the position after it packed as {@code r << 32 | rest}.
     *
     * @throws GoRegexp.SyntaxException when the escape sequence is not valid
     */
    private long parseEscape(int start) throws GoRegexp.SyntaxException {
        int t = start + 1;
        if (t >= end) {
            throw new GoRegexp.SyntaxException(ERR_TRAILING_BACKSLASH, "");
        }
        int d = nextRune(t, end);
        int c = d >> 3;
        t += d & 7;
        escape:
        switch (c) {
            case '1', '2', '3', '4', '5', '6', '7', '0': {
                if (c != '0' && (t >= end || s[t] < '0' || s[t] > '7')) {
                    break;
                }
                int r = c - '0';
                for (int i = 1; i < 3; i++) {
                    if (t >= end || s[t] < '0' || s[t] > '7') {
                        break;
                    }
                    r = r * 8 + s[t] - '0';
                    t++;
                }
                return pack(r, t);
            }
            case 'x': {
                if (t >= end) {
                    break;
                }
                d = nextRune(t, end);
                c = d >> 3;
                t += d & 7;
                if (c == '{') {
                    int nhex = 0;
                    int r = 0;
                    while (true) {
                        if (t >= end) {
                            break escape;
                        }
                        d = nextRune(t, end);
                        c = d >> 3;
                        t += d & 7;
                        if (c == '}') {
                            break;
                        }
                        int v = unhex(c);
                        if (v < 0) {
                            break escape;
                        }
                        r = r * 16 + v;
                        if (r > Utf8.MAX_RUNE) {
                            break escape;
                        }
                        nhex++;
                    }
                    if (nhex == 0) {
                        break;
                    }
                    return pack(r, t);
                }
                int x = unhex(c);
                d = nextRune(t, end);
                c = d >> 3;
                t += d & 7;
                int y = unhex(c);
                if (x < 0 || y < 0) {
                    break;
                }
                return pack(x * 16 + y, t);
            }
            case 'a':
                return pack(7, t);
            case 'f':
                return pack('\f', t);
            case 'n':
                return pack('\n', t);
            case 'r':
                return pack('\r', t);
            case 't':
                return pack('\t', t);
            case 'v':
                return pack(0x0B, t);
            default:
                if (c < Utf8.RUNE_SELF && !isalnum(c)) {
                    return pack(c, t);
                }
                break;
        }
        throw error(ERR_INVALID_ESCAPE, start, t);
    }

    private static long pack(int r, int rest) {
        return (long) r << 32 | (rest & 0xFFFFFFFFL);
    }

    private long parseClassChar(int t, int wholeClass) throws GoRegexp.SyntaxException {
        if (t >= end) {
            throw error(ERR_MISSING_BRACKET, wholeClass, end);
        }
        if (s[t] == '\\') {
            return parseEscape(t);
        }
        int d = nextRune(t, end);
        return pack(d >> 3, t + (d & 7));
    }

    private int parsePerlClassEscape(int t, RuneList r) {
        if ((flags & PERL_X) == 0 || end - t < 2 || s[t] != '\\') {
            return -1;
        }
        Group g = PERL_GROUPS.get(new String(s, t, 2, StandardCharsets.ISO_8859_1));
        if (g == null) {
            return -1;
        }
        appendGroup(r, g);
        return t + 2;
    }

    private int parseNamedClass(int t, RuneList r) throws GoRegexp.SyntaxException {
        if (end - t < 2 || s[t] != '[' || s[t + 1] != ':') {
            return -1;
        }
        int i = -1;
        for (int j = t + 2; j + 1 < end; j++) {
            if (s[j] == ':' && s[j + 1] == ']') {
                i = j;
                break;
            }
        }
        if (i < 0) {
            return -1;
        }
        int nameEnd = i + 2;
        Group g = POSIX_GROUPS.get(new String(s, t, nameEnd - t, StandardCharsets.ISO_8859_1));
        if (g == null) {
            throw error(ERR_INVALID_CHAR_RANGE, t, nameEnd);
        }
        appendGroup(r, g);
        return nameEnd;
    }

    private void appendGroup(RuneList r, Group g) {
        if ((flags & FOLD_CASE) == 0) {
            if (g.sign < 0) {
                CharClass.appendNegatedClass(r, g.cls, g.cls.length);
            } else {
                CharClass.appendClass(r, g.cls, g.cls.length);
            }
        } else {
            tmpClass.truncate(0);
            CharClass.appendFoldedClass(tmpClass, g.cls, g.cls.length);
            CharClass.clean(tmpClass);
            if (g.sign < 0) {
                CharClass.appendNegatedClass(r, tmpClass.a, tmpClass.n);
            } else {
                CharClass.appendClass(r, tmpClass.a, tmpClass.n);
            }
        }
    }

    static String canonicalName(String name) {
        StringBuilder b = new StringBuilder(name.length());
        boolean first = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || c == ' ') {
                continue;
            } else if (first) {
                if (c >= 'a' && c <= 'z') {
                    c -= 'a' - 'A';
                }
                first = false;
            } else if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            b.append(c);
        }
        return b.toString();
    }

    /** A Unicode table for \p: its ranges, the ranges to add under (?i) (or null) and its sign. */
    private record Table(int[] tab, int[] fold, int sign) {
    }

    private static Table unicodeTable(String name) {
        name = canonicalName(name);
        switch (name) {
            case "Any":
                return new Table(CharClass.ANY_TABLE, CharClass.ANY_TABLE, 1);
            case "Assigned":
                return new Table(UnicodeTables.CATEGORIES.get("Cn"), UnicodeTables.CATEGORIES.get("Cn"), -1);
            case "Ascii":
                return new Table(CharClass.ASCII_TABLE, CharClass.ASCII_FOLD_TABLE, 1);
            case "Lc":
                return new Table(UnicodeTables.CATEGORIES.get("LC"), UnicodeTables.FOLD_CATEGORY.get("LC"), 1);
            default:
                break;
        }
        int[] t = UnicodeTables.CATEGORIES.get(name);
        if (t != null) {
            return new Table(t, UnicodeTables.FOLD_CATEGORY.get(name), 1);
        }
        t = UnicodeTables.SCRIPTS.get(name);
        if (t != null) {
            return new Table(t, UnicodeTables.FOLD_SCRIPT.get(name), 1);
        }
        String actual = CATEGORY_ALIASES.get(name);
        if (actual != null) {
            return new Table(UnicodeTables.CATEGORIES.get(actual), UnicodeTables.FOLD_CATEGORY.get(actual), 1);
        }
        actual = SCRIPT_ALIASES.get(name);
        if (actual != null) {
            return new Table(UnicodeTables.SCRIPTS.get(actual), UnicodeTables.FOLD_SCRIPT.get(actual), 1);
        }
        return null;
    }

    private int parseUnicodeClass(int start, RuneList r) throws GoRegexp.SyntaxException {
        if ((flags & UNICODE_GROUPS) == 0 || end - start < 2 || s[start] != '\\'
                || s[start + 1] != 'p' && s[start + 1] != 'P') {
            return -1;
        }
        int sign = s[start + 1] == 'P' ? -1 : 1;
        int t = start + 2;
        int d = nextRune(t, end);
        int c = d >> 3;
        t += d & 7;
        int seqEnd;
        int nameStart;
        int nameEnd;
        if (c != '{') {
            seqEnd = t;
            nameStart = start + 2;
            nameEnd = t;
        } else {
            int close = -1;
            for (int i = start; i < end; i++) {
                if (s[i] == '}') {
                    close = i;
                    break;
                }
            }
            if (close < 0) {
                checkUTF8(start, end);
                throw error(ERR_INVALID_CHAR_RANGE, start, end);
            }
            seqEnd = close + 1;
            t = close + 1;
            nameStart = start + 3;
            nameEnd = close;
            checkUTF8(nameStart, nameEnd);
        }
        if (nameEnd > nameStart && s[nameStart] == '^') {
            sign = -sign;
            nameStart++;
        }
        Table tab = unicodeTable(new String(s, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8));
        if (tab == null) {
            throw error(ERR_INVALID_CHAR_RANGE, start, seqEnd);
        }
        if (tab.sign < 0) {
            sign = -sign;
        }
        if ((flags & FOLD_CASE) == 0 || tab.fold == null) {
            if (sign > 0) {
                CharClass.appendClass(r, tab.tab, tab.tab.length);
            } else {
                CharClass.appendNegatedClass(r, tab.tab, tab.tab.length);
            }
        } else {
            tmpClass.truncate(0);
            CharClass.appendClass(tmpClass, tab.tab, tab.tab.length);
            CharClass.appendClass(tmpClass, tab.fold, tab.fold.length);
            CharClass.clean(tmpClass);
            if (sign > 0) {
                CharClass.appendClass(r, tmpClass.a, tmpClass.n);
            } else {
                CharClass.appendNegatedClass(r, tmpClass.a, tmpClass.n);
            }
        }
        return t;
    }

    private int parseClass(int start) throws GoRegexp.SyntaxException {
        int t = start + 1;
        Node re = newRegexp(OP_CHAR_CLASS);
        re.flags = flags;
        int sign = 1;
        if (t < end && s[t] == '^') {
            sign = -1;
            t++;
            if ((flags & CLASS_NL) == 0) {
                re.rune.add('\n');
                re.rune.add('\n');
            }
        }
        RuneList cls = re.rune;
        boolean first = true;
        while (t >= end || s[t] != ']' || first) {
            if (t < end && s[t] == '-' && (flags & PERL_X) == 0 && !first && (t + 1 == end || s[t + 1] != ']')) {
                int w = Utf8.decode(s, t + 1, end) & 7;
                throw error(ERR_INVALID_CHAR_RANGE, t, t + 1 + w);
            }
            first = false;
            if (end - t > 2 && s[t] == '[' && s[t + 1] == ':') {
                int nt = parseNamedClass(t, cls);
                if (nt >= 0) {
                    t = nt;
                    continue;
                }
            }
            int nt = parseUnicodeClass(t, cls);
            if (nt >= 0) {
                t = nt;
                continue;
            }
            nt = parsePerlClassEscape(t, cls);
            if (nt >= 0) {
                t = nt;
                continue;
            }
            int rng = t;
            long v = parseClassChar(t, start);
            int lo = (int) (v >> 32);
            t = (int) v;
            int hi = lo;
            if (end - t >= 2 && s[t] == '-' && s[t + 1] != ']') {
                t++;
                v = parseClassChar(t, start);
                hi = (int) (v >> 32);
                t = (int) v;
                if (hi < lo) {
                    throw error(ERR_INVALID_CHAR_RANGE, rng, t);
                }
            }
            if ((flags & FOLD_CASE) == 0) {
                CharClass.appendRange(cls, lo, hi);
            } else {
                CharClass.appendFoldedRange(cls, lo, hi);
            }
        }
        t++;
        CharClass.clean(cls);
        if (sign < 0) {
            CharClass.negate(cls);
        }
        push(re);
        return t;
    }

    private void checkUTF8(int from, int to) throws GoRegexp.SyntaxException {
        while (from < to) {
            int d = Utf8.decode(s, from, to);
            if ((d >> 3) == Utf8.RUNE_ERROR && (d & 7) == 1) {
                throw error(ERR_INVALID_UTF8, from, to);
            }
            from += d & 7;
        }
    }

    /**
     * The rune at {@code t} packed as {@code r << 3 | width}; invalid UTF-8 is an error.
     *
     * @throws GoRegexp.SyntaxException when the bytes at {@code t} are not valid UTF-8
     */
    private int nextRune(int t, int limit) throws GoRegexp.SyntaxException {
        int d = Utf8.decode(s, t, limit);
        if ((d >> 3) == Utf8.RUNE_ERROR && (d & 7) == 1) {
            throw error(ERR_INVALID_UTF8, t, limit);
        }
        return d;
    }

    private static boolean isalnum(int c) {
        return c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }

    private static int unhex(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
