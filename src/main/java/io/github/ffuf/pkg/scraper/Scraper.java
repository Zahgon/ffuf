package io.github.ffuf.pkg.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ffuf.pkg.ffuf.MultiError;
import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.goquery.Document;
import io.github.ffuf.pkg.goquery.Selection;
import io.github.ffuf.pkg.goregexp.GoRegexp;
import io.github.ffuf.pkg.input.GoJson;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scraper rules from the configuration folder and -scraperfile, run against every response. */
public class Scraper {
    /** One rule of a scraper group. */
    static final class Rule {
        String name = "";
        String rule = "";
        String target = "";
        String type = "";
        boolean onlyMatched;
        List<String> action = new ArrayList<>();
        GoRegexp compiled;
    }

    private final List<Rule> rules = new ArrayList<>();

    /**
     * Loads the scraper rules from the JSON files in dirname, keeping the active groups; problems are added to errs.
     *
     * @param active comma-separated names of the groups to load; "all" loads every group marked active
     * @return the scraper, also when errs holds errors
     */
    public static Scraper fromDir(String dirname, String active, MultiError errs) {
        Scraper s = new Scraper();
        List<String> groups = new ArrayList<>();
        for (String v : active.split(",", -1)) {
            groups.add(v.strip().toLowerCase());
        }
        File[] files = new File(dirname).listFiles();
        if (files == null) {
            errs.add("open " + dirname + ": no such file or directory");
            return s;
        }
        Arrays.sort(files);
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) {
                continue;
            }
            String path = Paths.get(dirname, f.getName()).toString();
            JsonNode root;
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                String syntax = GoJson.checkValid(data);
                if (syntax != null) {
                    errs.add(path + " : " + syntax);
                    continue;
                }
                root = new ObjectMapper().readTree(data);
            } catch (IOException e) {
                errs.add(path + " : invalid character in JSON input");
                continue;
            }
            boolean groupActive = root.path("active").asBoolean(false);
            String name = root.path("groupname").asText("");
            if ((groupActive && groups.contains("all")) || groups.contains(name.strip().toLowerCase())) {
                for (JsonNode r : root.path("rules")) {
                    try {
                        s.rules.add(parseRule(r));
                    } catch (GoRegexp.SyntaxException e) {
                        errs.add(path + " : " + e.getMessage());
                    }
                }
            }
        }
        return s;
    }

    /**
     * Appends the valid rules of one scraper file.
     *
     * @return a read error, the regexp error of the last rule, or null
     */
    public String appendFromFile(String path) {
        JsonNode root;
        try {
            byte[] data = Files.readAllBytes(Paths.get(path));
            String syntax = GoJson.checkValid(data);
            if (syntax != null) {
                return syntax;
            }
            root = new ObjectMapper().readTree(data);
        } catch (NoSuchFileException e) {
            return "open " + path + ": no such file or directory";
        } catch (IOException e) {
            return "invalid character in JSON input";
        }
        String err = null;
        for (JsonNode r : root.path("rules")) {
            try {
                rules.add(parseRule(r));
                err = null;
            } catch (GoRegexp.SyntaxException e) {
                err = e.getMessage();
            }
        }
        return err;
    }

    private static Rule parseRule(JsonNode n) throws GoRegexp.SyntaxException {
        Rule r = new Rule();
        r.name = n.path("name").asText("");
        r.rule = n.path("rule").asText("");
        r.target = n.path("target").asText("");
        r.type = n.path("type").asText("");
        r.onlyMatched = n.path("onlymatched").asBoolean(false);
        for (JsonNode a : n.path("action")) {
            r.action.add(a.asText());
        }
        if (r.type.equals("regexp")) {
            r.compiled = GoRegexp.compile(r.rule);
        }
        return r;
    }

    /**
     * Runs every rule on the response.
     *
     * @param matched whether the response was reported, for rules limited to matches
     * @return name to results for the rules that found something
     */
    public Map<String, List<String>> execute(Response resp, boolean matched) {
        Map<String, List<String>> res = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (!matched && rule.onlyMatched) {
                continue;
            }
            ByteArrayOutputStream source = new ByteArrayOutputStream();
            if (!rule.target.equals("body")) {
                source.writeBytes(headerString(resp.headers).getBytes(StandardCharsets.UTF_8));
            }
            if (!rule.target.equals("headers")) {
                source.writeBytes(resp.data);
            }
            List<String> val = check(rule, source.toByteArray());
            if (!val.isEmpty()) {
                res.put(rule.name, val);
            }
        }
        return res;
    }

    private static List<String> check(Rule rule, byte[] data) {
        List<String> val = new ArrayList<>();
        if (rule.type.equals("regexp") && rule.compiled != null) {
            for (byte[][] groups : rule.compiled.findAllSubmatch(data, -1)) {
                for (byte[] g : groups) {
                    val.add(g == null ? "" : new String(g, StandardCharsets.UTF_8));
                }
            }
        } else if (rule.type.equals("query")) {
            Selection doc = Document.parse(data);
            if (doc == null) {
                return new ArrayList<>();
            }
            Selection found = doc.find(rule.rule);
            for (int i = 0; i < found.length(); i++) {
                val.add(found.eq(i).text());
            }
        }
        return val;
    }

    private static String headerString(Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            for (String v : e.getValue()) {
                sb.append(e.getKey()).append(": ").append(v).append('\n');
            }
        }
        return sb.toString();
    }
}
