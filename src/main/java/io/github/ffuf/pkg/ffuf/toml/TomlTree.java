package io.github.ffuf.pkg.ffuf.toml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed TOML table whose entries are leaf values, sub-tables or arrays of tables. Leaf values are
 * {@link String}, {@link Long}, {@link GoUint64}, {@link Double}, {@link Boolean}, {@link TomlDateTime} or a
 * {@link List} of values (arrays may hold inline tables too).
 */
public final class TomlTree {
    /** A leaf value with the position of the key that set it. */
    record Leaf(Object value, Position position) {
    }

    /** An array of tables, from {@code [[name]]} headers or an array made only of inline tables. */
    public record TreeArray(List<TomlTree> trees) {
    }

    final Map<String, Object> values = new HashMap<>();
    boolean inline;
    Position position;

    TomlTree(Position position) {
        this.position = position;
    }

    /** go-toml's {@code GetPath}: the leaf value or node at the path, or null. */
    Object getPath(List<String> keys) {
        if (keys.isEmpty()) {
            return this;
        }
        TomlTree subtree = this;
        for (String k : keys.subList(0, keys.size() - 1)) {
            Object value = subtree.values.get(k);
            if (value instanceof TomlTree t) {
                subtree = t;
            } else if (value instanceof TreeArray a && !a.trees().isEmpty()) {
                subtree = a.trees().get(a.trees().size() - 1);
            } else {
                return null;
            }
        }
        Object node = subtree.values.get(keys.get(keys.size() - 1));
        return node instanceof Leaf l ? l.value() : node;
    }

    /** {@return whether this table directly holds the key} */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** {@return the leaf value or node stored directly under the key, or null} */
    public Object get(String key) {
        return getPath(List.of(key));
    }

    /** {@return the position of a direct key in the document, as go-toml's {@code GetPositionPath} reports it} */
    public Position positionOf(String key) {
        Object node = values.get(key);
        if (node instanceof Leaf l) {
            return l.position();
        }
        if (node instanceof TomlTree t) {
            return t.position;
        }
        if (node instanceof TreeArray a && !a.trees().isEmpty()) {
            return a.trees().get(a.trees().size() - 1).position;
        }
        return Position.NONE;
    }

    /** go-toml's {@code SetPath}, including its habit of staying on the current table when a step is a leaf. */
    void setPath(List<String> keys, Object value) {
        TomlTree subtree = this;
        for (int i = 0; i < keys.size() - 1; i++) {
            String k = keys.get(i);
            Object next = subtree.values.get(k);
            if (next == null) {
                next = new TomlTree(new Position(position.line() + i, position.col()));
                subtree.values.put(k, next);
            }
            if (next instanceof TomlTree t) {
                subtree = t;
            } else if (next instanceof TreeArray a) {
                if (a.trees().isEmpty()) {
                    a.trees().add(new TomlTree(new Position(position.line() + i, position.col())));
                }
                subtree = a.trees().get(a.trees().size() - 1);
            }
        }
        Object toInsert = value;
        if (!(value instanceof TomlTree) && !(value instanceof TreeArray)) {
            toInsert = new Leaf(value, new Position(subtree.position.line() + subtree.values.size() + 1,
                    subtree.position.col()));
        }
        subtree.values.put(keys.get(keys.size() - 1), toInsert);
    }

    /** go-toml's {@code createSubTree}; returns its error text, or null on success. */
    String createSubTree(List<String> keys, Position pos) {
        TomlTree subtree = this;
        for (String k : keys) {
            Object next = subtree.values.get(k);
            if (next == null) {
                TomlTree tree = new TomlTree(pos);
                tree.inline = subtree.inline;
                subtree.values.put(k, tree);
                next = tree;
            }
            if (next instanceof TreeArray a) {
                subtree = a.trees().get(a.trees().size() - 1);
            } else if (next instanceof TomlTree t) {
                subtree = t;
            } else {
                Leaf leaf = (Leaf) next;
                return "unknown type for path " + String.join(".", keys) + " (" + k + "): *toml.tomlValue "
                        + "(&toml.tomlValue{value:" + GoFmt.sharpV(leaf.value())
                        + ", comment:\"\", commented:false, multiline:false, literal:false, position:toml.Position{Line:"
                        + leaf.position().line() + ", Col:" + leaf.position().col() + "}})";
            }
        }
        return null;
    }

