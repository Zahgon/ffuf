package io.github.ffuf.pkg.input;

/** One keyword's source of payloads. */
public interface InternalInputProvider {
    /** {@return the keyword this provider fills} */
    String keyword();

    /** {@return true while a payload remains at the current position} */
    boolean next();

    /** Moves to the next payload. */
    void incrementPosition();

    /** {@return the payload at the current position} */
    byte[] value();

    /** {@return the number of payloads} */
    long total();

    /** Moves back to the first payload. */
    void resetPosition();

    /** Moves to the given position. */
    void setPosition(int pos);

    /** {@return true unless the provider was disabled} */
    boolean active();

    /** Disables the provider; its keyword is no longer filled. */
    void disable();
}
