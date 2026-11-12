package io.github.ffuf.pkg.goregexp;

import java.util.Arrays;

/** A growable list of runes that holds literals and [lo, hi] class range pairs. */
final class RuneList {
    int[] a;
    int n;

    RuneList() {
        a = new int[4];
    }

    RuneList(int... runes) {
        a = runes.length == 0 ? new int[4] : runes.clone();
        n = runes.length;
    }

    int size() {
        return n;
    }

    int get(int i) {
        return a[i];
    }

    void set(int i, int v) {
        a[i] = v;
    }

    void add(int v) {
        if (n == a.length) {
            a = Arrays.copyOf(a, Math.max(8, n * 2));
        }
        a[n++] = v;
    }

    void addAll(RuneList o) {
        for (int i = 0; i < o.n; i++) {
            add(o.a[i]);
        }
    }

    void truncate(int len) {
        n = len;
    }

    int[] toArray() {
        return Arrays.copyOf(a, n);
    }

    boolean contentEquals(RuneList o) {
        return Arrays.equals(a, 0, n, o.a, 0, o.n);
    }
}