    /** {@return the document go-toml's {@code Tree.String} writes: keys sorted, plain values before tables} */
    public String toTomlString() {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, "", "");
        return sb.toString();
    }

    private List<String> sortedKeys() {
        List<String> simple = new ArrayList<>();
        List<String> complex = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            (e.getValue() instanceof Leaf ? simple : complex).add(e.getKey());
        }
        simple.sort(TomlTree::compareBytes);
        complex.sort(TomlTree::compareBytes);
        simple.addAll(complex);
        return simple;
    }

    private static int compareBytes(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void writeTo(StringBuilder sb, String indent, String keyspace) {
        for (String k : sortedKeys()) {
            Object v = values.get(k);
            if (v instanceof Leaf l) {
                sb.append(indent).append(quoteKeyIfNeeded(k)).append(" = ").append(valueRepr(l.value())).append('\n');
                continue;
            }
            String combinedKey = keyspace.isEmpty() ? quoteKeyIfNeeded(k) : keyspace + "." + quoteKeyIfNeeded(k);
            if (v instanceof TomlTree t) {
                sb.append('\n').append(indent).append('[').append(combinedKey).append("]\n");
                t.writeTo(sb, indent + "  ", combinedKey);
            } else {
                for (TomlTree t : ((TreeArray) v).trees()) {
                    sb.append('\n').append(indent).append("[[").append(combinedKey).append("]]\n");
                    t.writeTo(sb, indent + "  ", combinedKey);
                }
            }
        }
    }

    private String inlineRepr() {
        List<String> parts = new ArrayList<>();
        for (String k : sortedKeys()) {
            Object v = values.get(k);
            parts.add(quoteKeyIfNeeded(k) + " = " + valueRepr(v instanceof Leaf l ? l.value() : v));
        }
        return "{ " + String.join(", ", parts) + " }";
    }

    private static String valueRepr(Object v) {
        if (v instanceof GoUint64 u) {
            return Long.toUnsignedString(u.bits());
        }
        if (v instanceof Double d) {
            if (!d.isNaN() && (d.isInfinite() || Math.floor(d) == d)) {
                return GoFmt.formatOneDecimal(d).toLowerCase();
            }
            boolean float32 = !d.isNaN() && (double) d.floatValue() == d;
            return GoFmt.formatShortestFixed(d, float32).toLowerCase();
        }
        if (v instanceof String s) {
            return "\"" + encodeTomlString(s) + "\"";
        }
        if (v instanceof TomlDateTime t) {
            return t.tomlString();
        }
        if (v instanceof TomlTree t) {
            return t.inlineRepr();
        }
        if (v instanceof TreeArray a) {
            List<String> parts = new ArrayList<>();
            for (TomlTree t : a.trees()) {
                parts.add(t.inlineRepr());
            }
            return "[" + String.join(", ", parts) + "]";
        }
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object o : l) {
                parts.add(valueRepr(o));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        return String.valueOf(v);
    }

    private static String quoteKeyIfNeeded(String k) {
        if (k.length() >= 2 && k.charAt(0) == '"' && k.charAt(k.length() - 1) == '"') {
            return k;
        }
        if (k.codePoints().allMatch(Lexer::isValidBareChar)) {
            return k;
        }
        return "\"" + encodeTomlString(k) + "\"";
    }

    private static String encodeTomlString(String value) {
        StringBuilder b = new StringBuilder();
        value.codePoints().forEach(rr -> {
            switch (rr) {
                case '\b' -> b.append("\\b");
                case '\t' -> b.append("\\t");
                case '\n' -> b.append("\\n");
                case '\f' -> b.append("\\f");
                case '\r' -> b.append("\\r");
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    int low = rr & 0xFFFF;
                    if (low < 0x001F) {
                        b.append(String.format("\\u%04X", low));
                    } else {
                        b.appendCodePoint(rr);
                    }
                }
            }
        });
        return b.toString();
    }
}
