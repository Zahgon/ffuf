package io.github.ffuf.pkg.ffuf.toml;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Builds a {@link TomlTree} from the token stream, accepting what go-toml v1.9.5 accepts, with its error texts. */
final class Parser {
    private static final BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final Pattern GO_FLOAT = Pattern.compile("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?");

    private final List<Token> flow;
    private int flowIdx;
    private final TomlTree tree = new TomlTree(new Position(1, 1));
    private List<String> currentTable = new ArrayList<>();
    private final List<String> seenTableKeys = new ArrayList<>();

    private Parser(List<Token> flow) {
        this.flow = flow;
    }

    static TomlTree parse(List<Token> flow) throws TomlException {
        Parser p = new Parser(flow);
        p.run();
        return p.tree;
    }

    private static TomlException raise(Token tok, String msg) {
        if (tok == null) {
            throw new IllegalStateException("go-toml dereferences a missing token: " + msg);
        }
        return new TomlException(tok.pos() + ": " + msg);
    }

    private Token peek() {
        return flowIdx >= flow.size() ? null : flow.get(flowIdx);
    }

    private Token getToken() {
        Token tok = peek();
        if (tok != null) {
            flowIdx++;
        }
        return tok;
    }

    private void assume(Token.Type typ) throws TomlException {
        Token tok = getToken();
        if (tok == null) {
            throw raise(null, "was expecting token " + typ + ", but token stream is empty");
        }
        if (tok.type() != typ) {
            throw raise(tok, "was expecting token " + typ + ", but got " + tok + " instead");
        }
    }

    private void run() throws TomlException {
        while (true) {
            Token tok = peek();
            if (tok == null) {
                return;
            }
            switch (tok.type()) {
                case DOUBLE_LEFT_BRACKET -> parseGroupArray();
                case LEFT_BRACKET -> parseGroup();
                case KEY -> parseAssign();
                case EOF -> {
                    return;
                }
                case ERROR -> throw raise(tok, "parsing error: " + tok);
                default -> throw raise(tok, "unexpected token " + tok.type());
            }
        }
    }

    private void parseGroupArray() throws TomlException {
        Token startToken = getToken();
        Token key = getToken();
        if (key.type() != Token.Type.KEY_GROUP_ARRAY) {
            throw raise(key, "unexpected token " + key + ", was expecting a table array key");
        }
        List<String> keys;
        try {
            keys = parseKey(key.val());
        } catch (TomlException e) {
            throw raise(key, "invalid table array key: " + e.getMessage());
        }
        tree.createSubTree(keys.subList(0, keys.size() - 1), startToken.pos());
        Object destTree = tree.getPath(keys);
        List<TomlTree> array;
        if (destTree == null) {
            array = new ArrayList<>();
        } else if (destTree instanceof TomlTree.TreeArray a) {
            array = new ArrayList<>(a.trees());
        } else {
            throw raise(key, "key " + key + " is already assigned and not of type table array");
        }
        currentTable = keys;
        array.add(new TomlTree(startToken.pos()));
        tree.setPath(currentTable, new TomlTree.TreeArray(array));
        String prefix = key.val() + ".";
        boolean found = false;
        for (int ii = 0; ii < seenTableKeys.size(); ) {
            String tableKey = seenTableKeys.get(ii);
            if (tableKey.startsWith(prefix)) {
                seenTableKeys.remove(ii);
            } else {
                found = tableKey.equals(key.val());
                ii++;
            }
        }
        if (!found) {
            seenTableKeys.add(key.val());
        }
        assume(Token.Type.DOUBLE_RIGHT_BRACKET);
    }

