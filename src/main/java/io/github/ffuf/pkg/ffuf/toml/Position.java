package io.github.ffuf.pkg.ffuf.toml;

/** A one-based line and column in a TOML document, printed as go-toml prints it: {@code (line, col)}. */
public record Position(int line, int col) {
    /** The position of values that never had one, such as members of inline tables. */
    public static final Position NONE = new Position(0, 0);

    @Override
    public String toString() {
        return "(" + line + ", " + col + ")";
    }
}
