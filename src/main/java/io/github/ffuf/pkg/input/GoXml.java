package io.github.ffuf.pkg.input;

import io.github.ffuf.pkg.ffuf.GoString;
import io.github.ffuf.pkg.ffuf.GoUnicode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * The strict encoding/xml decoder as pencode's xmlunescape uses it: xml.Unmarshal of
 * {@code <xml><input>payload</input></xml>} into a struct with one string field "input", with the decoder's error
 * texts and line numbers.
 */
final class GoXml {
    private static final Map<String, String> ENTITIES = Map.of("lt", "<", "gt", ">", "amp", "&", "apos", "'", "quot", "\"");

    private final byte[] doc;
    private int pos;
    private int line = 1;
    private String pendingClose;
    private final Deque<String> stack = new ArrayDeque<>();

    /** A token: kind is 's' (start), 'e' (end), 'c' (character data) or 'o' (comment, directive, instruction). */
    private record Token(char kind, String name, byte[] data) {
    }

    private GoXml(byte[] doc) {
        this.doc = doc;
    }

    /** The Char production of XML 1.0. */
    static boolean isInCharacterRange(int r) {
        return r == 0x09 || r == 0x0a || r == 0x0d || r >= 0x20 && r <= 0xd7ff || r >= 0xe000 && r <= 0xfffd || r >= 0x10000 && r <= 0x10ffff;
    }

    static byte[] unescapeInput(byte[] in) {
        ByteArrayOutputStream d = new ByteArrayOutputStream(in.length + 30);
        d.writeBytes("<xml><input>".getBytes(StandardCharsets.US_ASCII));
        d.writeBytes(in);
        d.writeBytes("</input></xml>".getBytes(StandardCharsets.US_ASCII));
        return new GoXml(d.toByteArray()).unmarshal();
    }

    private byte[] unmarshal() {
        Token t;
        do {
            t = token();
            if (t == null) {
                throw new Encoders.EncodeException("EOF");
            }
        } while (t.kind != 's');
        byte[] value = new byte[0];
        while (true) {
            t = token();
            if (t.kind == 'e') {
                return value;
            }
            if (t.kind == 's') {
                if (local(t.name).equals("input")) {
                    value = stringElement();
                } else {
                    skip();
                }
            }
        }
    }

