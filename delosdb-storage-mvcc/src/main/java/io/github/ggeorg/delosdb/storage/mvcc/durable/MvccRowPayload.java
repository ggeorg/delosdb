package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Opaque durable row payload used by the page-backed MVCC table. */
public final class MvccRowPayload {
    private final String key;
    private final byte[] value;

    public MvccRowPayload(String key, byte[] value) {
        this.key = requireKey(key);
        this.value = Objects.requireNonNull(value, "value").clone();
    }

    public static MvccRowPayload ofString(String key, String value) {
        Objects.requireNonNull(value, "value");
        return new MvccRowPayload(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public String key() {
        return key;
    }

    public byte[] value() {
        return value.clone();
    }

    public String valueAsUtf8() {
        return new String(value, StandardCharsets.UTF_8);
    }

    static String requireKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("row key must not be empty");
        }
        return key;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MvccRowPayload that)) {
            return false;
        }
        return key.equals(that.key) && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }

    @Override
    public String toString() {
        return "MvccRowPayload[key=" + key + ", valueBytes=" + value.length + ']';
    }
}
