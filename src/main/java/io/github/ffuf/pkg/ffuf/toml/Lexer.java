package io.github.ffuf.pkg.ffuf.toml;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizes a TOML document: a state machine over the document's runes that emits every token up front, stopping at
 * the first error token. Positions, accepted syntax and error texts follow go-toml v1.9.5 exactly, including its
 * leniencies (line continuations in basic strings, literal strings spanning lines, dates followed by a newline being an
 * error).
 */
final class Lexer {
    private static final int EOF = -1;

    private interface State {
        State run();
    }

    private final int[] input;
    private int inputIdx;
    private int currentTokenStart;
    private int currentTokenStop;
    private final List<Token> tokens = new ArrayList<>();
    private final StringBuilder brackets = new StringBuilder();
    private int line = 1;
    private int col = 1;
    private int endbufferLine = 1;
    private int endbufferCol = 1;

    private Lexer(int[] input) {
        this.input = input;
    }

    static List<Token> lex(int[] runes) {
        Lexer l = new Lexer(runes);
        for (State state = l::lexVoid; state != null; ) {
            state = state.run();
        }
        return l.tokens;
    }

    private int read() {
        int r = peek();
        if (r == '\n') {
            endbufferLine++;
            endbufferCol = 1;
        } else {
            endbufferCol++;
        }
        inputIdx++;
        return r;
    }

    private int next() {
        int r = read();
        if (r != EOF) {
            currentTokenStop++;
        }
        return r;
    }

    private void ignore() {
        currentTokenStart = currentTokenStop;
        line = endbufferLine;
        col = endbufferCol;
    }

    private void skip() {
        next();
        ignore();
    }

    private void fastForward(int n) {
        for (int i = 0; i < n; i++) {
            next();
        }
    }

    private void emitWithValue(Token.Type t, String value) {
        tokens.add(new Token(t, value, new Position(line, col)));
        ignore();
    }

    private void emit(Token.Type t) {
        emitWithValue(t, new String(input, currentTokenStart, currentTokenStop - currentTokenStart));
    }

    private int peek() {
        return inputIdx >= input.length ? EOF : input[inputIdx];
    }

    private String peekString(int size) {
        int upper = Math.min(inputIdx + size, input.length);
        return new String(input, inputIdx, upper - inputIdx);
    }

    private boolean follow(String next) {
        return next.equals(peekString(next.length()));
    }

    private State errorf(String message) {
        tokens.add(new Token(Token.Type.ERROR, message, new Position(line, col)));
        return null;
    }

    private static boolean isSpace(int r) {
        return r == ' ' || r == '\t';
    }

    private static boolean isKeyChar(int r) {
        return !(r == '\r' || r == '\n' || r == EOF || r == '=');
    }

    private static boolean isKeyStartChar(int r) {
        return !(isSpace(r) || r == '\r' || r == '\n' || r == EOF || r == '[');
    }

    static boolean isDigit(int r) {
        return '0' <= r && r <= '9';
    }

    static boolean isHexDigit(int r) {
        return isDigit(r) || (r >= 'a' && r <= 'f') || (r >= 'A' && r <= 'F');
    }

    static boolean isValidBareChar(int r) {
        return 'a' <= r && r <= 'z' || 'A' <= r && r <= 'Z' || r == '_' || r == '-' || isDigit(r);
    }

    private static String rune(int r) {
        return r < 0 || r > 0x10FFFF || (r >= 0xD800 && r <= 0xDFFF) ? "�" : new String(Character.toChars(r));
    }

    private State lexVoid() {
        while (true) {
            int next = peek();
            switch (next) {
                case '}':
                    return this::lexRightCurlyBrace;
                case '[':
                    return this::lexTableKey;
                case '#':
                    return lexComment(this::lexVoid);
                case '=':
                    return this::lexEqual;
                case '\r':
                case '\n':
                    skip();
                    continue;
                default:
                    break;
            }
            if (isSpace(next)) {
                skip();
            }
            if (isKeyStartChar(next)) {
                return this::lexKey;
            }
            if (next == EOF) {
                next();
                break;
            }
        }
        emit(Token.Type.EOF);
        return null;
    }

