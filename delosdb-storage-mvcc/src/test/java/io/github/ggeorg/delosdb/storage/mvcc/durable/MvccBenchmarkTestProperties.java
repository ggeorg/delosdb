package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared, strict system-property parsing for opt-in MVCC benchmark tasks. */
final class MvccBenchmarkTestProperties {
    private MvccBenchmarkTestProperties() {
    }

    static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }

    static int integer(String name, int defaultValue) {
        return Integer.parseInt(value(name, Integer.toString(defaultValue)));
    }

    static long longValue(String name, long defaultValue) {
        return Long.parseLong(value(name, Long.toString(defaultValue)));
    }

    static List<Integer> integerList(String name, String defaultValue) {
        Set<Integer> values = new LinkedHashSet<>();
        for (String item : value(name, defaultValue).split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                values.add(Integer.valueOf(trimmed));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("System property must contain at least one integer: " + name);
        }
        return List.copyOf(values);
    }

    static <E extends Enum<E>> EnumSet<E> enumSet(
            String name,
            Class<E> enumType,
            EnumSet<E> defaultValue) {
        String configured = value(name, "").trim();
        if (configured.isEmpty()) {
            return defaultValue.clone();
        }
        EnumSet<E> values = EnumSet.noneOf(enumType);
        for (String item : configured.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                values.add(Enum.valueOf(enumType, trimmed));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("System property must contain at least one enum value: " + name);
        }
        return values;
    }

    private static String value(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }
}
