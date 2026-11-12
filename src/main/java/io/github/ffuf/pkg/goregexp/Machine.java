package io.github.ffuf.pkg.goregexp;

import static io.github.ffuf.pkg.goregexp.Prog.EMPTY_BEGIN_LINE;
import static io.github.ffuf.pkg.goregexp.Prog.EMPTY_BEGIN_TEXT;
import static io.github.ffuf.pkg.goregexp.Prog.EMPTY_END_LINE;
import static io.github.ffuf.pkg.goregexp.Prog.EMPTY_END_TEXT;
import static io.github.ffuf.pkg.goregexp.Prog.EMPTY_NO_WORD_BOUNDARY;
import static io.github.ffuf.pkg.goregexp.Prog.EMPTY_WORD_BOUNDARY;
import static io.github.ffuf.pkg.goregexp.Prog.INST_ALT;
import static io.github.ffuf.pkg.goregexp.Prog.INST_CAPTURE;
import static io.github.ffuf.pkg.goregexp.Prog.INST_EMPTY_WIDTH;
import static io.github.ffuf.pkg.goregexp.Prog.INST_FAIL;
import static io.github.ffuf.pkg.goregexp.Prog.INST_MATCH;
import static io.github.ffuf.pkg.goregexp.Prog.INST_NOP;
import static io.github.ffuf.pkg.goregexp.Prog.INST_RUNE;
import static io.github.ffuf.pkg.goregexp.Prog.INST_RUNE1;
import static io.github.ffuf.pkg.goregexp.Prog.INST_RUNE_ANY;
import static io.github.ffuf.pkg.goregexp.Prog.INST_RUNE_ANY_NOT_NL;
import static io.github.ffuf.pkg.goregexp.Prog.Inst;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The leftmost-first NFA simulation that runs a {@link Prog} over one byte input, matching like RE2. */
final class Machine {
    private static final int END_OF_TEXT = -1;

    /** A thread: the instruction it waits on and its capture positions. */
    private static final class Thread {
        Inst inst;
        final int[] cap;

        Thread(int ncap) {
            cap = new int[ncap];
        }
    }

    /** A sparse set of instructions with their threads, in priority order. */
    private static final class Queue {
        final int[] sparse;
        final int[] densePc;
        final Thread[] denseT;
        int n;

        Queue(int size) {
            sparse = new int[size];
            densePc = new int[size];
            denseT = new Thread[size];
        }
    }

    private final Prog prog;
    private final List<Inst> inst;
    private final byte[] in;
    private final int startCond;
    private final Queue q0;
    private final Queue q1;
    private final List<Thread> pool = new ArrayList<>();
    private final int ncap;
    private boolean matched;
    final int[] matchcap;

    Machine(Prog prog, byte[] in, int ncap, int startCond) {
        this.prog = prog;
        this.inst = prog.inst;
        this.in = in;
        this.ncap = ncap;
        this.startCond = startCond;
        this.matchcap = new int[ncap];
        q0 = new Queue(inst.size());
        q1 = new Queue(inst.size());
    }

    private int step(int pos) {
        if (pos < in.length) {
            return Utf8.decode(in, pos, in.length);
        }
        return END_OF_TEXT << 3;
    }

    private static int rune(int d) {
        return d >> 3;
    }

    private static boolean isWordChar(int r) {
        return r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_';
    }

    /** Whether the empty-width conditions {@code op} hold between runes r1 and r2. */
    private static boolean condMatch(int op, int r1, int r2) {
        if (op == 0) {
            return true;
        }
        if ((op & EMPTY_BEGIN_LINE) != 0) {
            if (r1 != '\n' && r1 >= 0) {
                return false;
            }
            op &= ~EMPTY_BEGIN_LINE;
        }
        if ((op & EMPTY_BEGIN_TEXT) != 0) {
            if (r1 >= 0) {
                return false;
            }
            op &= ~EMPTY_BEGIN_TEXT;
        }
        if (op == 0) {
            return true;
        }
        if ((op & EMPTY_END_LINE) != 0) {
            if (r2 != '\n' && r2 >= 0) {
                return false;
            }
            op &= ~EMPTY_END_LINE;
        }
        if ((op & EMPTY_END_TEXT) != 0) {
            if (r2 >= 0) {
                return false;
            }
            op &= ~EMPTY_END_TEXT;
        }
        if (op == 0) {
            return true;
        }
        if (isWordChar(r1) != isWordChar(r2)) {
            op &= ~EMPTY_WORD_BOUNDARY;
        } else {
            op &= ~EMPTY_NO_WORD_BOUNDARY;
        }
        return op == 0;
    }

