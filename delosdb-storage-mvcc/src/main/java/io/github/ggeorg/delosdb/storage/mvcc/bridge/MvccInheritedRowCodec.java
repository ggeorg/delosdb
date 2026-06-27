package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.StoreDataValue;

/** Durable StoreDataValue[] codec retained inside the MVCC provider module. */
final class MvccInheritedRowCodec implements PageVolumeMvccStateStore.RowCodec<StoreDataValue[]> {
    static final MvccInheritedRowCodec INSTANCE = new MvccInheritedRowCodec();

    private static final long MAX_SERIALIZED_ARRAY_LENGTH = 16_384L;
    private static final long MAX_SERIALIZED_DEPTH = 32L;
    private static final long MAX_SERIALIZED_REFERENCES = 65_536L;
    private static final ObjectInputFilter ROW_INPUT_FILTER = MvccInheritedRowCodec::filterSerializedRowClass;

    private MvccInheritedRowCodec() {
    }

    @Override
    public byte[] encode(StoreDataValue[] row) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(row == null ? new StoreDataValue[0] : row);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode inherited MVCC row", e);
        }
    }

    @Override
    public StoreDataValue[] decode(byte[] encoded) {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(encoded))) {
            input.setObjectInputFilter(ROW_INPUT_FILTER);
            Object value = input.readObject();
            if (value instanceof StoreDataValue[] row) {
                return row;
            }
            throw new IllegalStateException("Inherited MVCC row payload was not a StoreDataValue[]");
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Could not decode inherited MVCC row", e);
        }
    }

    private static ObjectInputFilter.Status filterSerializedRowClass(ObjectInputFilter.FilterInfo info) {
        if (info.depth() > MAX_SERIALIZED_DEPTH
                || info.references() > MAX_SERIALIZED_REFERENCES
                || info.arrayLength() > MAX_SERIALIZED_ARRAY_LENGTH) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> serializedClass = info.serialClass();
        if (serializedClass == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        Class<?> component = componentType(serializedClass);
        if (component.isPrimitive() || StoreDataValue.class.isAssignableFrom(component)) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        if (isKnownJdkValueClass(component) || isAllowedDerbyValueSupportClass(component)) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        return ObjectInputFilter.Status.REJECTED;
    }

    private static Class<?> componentType(Class<?> type) {
        Class<?> component = type;
        while (component.isArray()) {
            component = component.getComponentType();
        }
        return component;
    }

    private static boolean isKnownJdkValueClass(Class<?> type) {
        return type == String.class
                || type == BigDecimal.class
                || type == BigInteger.class
                || type == Date.class
                || type == Time.class
                || type == Timestamp.class
                || type == Integer.class
                || type == Long.class
                || type == Short.class
                || type == Byte.class
                || type == Boolean.class
                || type == Character.class
                || type == Float.class
                || type == Double.class;
    }

    private static boolean isAllowedDerbyValueSupportClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("org.apache.derby.iapi.types.")
                || name.startsWith("org.apache.derby.iapi.store.types.")
                || name.startsWith("org.apache.derby.iapi.services.io.")
                || name.startsWith("org.apache.derby.catalog.types.");
    }
}
