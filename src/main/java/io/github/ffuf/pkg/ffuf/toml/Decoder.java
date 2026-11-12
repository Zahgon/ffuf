package io.github.ffuf.pkg.ffuf.toml;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decodes a TOML tree into an options object with go-toml v1.9.5's rules: walks a struct type's fields, finds each one's key under go-toml's four
 * spellings, converts the value with Go's reflection rules and reports go-toml's errors. Fields are assigned one by
 * one, so a failing document leaves the fields decoded before the error in place.
 */
final class Decoder {
    private Decoder() {
    }

    static Object valueFromTree(GoType type, TomlTree tval, Object existing) throws TomlException {
        Object mval = existing != null ? existing : type.newValue();
        if (tval == null) {
            return mval;
        }
        for (GoType.Field f : type.fields()) {
            for (String key : keysToTry(f.name())) {
                if (!tval.has(key)) {
                    continue;
                }
                Object current = f.type().kind() == GoType.Kind.STRUCT ? f.get().apply(mval) : null;
                try {
                    Object v = valueFromToml(f.type(), tval.get(key), current);
                    if (f.set() != null) {
                        f.set().accept(mval, v);
                    }
                } catch (TomlException e) {
                    throw formatError(e, tval.positionOf(key));
                }
                break;
            }
        }
        return mval;
    }

    private static List<String> keysToTry(String base) {
        return List.of(base, base.toLowerCase(Locale.ROOT), base.toUpperCase(Locale.ROOT),
                base.substring(0, 1).toLowerCase(Locale.ROOT) + base.substring(1));
    }

    private static TomlException formatError(TomlException e, Position pos) {
        if (e.getMessage().startsWith("(")) {
            return e;
        }
        return new TomlException(pos + ": " + e.getMessage());
    }

    private static TomlException cannotConvert(Object tval, String target) {
        return new TomlException("Can't convert " + GoFmt.v(tval) + "(" + GoFmt.typeName(tval) + ") to " + target);
    }

    private static Object valueFromToml(GoType type, Object tval, Object existing) throws TomlException {
        if (tval instanceof TomlTree tree) {
            if (type.kind() == GoType.Kind.STRUCT) {
                return valueFromTree(type, tree, existing);
            }
            throw cannotConvert(tval, "a tree");
        }
        if (tval instanceof TomlTree.TreeArray array) {
            if (type.kind() == GoType.Kind.SLICE && type.elem().kind() == GoType.Kind.STRUCT) {
                List<Object> out = new ArrayList<>();
                for (TomlTree t : array.trees()) {
                    out.add(valueFromTree(type.elem(), t, null));
                }
                return out;
            }
            throw cannotConvert(tval, "trees");
        }
        if (tval instanceof List<?> list) {
            if (type.kind() == GoType.Kind.SLICE && type.elem().kind() != GoType.Kind.STRUCT) {
                List<Object> out = new ArrayList<>();
                for (Object item : list) {
                    out.add(valueFromToml(type.elem(), item, null));
                }
                return out;
            }
            throw cannotConvert(tval, "a slice");
        }
        switch (type.kind()) {
            case BOOL -> {
                if (tval instanceof Boolean) {
                    return tval;
                }
            }
            case STRING -> {
                if (tval instanceof String) {
                    return tval;
                }
                if (tval instanceof GoUint64) {
                    return "�";
                }
            }
            case INT -> {
                if (tval instanceof Long) {
                    return tval;
                }
                if (tval instanceof GoUint64 u) {
                    return u.bits();
                }
            }
            case SLICE -> throw cannotConvert(tval, type.name() + "(slice)");
            default -> {
            }
        }
        throw cannotConvert(tval, type.name());
    }
}