    private State lexRvalue() {
        while (true) {
            int next = peek();
            switch (next) {
                case '.':
                    return errorf("cannot start float with a dot");
                case '=':
                    return this::lexEqual;
                case '[':
                    return this::lexLeftBracket;
                case ']':
                    return this::lexRightBracket;
                case '{':
                    return this::lexLeftCurlyBrace;
                case '}':
                    return this::lexRightCurlyBrace;
                case '#':
                    return lexComment(this::lexRvalue);
                case '"':
                    return this::lexString;
                case '\'':
                    return this::lexLiteralString;
                case ',':
                    return this::lexComma;
                case '\r':
                case '\n':
                    skip();
                    if (brackets.length() > 0 && brackets.charAt(brackets.length() - 1) == '[') {
                        return this::lexRvalue;
                    }
                    return this::lexVoid;
                default:
                    break;
            }
            if (follow("true")) {
                return this::lexTrue;
            }
            if (follow("false")) {
                return this::lexFalse;
            }
            if (follow("inf")) {
                return this::lexInf;
            }
            if (follow("nan")) {
                return this::lexNan;
            }
            if (isSpace(next)) {
                skip();
                continue;
            }
            if (next == EOF) {
                next();
                break;
            }
            if (next == '+' || next == '-') {
                return this::lexNumber;
            }
            if (isDigit(next)) {
                return this::lexDateTimeOrNumber;
            }
            return errorf("no value can start with " + rune(next));
        }
        emit(Token.Type.EOF);
        return null;
    }

    private State lexDateTimeOrNumber() {
        String lookAhead = peekString(5);
        if (lookAhead.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 3) {
            return lexNumber();
        }
        int idx = 0;
        for (int i = 0; i < lookAhead.length(); ) {
            int r = lookAhead.codePointAt(i);
            if (!isDigit(r)) {
                if (idx == 2 && r == ':') {
                    return lexDateTimeOrTime();
                }
                if (idx == 4 && r == '-') {
                    return lexDateTimeOrTime();
                }
                return lexNumber();
            }
            i += Character.charCount(r);
            idx++;
        }
        return lexNumber();
    }

    private State lexLeftCurlyBrace() {
        next();
        emit(Token.Type.LEFT_CURLY_BRACE);
        brackets.append('{');
        return this::lexVoid;
    }

    private State lexRightCurlyBrace() {
        next();
        emit(Token.Type.RIGHT_CURLY_BRACE);
        if (brackets.length() == 0 || brackets.charAt(brackets.length() - 1) != '{') {
            return errorf("cannot have '}' here");
        }
        brackets.setLength(brackets.length() - 1);
        return this::lexRvalue;
    }

    private State lexDateTimeOrTime() {
        next();
        next();
        int r = next();
        if (r == ':') {
            return lexTime();
        }
        return lexDateTime();
    }

    private State lexDateTime() {
        next();
        next();
        for (int i = 0; i < 2; i++) {
            int r = next();
            if (!isDigit(r)) {
                return errorf("invalid month digit in date: " + rune(r));
            }
        }
        int r = next();
        if (r != '-') {
            return errorf("expected - to separate month of a date, not " + rune(r));
        }
        for (int i = 0; i < 2; i++) {
            r = next();
            if (!isDigit(r)) {
                return errorf("invalid day digit in date: " + rune(r));
            }
        }
        emit(Token.Type.LOCAL_DATE);
        r = peek();
        if (r == EOF) {
            return this::lexRvalue;
        }
        if (r != ' ' && r != 'T') {
            return errorf("incorrect date/time separation character: " + rune(r));
        }
        if (r == ' ') {
            String lookAhead = peekString(3).substring(1);
            if (lookAhead.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 2) {
                return this::lexRvalue;
            }
            for (int i = 0; i < lookAhead.length(); ) {
                int c = lookAhead.codePointAt(i);
                if (!isDigit(c)) {
                    return this::lexRvalue;
                }
                i += Character.charCount(c);
            }
        }
        skip();
        for (int i = 0; i < 2; i++) {
            r = next();
            if (!isDigit(r)) {
                return errorf("invalid hour digit in time: " + rune(r));
            }
        }
        r = next();
        if (r != ':') {
            return errorf("time hour/minute separator should be :, not " + rune(r));
        }
        for (int i = 0; i < 2; i++) {
            r = next();
            if (!isDigit(r)) {
                return errorf("invalid minute digit in time: " + rune(r));
            }
        }
        r = next();
        if (r != ':') {
            return errorf("time minute/second separator should be :, not " + rune(r));
        }
        for (int i = 0; i < 2; i++) {
            r = next();
            if (!isDigit(r)) {
                return errorf("invalid second digit in time: " + rune(r));
            }
        }
        State fraction = lexFraction();
        if (fraction != null) {
            return fraction;
        }
        emit(Token.Type.LOCAL_TIME);
        return this::lexTimeOffset;
    }

