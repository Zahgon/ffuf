package io.github.ffuf.pkg.filter;

import io.github.ffuf.pkg.ffuf.Response;
import io.github.ffuf.pkg.goregexp.GoRegexp;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Matches a regular expression against the response headers and body; keywords in it become payload literals. */
public class RegexpFilter implements FilterProvider {
    private final String valueRaw;
    private final GoRegexp value;

    /**
     * Creates the filter for a regular expression.
     *
     * @throws IllegalArgumentException when the expression does not compile
     */
    public RegexpFilter(String value) {
        try {
            this.value = GoRegexp.compile(value);
        } catch (GoRegexp.SyntaxException e) {
            throw new IllegalArgumentException("Regexp filter or matcher (-fr / -mr): invalid value: " + value);
        }
        this.valueRaw = value;
    }

    @Override
    public boolean filter(Response response) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (Map.Entry<String, List<String>> e : response.headers.entrySet()) {
            for (String v : e.getValue()) {
                data.writeBytes((e.getKey() + ": " + v + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        data.writeBytes(response.data);
        byte[] raw = valueRaw.getBytes(StandardCharsets.UTF_8);
        byte[] pattern = raw;
        if (response.request != null) {
            for (Map.Entry<String, byte[]> e : response.request.input.entrySet()) {
                pattern = replaceAll(pattern, e.getKey().getBytes(StandardCharsets.UTF_8), GoRegexp.quoteMeta(e.getValue()));
            }
        }
        GoRegexp re = value;
        if (!Arrays.equals(pattern, raw)) {
            try {
                re = GoRegexp.compile(pattern);
            } catch (GoRegexp.SyntaxException e) {
                return false;
            }
        }
        return re.match(data.toByteArray());
    }

    /** strings.ReplaceAll on bytes for a non-empty {@code old}. */
    private static byte[] replaceAll(byte[] s, byte[] old, byte[] with) {
        if (old.length == 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < s.length) {
            if (i + old.length <= s.length && Arrays.equals(s, i, i + old.length, old, 0, old.length)) {
                out.writeBytes(with);
                i += old.length;
            } else {
                out.write(s[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    @Override
    public String repr() {
        return valueRaw;
    }

    @Override
    public String reprVerbose() {
        return "Regexp: " + valueRaw;
    }
}
