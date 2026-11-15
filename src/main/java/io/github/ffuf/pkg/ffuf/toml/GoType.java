package io.github.ffuf.pkg.ffuf.toml;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The declared type of an option field a TOML value is decoded into, with the conversion rules go-toml applies.
 * Decoded values are {@link String}, {@link Boolean}, {@link Long} (for Go's 64-bit {@code int}), {@link List} for
 * slices and the Java object a struct type describes.
 */
public final class GoType {
    /** The kinds of value an option field holds, which decide how a TOML value converts into it. */
    public enum Kind {
        STRING, BOOL, INT, SLICE, STRUCT
    }

    /**
     * A struct field: its TOML name (the {@code toml} tag, or the Go field name), its type and its accessors. A null
     * setter marks a nested struct that is decoded in place.
     */
    public record Field(String name, GoType type, Function<Object, Object> get, BiConsumer<Object, Object> set) {
    }

    public static final GoType STRING = new GoType("string", Kind.STRING, null, null, null);
    public static final GoType BOOL = new GoType("bool", Kind.BOOL, null, null, null);
    public static final GoType INT = new GoType("int", Kind.INT, null, null, null);

    private final String name;
    private final Kind kind;
    private final GoType elem;
    private final Supplier<Object> factory;
    private final List<Field> fields;

    private GoType(String name, Kind kind, GoType elem, Supplier<Object> factory, List<Field> fields) {
        this.name = name;
        this.kind = kind;
        this.elem = elem;
        this.factory = factory;
        this.fields = fields;
    }

    /** {@return a slice type such as {@code []string}} */
    public static GoType sliceOf(GoType elem) {
        return new GoType("[]" + elem.name, Kind.SLICE, elem, null, null);
    }

    /** {@return a struct type named as Go prints it (for example {@code ffuf.HTTPOptions}), with its fields in order} */
    public static GoType struct(String name, Supplier<Object> factory, List<Field> fields) {
        return new GoType(name, Kind.STRUCT, null, factory, fields);
    }

    /**
     * Declares a struct field.
     *
     * @param name the field name the TOML keys are matched against
     * @param type the field's type
     * @param get reads the field from its struct
     * @param set receives the decoded value, or null for a field that is decoded in place
     * @return a typed field whose setter receives the decoded value
     */
    @SuppressWarnings("unchecked")
    public static <T> Field field(String name, GoType type, Function<T, Object> get, BiConsumer<T, Object> set) {
        return new Field(name, type, o -> get.apply((T) o), set == null ? null : (o, v) -> set.accept((T) o, v));
    }

    /**
     * Declares a field holding a nested struct.
     *
     * @param get reads the nested struct, which is decoded in place
     * @return the field
     */
    public static <T> Field nested(String name, GoType type, Function<T, Object> get) {
        return field(name, type, get, null);
    }

    String name() {
        return name;
    }

    Kind kind() {
        return kind;
    }

    GoType elem() {
        return elem;
    }

    Object newValue() {
        return factory.get();
    }

    List<Field> fields() {
        return fields;
    }
}
