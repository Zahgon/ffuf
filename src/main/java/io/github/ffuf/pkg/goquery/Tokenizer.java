package io.github.ffuf.pkg.goquery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The HTML tokenizer, reading a whole byte array with the tokenization rules of golang.org/x/net/html. */
final class Tokenizer {
    enum TokenType {
        ERROR, TEXT, START_TAG, END_TAG, SELF_CLOSING_TAG, COMMENT, DOCTYPE
    }

    /** One token; data and attributes are byte strings. atom is the tag name for tags, "" otherwise. */
    static final class Token {
        TokenType type;
        String atom = "";
        String data = "";
        List<Node.Attribute> attr;

        Token(TokenType type, String atom, String data, List<Node.Attribute> attr) {
            this.type = type;
            this.atom = atom;
            this.data = data;
            this.attr = attr;
        }
    }

    static final String REPLACEMENT = "\u00ef\u00bf\u00bd";

    private final byte[] buf;
    private TokenType tt = TokenType.ERROR;
    private boolean eof;
    private int rawStart;
    private int rawEnd;
    private int dataStart;
    private int dataEnd;
    private final int[] pendingAttr = new int[4];
    private final List<int[]> attrs = new ArrayList<>();
    private final Set<String> attrNames = new HashSet<>();
    private int nAttrReturned;
    private String rawTag = "";
    private boolean textIsRaw;
    private boolean convertNUL;
    private boolean allowCDATA;

    Tokenizer(byte[] buf) {
        this.buf = buf;
    }

    void allowCDATA(boolean allow) {
        allowCDATA = allow;
    }

    void nextIsNotRawText() {
        rawTag = "";
    }

    boolean atEOF() {
        return eof;
    }

    private int readByte() {
        if (rawEnd >= buf.length) {
            eof = true;
            return 0;
        }
        return buf[rawEnd++] & 0xFF;
    }

    private static boolean isSpace(int c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f';
    }

    private void skipWhiteSpace() {
        if (eof) {
            return;
        }
        while (true) {
            int c = readByte();
            if (eof) {
                return;
            }
            if (!isSpace(c)) {
                rawEnd--;
                return;
            }
        }
    }

    private void readRawOrRCDATA() {
        if (rawTag.equals("script")) {
            readScript();
            dataEnd = rawEnd;
            textIsRaw = true;
            rawTag = "";
            return;
        }
        while (true) {
            int c = readByte();
            if (eof) {
                break;
            }
            if (c != '<') {
                continue;
            }
            c = readByte();
            if (eof) {
                break;
            }
            if (c != '/') {
                rawEnd--;
                continue;
            }
            if (readRawEndTag() || eof) {
                break;
            }
        }
        dataEnd = rawEnd;
        textIsRaw = !rawTag.equals("textarea") && !rawTag.equals("title");
        rawTag = "";
    }

    private boolean readRawEndTag() {
        for (int i = 0; i < rawTag.length(); i++) {
            int c = readByte();
            if (eof) {
                return false;
            }
            int t = rawTag.charAt(i);
            if (c != t && c != t - ('a' - 'A')) {
                rawEnd--;
                return false;
            }
        }
        int c = readByte();
        if (eof) {
            return false;
        }
        if (isSpace(c) || c == '/' || c == '>') {
            rawEnd -= 3 + rawTag.length();
            return true;
        }
        rawEnd--;
        return false;
    }

    private enum ScriptState {
        DATA, LESS_THAN, END_TAG_OPEN, ESCAPE_START, ESCAPE_START_DASH, ESCAPED, ESCAPED_DASH, ESCAPED_DASH_DASH,
        ESCAPED_LESS_THAN, ESCAPED_END_TAG_OPEN, DOUBLE_ESCAPE_START, DOUBLE_ESCAPED, DOUBLE_ESCAPED_DASH,
        DOUBLE_ESCAPED_DASH_DASH, DOUBLE_ESCAPED_LESS_THAN, DOUBLE_ESCAPE_END
    }

