package io.github.ffuf.pkg.goquery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One node of an HTML document tree. Every string a node holds (data,
 * namespace, attribute keys and values) is a byte string: each char is one byte of the original UTF-8 text, so the
 * tree keeps the exact bytes of the parsed document. {@link GoStrings#decode} turns such a string into Java text.
 */
public final class Node {
    /** The node kinds of a parsed document, in the order golang.org/x/net/html declares them. */
    public enum Type {
        ERROR, TEXT, DOCUMENT, ELEMENT, COMMENT, DOCTYPE, RAW, SCOPE_MARKER
    }

    /** One attribute: namespace, key and value, all byte strings. */
    public static final class Attribute {
        String namespace;
        String key;
        String val;

        Attribute(String namespace, String key, String val) {
            this.namespace = namespace;
            this.key = key;
            this.val = val;
        }

        /** {@return the attribute's namespace} */
        public String namespace() {
            return namespace;
        }

        /** {@return the attribute's name} */
        public String key() {
            return key;
        }

        /** {@return the attribute's value} */
        public String val() {
            return val;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Attribute a && namespace.equals(a.namespace) && key.equals(a.key) && val.equals(a.val);
        }

        @Override
        public int hashCode() {
            return Objects.hash(namespace, key, val);
        }
    }

    Node parent;
    Node firstChild;
    Node lastChild;
    Node prevSibling;
    Node nextSibling;
    Type type;
    /** The tag name when it is a known element, "" otherwise. */
    String atom = "";
    String data = "";
    String namespace = "";
    List<Attribute> attr;

    Node(Type type, String atom, String data, List<Attribute> attr) {
        this.type = type;
        this.atom = atom;
        this.data = data;
        this.attr = attr == null ? new ArrayList<>() : attr;
    }

    static Node text(String data) {
        return new Node(Type.TEXT, "", data, null);
    }

    static Node comment(String data) {
        return new Node(Type.COMMENT, "", data, null);
    }

    /** {@return the node type} */
    public Type type() {
        return type;
    }

    /** {@return the tag name, text or comment data of the node} */
    public String data() {
        return data;
    }

    /** {@return the element's namespace} */
    public String namespace() {
        return namespace;
    }

    /** {@return the node's attributes} */
    public List<Attribute> attributes() {
        return attr;
    }

    /** {@return the parent node, or null} */
    public Node parent() {
        return parent;
    }

    /** {@return the first child, or null} */
    public Node firstChild() {
        return firstChild;
    }

    /** {@return the next sibling, or null} */
    public Node nextSibling() {
        return nextSibling;
    }

    void insertBefore(Node newChild, Node oldChild) {
        if (newChild.parent != null || newChild.prevSibling != null || newChild.nextSibling != null) {
            throw new HtmlParser.ParseException("html: InsertBefore called for an attached child Node");
        }
        Node prev;
        Node next;
        if (oldChild != null) {
            prev = oldChild.prevSibling;
            next = oldChild;
        } else {
            prev = lastChild;
            next = null;
        }
        if (prev != null) {
            prev.nextSibling = newChild;
        } else {
            firstChild = newChild;
        }
        if (next != null) {
            next.prevSibling = newChild;
        } else {
            lastChild = newChild;
        }
        newChild.parent = this;
        newChild.prevSibling = prev;
        newChild.nextSibling = next;
    }

    void appendChild(Node c) {
        if (c.parent != null || c.prevSibling != null || c.nextSibling != null) {
            throw new HtmlParser.ParseException("html: AppendChild called for an attached child Node");
        }
        Node last = lastChild;
        if (last != null) {
            last.nextSibling = c;
        } else {
            firstChild = c;
        }
        lastChild = c;
        c.parent = this;
        c.prevSibling = last;
    }

    void removeChild(Node c) {
        if (c.parent != this) {
            throw new HtmlParser.ParseException("html: RemoveChild called for a non-child Node");
        }
        if (firstChild == c) {
            firstChild = c.nextSibling;
        }
        if (c.nextSibling != null) {
            c.nextSibling.prevSibling = c.prevSibling;
        }
        if (lastChild == c) {
            lastChild = c.prevSibling;
        }
        if (c.prevSibling != null) {
            c.prevSibling.nextSibling = c.nextSibling;
        }
        c.parent = null;
        c.prevSibling = null;
        c.nextSibling = null;
    }

    /** Copies type, tag and attributes but not the namespace. */
    Node copyNode() {
        List<Attribute> copy = new ArrayList<>(attr.size());
        for (Attribute a : attr) {
            copy.add(new Attribute(a.namespace, a.key, a.val));
        }
        return new Node(type, atom, data, copy);
    }
}