    private byte[] stringElement() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        while (true) {
            Token t = token();
            switch (t.kind) {
                case 's' -> skip();
                case 'e' -> {
                    return data.toByteArray();
                }
                case 'c' -> data.writeBytes(t.data);
                default -> {
                }
            }
        }
    }

    private void skip() {
        while (true) {
            Token t = token();
            if (t.kind == 's') {
                skip();
            } else if (t.kind == 'e') {
                return;
            }
        }
    }

    private static String local(String name) {
        int c = name.indexOf(':');
        return c > 0 && c < name.length() - 1 ? name.substring(c + 1) : name;
    }

    private static String space(String name) {
        int c = name.indexOf(':');
        return c > 0 && c < name.length() - 1 ? name.substring(0, c) : "";
    }

    private Encoders.EncodeException syntaxError(String msg) {
        return new Encoders.EncodeException("XML syntax error on line " + line + ": " + msg);
    }

    /** The next token, with end elements checked against the open elements; null at the end. */
    private Token token() {
        Token t = rawToken();
        if (t == null) {
            if (!stack.isEmpty()) {
                throw syntaxError("unexpected EOF");
            }
            return null;
        }
        if (t.kind == 's') {
            stack.push(t.name);
        } else if (t.kind == 'e') {
            String open = stack.poll();
            if (open == null) {
                throw syntaxError("unexpected end element </" + local(t.name) + ">");
            }
            if (!local(open).equals(local(t.name))) {
                throw syntaxError("element <" + local(open) + "> closed by </" + local(t.name) + ">");
            }
            if (!space(open).equals(space(t.name))) {
                throw syntaxError("element <" + local(open) + "> in space " + space(open) + " closed by </" + local(t.name) + "> in space "
                        + space(t.name));
            }
        }
        return t;
    }

    private int getc() {
        if (pos >= doc.length) {
            return -1;
        }
        int b = doc[pos++] & 0xff;
        if (b == '\n') {
            line++;
        }
        return b;
    }

    private void ungetc() {
        pos--;
        if (doc[pos] == '\n') {
            line--;
        }
    }

    private int mustgetc() {
        int b = getc();
        if (b < 0) {
            throw syntaxError("unexpected EOF");
        }
        return b;
    }

    private void space() {
        while (pos < doc.length && (doc[pos] == ' ' || doc[pos] == '\r' || doc[pos] == '\n' || doc[pos] == '\t')) {
            getc();
        }
    }

    private static boolean isNameByte(int c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == ':' || c == '.' || c == '-';
    }

    /** The bytes up to the first single-byte character that cannot be in a name; null when there are none. */
    private byte[] readName() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c = mustgetc();
        if (c < 0x80 && !isNameByte(c)) {
            ungetc();
            return null;
        }
        b.write(c);
        while (true) {
            c = mustgetc();
            if (c < 0x80 && !isNameByte(c)) {
                ungetc();
                return b.toByteArray();
            }
            b.write(c);
        }
    }

    private static boolean isName(byte[] b) {
        String s = GoString.decode(b);
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); ) {
            int r = s.codePointAt(i);
            if (r <= 0xffff && GoString.isEscape((char) r)) {
                return false;
            }
            boolean ok = Character.isLetter(r) || r == '_' || r == ':'
                    || i > 0 && (Character.isDigit(r) || r == '.' || r == '-' || r == 0xb7 || Character.getType(r) == Character.NON_SPACING_MARK
                    || Character.getType(r) == Character.COMBINING_SPACING_MARK || Character.getType(r) == Character.MODIFIER_LETTER);
            if (!ok) {
                return false;
            }
            i += Character.charCount(r);
        }
        return true;
    }

    private String name() {
        byte[] b = readName();
        if (b == null) {
            return null;
        }
        if (!isName(b)) {
            throw syntaxError("invalid XML name: " + GoString.decode(b));
        }
        return GoString.decode(b);
    }

    /** A name with at most one ':'; null when there is none. */
    private String nsname() {
        String s = name();
        if (s == null || s.indexOf(':') != s.lastIndexOf(':')) {
            return null;
        }
        return s;
    }

    private Token rawToken() {
        if (pendingClose != null) {
            String n = pendingClose;
            pendingClose = null;
            return new Token('e', n, null);
        }
        int b = getc();
        if (b < 0) {
            return null;
        }
        if (b != '<') {
            ungetc();
            return new Token('c', null, text(-1, false));
        }
        b = mustgetc();
        switch (b) {
            case '/' -> {
                String n = nsname();
                if (n == null) {
                    throw syntaxError("expected element name after </");
                }
                space();
                if (mustgetc() != '>') {
                    throw syntaxError("invalid characters between </" + local(n) + " and >");
                }
                return new Token('e', n, null);
            }
            case '?' -> {
                processingInstruction();
                return new Token('o', null, null);
            }
            case '!' -> {
                byte[] cdata = markupDeclaration();
                return cdata != null ? new Token('c', null, cdata) : new Token('o', null, null);
            }
            default -> {
                ungetc();
                return startElement();
            }
        }
    }

    private void processingInstruction() {
        String target = name();
        if (target == null) {
            throw syntaxError("expected target name after <?");
        }
        space();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int b0 = 0;
        while (true) {
            int b = mustgetc();
            data.write(b);
            if (b0 == '?' && b == '>') {
                break;
            }
            b0 = b;
        }
        if (target.equals("xml")) {
            byte[] all = data.toByteArray();
            String content = GoString.decode(all, 0, all.length - 2);
            String ver = procInst("version", content);
            if (!ver.isEmpty() && !ver.equals("1.0")) {
                throw new Encoders.EncodeException("xml: unsupported version \"" + GoUnicode.quoteBody(ver) + "\"; only version 1.0 is supported");
            }
            String enc = procInst("encoding", content);
            if (!enc.isEmpty() && !enc.equalsIgnoreCase("utf-8")) {
                throw new Encoders.EncodeException("xml: encoding \"" + GoUnicode.quoteBody(enc) + "\" declared but Decoder.CharsetReader is nil");
            }
        }
    }

    private static String procInst(String param, String s) {
        String p = param + "=";
        int i = 0;
        char sep = 0;
        while (i < s.length()) {
            String sub = s.substring(i);
            int k = sub.indexOf(p);
            if (k < 0 || p.length() + k >= sub.length()) {
                return "";
            }
            i += p.length() + k + 1;
            char c = sub.charAt(p.length() + k);
            if (c == '\'' || c == '"') {
                sep = c;
                break;
            }
        }
        if (sep == 0) {
            return "";
        }
        int j = s.indexOf(sep, i);
        return j < 0 ? "" : s.substring(i, j);
    }

    /** Reads a comment, CDATA section or directive after "<!"; returns the text of a CDATA section, else null. */
    private byte[] markupDeclaration() {
        int b = mustgetc();
        if (b == '-') {
            if (mustgetc() != '-') {
                throw syntaxError("invalid sequence <!- not part of <!--");
            }
            int b0 = 0;
            int b1 = 0;
            while (true) {
                b = mustgetc();
                if (b0 == '-' && b1 == '-') {
                    if (b != '>') {
                        throw syntaxError("invalid sequence \"--\" not allowed in comments");
                    }
                    return null;
                }
                b0 = b1;
                b1 = b;
            }
        }
        if (b == '[') {
            for (int i = 0; i < 6; i++) {
                if (mustgetc() != "CDATA[".charAt(i)) {
                    throw syntaxError("invalid <![ sequence");
                }
            }
            return text(-1, true);
        }
        int inquote = 0;
        int depth = 0;
        while (true) {
            b = mustgetc();
            if (inquote == 0 && b == '>' && depth == 0) {
                return null;
            }
            if (b == inquote) {
                inquote = 0;
            } else if (inquote != 0) {
                continue;
            } else if (b == '\'' || b == '"') {
                inquote = b;
            } else if (b == '>') {
                depth--;
            } else if (b == '<') {
                if (!commentAfterLt()) {
                    depth++;
                }
            }
        }
    }

    /** Inside a directive after '<': consumes a whole "!-- ... -->" comment, or reports that none starts here. */
    private boolean commentAfterLt() {
        String s = "!--";
        for (int i = 0; i < s.length(); i++) {
            int b = mustgetc();
            if (b != s.charAt(i)) {
                ungetc();
                return false;
            }
        }
        int b0 = 0;
        int b1 = 0;
        while (true) {
            int b = mustgetc();
            if (b0 == '-' && b1 == '-' && b == '>') {
                return true;
            }
            b0 = b1;
            b1 = b;
        }
    }

    private Token startElement() {
        String n = nsname();
        if (n == null) {
            throw syntaxError("expected element name after <");
        }
        while (true) {
            space();
            int b = mustgetc();
            if (b == '/') {
                if (mustgetc() != '>') {
                    throw syntaxError("expected /> in element");
                }
                pendingClose = n;
                return new Token('s', n, null);
            }
            if (b == '>') {
                return new Token('s', n, null);
            }
            ungetc();
            if (nsname() == null) {
                throw syntaxError("expected attribute name in element");
            }
            space();
            if (mustgetc() != '=') {
                throw syntaxError("attribute name without = in element");
            }
            space();
            int q = mustgetc();
            if (q != '"' && q != '\'') {
                throw syntaxError("unquoted or missing attribute value in element");
            }
            text(q, false);
        }
    }

    /** Character data up to '<' (or the closing quote, or "]]>" in CDATA), with references resolved. */
    private byte[] text(int quote, boolean cdata) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b0 = 0;
        int b1 = 0;
        int trunc = 0;
        while (true) {
            int b = getc();
            if (b < 0) {
                if (cdata) {
                    throw syntaxError("unexpected EOF in CDATA section");
                }
                break;
            }
            if (b0 == ']' && b1 == ']' && b == '>') {
                if (cdata) {
                    trunc = 2;
                    break;
                }
                throw syntaxError("unescaped ]]> not in CDATA section");
            }
            if (b == '<' && !cdata) {
                if (quote >= 0) {
                    throw syntaxError("unescaped < inside quoted string");
                }
                ungetc();
                break;
            }
            if (quote >= 0 && b == quote) {
                break;
            }
            if (b == '&' && !cdata) {
                reference(buf);
                b0 = 0;
                b1 = 0;
                continue;
            }
            if (b == '\r') {
                buf.write('\n');
            } else if (!(b1 == '\r' && b == '\n')) {
                buf.write(b);
            }
            b0 = b1;
            b1 = b;
        }
        byte[] all = buf.toByteArray();
        byte[] data = java.util.Arrays.copyOf(all, all.length - trunc);
        String s = GoString.decode(data);
        for (int i = 0; i < s.length(); ) {
            int r = s.codePointAt(i);
            if (r <= 0xffff && GoString.isEscape((char) r)) {
                throw syntaxError("invalid UTF-8");
            }
            if (!isInCharacterRange(r)) {
                throw syntaxError(String.format("illegal character code U+%04X", r));
            }
            i += Character.charCount(r);
        }
        return data;
    }

    /** Resolves the reference after '&' into buf, or fails with "invalid character entity". */
    private void reference(ByteArrayOutputStream buf) {
        StringBuilder ent = new StringBuilder("&");
        String text = null;
        int b = mustgetc();
        if (b == '#') {
            ent.append('#');
            b = mustgetc();
            int base = 10;
            if (b == 'x') {
                base = 16;
                ent.append('x');
                b = mustgetc();
            }
            StringBuilder digits = new StringBuilder();
            while (b >= '0' && b <= '9' || base == 16 && (b >= 'a' && b <= 'f' || b >= 'A' && b <= 'F')) {
                digits.append((char) b);
                b = mustgetc();
            }
            ent.append(digits);
            if (b != ';') {
                ungetc();
            } else {
                ent.append(';');
                io.github.ffuf.pkg.ffuf.GoStrconv.IntResult n = io.github.ffuf.pkg.ffuf.GoStrconv.parseUint(digits.toString(), base);
                if (n.error() == null && Long.compareUnsigned(n.value(), 0x10ffff) <= 0) {
                    int r = (int) n.value();
                    text = new String(Character.toChars(r >= 0xd800 && r <= 0xdfff ? 0xfffd : r));
                }
            }
        } else {
            ungetc();
            byte[] name = readName();
            if (name != null) {
                ent.append(GoString.decode(name));
            }
            b = mustgetc();
            if (b != ';') {
                ungetc();
            } else {
                ent.append(';');
                if (name != null && isName(name)) {
                    text = ENTITIES.get(GoString.decode(name));
                }
            }
        }
        if (text != null) {
            buf.writeBytes(text.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String e = ent.toString();
        if (e.charAt(e.length() - 1) != ';') {
            e += " (no semicolon)";
        }
        throw syntaxError("invalid character entity " + e);
    }
}