    private void parseGroup() throws TomlException {
        Token startToken = getToken();
        Token key = getToken();
        if (key.type() != Token.Type.KEY_GROUP) {
            throw raise(key, "unexpected token " + key + ", was expecting a table key");
        }
        if (seenTableKeys.contains(key.val())) {
            throw raise(key, "duplicated tables");
        }
        seenTableKeys.add(key.val());
        List<String> keys;
        try {
            keys = parseKey(key.val());
        } catch (TomlException e) {
            throw raise(key, "invalid table array key: " + e.getMessage());
        }
        String err = tree.createSubTree(keys, startToken.pos());
        if (err != null) {
            throw raise(key, err);
        }
        if (tree.getPath(keys) instanceof TomlTree target && target.inline) {
            throw raise(key, "could not re-define exist inline table or its sub-table : " + String.join(".", keys));
        }
        assume(Token.Type.RIGHT_BRACKET);
        currentTable = keys;
    }

    private void parseAssign() throws TomlException {
        Token key = getToken();
        assume(Token.Type.EQUAL);
        List<String> parsedKey;
        try {
            parsedKey = parseKey(key.val());
        } catch (TomlException e) {
            throw raise(key, "invalid key: " + e.getMessage());
        }
        Object value = parseRvalue();
        List<String> tableKey = new ArrayList<>(currentTable);
        tableKey.addAll(parsedKey.subList(0, parsedKey.size() - 1));
        TomlTree targetNode;
        Object node = tree.getPath(tableKey);
        if (node instanceof TomlTree.TreeArray a) {
            targetNode = a.trees().get(a.trees().size() - 1);
        } else if (node instanceof TomlTree t) {
            targetNode = t;
        } else if (node == null) {
            String err = tree.createSubTree(tableKey, key.pos());
            if (err != null) {
                throw raise(key, "could not create intermediate group: " + err);
            }
            targetNode = (TomlTree) tree.getPath(tableKey);
        } else {
            throw raise(key, "Unknown table type for path: " + String.join(".", tableKey));
        }
        if (targetNode.inline) {
            throw raise(key, "could not add key or sub-table to exist inline table or its sub-table : "
                    + String.join(".", tableKey));
        }
        String keyVal = parsedKey.get(parsedKey.size() - 1);
        if (targetNode.getPath(List.of(keyVal)) != null) {
            List<String> finalKey = new ArrayList<>(tableKey);
            finalKey.add(keyVal);
            throw raise(key, "The following key was defined twice: " + String.join(".", finalKey));
        }
        Object toInsert = value instanceof TomlTree || value instanceof TomlTree.TreeArray ? value
                : new TomlTree.Leaf(value, key.pos());
        targetNode.values.put(keyVal, toInsert);
    }

    private static boolean invalidUnderscore(String value, boolean hex) {
        boolean hasBefore = false;
        for (int idx = 0; idx < value.length(); idx++) {
            char r = value.charAt(idx);
            if (r == '_' && (!hasBefore || idx + 1 >= value.length())) {
                return true;
            }
            hasBefore = hex ? Lexer.isHexDigit(r) : Lexer.isDigit(r);
        }
        return false;
    }

    private static String numError(String func, String num, String err) {
        return "strconv." + func + ": parsing " + GoFmt.quote(num) + ": " + err;
    }

    private Object parseInteger(Token tok) throws TomlException {
        String cleaned = tok.val().replace("_", "");
        int base = 10;
        String s = cleaned;
        boolean hex = false;
        if (cleaned.length() >= 3 && cleaned.charAt(0) == '0') {
            switch (cleaned.charAt(1)) {
                case 'x' -> {
                    hex = true;
                    base = 16;
                }
                case 'o' -> base = 8;
                case 'b' -> base = 2;
                default -> throw new TomlException("invalid base");
            }
            s = cleaned.substring(2);
        }
        if (invalidUnderscore(tok.val(), hex)) {
            throw raise(tok, hex ? "invalid use of _ in hex number" : "invalid use of _ in number");
        }
        BigInteger n = new BigInteger(s, base);
        if (n.compareTo(INT64_MIN) >= 0 && n.compareTo(INT64_MAX) <= 0) {
            return n.longValue();
        }
        String err = numError("ParseInt", s, "value out of range");
        if (s.charAt(0) != '-') {
            if (s.charAt(0) == '+') {
                err = numError("ParseUint", s, "invalid syntax");
            } else if (n.compareTo(UINT64_MAX) <= 0) {
                return new GoUint64(n.longValue());
            } else {
                err = numError("ParseUint", s, "value out of range");
            }
        }
        throw raise(tok, err);
    }

