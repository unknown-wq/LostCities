package com.lostbuildings.engine.codec;

import net.minecraft.resources.Identifier;

import java.util.Optional;

public class DataTools {

    /**
     * The namespace used internally for asset references. Data JSON references such as
     * "lostcities:common" are normalized against this namespace to a bare name ("common").
     */
    public static final String ASSET_NAMESPACE = "lostcities";

    public static Optional<String> toNullable(Character c) {
        if (c == null) {
            return Optional.empty();
        } else {
            return Optional.of(Character.toString(c));
        }
    }

    public static Character getNullableChar(Optional<String> opt) {
        return opt.isPresent() ? opt.get().charAt(0) : null;
    }

    public static String toName(Identifier rl) {
        if (rl.getNamespace().equals(ASSET_NAMESPACE)) {
            return rl.getPath();
        } else {
            return rl.toString();
        }
    }

    public static Identifier fromName(String name) {
        if (name.contains(":")) {
            return Identifier.parse(name);
        } else {
            return Identifier.fromNamespaceAndPath(ASSET_NAMESPACE, name);
        }
    }

    /**
     * Normalize an asset reference to the bare name used as a map key: strips a leading
     * "lostcities:" namespace, leaves other explicit namespaces untouched.
     */
    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        if (name.startsWith(ASSET_NAMESPACE + ":")) {
            return name.substring(ASSET_NAMESPACE.length() + 1);
        }
        return name;
    }
}
