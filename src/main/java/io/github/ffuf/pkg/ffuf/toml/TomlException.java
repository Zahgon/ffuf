package io.github.ffuf.pkg.ffuf.toml;

/** A TOML syntax or decoding error, carrying go-toml's exact message text. */
public class TomlException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Creates an error with the given message. */
    public TomlException(String message) {
        super(message);
    }
}
