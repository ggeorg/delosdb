package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;

import org.apache.derby.iapi.services.io.Storable;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proofs for the inherited-row typed durable codec. */
final class MvccInheritedRowCodecFilterTest {
    @Test
    void emptyRowsStillRoundTrip() {
        StoreDataValue[] decoded = MvccInheritedRowCodec.INSTANCE.decode(
                MvccInheritedRowCodec.INSTANCE.encode(new StoreDataValue[0]));

        assertEquals(0, decoded.length);
    }

    @Test
    void oldJavaSerializationPayloadsAreRejectedBeforeUse() throws Exception {
        byte[] encoded = serialize(new HashMap<>());

        assertThrows(IllegalStateException.class, () -> MvccInheritedRowCodec.INSTANCE.decode(encoded));
    }

    @Test
    void nonStorableStoreValuesAreRejectedBeforeEncoding() {
        StoreDataValue[] row = {new NonStorableStoreValue()};

        assertThrows(IllegalArgumentException.class, () -> MvccInheritedRowCodec.INSTANCE.encode(row));
    }

    @Test
    void storableValuesThatAttemptObjectSerializationAreRejected() {
        StoreDataValue[] row = {new ObjectSerializingStoreValue()};

        assertThrows(IllegalStateException.class, () -> MvccInheritedRowCodec.INSTANCE.encode(row));
    }

    @Test
    void userTypeFormatIdIsRejectedEvenWhenNull() {
        StoreDataValue[] row = {new NullUserTypeStoreValue()};

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MvccInheritedRowCodec.INSTANCE.encode(row));
        assertEquals(true, failure.getMessage().contains("JAVA_OBJECT"));
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static final class NonStorableStoreValue implements StoreDataValue, Serializable {
        private static final long serialVersionUID = 1L;
    }

    private static final class NullUserTypeStoreValue implements StoreDataValue, Storable {
        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public void restoreToNull() {
        }

        @Override
        public int getTypeFormatId() {
            return StoredFormatIds.SQL_USERTYPE_ID_V3;
        }

        @Override
        public void writeExternal(ObjectOutput out) {
            throw new AssertionError("UserType format ids must be rejected before writeExternal");
        }

        @Override
        public void readExternal(java.io.ObjectInput in) {
        }
    }

    private static final class ObjectSerializingStoreValue implements StoreDataValue, Storable {
        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public void restoreToNull() {
        }

        @Override
        public int getTypeFormatId() {
            return 1;
        }

        @Override
        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject("not allowed");
        }

        @Override
        public void readExternal(java.io.ObjectInput in) {
        }
    }
}
