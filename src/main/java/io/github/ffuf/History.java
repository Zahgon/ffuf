package io.github.ffuf;

import io.github.ffuf.pkg.engine.HistoryEntry;
import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.GoStrconv;
import io.github.ffuf.pkg.ffuf.MultiError;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.input.MainInputProvider;
import io.github.ffuf.pkg.output.Stdoutput;
import io.github.ffuf.pkg.runner.SimpleRunner;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The -search lookup of a FFUFHASH value in the history folder. */
final class History {
    private History() {
    }

    static int search(String hash, List<String> argv) {
        PrintStream out = Stdoutput.OUT;
        byte[] hb = hash.getBytes(StandardCharsets.UTF_8);
        if (hb.length < 6) {
            out.print("[ERR] bad FFUFHASH value\n");
            return 1;
        }
        GoStrconv.IntResult parsed = GoStrconv.parseInt(new String(hb, 5, hb.length - 5, StandardCharsets.ISO_8859_1), 16);
        if (parsed.error() != null || parsed.value() < Integer.MIN_VALUE || parsed.value() > Integer.MAX_VALUE) {
            out.print("[ERR] bad positional value in FFUFHASH\n");
            return 1;
        }
        long position = parsed.value();
        File[] dirs = new File(Util.historyDir()).listFiles();
        if (dirs == null) {
            out.print("[ERR] open " + Util.historyDir() + ": no such file or directory\n");
            return 1;
        }
        Arrays.sort(dirs);
        String prefix = new String(hb, 0, 5, StandardCharsets.ISO_8859_1).toLowerCase();
        List<HistoryEntry> entries = new ArrayList<>();
        for (File d : dirs) {
            if (d.isDirectory() && d.getName().toLowerCase().startsWith(prefix)) {
                try {
                    entries.add(HistoryEntry.read(d.toPath()));
                } catch (IOException e) {
                    // an unreadable history entry is skipped
                    continue;
                }
            }
        }
        if (!entries.isEmpty()) {
            out.print("Request candidate(s) for hash " + hash + "\n");
        }
        for (HistoryEntry e : entries) {
            Config conf;
            try {
                conf = Config.fromOptions(e.options, argv);
            } catch (Config.ConfigException ex) {
                continue;
            }
            String reason = null;
            for (String w : conf.wordlists) {
                if (w.equals("-") || w.startsWith("-:")) {
                    reason = "stdin input was used for one of the wordlists";
                }
            }
            if (reason != null) {
                out.print("[ERR] Hash cannot be mapped back because " + reason + "\n");
                continue;
            }
            printResult(conf, (int) position, e.time, hash);
        }
        return 0;
    }

    private static void printResult(Config conf, int pos, String time, String hash) {
        PrintStream out = Stdoutput.OUT;
        MultiError errs = new MultiError();
        MainInputProvider inp = new MainInputProvider(conf, errs);
        if (!errs.isEmpty()) {
            out.print("-------------------------------------------\n");
            out.print("Encountered error that prevents reproduction of the request:\n");
            out.print(errs.errorOrNull() + "\n");
            return;
        }
        inp.setPosition(pos);
        Map<String, byte[]> input = inp.value();
        input.put("FFUFHASH", hash.getBytes(StandardCharsets.UTF_8));
        SimpleRunner runner = new SimpleRunner(conf);
        Request req = runner.prepare(input, Request.base(conf));
        String raw;
        try {
            raw = runner.dump(req);
        } catch (SimpleRunner.RequestException ex) {
            raw = "";
        }
        out.print("-------------------------------------------\n");
        out.print("ffuf job started at: " + rfc3339(time) + "\n\n");
        out.print(raw + "\n");
    }

    private static String rfc3339(String t) {
        return t.replaceFirst("\\.\\d+", "");
    }
}
