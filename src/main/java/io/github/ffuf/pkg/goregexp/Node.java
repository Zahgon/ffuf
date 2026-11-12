package io.github.ffuf.pkg.goregexp;

import java.util.ArrayList;
import java.util.List;

/** A node of the parsed regular expression syntax tree, with equality, capture counting and simplification. */
final class Node {
    static final int OP_NO_MATCH = 1;
    static final int OP_EMPTY_MATCH = 2;
    static final int OP_LITERAL = 3;
    static final int OP_CHAR_CLASS = 4;
    static final int OP_ANY_CHAR_NOT_NL = 5;
    static final int OP_ANY_CHAR = 6;
    static final int OP_BEGIN_LINE = 7;
    static final int OP_END_LINE = 8;
    static final int OP_BEGIN_TEXT = 9;
    static final int OP_END_TEXT = 10;
    static final int OP_WORD_BOUNDARY = 11;
    static final int OP_NO_WORD_BOUNDARY = 12;
    static final int OP_CAPTURE = 13;
    static final int OP_STAR = 14;
    static final int OP_PLUS = 15;
    static final int OP_QUEST = 16;
    static final int OP_REPEAT = 17;
    static final int OP_CONCAT = 18;
    static final int OP_ALTERNATE = 19;
    static final int OP_PSEUDO = 128;
    static final int OP_LEFT_PAREN = OP_PSEUDO;
    static final int OP_VERTICAL_BAR = OP_PSEUDO + 1;

    static final int FOLD_CASE = 1;
    static final int CLASS_NL = 1 << 2;
    static final int DOT_NL = 1 << 3;
    static final int ONE_LINE = 1 << 4;
    static final int NON_GREEDY = 1 << 5;
    static final int PERL_X = 1 << 6;
    static final int UNICODE_GROUPS = 1 << 7;
    static final int WAS_DOLLAR = 1 << 8;
    static final int PERL = CLASS_NL | ONE_LINE | PERL_X | UNICODE_GROUPS;

    int op;
    int flags;
    List<Node> sub = new ArrayList<>();
    RuneList rune = new RuneList();
    int min;
    int max;
    int cap;
    String name = "";

    Node(int op) {
        this.op = op;
    }

    private Node copy() {
        Node n = new Node(op);
        n.flags = flags;
        n.sub = new ArrayList<>(sub);
        n.rune = new RuneList(rune.toArray());
        n.min = min;
        n.max = max;
        n.cap = cap;
        n.name = name;
        return n;
    }

    boolean equal(Node y) {
        if (y == null) {
            return false;
        }
        if (op != y.op) {
            return false;
        }
        switch (op) {
            case OP_END_TEXT:
                return (flags & WAS_DOLLAR) == (y.flags & WAS_DOLLAR);
            case OP_LITERAL:
            case OP_CHAR_CLASS:
                return (flags & FOLD_CASE) == (y.flags & FOLD_CASE) && rune.contentEquals(y.rune);
            case OP_ALTERNATE:
            case OP_CONCAT:
                if (sub.size() != y.sub.size()) {
                    return false;
                }
                for (int i = 0; i < sub.size(); i++) {
                    if (!sub.get(i).equal(y.sub.get(i))) {
                        return false;
                    }
                }
                return true;
            case OP_STAR:
            case OP_PLUS:
            case OP_QUEST:
                return (flags & NON_GREEDY) == (y.flags & NON_GREEDY) && sub.get(0).equal(y.sub.get(0));
            case OP_REPEAT:
                return (flags & NON_GREEDY) == (y.flags & NON_GREEDY) && min == y.min && max == y.max
                        && sub.get(0).equal(y.sub.get(0));
            case OP_CAPTURE:
                return cap == y.cap && name.equals(y.name) && sub.get(0).equal(y.sub.get(0));
            default:
                return true;
        }
    }

    int maxCap() {
        int m = op == OP_CAPTURE ? cap : 0;
        for (Node s : sub) {
            m = Math.max(m, s.maxCap());
        }
        return m;
    }

    /** Rewrites counted repetitions into stars, pluses and quests. */
    Node simplify() {
        switch (op) {
            case OP_CAPTURE, OP_CONCAT, OP_ALTERNATE -> {
                Node nre = this;
                for (int i = 0; i < sub.size(); i++) {
                    Node s = sub.get(i);
                    Node ns = s.simplify();
                    if (nre == this && ns != s) {
                        nre = copy();
                        nre.rune = new RuneList();
                        nre.sub = new ArrayList<>(sub.subList(0, i));
                    }
                    if (nre != this) {
                        nre.sub.add(ns);
                    }
                }
                return nre;
            }
            case OP_STAR, OP_PLUS, OP_QUEST -> {
                return simplify1(op, flags, sub.get(0).simplify(), this);
            }
            case OP_REPEAT -> {
                return simplifyRepeat();
            }
            default -> {
                return this;
            }
        }
    }

    private Node simplifyRepeat() {
        if (min == 0 && max == 0) {
            return new Node(OP_EMPTY_MATCH);
        }
        Node s = sub.get(0).simplify();
        if (max == -1) {
            if (min == 0) {
                return simplify1(OP_STAR, flags, s, null);
            }
            if (min == 1) {
                return simplify1(OP_PLUS, flags, s, null);
            }
            Node nre = new Node(OP_CONCAT);
            for (int i = 0; i < min - 1; i++) {
                nre.sub.add(s);
            }
            nre.sub.add(simplify1(OP_PLUS, flags, s, null));
            return nre;
        }
        if (min == 1 && max == 1) {
            return s;
        }
        Node prefix = null;
        if (min > 0) {
            prefix = new Node(OP_CONCAT);
            for (int i = 0; i < min; i++) {
                prefix.sub.add(s);
            }
        }
        if (max > min) {
            Node suffix = simplify1(OP_QUEST, flags, s, null);
            for (int i = min + 1; i < max; i++) {
                Node nre2 = new Node(OP_CONCAT);
                nre2.sub.add(s);
                nre2.sub.add(suffix);
                suffix = simplify1(OP_QUEST, flags, nre2, null);
            }
            if (prefix == null) {
                return suffix;
            }
            prefix.sub.add(suffix);
        }
        if (prefix != null) {
            return prefix;
        }
        return new Node(OP_NO_MATCH);
    }

    private static Node simplify1(int op, int flags, Node s, Node re) {
        if (s.op == OP_EMPTY_MATCH) {
            return s;
        }
        if (op == s.op && (flags & NON_GREEDY) == (s.flags & NON_GREEDY)) {
            return s;
        }
        if (re != null && re.op == op && (re.flags & NON_GREEDY) == (flags & NON_GREEDY) && s == re.sub.get(0)) {
            return re;
        }
        Node n = new Node(op);
        n.flags = flags;
        n.sub.add(s);
        return n;
    }
}
