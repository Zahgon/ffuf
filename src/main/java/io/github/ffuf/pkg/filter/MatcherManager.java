package io.github.ffuf.pkg.filter;

import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.ffuf.MultiError;
import io.github.ffuf.pkg.ffuf.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/** Holds the active matchers and filters and decides whether a response is reported. */
public class MatcherManager {
    private final Map<String, FilterProvider> matchers = new LinkedHashMap<>();
    private final Map<String, FilterProvider> filters = new LinkedHashMap<>();
    private final Map<String, Map<String, FilterProvider>> perDomainFilters = new LinkedHashMap<>();
    private boolean calibrated;

    /** Creates a manager with no matchers or filters. */
    public MatcherManager() {
        // Fields start at their declared defaults.
    }

    /**
     * Creates the filter or matcher a -m or -f flag name selects.
     *
     * @param name the filter kind, such as "status" or "size"
     * @param value the flag value
     * @return the filter for value
     * @throws IllegalArgumentException when the value is invalid
     */
    public static FilterProvider newFilterByName(String name, String value) {
        return switch (name) {
            case "status" -> RangeFilter.status(value);
            case "size" -> RangeFilter.counted(value, "Size", "-fs / -ms", "Response size", r -> r.contentLength);
            case "word" -> RangeFilter.counted(value, "Word", "-fw / -mw", "Response words", r -> countByte(r.data, (byte) ' ') + 1);
            case "line" -> RangeFilter.counted(value, "Line", "-fl / -ml", "Response lines", r -> countByte(r.data, (byte) '\n') + 1);
            case "regexp" -> new RegexpFilter(value);
            case "time" -> new TimeFilter(value);
            default -> throw new IllegalArgumentException("Could not create filter with name " + name);
        };
    }

    /** Counts the occurrences of b in data; the word and line filters count separators in the body itself. */
    private static long countByte(byte[] data, byte b) {
        long c = 0;
        for (byte x : data) {
            if (x == b) {
                c++;
            }
        }
        return c;
    }

    /**
     * Builds the manager from the -m* and -f* options and their modes.
     *
     * @param o the parsed options
     * @param addDefaultStatusMatcher whether the -mc status matcher (with its default) is installed
     * @param errs collects the error of every matcher or filter that cannot be built
     * @return the manager holding every matcher and filter that could be built
     */
    public static MatcherManager fromConfig(ConfigOptions o, boolean addDefaultStatusMatcher, MultiError errs) {
        MatcherManager mm = new MatcherManager();
        if (addDefaultStatusMatcher) {
            mm.tryAdd(errs, () -> mm.addMatcher("status", o.matcher.status));
        }
        String[][] filterSpecs = {{"status", o.filter.status}, {"size", o.filter.size}, {"regexp", o.filter.regexp},
            {"word", o.filter.words}, {"line", o.filter.lines}, {"time", o.filter.time}};
        for (String[] f : filterSpecs) {
            if (!f[1].isEmpty()) {
                mm.tryAdd(errs, () -> mm.addFilter(f[0], f[1], false));
            }
        }
        String[][] matcherSpecs = {{"size", o.matcher.size}, {"regexp", o.matcher.regexp}, {"word", o.matcher.words},
            {"line", o.matcher.lines}, {"time", o.matcher.time}};
        for (String[] m : matcherSpecs) {
            if (!m[1].isEmpty()) {
                mm.tryAdd(errs, () -> mm.addMatcher(m[0], m[1]));
            }
        }
        return mm;
    }

