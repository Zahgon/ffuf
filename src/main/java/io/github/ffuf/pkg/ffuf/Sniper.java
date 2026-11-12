package io.github.ffuf.pkg.ffuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds one request per template pair for sniper mode. */
public final class Sniper {
    private static final String KEYWORD = "FUZZ";

    private Sniper() {
    }

    /**
     * Expands the template markers of a request for the sniper mode.
     *
     * @return one request per pair of template markers, with that position selected for the keyword and the other
     *     markers removed
     */
    public static List<Request> requests(Request base, String template) {
        List<Request> reqs = new ArrayList<>();
        int c = Util.count(base.method, template);
        if (c > 0 && c % 2 == 0) {
            List<Integer> t = locations(template, base.method);
            for (int i = 0; i < t.size(); i += 2) {
                Request r = base.copy();
                r.method = inject(base.method, t.get(i), t.get(i + 1));
                scrub(r, template);
                reqs.add(r);
            }
        }
        c = Util.count(base.url, template);
        if (c > 0 && c % 2 == 0) {
            List<Integer> t = locations(template, base.url);
            for (int i = 0; i < t.size(); i += 2) {
                Request r = base.copy();
                r.url = inject(base.url, t.get(i), t.get(i + 1));
                scrub(r, template);
                reqs.add(r);
            }
        }
        String data = GoString.decode(base.data);
        c = Util.count(data, template);
        if (c > 0 && c % 2 == 0) {
            List<Integer> t = locations(template, data);
            for (int i = 0; i < t.size(); i += 2) {
                Request r = base.copy();
                r.data = GoString.encode(inject(data, t.get(i), t.get(i + 1)));
                scrub(r, template);
                reqs.add(r);
            }
        }
        for (Map.Entry<String, String> h : base.headers.entrySet()) {
            String k = h.getKey();
            String v = h.getValue();
            c = Util.count(k, template);
            if (c > 0 && c % 2 == 0) {
                List<Integer> t = locations(template, k);
                for (int i = 0; i < t.size(); i += 2) {
                    Request r = base.copy();
                    r.headers.remove(k);
                    r.headers.put(inject(k, t.get(i), t.get(i + 1)), v);
                    scrub(r, template);
                    reqs.add(r);
                }
            }
            c = Util.count(v, template);
            if (c > 0 && c % 2 == 0) {
                List<Integer> t = locations(template, v);
                for (int i = 0; i < t.size(); i += 2) {
                    Request r = base.copy();
                    r.headers.put(k, inject(v, t.get(i), t.get(i + 1)));
                    scrub(r, template);
                    reqs.add(r);
                }
            }
        }
        return reqs;
    }

    static List<Integer> locations(String template, String input) {
        List<Integer> out = new ArrayList<>();
        int first = template.codePointAt(0);
        int[] cps = input.codePoints().toArray();
        for (int k = 0; k < cps.length; k++) {
            if (cps[k] == first) {
                out.add(k);
            }
        }
        return out;
    }

    static String inject(String input, int start, int end) {
        int byteLen = input.getBytes(StandardCharsets.UTF_8).length;
        if (start > byteLen || end > byteLen || start > end) {
            return input;
        }
        int[] cps = input.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < start; i++) {
            sb.appendCodePoint(cps[i]);
        }
        sb.append(KEYWORD);
        for (int i = end + 1; i < cps.length; i++) {
            sb.appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    static void scrub(Request r, String template) {
        r.method = r.method.replace(template, "");
        r.url = r.url.replace(template, "");
        r.data = GoString.encode(GoString.decode(r.data).replace(template, ""));
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : r.headers.entrySet()) {
            String k = h.getKey();
            String v = h.getValue();
            int ck = Util.count(k, template);
            if (ck > 0 && ck % 2 == 0) {
                k = k.replace(template, "");
            }
            int cv = Util.count(v, template);
            if (cv > 0 && cv % 2 == 0) {
                v = v.replace(template, "");
            }
            headers.put(k, v);
        }
        r.headers = headers;
    }
}
