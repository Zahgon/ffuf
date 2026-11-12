package io.github.ffuf.pkg.ffuf;

import java.util.ArrayList;
import java.util.List;

/** One preflight or postflight request file and the variables extracted from its response. */
public class PreflightConfig {
    public String requestFile;
    public List<VarExtract> vars = new ArrayList<>();

    /** Creates a flight request read from the raw request file. */
    public PreflightConfig(String requestFile) {
        this.requestFile = requestFile;
    }
}