    boolean match(int pos) {
        matched = false;
        Arrays.fill(matchcap, -1);
        Queue runq = q0;
        Queue nextq = q1;
        int r = END_OF_TEXT;
        int r1 = END_OF_TEXT;
        int width = 0;
        int width1 = 0;
        int d = step(pos);
        r = rune(d);
        width = d & 7;
        if (r != END_OF_TEXT) {
            d = step(pos + width);
            r1 = rune(d);
            width1 = d & 7;
        }
        int flagR1;
        int flagR2;
        if (pos == 0) {
            flagR1 = END_OF_TEXT;
            flagR2 = r;
        } else {
            flagR1 = Utf8.decodeLastRune(in, pos);
            flagR2 = pos < in.length ? rune(Utf8.decode(in, pos, in.length)) : END_OF_TEXT;
        }
        while (true) {
            if (runq.n == 0) {
                if ((startCond & EMPTY_BEGIN_TEXT) != 0 && pos != 0) {
                    break;
                }
                if (matched) {
                    break;
                }
            }
            if (!matched) {
                if (ncap > 0) {
                    matchcap[0] = pos;
                }
                add(runq, prog.start, pos, matchcap, flagR1, flagR2, null);
            }
            flagR1 = r;
            flagR2 = r1;
            step(runq, nextq, pos, pos + width, r, flagR1, flagR2);
            if (width == 0) {
                break;
            }
            if (ncap == 0 && matched) {
                break;
            }
            pos += width;
            r = r1;
            width = width1;
            if (r != END_OF_TEXT) {
                d = step(pos + width);
                r1 = rune(d);
                width1 = d & 7;
            }
            Queue t = runq;
            runq = nextq;
            nextq = t;
        }
        return matched;
    }

    private void step(Queue runq, Queue nextq, int pos, int nextPos, int c, int condR1, int condR2) {
        for (int j = 0; j < runq.n; j++) {
            Thread t = runq.denseT[j];
            if (t == null) {
                continue;
            }
            Inst i = t.inst;
            boolean add = false;
            switch (i.op) {
                case INST_MATCH -> {
                    if (ncap > 0) {
                        t.cap[1] = pos;
                        System.arraycopy(t.cap, 0, matchcap, 0, ncap);
                    }
                    for (int k = j + 1; k < runq.n; k++) {
                        if (runq.denseT[k] != null) {
                            pool.add(runq.denseT[k]);
                        }
                    }
                    runq.n = 0;
                    matched = true;
                }
                case INST_RUNE -> add = i.matchRune(c);
                case INST_RUNE1 -> add = c == i.rune[0];
                case INST_RUNE_ANY -> add = true;
                case INST_RUNE_ANY_NOT_NL -> add = c != '\n';
                default -> throw new IllegalStateException("bad inst");
            }
            if (add) {
                t = add(nextq, i.out, nextPos, t.cap, condR1, condR2, t);
            }
            if (t != null) {
                pool.add(t);
            }
        }
        runq.n = 0;
    }

    private Thread alloc(Inst i) {
        Thread t = pool.isEmpty() ? new Thread(ncap) : pool.remove(pool.size() - 1);
        t.inst = i;
        return t;
    }

    private Thread add(Queue q, int pc, int pos, int[] cap, int condR1, int condR2, Thread t) {
        while (true) {
            if (pc == 0) {
                return t;
            }
            int j = q.sparse[pc];
            if (j < q.n && q.densePc[j] == pc) {
                return t;
            }
            j = q.n++;
            q.denseT[j] = null;
            q.densePc[j] = pc;
            q.sparse[pc] = j;
            Inst i = inst.get(pc);
            switch (i.op) {
                case INST_FAIL:
                    return t;
                case INST_ALT:
                    t = add(q, i.out, pos, cap, condR1, condR2, t);
                    pc = i.arg;
                    continue;
                case INST_EMPTY_WIDTH:
                    if (condMatch(i.arg, condR1, condR2)) {
                        pc = i.out;
                        continue;
                    }
                    return t;
                case INST_NOP:
                    pc = i.out;
                    continue;
                case INST_CAPTURE:
                    if (i.arg < cap.length) {
                        int opos = cap[i.arg];
                        cap[i.arg] = pos;
                        add(q, i.out, pos, cap, condR1, condR2, null);
                        cap[i.arg] = opos;
                        return t;
                    }
                    pc = i.out;
                    continue;
                case INST_MATCH, INST_RUNE, INST_RUNE1, INST_RUNE_ANY, INST_RUNE_ANY_NOT_NL:
                    if (t == null) {
                        t = alloc(i);
                    } else {
                        t.inst = i;
                    }
                    if (cap.length > 0 && t.cap != cap) {
                        System.arraycopy(cap, 0, t.cap, 0, cap.length);
                    }
                    q.denseT[j] = t;
                    return null;
                default:
                    throw new IllegalStateException("unhandled");
            }
        }
    }
}
