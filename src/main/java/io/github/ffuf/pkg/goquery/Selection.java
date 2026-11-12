package io.github.ffuf.pkg.goquery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** An ordered, duplicate-free set of nodes with the traversal and property calls ffuf uses. */
public final class Selection {
    private final List<Node> nodes;

    Selection(List<Node> nodes) {
        this.nodes = nodes;
    }

    /** {@return the selected nodes, in document order} */
    public List<Node> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /** {@return the number of nodes} */
    public int length() {
        return nodes.size();
    }

    /** {@return the i-th node as a one-node selection} */
    public Selection eq(int i) {
        return new Selection(List.of(nodes.get(i)));
    }

    /** {@return the first node, or an empty selection} */
    public Selection first() {
        return nodes.isEmpty() ? this : eq(0);
    }

    /**
     * Selects the descendants of every selected node that the CSS selector matches. A selector that does not
     * compile matches nothing.
     *
     * @return the matching descendants, in document order without duplicates
     */
    public Selection find(String selector) {
        Matchers.Matcher m;
        try {
            m = SelectorParser.parseGroup(GoStrings.encode(selector));
        } catch (SelectorParser.SyntaxException e) {
            return new Selection(List.of());
        }
        List<Node> result = new ArrayList<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node n : nodes) {
            List<Node> found = new ArrayList<>();
            for (Node c = n.firstChild; c != null; c = c.nextSibling) {
                if (c.type == Node.Type.ELEMENT) {
                    matchAll(m, c, found);
                }
            }
            for (Node f : found) {
                if (seen.add(f)) {
                    result.add(f);
                }
            }
        }
        return new Selection(result);
    }

    private static void matchAll(Matchers.Matcher m, Node n, List<Node> out) {
        if (m.match(n)) {
            out.add(n);
        }
        for (Node c = n.firstChild; c != null; c = c.nextSibling) {
            matchAll(m, c, out);
        }
    }

    /** {@return the data of every descendant text node, concatenated without any whitespace change} */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            textHelper(n, sb);
        }
        return GoStrings.decode(sb.toString());
    }

    private static void textHelper(Node n, StringBuilder sb) {
        if (n.type == Node.Type.TEXT) {
            sb.append(n.data);
        }
        for (Node c = n.firstChild; c != null; c = c.nextSibling) {
            textHelper(c, sb);
        }
    }

    /** {@return the first node's attribute value, if that attribute is present} */
    public Optional<String> attr(String name) {
        if (nodes.isEmpty()) {
            return Optional.empty();
        }
        String key = GoStrings.encode(name);
        for (Node.Attribute a : nodes.get(0).attr) {
            if (a.key.equals(key)) {
                return Optional.of(GoStrings.decode(a.val));
            }
        }
        return Optional.empty();
    }

    /** {@return the attribute value of the first node, or dflt when it is absent} */
    public String attrOr(String name, String dflt) {
        return attr(name).orElse(dflt);
    }

    /** {@return the name of the first node: the tag of an element or doctype, "#text" and the like for other nodes, "" when empty} */
    public String nodeName() {
        if (nodes.isEmpty()) {
            return "";
        }
        Node n = nodes.get(0);
        return switch (n.type) {
            case ELEMENT, DOCTYPE -> GoStrings.decode(n.data);
            case TEXT -> "#text";
            case DOCUMENT -> "#document";
            case COMMENT -> "#comment";
            case ERROR -> "#error";
            default -> "";
        };
    }
}