    /** Consumes an optional fractional-seconds part; returns an error state, or null when it is well formed. */
    private State lexFraction() {
        if (peek() == '.') {
            next();
            int r = next();
            if (!isDigit(r)) {
                return errorf("expected at least one digit in time's fraction, not " + rune(r));
            }
            while (isDigit(peek())) {
                next();
            }
        }
        return null;
    }

    private State lexTimeOffset() {
        int r = peek();
        if (r == 'Z') {
            next();
            emit(Token.Type.TIME_OFFSET);
        } else if (r == '+' || r == '-') {
            next();
            for (int i = 0; i < 2; i++) {
                r = next();
                if (!isDigit(r)) {
                    return errorf("invalid hour digit in time offset: " + rune(r));
                }
            }
            r = next();
            if (r != ':') {
                return errorf("time offset hour/minute separator should be :, not " + rune(r));
            }
            for (int i = 0; i < 2; i++) {
                r = next();
                if (!isDigit(r)) {
                    return errorf("invalid minute digit in time offset: " + rune(r));
                }
            }
            emit(Token.Type.TIME_OFFSET);
        }
        return this::lexRvalue;
    }

    private State lexTime() {
        for (int i = 0; i < 2; i++) {
            int r = next();
            if (!isDigit(r)) {
                return errorf("invalid minute digit in time: " + rune(r));
            }
        }
        int r = next();
        if (r != ':') {
            return errorf("time minute/second separator should be :, not " + rune(r));
        }
        for (int i = 0; i < 2; i++) {
            r = next();
            if (!isDigit(r)) {
                return errorf("invalid second digit in time: " + rune(r));
            }
        }
        State fraction = lexFraction();
        if (fraction != null) {
            return fraction;
        }
        emit(Token.Type.LOCAL_TIME);
        return this::lexRvalue;
    }

    private State lexFixed(int n, Token.Type type) {
        fastForward(n);
        emit(type);
        return this::lexRvalue;
    }

    private State lexTrue() {
        return lexFixed(4, Token.Type.TRUE);
    }

    private State lexFalse() {
        return lexFixed(5, Token.Type.FALSE);
    }

    private State lexInf() {
        return lexFixed(3, Token.Type.INF);
    }

    private State lexNan() {
        return lexFixed(3, Token.Type.NAN);
    }

    private State lexEqual() {
        next();
        emit(Token.Type.EQUAL);
        return this::lexRvalue;
    }

    private State lexComma() {
        next();
        emit(Token.Type.COMMA);
        if (brackets.length() > 0 && brackets.charAt(brackets.length() - 1) == '{') {
            return this::lexVoid;
        }
        return this::lexRvalue;
    }

    private State lexKey() {
        StringBuilder sb = new StringBuilder();
        for (int r = peek(); isKeyChar(r) || r == '\n' || r == '\r'; r = peek()) {
            if (r == '"') {
                next();
                String str;
                try {
                    str = lexStringAsString("\"", false, true);
                } catch (TomlException e) {
                    return errorf(GoFmt.sprintfNoArgs(e.getMessage()));
                }
                sb.append('"').append(str).append('"');
                next();
                continue;
            } else if (r == '\'') {
                next();
                String str;
                try {
                    str = lexLiteralStringAsString("'", false);
                } catch (TomlException e) {
                    return errorf(GoFmt.sprintfNoArgs(e.getMessage()));
                }
                sb.append('\'').append(str).append('\'');
                next();
                continue;
            } else if (r == '\n') {
                return errorf("keys cannot contain new lines");
            } else if (isSpace(r)) {
                StringBuilder str = new StringBuilder(" ");
                next();
                for (r = peek(); isSpace(r); r = peek()) {
                    str.appendCodePoint(r);
                    next();
                }
                if (r != '.') {
                    break;
                }
                str.append('.');
                next();
                for (r = peek(); isSpace(r); r = peek()) {
                    str.appendCodePoint(r);
                    next();
                }
                sb.append(str);
                continue;
            } else if (r != '.' && !isValidBareChar(r)) {
                return errorf("keys cannot contain " + rune(r) + " character");
            }
            sb.appendCodePoint(r);
            next();
        }
        emitWithValue(Token.Type.KEY, sb.toString());
        return this::lexVoid;
    }

