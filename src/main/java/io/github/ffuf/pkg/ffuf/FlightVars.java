package io.github.ffuf.pkg.ffuf;

import java.util.List;

/** Parsing of the -preflight-var and -preflight-var-auto specifications. */
public final class FlightVars {
    public static final String SOURCE_AUTO = "auto";
    public static final List<String> SOURCES = List.of("form", "meta", "cookie", "header");

    private FlightVars() {
    }

    /**
     * Attaches a "NAME:regex" extraction to the last flight of the chain.
     *
     * @param chain the preflight or postflight chain read so far
     * @param varFlag the flag name used in error texts
     * @param fileFlag the flag that adds a flight, named when the chain is empty
     * @param spec the flag value
     * @return the error text, or null when the extraction was attached
     */
    public static String appendVar(List<PreflightConfig> chain, String varFlag, String fileFlag, String spec) {
        if (chain.isEmpty()) {
            return varFlag + " \"" + Flags.goQuote(spec) + "\" has no preceding " + fileFlag;
        }
        String[] p = splitSpec(spec);
        if (p == null) {
            return varFlag + " value \"" + Flags.goQuote(spec) + "\" must be \"NAME:regex\" with a non-empty name and regex";
        }
        VarExtract v = new VarExtract();
        v.name = p[0];
        v.regex = p[1];
        chain.get(chain.size() - 1).vars.add(v);
        return null;
    }

    /**
     * Attaches a "NAME:key" or "NAME:[source]key" extraction to the last flight of the chain.
     *
     * @param chain the preflight or postflight chain read so far
     * @param varFlag the flag name used in error texts
     * @param fileFlag the flag that adds a flight, named when the chain is empty
     * @param spec the flag value
     * @return the error text, or null when the extraction was attached
     */
    public static String appendVarAuto(List<PreflightConfig> chain, String varFlag, String fileFlag, String spec) {
        if (chain.isEmpty()) {
            return varFlag + " \"" + Flags.goQuote(spec) + "\" has no preceding " + fileFlag;
        }
        String[] p = splitSpec(spec);
        if (p == null) {
            return varFlag + " value \"" + Flags.goQuote(spec) + "\" must be \"NAME:key\" or \"NAME:[source]key\" with a non-empty name and key";
        }
        String sel = p[1];
        String source = SOURCE_AUTO;
        String key = sel;
        if (sel.startsWith("[")) {
            int end = sel.indexOf(']');
            if (end < 0) {
                return varFlag + " value \"" + Flags.goQuote(spec) + "\": unterminated source tag, want [" + String.join("|", SOURCES) + "]key";
            }
            source = sel.substring(1, end);
            key = sel.substring(end + 1);
            String err = validateSource(source, key);
            if (err != null) {
                return varFlag + " value \"" + Flags.goQuote(spec) + "\": " + err;
            }
        }
        VarExtract v = new VarExtract();
        v.name = p[0];
        v.source = source;
        v.key = key;
        chain.get(chain.size() - 1).vars.add(v);
        return null;
    }

    /**
     * Checks a -preflight-var source and key.
     *
     * @return the error text, or null when the source and key are valid
     */
    public static String validateSource(String source, String key) {
        if (!source.equals(SOURCE_AUTO) && !SOURCES.contains(source)) {
            return "unknown source \"" + Flags.goQuote(source) + "\", want one of " + String.join(", ", SOURCES);
        }
        if (key.isEmpty()) {
            return "source \"" + Flags.goQuote(source) + "\" needs a non-empty key";
        }
        return null;
    }

    private static String[] splitSpec(String spec) {
        int idx = spec.indexOf(':');
        if (idx <= 0 || idx == spec.length() - 1) {
            return null;
        }
        return new String[] {spec.substring(0, idx), spec.substring(idx + 1)};
    }
}
