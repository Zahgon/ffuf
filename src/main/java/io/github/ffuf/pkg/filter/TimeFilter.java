package io.github.ffuf.pkg.filter;

import io.github.ffuf.pkg.ffuf.Response;

/** Compares the time to the first response byte with a limit. */
public class TimeFilter implements FilterProvider {
    private final long ms;
    private final boolean gt;
    private final String valueRaw;

    /**
     * Creates the filter for a response time condition such as ">100" or "<100" milliseconds.
     *
     * @throws IllegalArgumentException when the value is invalid
     */
    public TimeFilter(String value) {
        boolean g = value.startsWith(">");
        boolean l = value.startsWith("<");
        if (g == l) {
            throw new IllegalArgumentException("Time filter or matcher (-ft / -mt): invalid value: " + value);
        }
        try {
            String n = value.substring(1);
            if (n.startsWith("+")) {
                n = n.substring(1);
                if (n.startsWith("-") || n.startsWith("+")) {
                    throw new NumberFormatException(n);
                }
            }
            this.ms = Long.parseLong(n);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Time filter or matcher (-ft / -mt): invalid value: " + value);
        }
        this.gt = g;
        this.valueRaw = value;
    }

    @Override
    public boolean filter(Response response) {
        long d = response.durationNanos / 1_000_000L;
        return gt ? d > ms : d < ms;
    }

    @Override
    public String repr() {
        return valueRaw;
    }

    @Override
    public String reprVerbose() {
        return "Response time: " + valueRaw;
    }
}