    private void tryAdd(MultiError errs, Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            errs.add(e.getMessage());
        }
    }

    /**
     * Adds a filter.
     *
     * @param name the filter kind, such as "status" or "size"
     * @param option the flag value
     * @param replace whether the new value replaces an existing filter of that name instead of extending it
     */
    public synchronized void addFilter(String name, String option, boolean replace) {
        FilterProvider f = newFilterByName(name, option);
        if (!filters.containsKey(name) || replace) {
            filters.put(name, f);
        } else {
            filters.put(name, newFilterByName(name, filters.get(name).repr() + "," + option));
        }
    }

    /** Adds a filter for one host only. */
    public synchronized void addPerDomainFilter(String domain, String name, String option) {
        Map<String, FilterProvider> pd = perDomainFilters.computeIfAbsent(domain, k -> new LinkedHashMap<>(filters));
        FilterProvider f = newFilterByName(name, option);
        if (!pd.containsKey(name)) {
            pd.put(name, f);
        } else {
            pd.put(name, newFilterByName(name, pd.get(name).repr() + "," + option));
        }
    }

    /** Adds a matcher. */
    public synchronized void addMatcher(String name, String option) {
        FilterProvider f = newFilterByName(name, option);
        if (!matchers.containsKey(name)) {
            matchers.put(name, f);
        } else {
            matchers.put(name, newFilterByName(name, matchers.get(name).repr() + "," + option));
        }
    }

    /** Removes the filter with that name. */
    public synchronized void removeFilter(String name) {
        filters.remove(name);
    }

    /** {@return a copy of the filters} */
    public synchronized Map<String, FilterProvider> getFilters() {
        return new LinkedHashMap<>(filters);
    }

    /** {@return a copy of the matchers} */
    public synchronized Map<String, FilterProvider> getMatchers() {
        return new LinkedHashMap<>(matchers);
    }

    /** {@return a copy of the filters that apply to a host, its own when it has any, the global ones otherwise} */
    public synchronized Map<String, FilterProvider> filtersForDomain(String domain) {
        Map<String, FilterProvider> pd = perDomainFilters.get(domain);
        return new LinkedHashMap<>(pd == null ? filters : pd);
    }

    /** {@return true when the host has been calibrated} */
    public synchronized boolean calibratedForDomain(String domain) {
        return perDomainFilters.containsKey(domain) && Boolean.TRUE.equals(perDomainCalibrated.get(domain));
    }

    private final Map<String, Boolean> perDomainCalibrated = new LinkedHashMap<>();

    /** Marks the host as calibrated or not, giving it its own filters. */
    public synchronized void setCalibratedForHost(String host, boolean value) {
        perDomainFilters.computeIfAbsent(host, k -> new LinkedHashMap<>(filters));
        perDomainCalibrated.put(host, value);
    }

    /** Marks the global filters as calibrated or not. */
    public synchronized void setCalibrated(boolean value) {
        calibrated = value;
    }

    /** {@return true when the global filters are calibrated} */
    public synchronized boolean calibrated() {
        return calibrated;
    }

    /** {@return the manager as it is serialised inside the configuration} */
    public synchronized io.github.ffuf.pkg.ffuf.Json.Obj toJson() {
        io.github.ffuf.pkg.ffuf.Json.Obj o = new io.github.ffuf.pkg.ffuf.Json.Obj();
        o.put("IsCalibrated", calibrated);
        o.put("Matchers", valuesJson(matchers));
        o.put("Filters", valuesJson(filters));
        Map<String, Object> pd = new java.util.TreeMap<>();
        for (Map.Entry<String, Map<String, FilterProvider>> e : perDomainFilters.entrySet()) {
            io.github.ffuf.pkg.ffuf.Json.Obj d = new io.github.ffuf.pkg.ffuf.Json.Obj();
            d.put("IsCalibrated", Boolean.TRUE.equals(perDomainCalibrated.get(e.getKey())));
            d.put("Filters", valuesJson(e.getValue()));
            pd.put(e.getKey(), d);
        }
        o.put("PerDomainFilters", pd);
        return o;
    }

    private static Map<String, Object> valuesJson(Map<String, FilterProvider> fs) {
        Map<String, Object> out = new java.util.TreeMap<>();
        for (Map.Entry<String, FilterProvider> e : fs.entrySet()) {
            out.put(e.getKey(), new io.github.ffuf.pkg.ffuf.Json.Obj().put("value", e.getValue().repr()));
        }
        return out;
    }

    /**
     * Decides whether a response is reported: it must pass the matchers and not the filters, combined with the given modes.
     *
     * @param perHost whether the per-host calibrated filters of hostUrl apply
     * @return true when the response is reported
     */
    public boolean matches(Response resp, boolean perHost, String matcherMode, String filterMode, String hostUrl) {
        boolean matched = false;
        Map<String, FilterProvider> fs = perHost ? filtersForDomain(hostUrl) : getFilters();
        for (FilterProvider m : getMatchers().values()) {
            if (m.filter(resp)) {
                matched = true;
            } else if (matcherMode.equals("and")) {
                return false;
            }
        }
        if (!matched) {
            return false;
        }
        for (FilterProvider f : fs.values()) {
            if (f.filter(resp)) {
                if (filterMode.equals("or")) {
                    return false;
                }
            } else if (filterMode.equals("and")) {
                return true;
            }
        }
        return fs.isEmpty() || !filterMode.equals("and");
    }
}
