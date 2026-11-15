package io.github.ffuf.pkg.ffuf.toml;

/** A lexer token: its type, its text and where it starts. */
record Token(Type type, String val, Position pos) {

    /** The token types, in go-toml's declaration order, with the names its messages print. */
    enum Type {
        ERROR("Error"), EOF("EOF"), COMMENT("Comment"), KEY("Key"), STRING("String"), INTEGER("Integer"),
        TRUE("True"), FALSE("False"), FLOAT("Float"), INF("Inf"), NAN("NaN"), EQUAL("="), LEFT_BRACKET("["),
        RIGHT_BRACKET("]"), LEFT_CURLY_BRACE("{"), RIGHT_CURLY_BRACE("}"), LEFT_PAREN("("), RIGHT_PAREN(")"),
        DOUBLE_LEFT_BRACKET("]]"), DOUBLE_RIGHT_BRACKET("[["), LOCAL_DATE("LocalDate"), LOCAL_TIME("LocalTime"),
        TIME_OFFSET("TimeOffset"), KEY_GROUP("KeyGroup"), KEY_GROUP_ARRAY("KeyGroupArray"), COMMA(",");

        private final String display;

        Type(String display) {
            this.display = display;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    @Override
    public String toString() {
        return switch (type) {
            case EOF -> "EOF";
            case ERROR -> val;
            default -> GoFmt.quote(val);
        };
    }
}
