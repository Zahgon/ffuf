package io.github.ffuf.pkg.goquery;

import java.util.List;
import java.util.function.Predicate;
import io.github.ffuf.pkg.goregexp.GoRegexp;

/** The CSS selector kinds, matched over {@link Node}s with the semantics of cascadia v1.3.5. */
final class Matchers {
    private Matchers() {
    }

    /** A compiled selector or selector part. */
    interface Matcher {
        boolean match(Node n);
    }

    static boolean isElement(Node n) {
        return n.type == Node.Type.ELEMENT;
    }

    static boolean matchAttribute(Node n, String key, Predicate<String> f) {
        if (!isElement(n)) {
            return false;
        }
        for (Node.Attribute a : n.attr) {
            if (a.key.equals(key) && f.test(a.val)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasAttr(Node n, String key) {
        return matchAttribute(n, key, v -> true);
    }

    static boolean matchInsensitiveValue(String userAttr, String realAttr, boolean ignoreCase) {
        return ignoreCase ? GoStrings.equalFold(userAttr, realAttr) : userAttr.equals(realAttr);
    }

    static boolean matchInclude(String val, String s, boolean ignoreCase) {
        while (!s.isEmpty()) {
            int i = -1;
            for (int k = 0; k < s.length(); k++) {
                if (" \t\r\n\f".indexOf(s.charAt(k)) >= 0) {
                    i = k;
                    break;
                }
            }
            if (i == -1) {
                return matchInsensitiveValue(s, val, ignoreCase);
            }
            if (matchInsensitiveValue(s.substring(0, i), val, ignoreCase)) {
                return true;
            }
            s = s.substring(i + 1);
        }
        return false;
    }

    /** Element with the given tag name. */
    record Tag(String tag) implements Matcher {
        @Override
        public boolean match(Node n) {
            return isElement(n) && n.data.equals(tag);
        }
    }

    /** .class */
    record ClassName(String className) implements Matcher {
        @Override
        public boolean match(Node n) {
            return matchAttribute(n, "class", s -> matchInclude(className, s, false));
        }
    }

    /** #id */
    record Id(String id) implements Matcher {
        @Override
        public boolean match(Node n) {
            return matchAttribute(n, "id", s -> s.equals(id));
        }
    }

    /** [key], [key=val] and the other attribute operators. */
    record Attr(String key, String val, String operation, GoRegexp regexp, boolean insensitive) implements Matcher {
        @Override
        public boolean match(Node n) {
            switch (operation) {
                case "" -> {
                    return hasAttr(n, key);
                }
                case "=" -> {
                    return matchAttribute(n, key, s -> matchInsensitiveValue(s, val, insensitive));
                }
                case "!=" -> {
                    if (!isElement(n)) {
                        return false;
                    }
                    for (Node.Attribute a : n.attr) {
                        if (a.key.equals(key) && matchInsensitiveValue(a.val, val, insensitive)) {
                            return false;
                        }
                    }
                    return true;
                }
                case "~=" -> {
                    return matchAttribute(n, key, s -> matchInclude(val, s, insensitive));
                }
                case "|=" -> {
                    return matchAttribute(n, key, s -> {
                        if (matchInsensitiveValue(s, val, insensitive)) {
                            return true;
                        }
                        if (s.length() <= val.length()) {
                            return false;
                        }
                        return matchInsensitiveValue(s.substring(0, val.length()), val, insensitive)
                                && s.charAt(val.length()) == '-';
                    });
                }
                case "^=" -> {
                    return matchAttribute(n, key, s -> !GoStrings.isBlank(s) && (insensitive
                            ? GoStrings.toLower(s).startsWith(GoStrings.toLower(val)) : s.startsWith(val)));
                }
                case "$=" -> {
                    return matchAttribute(n, key, s -> !GoStrings.isBlank(s) && (insensitive
                            ? GoStrings.toLower(s).endsWith(GoStrings.toLower(val)) : s.endsWith(val)));
                }
                case "*=" -> {
                    return matchAttribute(n, key, s -> !GoStrings.isBlank(s) && (insensitive
                            ? GoStrings.toLower(s).contains(GoStrings.toLower(val)) : s.contains(val)));
                }
                case "#=" -> {
                    return matchAttribute(n, key, s -> regexp.matchString(GoStrings.decode(s)));
                }
                default -> throw new IllegalStateException("unsupported operation: " + operation);
            }
        }
    }

    /** :visited, :hover and the other dynamic states, which never match. */
    record Never() implements Matcher {
        @Override
        public boolean match(Node n) {
            return false;
        }
    }

    /** A sequence of simple selectors on one element; empty means the universal selector. */
    record Compound(List<Matcher> selectors) implements Matcher {
        @Override
        public boolean match(Node n) {
            if (selectors.isEmpty()) {
                return isElement(n);
            }
            for (Matcher m : selectors) {
                if (!m.match(n)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Two selectors joined by a combinator: ' ', '>', '+' or '~'. */
    record Combined(Matcher first, char combinator, Matcher second) implements Matcher {
        @Override
        public boolean match(Node n) {
            switch (combinator) {
                case ' ' -> {
                    if (!second.match(n)) {
                        return false;
                    }
                    for (Node p = n.parent; p != null; p = p.parent) {
                        if (first.match(p)) {
                            return true;
                        }
                    }
                    return false;
                }
                case '>' -> {
                    return second.match(n) && n.parent != null && first.match(n.parent);
                }
                case '+' -> {
                    if (!second.match(n)) {
                        return false;
                    }
                    for (Node c = n.prevSibling; c != null; c = c.prevSibling) {
                        if (c.type == Node.Type.TEXT || c.type == Node.Type.COMMENT) {
                            continue;
                        }
                        return first.match(c);
                    }
                    return false;
                }
                case '~' -> {
                    if (!second.match(n)) {
                        return false;
                    }
                    for (Node c = n.prevSibling; c != null; c = c.prevSibling) {
                        if (first.match(c)) {
                            return true;
                        }
                    }
                    return false;
                }
                default -> throw new IllegalStateException("unknown combinator");
            }
        }
    }

    /** A comma-separated selector group. */
    record Group(List<Matcher> selectors) implements Matcher {
        @Override
        public boolean match(Node n) {
            for (Matcher m : selectors) {
                if (m.match(n)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** :not, :has and :haschild. */
    record Relative(String name, Group group) implements Matcher {
        @Override
        public boolean match(Node n) {
            if (!isElement(n)) {
                return false;
            }
            return switch (name) {
                case "not" -> !group.match(n);
                case "has" -> hasDescendant(n);
                default -> hasChild(n);
            };
        }

        private boolean hasChild(Node n) {
            for (Node c = n.firstChild; c != null; c = c.nextSibling) {
                if (group.match(c)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasDescendant(Node n) {
            for (Node c = n.firstChild; c != null; c = c.nextSibling) {
                if (group.match(c) || (isElement(c) && hasDescendant(c))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void writeNodeText(Node n, StringBuilder b) {
        if (n.type == Node.Type.TEXT) {
            b.append(n.data);
        } else if (n.type == Node.Type.ELEMENT) {
            for (Node c = n.firstChild; c != null; c = c.nextSibling) {
                writeNodeText(c, b);
            }
        }
    }

    static String nodeText(Node n) {
        StringBuilder b = new StringBuilder();
        writeNodeText(n, b);
        return b.toString();
    }

    static String nodeOwnText(Node n) {
        StringBuilder b = new StringBuilder();
        for (Node c = n.firstChild; c != null; c = c.nextSibling) {
            if (c.type == Node.Type.TEXT) {
                b.append(c.data);
            }
        }
        return b.toString();
    }

    /** :contains and :containsOwn; value is already lower-cased. Matches any node kind, as cascadia does. */
    record Contains(String value, boolean own) implements Matcher {
        @Override
        public boolean match(Node n) {
            String text = GoStrings.toLower(own ? nodeOwnText(n) : nodeText(n));
            return text.contains(value);
        }
    }

    /** :matches and :matchesOwn. Matches any node kind, as cascadia does. */
    record Regexp(GoRegexp regexp, boolean own) implements Matcher {
        @Override
        public boolean match(Node n) {
            String text = own ? nodeOwnText(n) : nodeText(n);
            return regexp.matchString(GoStrings.decode(text));
        }
    }

    private static boolean counts(Node c, Node n, boolean ofType) {
        return isElement(c) && !(ofType && !c.data.equals(n.data));
    }

    /** :nth-child and relatives, including :first-child and :last-of-type. */
    record Nth(long a, long b, boolean last, boolean ofType) implements Matcher {
        @Override
        public boolean match(Node n) {
            if (!isElement(n) || n.parent == null) {
                return false;
            }
            if (a == 0) {
                long count = 0;
                for (Node c = last ? n.parent.lastChild : n.parent.firstChild; c != null;
                        c = last ? c.prevSibling : c.nextSibling) {
                    if (!counts(c, n, ofType)) {
                        continue;
                    }
                    count++;
                    if (c == n) {
                        return count == b;
                    }
                    if (count >= b) {
                        return false;
                    }
                }
                return false;
            }
            long i = -1;
            long count = 0;
            for (Node c = n.parent.firstChild; c != null; c = c.nextSibling) {
                if (!counts(c, n, ofType)) {
                    continue;
                }
                count++;
                if (c == n) {
                    i = count;
                    if (!last) {
                        break;
                    }
                }
            }
            if (i == -1) {
                return false;
            }
            if (last) {
                i = count - i + 1;
            }
            i -= b;
            return i % a == 0 && i / a >= 0;
        }
    }

    /** :only-child and :only-of-type. */
    record OnlyChild(boolean ofType) implements Matcher {
        @Override
        public boolean match(Node n) {
            if (!isElement(n) || n.parent == null) {
                return false;
            }
            int count = 0;
            for (Node c = n.parent.firstChild; c != null; c = c.nextSibling) {
                if (!counts(c, n, ofType)) {
                    continue;
                }
                count++;
                if (count > 1) {
                    return false;
                }
            }
            return count == 1;
        }
    }

    /** :input */
    record Input() implements Matcher {
        @Override
        public boolean match(Node n) {
            return isElement(n) && (n.data.equals("input") || n.data.equals("select") || n.data.equals("textarea")
                    || n.data.equals("button"));
        }
    }

    /** :empty, where whitespace-only text still counts as empty. */
    record Empty() implements Matcher {
        @Override
        public boolean match(Node n) {
            if (!isElement(n)) {
                return false;
            }
            for (Node c = n.firstChild; c != null; c = c.nextSibling) {
                if (isElement(c)) {
                    return false;
                }
                if (c.type == Node.Type.TEXT && !GoStrings.isBlank(c.data)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** :root */
    record Root() implements Matcher {
        @Override
        public boolean match(Node n) {
            return isElement(n) && n.parent != null && n.parent.type == Node.Type.DOCUMENT;
        }
    }

    /** :link */
    record Link() implements Matcher {
        @Override
        public boolean match(Node n) {
            return (n.atom.equals("a") || n.atom.equals("area") || n.atom.equals("link")) && hasAttr(n, "href");
        }
    }

    /** :lang(x), inherited from ancestors; like cascadia it also matches text nodes inside such elements. */
    record Lang(String lang) implements Matcher {
        @Override
        public boolean match(Node n) {
            for (Node c = n; c != null; c = c.parent) {
                if (matchAttribute(c, "lang", v -> v.equals(lang) || v.startsWith(lang + "-"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean inDisabledFieldset(Node n) {
        for (; n.parent != null; n = n.parent) {
            Node p = n.parent;
            if (p.atom.equals("fieldset") && hasAttr(p, "disabled")
                    && (!n.atom.equals("legend") || hasLegendInPreviousSiblings(n))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLegendInPreviousSiblings(Node n) {
        for (Node s = n.prevSibling; s != null; s = s.prevSibling) {
            if (s.atom.equals("legend")) {
                return true;
            }
        }
        return false;
    }

    /** :enabled */
    record Enabled() implements Matcher {
        @Override
        public boolean match(Node n) {
            if (!isElement(n)) {
                return false;
            }
            return switch (n.atom) {
                case "a", "area", "link" -> hasAttr(n, "href");
                case "optgroup", "menuitem", "fieldset" -> !hasAttr(n, "disabled");
                case "button", "input", "select", "textarea", "option" -> !hasAttr(n, "disabled") && !inDisabledFieldset(n);
                default -> false;
            };
        }
    }

    /** :disabled */
    record Disabled() implements Matcher {
        @Override
        public boolean match(Node n) {
            if (!isElement(n)) {
                return false;
            }
            return switch (n.atom) {
                case "optgroup", "menuitem", "fieldset" -> hasAttr(n, "disabled");
                case "button", "input", "select", "textarea", "option" -> hasAttr(n, "disabled") || inDisabledFieldset(n);
                default -> false;
            };
        }
    }

    /** :checked */
    record Checked() implements Matcher {
        @Override
        public boolean match(Node n) {
            if (!isElement(n)) {
                return false;
            }
            return switch (n.atom) {
                case "input", "menuitem" -> hasAttr(n, "checked") && matchAttribute(n, "type", v -> {
                    String t = GoStrings.toLowerAscii(v);
                    return t.equals("checkbox") || t.equals("radio");
                });
                case "option" -> hasAttr(n, "selected");
                default -> false;
            };
        }
    }
}
