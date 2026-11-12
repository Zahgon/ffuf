package io.github.ffuf.pkg.goquery;

import io.github.ffuf.pkg.goquery.Matchers.Matcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import io.github.ffuf.pkg.goregexp.GoRegexp;

/**
 * The CSS selector parser, accepting the grammar cascadia v1.3.5 accepts in cascadia.Compile: a selector group with
 * pseudo-elements rejected. The source is a byte string; any syntax error throws {@link SyntaxException}.
 */
final class SelectorParser {
    /** A selector cascadia refuses to compile. */
    static final class SyntaxException extends Exception {
        private static final long serialVersionUID = 1L;

        SyntaxException(String msg) {
            super(msg);
        }
    }

    private static final int MAX_PARSE_DEPTH = 1000;
    private static final Set<String> PSEUDO_ELEMENTS = Set.of("after", "backdrop", "before", "cue", "first-letter",
            "first-line", "grammar-error", "marker", "placeholder", "selection", "spelling-error");

    private final String s;
    private int i;
    private int depth;

    private SelectorParser(String s) {
        this.s = s;
    }

    /** cascadia.ParseGroup over a byte string. */
    static Matcher parseGroup(String sel) throws SyntaxException {
        SelectorParser p = new SelectorParser(sel);
        Matcher m = p.parseSelectorGroup();
        if (p.i < sel.length()) {
            throw new SyntaxException("parsing: " + (sel.length() - p.i) + " bytes left over");
        }
        return m;
    }

    private static boolean hexDigit(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static boolean nameStart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c > 127;
    }

    private static boolean nameChar(char c) {
        return nameStart(c) || c == '-' || c >= '0' && c <= '9';
    }

    private String parseEscape() throws SyntaxException {
        if (s.length() < i + 2 || s.charAt(i) != '\\') {
            throw new SyntaxException("invalid escape sequence");
        }
        int start = i + 1;
        char c = s.charAt(start);
        if (c == '\r' || c == '\n' || c == '\f') {
            throw new SyntaxException("escaped line ending outside string");
        }
        if (hexDigit(c)) {
            int j = start;
            while (j < start + 6 && j < s.length() && hexDigit(s.charAt(j))) {
                j++;
            }
            long v = Long.parseLong(s.substring(start, j), 16);
            if (s.length() > j) {
                switch (s.charAt(j)) {
                    case '\r' -> {
                        j++;
                        if (s.length() > j && s.charAt(j) == '\n') {
                            j++;
                        }
                    }
                    case ' ', '\t', '\n', '\f' -> j++;
                    default -> {
                    }
                }
            }
            i = j;
            StringBuilder sb = new StringBuilder();
            GoStrings.appendRune(sb, (int) v);
            return sb.toString();
        }
        String result = s.substring(start, start + 1);
        i += 2;
        return result;
    }

    private String parseIdentifier() throws SyntaxException {
        int numPrefix = 0;
        while (s.length() > i && s.charAt(i) == '-') {
            i++;
            numPrefix++;
        }
        if (s.length() <= i) {
            throw new SyntaxException("expected identifier, found EOF instead");
        }
        char c = s.charAt(i);
        if (!(nameStart(c) || c == '\\')) {
            throw new SyntaxException("expected identifier, found " + c + " instead");
        }
        String result = parseName();
        return "-".repeat(numPrefix) + result;
    }

    private String parseName() throws SyntaxException {
        int j = i;
        StringBuilder result = new StringBuilder();
        while (j < s.length()) {
            char c = s.charAt(j);
            if (nameChar(c)) {
                int start = j;
                while (j < s.length() && nameChar(s.charAt(j))) {
                    j++;
                }
                result.append(s, start, j);
            } else if (c == '\\') {
                i = j;
                result.append(parseEscape());
                j = i;
            } else {
                break;
            }
        }
        if (result.length() == 0) {
            throw new SyntaxException("expected name, found EOF instead");
        }
        i = j;
        return result.toString();
    }

