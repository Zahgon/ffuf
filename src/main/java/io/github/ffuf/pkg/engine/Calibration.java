package io.github.ffuf.pkg.engine;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.Request;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.ffuf.Util;
import io.github.ffuf.pkg.filter.FilterProvider;
import io.github.ffuf.pkg.output.Stdoutput;
import io.github.ffuf.pkg.runner.SimpleRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Autocalibration: probe with junk payloads and add size, word or line filters for the common answer. */
class Calibration {
    private static final Pattern ENTRY = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private final Config config;
    private final SimpleRunner runner;
    private final Stdoutput output;
    private final Function<Response, Boolean> isMatch;
    private final Runnable incError;

    Calibration(Config config, SimpleRunner runner, Stdoutput output, Function<Response, Boolean> isMatch, Runnable incError) {
        this.config = config;
        this.runner = runner;
        this.output = output;
        this.isMatch = isMatch;
        this.incError = incError;
    }

    /**
     * Merges the maps in insertion order: new keys are added after the known ones, and entries of a known key are
     * appended when missing.
     */
    private static void mergeMaps(Map<String, List<String>> merged, Map<String, List<String>> add) {
        for (Map.Entry<String, List<String>> e : add.entrySet()) {
            List<String> values = merged.get(e.getKey());
            if (values == null) {
                merged.put(e.getKey(), new ArrayList<>(e.getValue()));
                continue;
            }
            for (String v : e.getValue()) {
                if (!values.contains(v)) {
                    values.add(v);
                }
            }
        }
    }

    Map<String, List<String>> strings() {
        Map<String, List<String>> c = new LinkedHashMap<>();
        if (!config.autoCalibrationStrings.isEmpty()) {
            c.put("custom", new ArrayList<>(config.autoCalibrationStrings));
            return c;
        }
        for (String strategy : config.autoCalibrationStrategies) {
            String path = Util.configDir() + "/autocalibration/" + strategy + ".json";
            String json;
            try {
                json = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                output.warning("Skipping strategy \"" + strategy + "\" because of error: open " + path + ": no such file or directory\n");
                continue;
            } catch (IOException e) {
                output.warning("Skipping strategy \"" + strategy + "\" because of error: read " + path + ": is a directory\n");
                continue;
            }
            Map<String, List<String>> strategyMap = new LinkedHashMap<>();
            Matcher m = ENTRY.matcher(json);
            while (m.find()) {
                List<String> values = new ArrayList<>();
                Matcher s = STRING.matcher(m.group(2));
                while (s.find()) {
                    values.add(s.group(1).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\"));
                }
                strategyMap.put(m.group(1), values);
            }
            mergeMaps(c, strategyMap);
        }
        return c;
    }

    private Response request(Map<String, byte[]> inputs) {
        Request base = Request.base(config);
        Request req = runner.prepare(inputs, base);
        try {
            Response resp = runner.execute(req);
            return isMatch.apply(resp) ? resp : null;
        } catch (SimpleRunner.RequestException e) {
            output.error("Encountered an error while executing autocalibration request: " + e.getMessage() + "\n");
            incError.run();
            return null;
        }
    }

    void calibrateIfNeeded(String host, Map<String, byte[]> input) {
        if (!config.autoCalibration) {
            return;
        }
        if (config.autoCalibrationPerHost) {
            if (config.matcherManager.calibratedForDomain(host)) {
                return;
            }
            if (!input.containsKey(config.autoCalibrationKeyword)) {
                return;
            }
            Map<String, byte[]> in = new LinkedHashMap<>(input);
            for (List<String> v : strings().values()) {
                List<Response> responses = new ArrayList<>();
                for (String cs : v) {
                    in.put(config.autoCalibrationKeyword, cs.getBytes(StandardCharsets.UTF_8));
                    Response r = request(in);
                    if (r == null) {
                        continue;
                    }
                    responses.add(r);
                    if (!calibrateFilters(responses, true, host)) {
                        output.error("No common filtering values found");
                    }
                }
            }
            config.matcherManager.setCalibratedForHost(host, true);
            return;
        }
        if (config.matcherManager.calibrated()) {
            return;
        }
        Map<String, byte[]> in = new LinkedHashMap<>(input);
        for (List<String> v : strings().values()) {
            List<Response> responses = new ArrayList<>();
            for (String cs : v) {
                in.put(config.autoCalibrationKeyword, cs.getBytes(StandardCharsets.UTF_8));
                Response r = request(in);
                if (r != null) {
                    responses.add(r);
                }
            }
            calibrateFilters(responses, false, host);
        }
        config.matcherManager.setCalibrated(true);
    }

    private boolean calibrateFilters(List<Response> responses, boolean perHost, String host) {
        if (responses.isEmpty()) {
            return false;
        }
        String[] names = {"size", "word", "line"};
        List<ToLongFunction<Response>> metrics = List.of(r -> r.contentLength, r -> r.contentWords, r -> r.contentLines);
        for (int i = 0; i < names.length; i++) {
            ToLongFunction<Response> metric = metrics.get(i);
            long baseline = metric.applyAsLong(responses.get(0));
            boolean same = responses.stream().allMatch(r -> metric.applyAsLong(r) == baseline);
            if (!same) {
                continue;
            }
            Map<String, FilterProvider> current = perHost ? config.matcherManager.filtersForDomain(host) : config.matcherManager.getFilters();
            for (FilterProvider f : current.values()) {
                if (f.filter(responses.get(0))) {
                    return true;
                }
            }
            if (perHost) {
                config.matcherManager.addPerDomainFilter(host, names[i], Long.toString(baseline));
            } else {
                config.matcherManager.addFilter(names[i], Long.toString(baseline), false);
            }
            return true;
        }
        return false;
    }
}
