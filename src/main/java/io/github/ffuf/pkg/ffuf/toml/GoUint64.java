package io.github.ffuf.pkg.ffuf.toml;

/** A TOML integer above Go's int64 range, which go-toml keeps as a {@code uint64}; {@code bits} holds it unsigned. */
public record GoUint64(long bits) {
}