    private State lexComment(State previousState) {
        return () -> {
            for (int next = peek(); next != '\n' && next != EOF; next = peek()) {
                if (next == '\r' && follow("\r\n")) {
                    break;
                }
                next();
            }
            ignore();
            return previousState;
        };
    }

    private State lexLeftBracket() {
        next();
        emit(Token.Type.LEFT_BRACKET);
        brackets.append('[');
        return this::lexRvalue;
    }

    private void discardLeadingNewLine() {
        if (follow("\r\n")) {
            skip();
            skip();
        } else if (peek() == '\n') {
            skip();
        }
    }

    private String lexLiteralStringAsString(String terminator, boolean discardLeadingNewLine) throws TomlException {
        StringBuilder sb = new StringBuilder();
        if (discardLeadingNewLine) {
            discardLeadingNewLine();
        }
        while (true) {
            if (follow(terminator)) {
                return sb.toString();
            }
            if (peek() == EOF) {
                break;
            }
            sb.appendCodePoint(next());
        }
        throw new TomlException("unclosed string");
    }

    private State lexLiteralString() {
        skip();
        String terminator = "'";
        boolean discard = false;
        if (follow("''")) {
            skip();
            skip();
            terminator = "'''";
            discard = true;
        }
        String str;
        try {
            str = lexLiteralStringAsString(terminator, discard);
        } catch (TomlException e) {
            return errorf(e.getMessage());
        }
        emitWithValue(Token.Type.STRING, str);
        fastForward(terminator.length());
        ignore();
        return this::lexRvalue;
    }

    private String lexStringAsString(String terminator, boolean discardLeadingNewLine, boolean acceptNewLines)
            throws TomlException {
        StringBuilder sb = new StringBuilder();
        if (discardLeadingNewLine) {
            discardLeadingNewLine();
        }
        while (true) {
            if (follow(terminator)) {
                return sb.toString();
            }
            if (follow("\\")) {
                next();
                int c = peek();
                switch (c) {
                    case '\r', '\n', '\t', ' ' -> {
                        while (c == '\r' || c == '\n' || c == '\t' || c == ' ') {
                            next();
                            c = peek();
                        }
                    }
                    case '"' -> escape(sb, "\"");
                    case 'n' -> escape(sb, "\n");
                    case 'b' -> escape(sb, "\b");
                    case 'f' -> escape(sb, "\f");
                    case '/' -> escape(sb, "/");
                    case 't' -> escape(sb, "\t");
                    case 'r' -> escape(sb, "\r");
                    case '\\' -> escape(sb, "\\");
                    case 'u' -> sb.append(unicodeEscape(4));
                    case 'U' -> sb.append(unicodeEscape(8));
                    default -> throw new TomlException("invalid escape sequence: \\" + rune(c));
                }
            } else {
                int r = peek();
                if (0x00 <= r && r <= 0x1F && r != '\t' && !(acceptNewLines && (r == '\n' || r == '\r'))) {
                    throw new TomlException(String.format("unescaped control character U+%04X", r));
                }
                next();
                sb.append(rune(r));
            }
            if (peek() == EOF) {
                break;
            }
        }
        throw new TomlException("unclosed string");
    }

    private void escape(StringBuilder sb, String replacement) {
        sb.append(replacement);
        next();
    }

