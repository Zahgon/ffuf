package io.github.ffuf;

import io.github.ffuf.pkg.ffuf.Flags;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.output.Stdoutput;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The sectioned usage screen. */
final class Help {
    private Help() {
    }

    private record Section(String name, String key, boolean hidden) {
    }

    static void usage(Flags flags) {
        PrintStream out = Stdoutput.OUT;
        out.print(render(flags));
        out.flush();
    }

    static String render(Flags flags) {
        List<Section> sections = List.of(
                new Section("HTTP OPTIONS", Flags.SECTION_HTTP, false),
                new Section("GENERAL OPTIONS", Flags.SECTION_GENERAL, false),
                new Section("COMPATIBILITY OPTIONS", Flags.SECTION_COMPAT, true),
                new Section("MATCHER OPTIONS", Flags.SECTION_MATCHER, false),
                new Section("FILTER OPTIONS", Flags.SECTION_FILTER, false),
                new Section("INPUT OPTIONS", Flags.SECTION_INPUT, false),
                new Section("OUTPUT OPTIONS", Flags.SECTION_OUTPUT, false));
        Map<String, List<Flags.Flag>> bySection = new LinkedHashMap<>();
        int maxLength = 0;
        for (Flags.Flag f : flags.all()) {
            bySection.computeIfAbsent(f.section, k -> new ArrayList<>()).add(f);
            maxLength = Math.max(maxLength, f.name.length());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Fuzz Faster U Fool - ").append(Util.formattedVersion()).append("\n\n");
        for (Section s : sections) {
            if (s.hidden) {
                continue;
            }
            sb.append(s.name).append(":\n");
            for (Flags.Flag f : bySection.getOrDefault(s.key, List.of())) {
                sb.append(String.format("  -%-" + maxLength + "s %s", f.name, f.usage));
                if (!f.defValue.isEmpty()) {
                    sb.append(" (default: ").append(f.defValue).append(")");
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        sb.append("EXAMPLE USAGE:\n");
        sb.append("  Fuzz file paths from wordlist.txt, match all responses but filter out those with content-size 42.\n");
        sb.append("  Colored, verbose output.\n");
        sb.append("    ffuf -w wordlist.txt -u https://example.org/FUZZ -mc all -fs 42 -c -v\n\n");
        sb.append("  Fuzz Host-header, match HTTP 200 responses.\n");
        sb.append("    ffuf -w hosts.txt -u https://example.org/ -H \"Host: FUZZ\" -mc 200\n\n");
        sb.append("  Fuzz POST JSON data. Match all responses not containing text \"error\".\n");
        sb.append("    ffuf -w entries.txt -u https://example.org/ -X POST -H \"Content-Type: application/json\" \\\n");
        sb.append("      -d '{\"name\": \"FUZZ\", \"anotherkey\": \"anothervalue\"}' -fr \"error\"\n\n");
        sb.append("  Fuzz multiple locations. Match only responses reflecting the value of \"VAL\" keyword. Colored.\n");
        sb.append("    ffuf -w params.txt:PARAM -w values.txt:VAL -u https://example.org/?PARAM=VAL -mr \"VAL\" -c\n\n");
        sb.append("  More information and examples: https://github.com/ffuf/ffuf\n\n");
        return sb.toString();
    }
}
