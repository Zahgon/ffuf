package io.github.ffuf.pkg.goregexp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The parts of Go's unicode package that regexp/syntax uses: categories, scripts, their case-folding companions, the
 * category aliases and SimpleFold. The data is generated from Go's tables so it follows Go's Unicode version.
 */
final class UnicodeTables {
    static final Map<String, int[]> CATEGORIES = new HashMap<>();
    static final Map<String, int[]> FOLD_CATEGORY = new HashMap<>();
    static final Map<String, int[]> SCRIPTS = new HashMap<>();
    static final Map<String, int[]> FOLD_SCRIPT = new HashMap<>();
    static final Map<String, String> CATEGORY_ALIASES = new HashMap<>();

    private static int[] foldFrom = new int[0];
    private static int[] foldTo = new int[0];

    static {
        try (InputStream in = UnicodeTables.class.getResourceAsStream("unicode-tables.txt")) {
            if (in == null) {
                throw new IllegalStateException("unicode-tables.txt resource is missing");
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    load(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private UnicodeTables() {
    }

    private static void load(String line) {
        if (line.startsWith("#")) {
            return;
        }
        String[] f = line.split(" ");
        switch (f[0]) {
            case "C" -> CATEGORIES.put(f[1], deltas(f, 2));
            case "FC" -> FOLD_CATEGORY.put(f[1], deltas(f, 2));
            case "S" -> SCRIPTS.put(f[1], deltas(f, 2));
            case "FS" -> FOLD_SCRIPT.put(f[1], deltas(f, 2));
            case "A" -> CATEGORY_ALIASES.put(f[1], f[2]);
            case "F" -> {
                int n = (f.length - 1) / 2;
                foldFrom = new int[n];
                foldTo = new int[n];
                int prev = 0;
                for (int i = 0; i < n; i++) {
                    prev += Integer.parseInt(f[1 + 2 * i], 36);
                    foldFrom[i] = prev;
                    foldTo[i] = prev + Integer.parseInt(f[2 + 2 * i], 36);
                }
            }
            default -> throw new IllegalStateException("bad unicode table line: " + f[0]);
        }
    }

    private static int[] deltas(String[] f, int from) {
        int[] out = new int[f.length - from];
        int prev = 0;
        for (int i = from; i < f.length; i++) {
            prev += Integer.parseInt(f[i], 36);
            out[i - from] = prev;
        }
        return out;
    }

    /** The next rune of the case-folding orbit of {@code r}, or {@code r} itself. */
    static int simpleFold(int r) {
        int i = Arrays.binarySearch(foldFrom, r);
        return i >= 0 ? foldTo[i] : r;
    }
}
