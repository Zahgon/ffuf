package io.github.ffuf.pkg.goregexp;

import java.util.Arrays;

/** Character class range-list helpers for the regular expression parser; a class is a sorted list of [lo, hi] pairs. */
final class CharClass {
    static final int MIN_FOLD = 0x0041;
    static final int MAX_FOLD = 0x1e943;

    static final int[] ANY_TABLE = {0, Utf8.MAX_RUNE};
    static final int[] ASCII_TABLE = {0, 0x7F};
    static final int[] ASCII_FOLD_TABLE = {0, 0x7F, 0x017F, 0x017F, 0x212A, 0x212A};

    private CharClass() {
    }

    /** Sorts the ranges and merges overlapping or adjacent ones, in place. */
    static void clean(RuneList rl) {
        int pairs = rl.n / 2;
        long[] keyed = new long[pairs];
        for (int i = 0; i < pairs; i++) {
            keyed[i] = (long) rl.a[2 * i] << 32 | (0xFFFFFFFFL - rl.a[2 * i + 1]);
        }
        Arrays.sort(keyed);
        for (int i = 0; i < pairs; i++) {
            rl.a[2 * i] = (int) (keyed[i] >>> 32);
            rl.a[2 * i + 1] = (int) (0xFFFFFFFFL - (keyed[i] & 0xFFFFFFFFL));
        }
        int[] r = rl.a;
        if (rl.n < 2) {
            return;
        }
        int w = 2;
        for (int i = 2; i < rl.n; i += 2) {
            int lo = r[i];
            int hi = r[i + 1];
            if (lo <= r[w - 1] + 1) {
                if (hi > r[w - 1]) {
                    r[w - 1] = hi;
                }
                continue;
            }
            r[w] = lo;
            r[w + 1] = hi;
            w += 2;
        }
        rl.n = w;
    }

    static void appendLiteral(RuneList r, int x, int flags) {
        if ((flags & Node.FOLD_CASE) != 0) {
            appendFoldedRange(r, x, x);
        } else {
            appendRange(r, x, x);
        }
    }

    static void appendRange(RuneList rl, int lo, int hi) {
        int n = rl.n;
        int[] r = rl.a;
        for (int i = 2; i <= 4; i += 2) {
            if (n >= i) {
                int rlo = r[n - i];
                int rhi = r[n - i + 1];
                if (lo <= rhi + 1 && rlo <= hi + 1) {
                    if (lo < rlo) {
                        r[n - i] = lo;
                    }
                    if (hi > rhi) {
                        r[n - i + 1] = hi;
                    }
                    return;
                }
            }
        }
        rl.add(lo);
        rl.add(hi);
    }

    static void appendFoldedRange(RuneList r, int lo, int hi) {
        if (lo <= MIN_FOLD && hi >= MAX_FOLD) {
            appendRange(r, lo, hi);
            return;
        }
        if (hi < MIN_FOLD || lo > MAX_FOLD) {
            appendRange(r, lo, hi);
            return;
        }
        if (lo < MIN_FOLD) {
            appendRange(r, lo, MIN_FOLD - 1);
            lo = MIN_FOLD;
        }
        if (hi > MAX_FOLD) {
            appendRange(r, MAX_FOLD + 1, hi);
            hi = MAX_FOLD;
        }
        for (int c = lo; c <= hi; c++) {
            appendRange(r, c, c);
            for (int f = UnicodeTables.simpleFold(c); f != c; f = UnicodeTables.simpleFold(f)) {
                appendRange(r, f, f);
            }
        }
    }

    static void appendClass(RuneList r, int[] x, int len) {
        for (int i = 0; i < len; i += 2) {
            appendRange(r, x[i], x[i + 1]);
        }
    }

    static void appendFoldedClass(RuneList r, int[] x, int len) {
        for (int i = 0; i < len; i += 2) {
            appendFoldedRange(r, x[i], x[i + 1]);
        }
    }

    static void appendNegatedClass(RuneList r, int[] x, int len) {
        int nextLo = 0;
        for (int i = 0; i < len; i += 2) {
            int lo = x[i];
            int hi = x[i + 1];
            if (nextLo <= lo - 1) {
                appendRange(r, nextLo, lo - 1);
            }
            nextLo = hi + 1;
        }
        if (nextLo <= Utf8.MAX_RUNE) {
            appendRange(r, nextLo, Utf8.MAX_RUNE);
        }
    }

    /** Replaces a cleaned class by its complement, in place. */
    static void negate(RuneList rl) {
        int[] r = rl.a;
        int nextLo = 0;
        int w = 0;
        for (int i = 0; i < rl.n; i += 2) {
            int lo = r[i];
            int hi = r[i + 1];
            if (nextLo <= lo - 1) {
                r[w] = nextLo;
                r[w + 1] = lo - 1;
                w += 2;
            }
            nextLo = hi + 1;
        }
        rl.n = w;
        if (nextLo <= Utf8.MAX_RUNE) {
            rl.add(nextLo);
            rl.add(Utf8.MAX_RUNE);
        }
    }

    static boolean contains(int[] r, int len, int c) {
        int lo = 0;
        int hi = len / 2;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (r[2 * m] <= c) {
                if (c <= r[2 * m + 1]) {
                    return true;
                }
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return false;
    }
}
