package io.github.ggeorg.delosdb.storage.mvcc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;

import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueOperations;

/** Row/value adapter between native MVCC rows and the storage-api row contract. */
public final class MvccStorageRow {
    private MvccStorageRow() {
    }

    public static StoreDataValue value(Object value) {
        return new MvccStorageValue(value);
    }

    public static Object nativeValue(StoreDataValue value) {
        if (value instanceof MvccStorageValue mvccValue) {
            return mvccValue.value();
        }
        if (value instanceof StoreValueOperations operations) {
            try {
                return operations.getObject();
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not unwrap store data value", e);
            }
        }
        Object reflected = reflectGetObject(value);
        return reflected == NO_REFLECTED_VALUE ? value : reflected;
    }

    public static List<Object> nativeValues(DelosRow row) {
        Objects.requireNonNull(row, "row");
        List<Object> values = new ArrayList<>(row.values().size());
        for (StoreDataValue value : row.values()) {
            values.add(nativeValue(value));
        }
        return Collections.unmodifiableList(values);
    }

    static DelosRow delosRow(VersionedRow<Long, List<Object>> row, List<Integer> projectionIndexes) {
        List<StoreDataValue> values = new ArrayList<>(projectionIndexes.size());
        for (int index : projectionIndexes) {
            values.add(value(row.value().get(index)));
        }
        return DelosRow.withIdentity(MvccStorageLocator.of(row.key()), values);
    }

    static List<Integer> projectionIndexes(DelosTableShape rowShape, DelosProjection projection) {
        Objects.requireNonNull(rowShape, "rowShape");
        Objects.requireNonNull(projection, "projection");
        if (projection.allColumns()) {
            List<Integer> indexes = new ArrayList<>(rowShape.columns().size());
            for (int i = 0; i < rowShape.columns().size(); i++) {
                indexes.add(i);
            }
            return List.copyOf(indexes);
        }
        List<Integer> indexes = new ArrayList<>(projection.columnNames().size());
        for (String columnName : projection.columnNames()) {
            int index = columnIndexOrNegative(rowShape, columnName);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown projected delos_mvcc column: " + columnName);
            }
            indexes.add(index);
        }
        return List.copyOf(indexes);
    }

    static int columnIndexOrNegative(DelosTableShape rowShape, String columnName) {
        String normalized = MvccStorageTable.normalize(columnName);
        for (int i = 0; i < rowShape.columns().size(); i++) {
            if (MvccStorageTable.normalize(rowShape.columns().get(i).name()).equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    private static Object reflectGetObject(StoreDataValue value) {
        if (value == null) {
            return null;
        }
        try {
            Method getObject = value.getClass().getMethod("getObject");
            return getObject.invoke(value);
        } catch (NoSuchMethodException e) {
            return NO_REFLECTED_VALUE;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access store value object operation on "
                    + value.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalArgumentException("Could not unwrap store data value", cause);
        }
    }

    private static final Object NO_REFLECTED_VALUE = new Object();

    private record MvccStorageValue(Object value) implements StoreDataValue {
    }
}
