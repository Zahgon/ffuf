package io.github.ffuf.pkg.ffuf;

import io.github.ffuf.pkg.goregexp.GoRegexp;

/** A variable captured from a flight response, by regexp or by named source. */
public class VarExtract {
    public String name = "";
    public String regex = "";
    public String source = "";
    public String key = "";
    /** The regex compiled while the configuration is validated; null until then. */
    public GoRegexp compiled;

    /** Creates an empty variable extraction rule. */
    public VarExtract() {
        // Fields start at their declared defaults.
    }
}
