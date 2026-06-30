package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Documents and enforces the current durable SQL row shape for delos_mvcc.
 *
 * <p>The page-backed SQL path is intentionally narrow for S0: Derby row
 * locations map to {@link Long} logical row keys, and row payloads are stored
 * as immutable {@code List<Object>} values. Keeping the guard in one place
 * makes the next generic typed-storage step explicit instead of scattering
 * Long/List checks through WAL, checkpoint, and page-backed commit code.</p>
 */
final class MvccSqlStorageContract {
    static final String ROW_KEY_SHAPE = "Long row keys";
    static final String ROW_VALUE_SHAPE = "List<Object> row values";

    private MvccSqlStorageContract() {
    }

    static long requireLongRowKey(Object key, String component) {
        if (key instanceof Long longKey) {
            return longKey;
        }
        throw unsupported(component, ROW_KEY_SHAPE);
    }

    static String requireStringRowKey(Object key, String component) {
        return Long.toString(requireLongRowKey(key, component));
    }

    static List<Object> requireSqlRowValue(Object value, String component) {
        if (value instanceof List<?> values) {
            return copySqlRowValues(values);
        }
        throw unsupported(component, ROW_VALUE_SHAPE);
    }

    static List<Object> copySqlRowValues(List<?> values) {
        List<Object> copy = new ArrayList<>(values.size());
        copy.addAll(values);
        return Collections.unmodifiableList(copy);
    }

    private static UnsupportedOperationException unsupported(String component, String supportedShape) {
        return new UnsupportedOperationException("delos_mvcc " + component
                + " currently supports " + supportedShape + " only");
    }
}