    private void readScript() {
        ScriptState st = ScriptState.DATA;
        int c;
        while (true) {
            switch (st) {
                case DATA -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '<') {
                        st = ScriptState.LESS_THAN;
                    }
                }
                case LESS_THAN -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '/') {
                        st = ScriptState.END_TAG_OPEN;
                    } else if (c == '!') {
                        st = ScriptState.ESCAPE_START;
                    } else {
                        rawEnd--;
                        st = ScriptState.DATA;
                    }
                }
                case END_TAG_OPEN -> {
                    if (readRawEndTag() || eof) {
                        return;
                    }
                    st = ScriptState.DATA;
                }
                case ESCAPE_START -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '-') {
                        st = ScriptState.ESCAPE_START_DASH;
                    } else {
                        rawEnd--;
                        st = ScriptState.DATA;
                    }
                }
                case ESCAPE_START_DASH -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '-') {
                        st = ScriptState.ESCAPED_DASH_DASH;
                    } else {
                        rawEnd--;
                        st = ScriptState.DATA;
                    }
                }
                case ESCAPED -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '-') {
                        st = ScriptState.ESCAPED_DASH;
                    } else if (c == '<') {
                        st = ScriptState.ESCAPED_LESS_THAN;
                    }
                }
                case ESCAPED_DASH -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '-') {
                        st = ScriptState.ESCAPED_DASH_DASH;
                    } else if (c == '<') {
                        st = ScriptState.ESCAPED_LESS_THAN;
                    } else {
                        st = ScriptState.ESCAPED;
                    }
                }
                case ESCAPED_DASH_DASH -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '-') {
                        st = ScriptState.ESCAPED_DASH_DASH;
                    } else if (c == '<') {
                        st = ScriptState.ESCAPED_LESS_THAN;
                    } else if (c == '>') {
                        st = ScriptState.DATA;
                    } else {
                        st = ScriptState.ESCAPED;
                    }
                }
                case ESCAPED_LESS_THAN -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '/') {
                        st = ScriptState.ESCAPED_END_TAG_OPEN;
                    } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                        st = ScriptState.DOUBLE_ESCAPE_START;
                    } else {
                        rawEnd--;
                        st = ScriptState.DATA;
                    }
                }
                case ESCAPED_END_TAG_OPEN -> {
                    if (readRawEndTag() || eof) {
                        return;
                    }
                    st = ScriptState.ESCAPED;
                }
                case DOUBLE_ESCAPE_START -> {
                    rawEnd--;
                    st = null;
                    for (int i = 0; i < "script".length(); i++) {
                        c = readByte();
                        if (eof) {
                            return;
                        }
                        if (c != "script".charAt(i) && c != "SCRIPT".charAt(i)) {
                            rawEnd--;
                            st = ScriptState.ESCAPED;
                            break;
                        }
                    }
                    if (st == null) {
                        c = readByte();
                        if (eof) {
                            return;
                        }
                        if (isSpace(c) || c == '/' || c == '>') {
                            st = ScriptState.DOUBLE_ESCAPED;
                        } else {
                            rawEnd--;
                            st = ScriptState.ESCAPED;
                        }
                    }
                }
                case DOUBLE_ESCAPED -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '-') {
                        st = ScriptState.DOUBLE_ESCAPED_DASH;
                    } else if (c == '<') {
                        st = ScriptState.DOUBLE_ESCAPED_LESS_THAN;
                    }
                }
                case DOUBLE_ESCAPED_DASH -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '-') {
                        st = ScriptState.DOUBLE_ESCAPED_DASH_DASH;
                    } else if (c == '<') {
                        st = ScriptState.DOUBLE_ESCAPED_LESS_THAN;
                    } else {
                        st = ScriptState.DOUBLE_ESCAPED;
                    }
                }
                case DOUBLE_ESCAPED_DASH_DASH -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '-') {
                        st = ScriptState.DOUBLE_ESCAPED_DASH_DASH;
                    } else if (c == '<') {
                        st = ScriptState.DOUBLE_ESCAPED_LESS_THAN;
                    } else if (c == '>') {
                        st = ScriptState.DATA;
                    } else {
                        st = ScriptState.DOUBLE_ESCAPED;
                    }
                }
                case DOUBLE_ESCAPED_LESS_THAN -> {
                    c = readByte();
                    if (eof) {
                        return;
                    }
                    if (c == '/') {
                        st = ScriptState.DOUBLE_ESCAPE_END;
                    } else {
                        rawEnd--;
                        st = ScriptState.DOUBLE_ESCAPED;
                    }
                }
                case DOUBLE_ESCAPE_END -> {
                    if (readRawEndTag()) {
                        rawEnd += "</script>".length();
                        st = ScriptState.ESCAPED;
                    } else if (eof) {
                        return;
                    } else {
                        st = ScriptState.DOUBLE_ESCAPED;
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
    }

    private void readComment() {
        dataStart = rawEnd;
        int dashCount = 0;
        boolean beginning = true;
        try {
            while (true) {
                int c = readByte();
                if (eof) {
                    dataEnd = calculateAbruptCommentDataEnd();
                    return;
                }
                switch (c) {
                    case '-' -> {
                        dashCount++;
                        continue;
                    }
                    case '>' -> {
                        if (dashCount >= 2 || beginning) {
                            dataEnd = rawEnd - "-->".length();
                            return;
                        }
                    }
                    case '!' -> {
                        if (dashCount >= 2) {
                            c = readByte();
                            if (eof) {
                                dataEnd = calculateAbruptCommentDataEnd();
                                return;
                            } else if (c == '>') {
                                dataEnd = rawEnd - "--!>".length();
                                return;
                            } else if (c == '-') {
                                dashCount = 1;
                                beginning = false;
                                continue;
                            }
                        }
                    }
                    default -> {
                    }
                }
                dashCount = 0;
                beginning = false;
            }
        } finally {
            if (dataEnd < dataStart) {
                dataEnd = dataStart;
            }
        }
    }

    private boolean rawHasSuffix(int from, String suffix) {
        int len = rawEnd - from;
        if (len < suffix.length()) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if ((buf[rawEnd - suffix.length() + i] & 0xFF) != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int calculateAbruptCommentDataEnd() {
        if (rawEnd - rawStart >= "<!--".length()) {
            int from = rawStart + "<!--".length();
            if (rawHasSuffix(from, "--!")) {
                return rawEnd - 3;
            } else if (rawHasSuffix(from, "--")) {
                return rawEnd - 2;
            } else if (rawHasSuffix(from, "-")) {
                return rawEnd - 1;
            }
        }
        return rawEnd;
    }

    private void readUntilCloseAngle() {
        dataStart = rawEnd;
        while (true) {
            int c = readByte();
            if (eof) {
                dataEnd = rawEnd;
                return;
            }
            if (c == '>') {
                dataEnd = rawEnd - 1;
                return;
            }
        }
    }

    private TokenType readMarkupDeclaration() {
        dataStart = rawEnd;
        int[] c = new int[2];
        for (int i = 0; i < 2; i++) {
            c[i] = readByte();
            if (eof) {
                dataEnd = rawEnd;
                if (i == 1 && c[0] == '>') {
                    dataEnd--;
                }
                return TokenType.COMMENT;
            }
        }
        if (c[0] == '-' && c[1] == '-') {
            readComment();
            return TokenType.COMMENT;
        }
        rawEnd -= 2;
        if (readDoctype()) {
            return TokenType.DOCTYPE;
        }
        if (allowCDATA && readCDATA()) {
            convertNUL = true;
            return TokenType.TEXT;
        }
        readUntilCloseAngle();
        return TokenType.COMMENT;
    }

    private boolean readDoctype() {
        String s = "DOCTYPE";
        for (int i = 0; i < s.length(); i++) {
            int c = readByte();
            if (eof) {
                rawEnd = dataStart;
                eof = false;
                return false;
            }
            if (c != s.charAt(i) && c != s.charAt(i) + ('a' - 'A')) {
                rawEnd = dataStart;
                return false;
            }
        }
        skipWhiteSpace();
        if (eof) {
            dataStart = rawEnd;
            dataEnd = rawEnd;
            return true;
        }
        readUntilCloseAngle();
        return true;
    }

    private boolean readCDATA() {
        String s = "[CDATA[";
        for (int i = 0; i < s.length(); i++) {
            int c = readByte();
            if (eof) {
                rawEnd = dataStart;
                eof = false;
                return false;
            }
            if (c != s.charAt(i)) {
                rawEnd = dataStart;
                return false;
            }
        }
        dataStart = rawEnd;
        int brackets = 0;
        while (true) {
            int c = readByte();
            if (eof) {
                dataEnd = rawEnd;
                return true;
            }
            if (c == ']') {
                brackets++;
            } else if (c == '>') {
                if (brackets >= 2) {
                    dataEnd = rawEnd - "]]>".length();
                    return true;
                }
                brackets = 0;
            } else {
                brackets = 0;
            }
        }
    }

    private boolean startTagIn(String... ss) {
        outer:
        for (String s : ss) {
            if (dataEnd - dataStart != s.length()) {
                continue;
            }
            for (int i = 0; i < s.length(); i++) {
                int c = buf[dataStart + i] & 0xFF;
                if (c >= 'A' && c <= 'Z') {
                    c += 'a' - 'A';
                }
                if (c != s.charAt(i)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private TokenType readStartTag() {
        readTag(true);
        if (eof) {
            return TokenType.ERROR;
        }
        int c = buf[dataStart] & 0xFF;
        if (c >= 'A' && c <= 'Z') {
            c += 'a' - 'A';
        }
        boolean raw = switch (c) {
            case 'i' -> startTagIn("iframe");
            case 'n' -> startTagIn("noembed", "noframes", "noscript");
            case 'p' -> startTagIn("plaintext");
            case 's' -> startTagIn("script", "style");
            case 't' -> startTagIn("textarea", "title");
            case 'x' -> startTagIn("xmp");
            default -> false;
        };
        if (raw) {
            rawTag = GoStrings.toLowerAscii(bytes(dataStart, dataEnd));
        }
        int n = attrs.size();
        if (buf[rawEnd - 2] == '/' && (n == 0 || rawEnd - 2 != attrs.get(n - 1)[3] - 1)) {
            return TokenType.SELF_CLOSING_TAG;
        }
        return TokenType.START_TAG;
    }

    private void readTag(boolean saveAttr) {
        attrs.clear();
        nAttrReturned = 0;
        attrNames.clear();
        readTagName();
        skipWhiteSpace();
        if (eof) {
            return;
        }
        while (true) {
            int c = readByte();
            if (eof || c == '>') {
                break;
            }
            rawEnd--;
            readTagAttrKey();
            readTagAttrVal();
            String key = GoStrings.toLowerAscii(bytes(pendingAttr[0], pendingAttr[1]));
            if (saveAttr && pendingAttr[0] != pendingAttr[1] && !attrNames.contains(key)) {
                attrs.add(pendingAttr.clone());
                attrNames.add(key);
            }
            skipWhiteSpace();
            if (eof) {
                break;
            }
        }
    }

    private void readTagName() {
        dataStart = rawEnd - 1;
        while (true) {
            int c = readByte();
            if (eof) {
                dataEnd = rawEnd;
                return;
            }
            if (isSpace(c)) {
                dataEnd = rawEnd - 1;
                return;
            }
            if (c == '/' || c == '>') {
                rawEnd--;
                dataEnd = rawEnd;
                return;
            }
        }
    }

    private void readTagAttrKey() {
        pendingAttr[0] = rawEnd;
        while (true) {
            int c = readByte();
            if (eof) {
                pendingAttr[1] = rawEnd;
                return;
            }
            if (c == '=') {
                if (pendingAttr[0] + 1 == rawEnd) {
                    continue;
                }
                rawEnd--;
                pendingAttr[1] = rawEnd;
                return;
            }
            if (isSpace(c) || c == '/' || c == '>') {
                rawEnd--;
                pendingAttr[1] = rawEnd;
                return;
            }
        }
    }

    private void readTagAttrVal() {
        pendingAttr[2] = rawEnd;
        pendingAttr[3] = rawEnd;
        skipWhiteSpace();
        if (eof) {
            return;
        }
        int c = readByte();
        if (eof) {
            return;
        }
        if (c == '/') {
            return;
        }
        if (c != '=') {
            rawEnd--;
            return;
        }
        skipWhiteSpace();
        if (eof) {
            return;
        }
        int quote = readByte();
        if (eof) {
            return;
        }
        if (quote == '>') {
            rawEnd--;
            return;
        }
        if (quote == '\'' || quote == '"') {
            pendingAttr[2] = rawEnd;
            while (true) {
                c = readByte();
                if (eof) {
                    pendingAttr[3] = rawEnd;
                    return;
                }
                if (c == quote) {
                    pendingAttr[3] = rawEnd - 1;
                    return;
                }
            }
        }
        pendingAttr[2] = rawEnd - 1;
        while (true) {
            c = readByte();
            if (eof) {
                pendingAttr[3] = rawEnd;
                return;
            }
            if (isSpace(c)) {
                pendingAttr[3] = rawEnd - 1;
                return;
            }
            if (c == '>') {
                rawEnd--;
                pendingAttr[3] = rawEnd;
                return;
            }
        }
    }

    /** Scans the next token and returns its type. */
    TokenType next() {
        rawStart = rawEnd;
        dataStart = rawEnd;
        dataEnd = rawEnd;
        if (eof) {
            tt = TokenType.ERROR;
            return tt;
        }
        if (!rawTag.isEmpty()) {
            if (rawTag.equals("plaintext")) {
                while (!eof) {
                    readByte();
                }
                dataEnd = rawEnd;
                textIsRaw = true;
            } else {
                readRawOrRCDATA();
            }
            if (dataEnd > dataStart) {
                tt = TokenType.TEXT;
                convertNUL = true;
                return tt;
            }
        }
        textIsRaw = false;
        convertNUL = false;
        while (true) {
            int c = readByte();
            if (eof) {
                break;
            }
            if (c != '<') {
                continue;
            }
            c = readByte();
            if (eof) {
                break;
            }
            TokenType tokenType;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                tokenType = TokenType.START_TAG;
            } else if (c == '/') {
                tokenType = TokenType.END_TAG;
            } else if (c == '!' || c == '?') {
                tokenType = TokenType.COMMENT;
            } else {
                rawEnd--;
                continue;
            }
            int x = rawEnd - "<a".length();
            if (rawStart < x) {
                rawEnd = x;
                dataEnd = x;
                tt = TokenType.TEXT;
                return tt;
            }
            switch (tokenType) {
                case START_TAG -> {
                    tt = readStartTag();
                    return tt;
                }
                case END_TAG -> {
                    c = readByte();
                    if (eof) {
                        return finish();
                    }
                    if (c == '>') {
                        tt = TokenType.COMMENT;
                        return tt;
                    }
                    if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                        readTag(false);
                        tt = eof ? TokenType.ERROR : TokenType.END_TAG;
                        return tt;
                    }
                    rawEnd--;
                    readUntilCloseAngle();
                    tt = TokenType.COMMENT;
                    return tt;
                }
                default -> {
                    if (c == '!') {
                        tt = readMarkupDeclaration();
                        return tt;
                    }
                    rawEnd--;
                    readUntilCloseAngle();
                    tt = TokenType.COMMENT;
                    return tt;
                }
            }
        }
        return finish();
    }

    private TokenType finish() {
        if (rawStart < rawEnd) {
            dataEnd = rawEnd;
            tt = TokenType.TEXT;
            return tt;
        }
        tt = TokenType.ERROR;
        return tt;
    }

    private String bytes(int start, int end) {
        return new String(buf, start, end - start, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static String convertNewlines(String s) {
        if (s.indexOf('\r') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    continue;
                }
                sb.append('\n');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String text() {
        String s = bytes(dataStart, dataEnd);
        dataStart = rawEnd;
        dataEnd = rawEnd;
        s = convertNewlines(s);
        if ((convertNUL || tt == TokenType.COMMENT) && s.indexOf('\0') >= 0) {
            s = s.replace("\0", REPLACEMENT);
        }
        if (!textIsRaw) {
            s = Entities.unescape(s, false);
        }
        return s;
    }

    /** The current token, as Tokenizer.Token() builds it. */
    Token token() {
        switch (tt) {
            case TEXT, COMMENT, DOCTYPE -> {
                return new Token(tt, "", text(), null);
            }
            case START_TAG, SELF_CLOSING_TAG, END_TAG -> {
                String name = "";
                if (dataStart < dataEnd) {
                    name = GoStrings.toLowerAscii(bytes(dataStart, dataEnd).replace("\0", REPLACEMENT));
                }
                List<Node.Attribute> list = null;
                if (tt != TokenType.END_TAG) {
                    for (int[] a : attrs) {
                        if (list == null) {
                            list = new ArrayList<>();
                        }
                        String key = GoStrings.toLowerAscii(bytes(a[0], a[1]).replace("\0", REPLACEMENT));
                        String val = Entities.unescape(convertNewlines(bytes(a[2], a[3]).replace("\0", REPLACEMENT)), true);
                        list.add(new Node.Attribute("", key, val));
                    }
                    nAttrReturned = attrs.size();
                }
                return new Token(tt, name, name, list);
            }
            default -> {
                return new Token(tt, "", "", null);
            }
        }
    }
}