    private Object parseFloat(Token tok) throws TomlException {
        if (invalidUnderscore(tok.val(), false)) {
            throw raise(tok, "invalid use of _ in number");
        }
        String cleaned = tok.val().replace("_", "");
        if (!GO_FLOAT.matcher(cleaned).matches()) {
            throw raise(tok, numError("ParseFloat", cleaned, "invalid syntax"));
        }
        double d = Double.parseDouble(cleaned);
        if (Double.isInfinite(d)) {
            throw raise(tok, numError("ParseFloat", cleaned, "value out of range"));
        }
        return d;
    }

    private Object parseRvalue() throws TomlException {
        Token tok = getToken();
        if (tok == null || tok.type() == Token.Type.EOF) {
            throw raise(tok, "expecting a value");
        }
        return switch (tok.type()) {
            case STRING -> tok.val();
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case INF -> tok.val().charAt(0) == '-' ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            case NAN -> Double.NaN;
            case INTEGER -> parseInteger(tok);
            case FLOAT -> parseFloat(tok);
            case LOCAL_TIME, LOCAL_DATE -> parseDateTimeValue(tok);
            case LEFT_BRACKET -> parseArray();
            case LEFT_CURLY_BRACE -> parseInlineTable();
            case EQUAL -> throw raise(tok, "cannot have multiple equals for the same key");
            case ERROR -> throw raise(tok, tok.toString());
            default -> throw new TomlException("unhandled token: " + tok);
        };
    }

    /**
     * A local time, or a local date that may continue as a local or offset date-time; errors sit at {@code first}.
     *
     * @throws TomlException when the token is not a valid TOML date, time or date-time
     */
    private Object parseDateTimeValue(Token first) throws TomlException {
        try {
            if (first.type() == Token.Type.LOCAL_TIME) {
                return TomlDateTime.parseTime(first.val());
            }
            Token next = peek();
            if (next == null || next.type() != Token.Type.LOCAL_TIME) {
                return TomlDateTime.parseDate(first.val());
            }
            Token localTime = getToken();
            next = peek();
            if (next == null || next.type() != Token.Type.TIME_OFFSET) {
                return TomlDateTime.parseDateTime(first.val() + "T" + localTime.val());
            }
            Token offset = getToken();
            return TomlDateTime.parseOffset(first.val() + "T" + localTime.val() + offset.val());
        } catch (TomlException e) {
            throw raise(first, e.getMessage());
        }
    }

    private TomlTree parseInlineTable() throws TomlException {
        TomlTree inline = new TomlTree(Position.NONE);
        Token previous = null;
        loop:
        while (true) {
            Token follow = peek();
            if (follow == null || follow.type() == Token.Type.EOF) {
                throw raise(follow, "unterminated inline table");
            }
            switch (follow.type()) {
                case RIGHT_CURLY_BRACE -> {
                    getToken();
                    break loop;
                }
                case KEY, INTEGER, STRING -> {
                    if (!isComma(previous) && previous != null) {
                        throw raise(follow, "comma expected between fields in inline table");
                    }
                    Token key = getToken();
                    assume(Token.Type.EQUAL);
                    List<String> parsedKey;
                    try {
                        parsedKey = parseKey(key.val());
                    } catch (TomlException e) {
                        throw raise(key, "invalid key: " + e.getMessage());
                    }
                    inline.setPath(parsedKey, parseRvalue());
                }
                case COMMA -> {
                    if (isComma(previous)) {
                        throw raise(follow, "need field between two commas in inline table");
                    }
                    getToken();
                }
                default -> throw raise(follow, "unexpected token type in inline table: " + follow);
            }
            previous = follow;
        }
        if (isComma(previous)) {
            throw raise(previous, "trailing comma at the end of inline table");
        }
        inline.inline = true;
        return inline;
    }