    private String parseString() throws SyntaxException {
        int j = i;
        if (s.length() < j + 2) {
            throw new SyntaxException("expected string, found EOF instead");
        }
        char quote = s.charAt(j);
        j++;
        StringBuilder result = new StringBuilder();
        loop:
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') {
                if (s.length() > j + 1) {
                    char d = s.charAt(j + 1);
                    if (d == '\r' && s.length() > j + 2 && s.charAt(j + 2) == '\n') {
                        j += 3;
                        continue;
                    }
                    if (d == '\r' || d == '\n' || d == '\f') {
                        j += 2;
                        continue;
                    }
                }
                i = j;
                result.append(parseEscape());
                j = i;
            } else if (c == quote) {
                break loop;
            } else if (c == '\r' || c == '\n' || c == '\f') {
                throw new SyntaxException("unexpected end of line in string");
            } else {
                int start = j;
                while (j < s.length()) {
                    char e = s.charAt(j);
                    if (e == quote || e == '\\' || e == '\r' || e == '\n' || e == '\f') {
                        break;
                    }
                    j++;
                }
                result.append(s, start, j);
            }
        }
        if (j >= s.length()) {
            throw new SyntaxException("EOF in string");
        }
        i = j + 1;
        return result.toString();
    }

    private GoRegexp parseRegex() throws SyntaxException {
        int j = i;
        if (s.length() < j + 2) {
            throw new SyntaxException("expected regular expression, found EOF instead");
        }
        int open = 0;
        loop:
        while (j < s.length()) {
            switch (s.charAt(j)) {
                case '(', '[' -> open++;
                case ')', ']' -> {
                    open--;
                    if (open < 0) {
                        break loop;
                    }
                }
                default -> {
                }
            }
            j++;
        }
        if (j >= s.length()) {
            throw new SyntaxException("EOF in regular expression");
        }
        GoRegexp rx;
        try {
            rx = GoRegexp.compile(GoStrings.decode(s.substring(i, j)));
        } catch (GoRegexp.SyntaxException e) {
            throw new SyntaxException(e.getMessage());
        }
        i = j;
        return rx;
    }

    private boolean skipWhitespace() {
        int j = i;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f') {
                j++;
                continue;
            }
            if (c == '/' && s.startsWith("/*", j)) {
                int end = s.indexOf("*/", j + 2);
                if (end != -1) {
                    j = end + 2;
                    continue;
                }
            }
            break;
        }
        if (j > i) {
            i = j;
            return true;
        }
        return false;
    }

    private boolean consumeParenthesis() {
        if (i < s.length() && s.charAt(i) == '(') {
            i++;
            skipWhitespace();
            return true;
        }
        return false;
    }

    private boolean consumeClosingParenthesis() {
        int j = i;
        skipWhitespace();
        if (i < s.length() && s.charAt(i) == ')') {
            i++;
            return true;
        }
        i = j;
        return false;
    }

    private Matcher parseAttributeSelector() throws SyntaxException {
        i++;
        skipWhitespace();
        String key = GoStrings.toLowerAscii(parseIdentifier());
        skipWhitespace();
        if (i >= s.length()) {
            throw new SyntaxException("unexpected EOF in attribute selector");
        }
        if (s.charAt(i) == ']') {
            i++;
            return new Matchers.Attr(key, "", "", null, false);
        }
        if (i + 2 >= s.length()) {
            throw new SyntaxException("unexpected EOF in attribute selector");
        }
        String op = s.substring(i, i + 2);
        if (op.charAt(0) == '=') {
            op = "=";
        } else if (op.charAt(1) != '=') {
            throw new SyntaxException("expected equality operator, found \"" + op + "\" instead");
        }
        i += op.length();
        skipWhitespace();
        if (i >= s.length()) {
            throw new SyntaxException("unexpected EOF in attribute selector");
        }
        String val = "";
        GoRegexp rx = null;
        if (op.equals("#=")) {
            rx = parseRegex();
        } else if (s.charAt(i) == '\'' || s.charAt(i) == '"') {
            val = parseString();
        } else {
            val = parseIdentifier();
        }
        skipWhitespace();
        if (i >= s.length()) {
            throw new SyntaxException("unexpected EOF in attribute selector");
        }
        boolean ignoreCase = false;
        if (s.charAt(i) == 'i' || s.charAt(i) == 'I') {
            ignoreCase = true;
            i++;
        }
        skipWhitespace();
        if (i >= s.length()) {
            throw new SyntaxException("unexpected EOF in attribute selector");
        }
        if (s.charAt(i) != ']') {
            throw new SyntaxException("expected ']'");
        }
        i++;
        if (!Set.of("=", "!=", "~=", "|=", "^=", "$=", "*=", "#=").contains(op)) {
            throw new SyntaxException("attribute operator " + op + " is not supported");
        }
        return new Matchers.Attr(key, val, op, rx, ignoreCase);
    }

    /** Returns the pseudo-class matcher, or null for a pseudo-element (which Compile then rejects). */
    private Matcher parsePseudoclassSelector() throws SyntaxException {
        i++;
        if (i >= s.length()) {
            throw new SyntaxException("got empty pseudoclass (or pseudoelement)");
        }
        boolean mustBePseudoElement = false;
        if (s.charAt(i) == ':') {
            mustBePseudoElement = true;
            i++;
        }
        String name = GoStrings.toLowerAscii(parseIdentifier());
        if (mustBePseudoElement && !PSEUDO_ELEMENTS.contains(name)) {
            throw new SyntaxException("unknown pseudoelement :" + name);
        }
        switch (name) {
            case "not", "has", "haschild" -> {
                expectParenthesis();
                Matchers.Group sel = parseSelectorGroup();
                expectClosingParenthesis();
                return new Matchers.Relative(name, sel);
            }
            case "contains", "containsown" -> {
                expectParenthesis();
                if (i == s.length()) {
                    throw new SyntaxException("unmatched '('");
                }
                String val = s.charAt(i) == '\'' || s.charAt(i) == '"' ? parseString() : parseIdentifier();
                val = GoStrings.toLower(val);
                skipWhitespace();
                if (i >= s.length()) {
                    throw new SyntaxException("unexpected EOF in pseudo selector");
                }
                expectClosingParenthesis();
                return new Matchers.Contains(val, name.equals("containsown"));
            }
            case "matches", "matchesown" -> {
                expectParenthesis();
                GoRegexp rx = parseRegex();
                if (i >= s.length()) {
                    throw new SyntaxException("unexpected EOF in pseudo selector");
                }
                expectClosingParenthesis();
                return new Matchers.Regexp(rx, name.equals("matchesown"));
            }
            case "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> {
                expectParenthesis();
                long[] ab = parseNth();
                expectClosingParenthesis();
                boolean last = name.equals("nth-last-child") || name.equals("nth-last-of-type");
                boolean ofType = name.equals("nth-of-type") || name.equals("nth-last-of-type");
                return new Matchers.Nth(ab[0], ab[1], last, ofType);
            }
            case "first-child" -> {
                return new Matchers.Nth(0, 1, false, false);
            }
            case "last-child" -> {
                return new Matchers.Nth(0, 1, true, false);
            }
            case "first-of-type" -> {
                return new Matchers.Nth(0, 1, false, true);
            }
            case "last-of-type" -> {
                return new Matchers.Nth(0, 1, true, true);
            }
            case "only-child" -> {
                return new Matchers.OnlyChild(false);
            }
            case "only-of-type" -> {
                return new Matchers.OnlyChild(true);
            }
            case "input" -> {
                return new Matchers.Input();
            }
            case "empty" -> {
                return new Matchers.Empty();
            }
            case "root" -> {
                return new Matchers.Root();
            }
            case "link" -> {
                return new Matchers.Link();
            }
            case "lang" -> {
                expectParenthesis();
                if (i == s.length()) {
                    throw new SyntaxException("unmatched '('");
                }
                String val = GoStrings.toLower(parseIdentifier());
                skipWhitespace();
                if (i >= s.length()) {
                    throw new SyntaxException("unexpected EOF in pseudo selector");
                }
                expectClosingParenthesis();
                return new Matchers.Lang(val);
            }
            case "enabled" -> {
                return new Matchers.Enabled();
            }
            case "disabled" -> {
                return new Matchers.Disabled();
            }
            case "checked" -> {
                return new Matchers.Checked();
            }
            case "visited", "hover", "active", "focus", "target" -> {
                return new Matchers.Never();
            }
            default -> {
                if (PSEUDO_ELEMENTS.contains(name)) {
                    return null;
                }
                throw new SyntaxException("unknown pseudoclass or pseudoelement :" + name);
            }
        }
    }

    private void expectParenthesis() throws SyntaxException {
        if (!consumeParenthesis()) {
            throw new SyntaxException("expected '(' but didn't find it");
        }
    }

    private void expectClosingParenthesis() throws SyntaxException {
        if (!consumeClosingParenthesis()) {
            throw new SyntaxException("expected ')' but didn't find it");
        }
    }

    private long parseInteger() throws SyntaxException {
        int start = i;
        int j = i;
        while (j < s.length() && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
            j++;
        }
        if (j == start) {
            throw new SyntaxException("expected integer, but didn't find it");
        }
        i = j;
        try {
            return Long.parseLong(s.substring(start, j));
        } catch (NumberFormatException e) {
            throw new SyntaxException("value out of range");
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private long[] parseNth() throws SyntaxException {
        long a;
        if (i >= s.length()) {
            throw eofNth();
        }
        char c = s.charAt(i);
        if (c == '-') {
            i++;
            if (i >= s.length()) {
                throw eofNth();
            }
            c = s.charAt(i);
            if (isDigit(c)) {
                a = -parseInteger();
                return readA(a);
            } else if (c == 'n' || c == 'N') {
                i++;
                return readN(-1);
            }
            throw invalidNth();
        }
        if (c == '+') {
            i++;
            return positiveA();
        }
        if (isDigit(c)) {
            return positiveA();
        }
        if (c == 'n' || c == 'N') {
            i++;
            return readN(1);
        }
        if (c == 'o' || c == 'O' || c == 'e' || c == 'E') {
            String id = GoStrings.toLowerAscii(parseName());
            if (id.equals("odd")) {
                return new long[] {2, 1};
            }
            if (id.equals("even")) {
                return new long[] {2, 0};
            }
            throw new SyntaxException("expected 'odd' or 'even', but found '" + id + "' instead");
        }
        throw invalidNth();
    }

    private long[] positiveA() throws SyntaxException {
        if (i >= s.length()) {
            throw eofNth();
        }
        char c = s.charAt(i);
        if (isDigit(c)) {
            return readA(parseInteger());
        } else if (c == 'n' || c == 'N') {
            i++;
            return readN(1);
        }
        throw invalidNth();
    }

    private long[] readA(long a) throws SyntaxException {
        if (i >= s.length()) {
            throw eofNth();
        }
        char c = s.charAt(i);
        if (c == 'n' || c == 'N') {
            i++;
            return readN(a);
        }
        return new long[] {0, a};
    }

    private long[] readN(long a) throws SyntaxException {
        skipWhitespace();
        if (i >= s.length()) {
            throw eofNth();
        }
        char c = s.charAt(i);
        if (c == '+') {
            i++;
            skipWhitespace();
            return new long[] {a, parseInteger()};
        } else if (c == '-') {
            i++;
            skipWhitespace();
            return new long[] {a, -parseInteger()};
        }
        return new long[] {a, 0};
    }

    private static SyntaxException eofNth() {
        return new SyntaxException("unexpected EOF while attempting to parse expression of form an+b");
    }

    private static SyntaxException invalidNth() {
        return new SyntaxException("unexpected character while attempting to parse expression of form an+b");
    }

    private Matcher parseSimpleSelectorSequence() throws SyntaxException {
        List<Matcher> selectors = new ArrayList<>();
        if (i >= s.length()) {
            throw new SyntaxException("expected selector, found EOF instead");
        }
        char c = s.charAt(i);
        if (c == '*') {
            i++;
            if (i + 2 < s.length() && s.startsWith("|*", i)) {
                i += 2;
            }
        } else if (c != '#' && c != '.' && c != '[' && c != ':') {
            selectors.add(new Matchers.Tag(GoStrings.toLowerAscii(parseIdentifier())));
        }
        loop:
        while (i < s.length()) {
            Matcher ns;
            switch (s.charAt(i)) {
                case '#' -> {
                    i++;
                    ns = new Matchers.Id(parseName());
                }
                case '.' -> {
                    i++;
                    ns = new Matchers.ClassName(parseIdentifier());
                }
                case '[' -> ns = parseAttributeSelector();
                case ':' -> ns = parsePseudoclassSelector();
                default -> {
                    break loop;
                }
            }
            if (ns == null) {
                throw new SyntaxException("pseudo-element found, but pseudo-elements support is disabled");
            }
            selectors.add(ns);
        }
        if (selectors.size() == 1) {
            return selectors.get(0);
        }
        return new Matchers.Compound(selectors);
    }

    private Matcher parseSelector() throws SyntaxException {
        skipWhitespace();
        Matcher result = parseSimpleSelectorSequence();
        while (true) {
            char combinator = 0;
            if (skipWhitespace()) {
                combinator = ' ';
            }
            if (i >= s.length()) {
                return result;
            }
            char c = s.charAt(i);
            if (c == '+' || c == '>' || c == '~') {
                combinator = c;
                i++;
                skipWhitespace();
            } else if (c == ',' || c == ')') {
                return result;
            }
            if (combinator == 0) {
                return result;
            }
            Matcher second = parseSimpleSelectorSequence();
            result = new Matchers.Combined(result, combinator, second);
        }
    }

    private Matchers.Group parseSelectorGroup() throws SyntaxException {
        if (depth > MAX_PARSE_DEPTH) {
            throw new SyntaxException("selector nesting too deep (max " + MAX_PARSE_DEPTH + ")");
        }
        depth++;
        try {
            List<Matcher> result = new ArrayList<>();
            result.add(parseSelector());
            while (i < s.length() && s.charAt(i) == ',') {
                i++;
                result.add(parseSelector());
            }
            return new Matchers.Group(result);
        } finally {
            depth--;
        }
    }
}
