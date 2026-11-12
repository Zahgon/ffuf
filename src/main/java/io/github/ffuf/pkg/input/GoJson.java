package io.github.ffuf.pkg.input;

import io.github.ffuf.pkg.ffuf.GoString;
import io.github.ffuf.pkg.ffuf.GoUnicode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The encoding/json behaviour of Go 1.27 (the v1 API on the v2 implementation) that pencode's JSON encoders rely on:
 * escaping a string without HTML escaping, unmarshalling {@code {"input":"<payload>"}} and checking a document's
 * syntax, with the v1 error texts.
 */
public final class GoJson {
    private final byte[] doc;
    private int pos;
    private byte[] input;
    private String typeError;

    private GoJson(byte[] doc) {
        this.doc = doc;
    }

    /** json.Encoder with SetEscapeHTML(false) encoding string(in), without the quotes and the newline. */
    static byte[] escape(byte[] in) {
        String s = GoString.decode(in);
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ', ' ' -> sb.append(String.format("\\u%04x", (int) c));
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
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** json.Unmarshal of {"input":"<in>"} into a struct with one string field "input"; returns that field. */
    static byte[] unescapeInput(byte[] in) {
        ByteArrayOutputStream d = new ByteArrayOutputStream(in.length + 13);
        d.writeBytes("{\"input\":\"".getBytes(StandardCharsets.US_ASCII));
        d.writeBytes(in);
        d.writeBytes("\"}".getBytes(StandardCharsets.US_ASCII));
        GoJson p = new GoJson(d.toByteArray());
        p.value(true);
        p.skipSpace();
        if (p.pos < p.doc.length) {
            throw p.invalidCharacter("after top-level value");
        }
        if (p.typeError != null) {
            throw new Encoders.EncodeException(p.typeError);
        }
        return p.input == null ? new byte[0] : p.input;
    }

    /** {@return the syntax error json.Unmarshal reports for a document before decoding it, or null when it is valid JSON} */
    public static String checkValid(byte[] doc) {
        GoJson p = new GoJson(doc);
        try {
            p.value(false);
            p.skipSpace();
            if (p.pos < p.doc.length) {
                throw p.invalidCharacter("after top-level value");
            }
        } catch (SyntaxError e) {
            return e.getMessage();
        }
        return null;
    }

    private static class SyntaxError extends Encoders.EncodeException {
        SyntaxError(String message) {
            super(message);
        }
    }

    private SyntaxError eof() {
        return new SyntaxError("unexpected end of JSON input");
    }

    /** The scanner error for the byte at pos, quoted as a single character (a byte above 0x7F as that code point). */
    private SyntaxError invalidCharacter(String where) {
        int b = doc[pos] & 0xff;
        String q = b == '\'' ? "'\''" : b == '"' ? "'\"'" : "'" + GoUnicode.quoteBody(new String(Character.toChars(b))) + "'";
        return new SyntaxError("invalid character " + q + " " + where);
    }

    private int peek() {
        if (pos >= doc.length) {
            throw eof();
        }
        return doc[pos] & 0xff;
    }

    private void skipSpace() {
        while (pos < doc.length && (doc[pos] == ' ' || doc[pos] == '\t' || doc[pos] == '\n' || doc[pos] == '\r')) {
            pos++;
        }
    }

    /** Parses one value; the top-level object is the struct whose "input" member is collected. */
    private void value(boolean top) {
        skipSpace();
        int c = peek();
        switch (c) {
            case '{' -> object(top);
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> {
                if (c == '-' || c >= '0' && c <= '9') {
                    number();
                } else {
                    throw invalidCharacter("looking for beginning of value");
                }
            }
        }
    }

    private void object(boolean top) {
        pos++;
        skipSpace();
        if (peek() == '}') {
            pos++;
            return;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw invalidCharacter("looking for beginning of object key string");
            }
            byte[] key = string();
            skipSpace();
            if (peek() != ':') {
                throw invalidCharacter("after object key");
            }
            pos++;
            skipSpace();
            int start = peek();
            if (top && isInputKey(key)) {
                if (start == '"') {
                    input = string();
                } else {
                    int before = pos;
                    value(false);
                    if (start != 'n' && typeError == null) {
                        typeError = "json: cannot unmarshal " + kind(doc[before]) + " into Go struct field JSONInput.input of type string";
                    }
                }
            } else {
                value(false);
            }
            skipSpace();
            int c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return;
            }
            throw invalidCharacter("after object key:value pair");
        }
    }

    private static boolean isInputKey(byte[] key) {
        return new String(key, StandardCharsets.ISO_8859_1).equalsIgnoreCase("input");
    }

    private static String kind(byte first) {
        return switch (first) {
            case '{' -> "object";
            case '[' -> "array";
            case 't', 'f' -> "bool";
            default -> "number";
        };
    }

    private void array() {
        pos++;
        skipSpace();
        if (peek() == ']') {
            pos++;
            return;
        }
        while (true) {
            value(false);
            skipSpace();
            int c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return;
            }
            throw invalidCharacter("after array element");
        }
    }

    private void literal(String lit) {
        for (int i = 0; i < lit.length(); i++) {
            if (peek() != lit.charAt(i)) {
                throw invalidCharacter("in literal " + lit + " (expecting '" + lit.charAt(i) + "')");
            }
            pos++;
        }
    }

    private void digits(boolean required) {
        if (required && (peek() < '0' || peek() > '9')) {
            throw invalidCharacter("in numeric literal");
        }
        while (pos < doc.length && doc[pos] >= '0' && doc[pos] <= '9') {
            pos++;
        }
    }

    private void number() {
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else {
            digits(true);
        }
        if (pos < doc.length && doc[pos] == '.') {
            pos++;
            digits(true);
        }
        if (pos < doc.length && (doc[pos] == 'e' || doc[pos] == 'E')) {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            digits(true);
        }
    }

    private int hex4(int at) {
        int r = 0;
        for (int k = at; k < at + 4; k++) {
            int d = doc[k] < 0 ? -1 : Character.digit(doc[k], 16);
            if (d < 0) {
                return -1;
            }
            r = r * 16 + d;
        }
        return r;
    }

    /** Parses a string at '"' and returns its decoded bytes; invalid UTF-8 and lone surrogates become U+FFFD. */
    private byte[] string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peek();
            if (c == '"') {
                pos++;
                return GoString.toValidUtf8(sb.toString()).getBytes(StandardCharsets.UTF_8);
            }
            if (c < 0x20) {
                throw invalidCharacter("in string literal");
            }
            if (c != '\\') {
                int n = 1;
                while (pos + n < doc.length && n < 4 && (doc[pos + n] & 0xc0) == 0x80) {
                    n++;
                }
                String part = GoString.decode(doc, pos, n);
                int first = part.codePointAt(0);
                sb.appendCodePoint(first);
                pos += first <= 0xffff && GoString.isEscape((char) first) ? 1 : new String(Character.toChars(first)).getBytes(StandardCharsets.UTF_8).length;
                continue;
            }
            if (pos + 1 >= doc.length) {
                throw eof();
            }
            int e = doc[pos + 1] & 0xff;
            switch (e) {
                case '"', '\\', '/' -> sb.append((char) e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    for (int k = pos + 2; k < pos + 6; k++) {
                        if (k >= doc.length) {
                            throw eof();
                        }
                        if (doc[k] < 0 || Character.digit(doc[k], 16) < 0) {
                            pos = k;
                            throw invalidCharacter("in \\u hexadecimal character escape");
                        }
                    }
                    int r = hex4(pos + 2);
                    pos += 6;
                    if (r >= 0xd800 && r < 0xdc00 && pos + 6 <= doc.length && doc[pos] == '\\' && doc[pos + 1] == 'u') {
                        int r2 = hex4(pos + 2);
                        if (r2 >= 0xdc00 && r2 < 0xe000) {
                            sb.appendCodePoint(Character.toCodePoint((char) r, (char) r2));
                            pos += 6;
                            continue;
                        }
                    }
                    sb.append(r >= 0xd800 && r < 0xe000 ? '\ufffd' : (char) r);
                    continue;
                }
                default -> {
                    pos++;
                    throw invalidCharacter("in string escape code");
                }
            }
            pos += 2;
        }
    }
}