    private static boolean isComma(Token t) {
        return t != null && t.type() == Token.Type.COMMA;
    }

    private Object parseArray() throws TomlException {
        List<Object> array = new ArrayList<>();
        boolean allTrees = true;
        while (true) {
            Token follow = peek();
            if (follow == null || follow.type() == Token.Type.EOF) {
                throw raise(follow, "unterminated array");
            }
            if (follow.type() == Token.Type.RIGHT_BRACKET) {
                getToken();
                break;
            }
            Object val = parseRvalue();
            if (!(val instanceof TomlTree)) {
                allTrees = false;
            }
            array.add(val);
            follow = peek();
            if (follow == null || follow.type() == Token.Type.EOF) {
                throw raise(follow, "unterminated array");
            }
            if (follow.type() != Token.Type.RIGHT_BRACKET && follow.type() != Token.Type.COMMA) {
                throw raise(follow, "missing comma");
            }
            if (follow.type() == Token.Type.COMMA) {
                getToken();
            }
        }
        if (!array.isEmpty() && allTrees) {
            List<TomlTree> trees = new ArrayList<>();
            for (Object o : array) {
                trees.add((TomlTree) o);
            }
            return new TomlTree.TreeArray(trees);
        }
        return array;
    }

    /**
     * go-toml's {@code parseKey}: splits a raw key into its dotted parts, quoted parts kept verbatim.
     *
     * @throws TomlException when the text is not a valid TOML key
     */
    static List<String> parseKey(String key) throws TomlException {
        int[] runes = key.codePoints().toArray();
        List<String> groups = new ArrayList<>();
        if (key.isEmpty()) {
            throw new TomlException("empty key");
        }
        int idx = 0;
        while (idx < runes.length) {
            while (idx < runes.length && (runes[idx] == ' ' || runes[idx] == '\t')) {
                idx++;
            }
            if (idx >= runes.length) {
                break;
            }
            int r = runes[idx];
            if (Lexer.isValidBareChar(r)) {
                int startIdx = idx;
                int endIdx = -1;
                idx++;
                while (idx < runes.length) {
                    r = runes[idx];
                    if (Lexer.isValidBareChar(r)) {
                        idx++;
                    } else if (r == '.') {
                        endIdx = idx;
                        break;
                    } else if (r == ' ' || r == '\t') {
                        endIdx = idx;
                        while (idx < runes.length && (runes[idx] == ' ' || runes[idx] == '\t')) {
                            idx++;
                        }
                        if (idx < runes.length && runes[idx] != '.') {
                            throw new TomlException("invalid key character after whitespace: "
                                    + new String(Character.toChars(runes[idx])));
                        }
                        break;
                    } else {
                        throw new TomlException("invalid bare key character: " + new String(Character.toChars(r)));
                    }
                }
                if (endIdx == -1) {
                    endIdx = idx;
                }
                groups.add(new String(runes, startIdx, endIdx - startIdx));
            } else if (r == '\'' || r == '"') {
                int quote = r;
                idx++;
                int startIdx = idx;
                while (true) {
                    if (idx >= runes.length) {
                        throw new TomlException(quote == '\'' ? "unclosed single-quoted key" : "unclosed double-quoted key");
                    }
                    if (runes[idx] == quote) {
                        groups.add(new String(runes, startIdx, idx - startIdx));
                        idx++;
                        break;
                    }
                    idx++;
                }
            } else if (r == '.') {
                idx++;
                if (idx >= runes.length) {
                    throw new TomlException("unexpected end of key");
                }
                r = runes[idx];
                if (!Lexer.isValidBareChar(r) && r != '\'' && r != '"' && r != ' ') {
                    throw new TomlException("expecting key part after dot");
                }
            } else {
                throw new TomlException("invalid key character: " + new String(Character.toChars(r)));
            }
        }
        if (groups.isEmpty()) {
            throw new TomlException("empty key");
        }
        return groups;
    }
}
