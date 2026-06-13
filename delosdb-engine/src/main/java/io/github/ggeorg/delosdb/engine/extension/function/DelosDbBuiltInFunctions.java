package io.github.ggeorg.delosdb.engine.extension.function;

/**
 * Java entry points for DelosDB built-in SQL functions.
 */
public final class DelosDbBuiltInFunctions {
    public static final String VERSION = "DelosDB";

    private DelosDbBuiltInFunctions() {
    }

    public static String delosVersion() {
        return VERSION;
    }
}
