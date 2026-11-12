package io.github.ffuf.pkg.goregexp;

import static io.github.ffuf.pkg.goregexp.Node.FOLD_CASE;
import static io.github.ffuf.pkg.goregexp.Node.NON_GREEDY;
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
import static io.github.ffuf.pkg.goregexp.Node.OP_LITERAL;
import static io.github.ffuf.pkg.goregexp.Node.OP_NO_MATCH;
import static io.github.ffuf.pkg.goregexp.Node.OP_NO_WORD_BOUNDARY;
import static io.github.ffuf.pkg.goregexp.Node.OP_PLUS;
import static io.github.ffuf.pkg.goregexp.Node.OP_QUEST;
import static io.github.ffuf.pkg.goregexp.Node.OP_STAR;
import static io.github.ffuf.pkg.goregexp.Node.OP_WORD_BOUNDARY;

import java.util.ArrayList;
import java.util.List;

/** A compiled regular expression program, an instruction list for the NFA {@link Machine}, compiled from a {@link Node} tree. */
final class Prog {
    static final int INST_ALT = 0;
    static final int INST_CAPTURE = 2;
    static final int INST_EMPTY_WIDTH = 3;
    static final int INST_MATCH = 4;
    static final int INST_FAIL = 5;
    static final int INST_NOP = 6;
    static final int INST_RUNE = 7;
    static final int INST_RUNE1 = 8;
    static final int INST_RUNE_ANY = 9;
    static final int INST_RUNE_ANY_NOT_NL = 10;

    static final int EMPTY_BEGIN_LINE = 1;
    static final int EMPTY_END_LINE = 1 << 1;
    static final int EMPTY_BEGIN_TEXT = 1 << 2;
    static final int EMPTY_END_TEXT = 1 << 3;
    static final int EMPTY_WORD_BOUNDARY = 1 << 4;
    static final int EMPTY_NO_WORD_BOUNDARY = 1 << 5;

    private static final int[] ANY_RUNE_NOT_NL = {0, '\n' - 1, '\n' + 1, Utf8.MAX_RUNE};
    private static final int[] ANY_RUNE = {0, Utf8.MAX_RUNE};

    /** One instruction. */
    static final class Inst {
        int op;
        int out;
        int arg;
        int[] rune;

        Inst(int op) {
            this.op = op;
        }

