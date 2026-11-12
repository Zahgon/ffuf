package io.github.ffuf.pkg.filter;

import io.github.ffuf.pkg.ffuf.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Status, size, word and line filters: a list of values and ranges compared with one response figure. */
public class RangeFilter implements FilterProvider {
    private static final Pattern RANGE = Pattern.compile("^(\\d+)-(\\d+)$");

    private final List<long[]> ranges;
    private final ToLongFunction<Response> metric;
    private final String verboseName;
    private final boolean status;

    private RangeFilter(List<long[]> ranges, ToLongFunction<Response> metric, String verboseName, boolean status) {
        this.ranges = ranges;
        this.metric = metric;
        this.verboseName = verboseName;
        this.status = status;
    }

    /**
     * Parses one value ("200") or inclusive range ("100-200").
     *
     * @return the lower and upper bound, or null when s is not a number or a range whose lower bound is below its upper
     *     bound
     */
    public static long[] valueRange(String s) {
        Matcher m = RANGE.matcher(s);
        if (m.matches()) {
            Long min = parse(m.group(1));
            Long max = parse(m.group(2));
            if (min == null || max == null || min >= max) {
                return null;
            }
            return new long[] {min, max};
        }
        Long v = parse(s);
        return v == null ? null : new long[] {v, v};
    }

    private static Long parse(String s) {
        String t = s.startsWith("+") || s.startsWith("-") ? s.substring(1) : s;
        if (t.isEmpty()) {
            return null;
        }
        for (char c : t.toCharArray()) {
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(s.startsWith("+") ? t : s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Creates the status code filter for a comma-separated list of codes, ranges or "all".
     *
     * @return the status filter
     * @throws IllegalArgumentException when a value is invalid
     */
    public static FilterProvider status(String value) {
        List<long[]> r = new ArrayList<>();
        for (String sv : value.split(",", -1)) {
            if (sv.equals("all")) {
                r.add(new long[] {0, 0});
            } else {
                long[] vr = valueRange(sv);
                if (vr == null) {
                    throw new IllegalArgumentException("Status filter or matcher (-fc / -mc): invalid value " + sv);
                }
                r.add(vr);
            }
        }
        return new RangeFilter(r, resp -> resp.statusCode, "Response status", true);
    }

    /**
     * Creates a filter on a counted response metric (size, words, lines) for a comma-separated list of values or ranges.
     *
     * @param value the flag value
     * @param label the metric name in error texts, such as "Size"
     * @param flags the flags named in error texts, such as "-fs / -ms"
     * @param verboseName the name of the filter in its description
     * @param metric reads the metric from a response
     * @return the filter on that metric
     * @throws IllegalArgumentException when a value is invalid
     */
    public static FilterProvider counted(String value, String label, String flags, String verboseName, ToLongFunction<Response> metric) {
        List<long[]> r = new ArrayList<>();
        for (String sv : value.split(",", -1)) {
            long[] vr = valueRange(sv);
            if (vr == null) {
                throw new IllegalArgumentException(label + " filter or matcher (" + flags + "): invalid value: " + sv);
            }
            r.add(vr);
        }
        return new RangeFilter(r, metric, verboseName, false);
    }

    @Override
    public boolean filter(Response response) {
        long v = metric.applyAsLong(response);
        for (long[] iv : ranges) {
            if (status && iv[0] == 0 && iv[1] == 0) {
                return true;
            }
            if (iv[0] <= v && v <= iv[1]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String repr() {
        List<String> out = new ArrayList<>();
        for (long[] iv : ranges) {
            if (status && iv[0] == 0 && iv[1] == 0) {
                out.add("all");
            } else if (iv[0] == iv[1]) {
                out.add(Long.toString(iv[0]));
            } else {
                out.add(iv[0] + "-" + iv[1]);
            }
        }
        return String.join(",", out);
    }

    @Override
    public String reprVerbose() {
        return verboseName + ": " + repr();
    }
}