    private String unicodeEscape(int digits) throws TomlException {
        next();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < digits; i++) {
            int c = peek();
            if (!isHexDigit(c)) {
                throw new TomlException("unfinished unicode escape");
            }
            next();
            code.appendCodePoint(c);
        }
        return rune((int) Long.parseLong(code.toString(), 16));
    }

    private State lexString() {
        skip();
        String terminator = "\"";
        boolean discard = false;
        boolean acceptNewLines = false;
        if (follow("\"\"")) {
            skip();
            skip();
            terminator = "\"\"\"";
            discard = true;
            acceptNewLines = true;
        }
        String str;
        try {
            str = lexStringAsString(terminator, discard, acceptNewLines);
        } catch (TomlException e) {
            return errorf(GoFmt.sprintfNoArgs(e.getMessage()));
        }
        emitWithValue(Token.Type.STRING, str);
        fastForward(terminator.length());
        ignore();
        return this::lexRvalue;
    }

    private State lexTableKey() {
        next();
        if (peek() == '[') {
            next();
            emit(Token.Type.DOUBLE_LEFT_BRACKET);
            return this::lexInsideTableArrayKey;
        }
        emit(Token.Type.LEFT_BRACKET);
        return this::lexInsideTableKey;
    }

    private State lexInsideTableArrayKey() {
        for (int r = peek(); r != EOF; r = peek()) {
            if (r == ']') {
                if (currentTokenStop > currentTokenStart) {
                    emit(Token.Type.KEY_GROUP_ARRAY);
                }
                next();
                if (peek() != ']') {
                    continue;
                }
                next();
                emit(Token.Type.DOUBLE_RIGHT_BRACKET);
                return this::lexVoid;
            } else if (r == '[') {
                return errorf("table array key cannot contain ']'");
            }
            next();
        }
        return errorf("unclosed table array key");
    }

    private State lexInsideTableKey() {
        for (int r = peek(); r != EOF; r = peek()) {
            if (r == ']') {
                if (currentTokenStop > currentTokenStart) {
                    emit(Token.Type.KEY_GROUP);
                }
                next();
                emit(Token.Type.RIGHT_BRACKET);
                return this::lexVoid;
            } else if (r == '[') {
                return errorf("table key cannot contain ']'");
            }
            next();
        }
        return errorf("unclosed table key");
    }

    private State lexRightBracket() {
        next();
        emit(Token.Type.RIGHT_BRACKET);
        if (brackets.length() == 0 || brackets.charAt(brackets.length() - 1) != '[') {
            return errorf("cannot have ']' here");
        }
        brackets.setLength(brackets.length() - 1);
        return this::lexRvalue;
    }

    private static boolean isValidHexRune(int r) {
        return r >= 'a' && r <= 'f' || r >= 'A' && r <= 'F' || r >= '0' && r <= '9' || r == '_';
    }

    private static boolean isValidOctalRune(int r) {
        return r >= '0' && r <= '7' || r == '_';
    }

    private static boolean isValidBinaryRune(int r) {
        return r == '0' || r == '1' || r == '_';
    }

    private State lexNumber() {
        int r = peek();
        if (r == '0' && inputIdx + 1 < input.length) {
            int base = input[inputIdx + 1];
            java.util.function.IntPredicate valid = switch (base) {
                case 'x' -> Lexer::isValidHexRune;
                case 'o' -> Lexer::isValidOctalRune;
                case 'b' -> Lexer::isValidBinaryRune;
                default -> null;
            };
            if (valid == null && (base >= 'a' && base <= 'z' || base >= 'A' && base <= 'Z')) {
                return errorf("unknown number base: " + (char) base
                        + ". possible options are x (hex) o (octal) b (binary)");
            }
            if (valid != null) {
                next();
                next();
                boolean digitSeen = false;
                while (valid.test(peek())) {
                    digitSeen = true;
                    next();
                }
                if (!digitSeen) {
                    return errorf("number needs at least one digit");
                }
                emit(Token.Type.INTEGER);
                return this::lexRvalue;
            }
        }
        if (r == '+' || r == '-') {
            next();
            if (follow("inf")) {
                return this::lexInf;
            }
            if (follow("nan")) {
                return this::lexNan;
            }
        }
        boolean pointSeen = false;
        boolean expSeen = false;
        boolean digitSeen = false;
        while (true) {
            int next = peek();
            if (next == '.') {
                if (pointSeen) {
                    return errorf("cannot have two dots in one float");
                }
                next();
                if (!isDigit(peek())) {
                    return errorf("float cannot end with a dot");
                }
                pointSeen = true;
            } else if (next == 'e' || next == 'E') {
                expSeen = true;
                next();
                int s = peek();
                if (s == '+' || s == '-') {
                    next();
                }
            } else if (isDigit(next)) {
                digitSeen = true;
                next();
            } else if (next == '_') {
                next();
            } else {
                break;
            }
            if (pointSeen && !digitSeen) {
                return errorf("cannot start float with a dot");
            }
        }
        if (!digitSeen) {
            return errorf("no digit in that number");
        }
        emit(pointSeen || expSeen ? Token.Type.FLOAT : Token.Type.INTEGER);
        return this::lexRvalue;
    }
}