        boolean matchRune(int r) {
            int[] rn = rune;
            switch (rn.length) {
                case 0:
                    return false;
                case 1: {
                    int r0 = rn[0];
                    if (r == r0) {
                        return true;
                    }
                    if ((arg & FOLD_CASE) != 0) {
                        for (int r1 = UnicodeTables.simpleFold(r0); r1 != r0; r1 = UnicodeTables.simpleFold(r1)) {
                            if (r == r1) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
                case 2:
                    return r >= rn[0] && r <= rn[1];
                case 4, 6, 8:
                    for (int j = 0; j < rn.length; j += 2) {
                        if (r < rn[j]) {
                            return false;
                        }
                        if (r <= rn[j + 1]) {
                            return true;
                        }
                    }
                    return false;
                default:
                    return CharClass.contains(rn, rn.length, r);
            }
        }
    }

    final List<Inst> inst = new ArrayList<>();
    int start;
    int numCap = 2;

    private Prog() {
    }

    /** Compiles a parsed expression into a program. */
    static Prog compile(Node re) {
        Prog p = new Prog();
        p.newInst(INST_FAIL);
        Frag f = p.compileNode(re);
        p.patch(f.out, p.newInst(INST_MATCH).i);
        p.start = f.i;
        return p;
    }

    /**
     * prog.StartCond: the empty-width conditions every match must satisfy at its start, or -1 when nothing can match.
     */
    int startCond() {
        int flag = 0;
        Inst i = inst.get(start);
        while (true) {
            if (i.op == INST_EMPTY_WIDTH) {
                flag |= i.arg;
            } else if (i.op == INST_FAIL) {
                return -1;
            } else if (i.op != INST_CAPTURE && i.op != INST_NOP) {
                break;
            }
            i = inst.get(i.out);
        }
        return flag;
    }

    /** A patch list: instruction slots (index << 1, | 1 for arg) still to be pointed at the next instruction. */
    private record PatchList(int head, int tail) {
        static final PatchList EMPTY = new PatchList(0, 0);
    }

    private record Frag(int i, PatchList out, boolean nullable) {
    }

    private void patch(PatchList l, int val) {
        int head = l.head;
        while (head != 0) {
            Inst i = inst.get(head >> 1);
            if ((head & 1) == 0) {
                head = i.out;
                i.out = val;
            } else {
                head = i.arg;
                i.arg = val;
            }
        }
    }

    private PatchList append(PatchList l1, PatchList l2) {
        if (l1.head == 0) {
            return l2;
        }
        if (l2.head == 0) {
            return l1;
        }
        Inst i = inst.get(l1.tail >> 1);
        if ((l1.tail & 1) == 0) {
            i.out = l2.head;
        } else {
            i.arg = l2.head;
        }
        return new PatchList(l1.head, l2.tail);
    }

    private Frag newInst(int op) {
        Frag f = new Frag(inst.size(), PatchList.EMPTY, true);
        inst.add(new Inst(op));
        return f;
    }

    private Frag compileNode(Node re) {
        switch (re.op) {
            case OP_NO_MATCH:
                return fail();
            case OP_EMPTY_MATCH:
                return nop();
            case OP_LITERAL: {
                if (re.rune.size() == 0) {
                    return nop();
                }
                Frag f = null;
                for (int j = 0; j < re.rune.size(); j++) {
                    Frag f1 = rune(new int[] {re.rune.get(j)}, re.flags);
                    f = j == 0 ? f1 : cat(f, f1);
                }
                return f;
            }
            case OP_CHAR_CLASS:
                return rune(re.rune.toArray(), re.flags);
            case OP_ANY_CHAR_NOT_NL:
                return rune(ANY_RUNE_NOT_NL, 0);
            case OP_ANY_CHAR:
                return rune(ANY_RUNE, 0);
            case OP_BEGIN_LINE:
                return empty(EMPTY_BEGIN_LINE);
            case OP_END_LINE:
                return empty(EMPTY_END_LINE);
            case OP_BEGIN_TEXT:
                return empty(EMPTY_BEGIN_TEXT);
            case OP_END_TEXT:
                return empty(EMPTY_END_TEXT);
            case OP_WORD_BOUNDARY:
                return empty(EMPTY_WORD_BOUNDARY);
            case OP_NO_WORD_BOUNDARY:
                return empty(EMPTY_NO_WORD_BOUNDARY);
            case OP_CAPTURE: {
                Frag bra = cap(re.cap << 1);
                Frag sub = compileNode(re.sub.get(0));
                Frag ket = cap(re.cap << 1 | 1);
                return cat(cat(bra, sub), ket);
            }
            case OP_STAR:
                return star(compileNode(re.sub.get(0)), (re.flags & NON_GREEDY) != 0);
            case OP_PLUS:
                return plus(compileNode(re.sub.get(0)), (re.flags & NON_GREEDY) != 0);
            case OP_QUEST:
                return quest(compileNode(re.sub.get(0)), (re.flags & NON_GREEDY) != 0);
            case OP_CONCAT: {
                if (re.sub.isEmpty()) {
                    return nop();
                }
                Frag f = null;
                for (int i = 0; i < re.sub.size(); i++) {
                    Frag next = compileNode(re.sub.get(i));
                    f = i == 0 ? next : cat(f, next);
                }
                return f;
            }
            case OP_ALTERNATE: {
                Frag f = new Frag(0, PatchList.EMPTY, false);
                for (Node sub : re.sub) {
                    f = alt(f, compileNode(sub));
                }
                return f;
            }
            default:
                throw new IllegalStateException("regexp: unhandled case in compile");
        }
    }

    private Frag nop() {
        Frag f = newInst(INST_NOP);
        return new Frag(f.i, new PatchList(f.i << 1, f.i << 1), f.nullable);
    }

    private static Frag fail() {
        return new Frag(0, PatchList.EMPTY, false);
    }

    private Frag cap(int arg) {
        Frag f = newInst(INST_CAPTURE);
        inst.get(f.i).arg = arg;
        numCap = Math.max(numCap, arg + 1);
        return new Frag(f.i, new PatchList(f.i << 1, f.i << 1), f.nullable);
    }

    private Frag cat(Frag f1, Frag f2) {
        if (f1.i == 0 || f2.i == 0) {
            return fail();
        }
        patch(f1.out, f2.i);
        return new Frag(f1.i, f2.out, f1.nullable && f2.nullable);
    }

    private Frag alt(Frag f1, Frag f2) {
        if (f1.i == 0) {
            return f2;
        }
        if (f2.i == 0) {
            return f1;
        }
        Frag f = newInst(INST_ALT);
        Inst i = inst.get(f.i);
        i.out = f1.i;
        i.arg = f2.i;
        return new Frag(f.i, append(f1.out, f2.out), f1.nullable || f2.nullable);
    }

    private Frag quest(Frag f1, boolean nongreedy) {
        Frag f = newInst(INST_ALT);
        Inst i = inst.get(f.i);
        PatchList out;
        if (nongreedy) {
            i.arg = f1.i;
            out = new PatchList(f.i << 1, f.i << 1);
        } else {
            i.out = f1.i;
            out = new PatchList(f.i << 1 | 1, f.i << 1 | 1);
        }
        return new Frag(f.i, append(out, f1.out), f.nullable);
    }

    private Frag loop(Frag f1, boolean nongreedy) {
        Frag f = newInst(INST_ALT);
        Inst i = inst.get(f.i);
        PatchList out;
        if (nongreedy) {
            i.arg = f1.i;
            out = new PatchList(f.i << 1, f.i << 1);
        } else {
            i.out = f1.i;
            out = new PatchList(f.i << 1 | 1, f.i << 1 | 1);
        }
        patch(f1.out, f.i);
        return new Frag(f.i, out, f.nullable);
    }

    private Frag star(Frag f1, boolean nongreedy) {
        if (f1.nullable) {
            return quest(plus(f1, nongreedy), nongreedy);
        }
        return loop(f1, nongreedy);
    }

    private Frag plus(Frag f1, boolean nongreedy) {
        return new Frag(f1.i, loop(f1, nongreedy).out, f1.nullable);
    }

    private Frag empty(int op) {
        Frag f = newInst(INST_EMPTY_WIDTH);
        inst.get(f.i).arg = op;
        return new Frag(f.i, new PatchList(f.i << 1, f.i << 1), f.nullable);
    }

    private Frag rune(int[] r, int flags) {
        Frag f = newInst(INST_RUNE);
        Inst i = inst.get(f.i);
        i.rune = r;
        flags &= FOLD_CASE;
        if (r.length != 1 || UnicodeTables.simpleFold(r[0]) == r[0]) {
            flags &= ~FOLD_CASE;
        }
        i.arg = flags;
        if ((flags & FOLD_CASE) == 0 && (r.length == 1 || r.length == 2 && r[0] == r[1])) {
            i.op = INST_RUNE1;
        } else if (r.length == 2 && r[0] == 0 && r[1] == Utf8.MAX_RUNE) {
            i.op = INST_RUNE_ANY;
        } else if (r.length == 4 && r[0] == 0 && r[1] == '\n' - 1 && r[2] == '\n' + 1 && r[3] == Utf8.MAX_RUNE) {
            i.op = INST_RUNE_ANY_NOT_NL;
        }
        return new Frag(f.i, new PatchList(f.i << 1, f.i << 1), false);
    }
}
