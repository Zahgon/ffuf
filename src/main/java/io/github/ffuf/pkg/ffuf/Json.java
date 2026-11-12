package io.github.ffuf.pkg.ffuf;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer that reproduces Go encoding/json output (HTML-safe escaping, base64 byte slices). */
public final class Json {
    private Json() {
    }

    /** An ordered JSON object. */
    public static class Obj {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        /**
         * Adds a field, keeping insertion order.
         *
         * @return this object
         */
        public Obj put(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            write(sb, this);
            return sb.toString();
        }
    }

    /** {@return the JSON text of a value} */
    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    /** Appends the JSON text of a value: null, an Obj, a map, a list, a byte array, a string, a number or a boolean. */
    public static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Obj o) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : o.fields.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : new java.util.TreeMap<>(m).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, l.get(i));
            }
            sb.append(']');
        } else if (value instanceof byte[] b) {
            sb.append('"').append(Base64.getEncoder().encodeToString(b)).append('"');
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else {
            sb.append(value);
        }
    }

    /** Appends s as a JSON string, escaping like Go's encoding/json with HTML-safe escaping. */
    public static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '<', '>', '&', ' ', ' ' -> sb.append(String.format("\\u%04x", (int) c));
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else if (GoString.isEscape(c)) {
                        sb.append("\\ufffd");
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** {@return the text of b as JSON output holds it: invalid UTF-8 becomes U+FFFD} */
    public static String goString(byte[] b) {
        return GoString.toValidUtf8(GoString.decode(b));
    }
}
