package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;

import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proofs for the inherited-row native-serialization allowlist. */
final class MvccInheritedRowCodecFilterTest {
    @Test
    void storeDataValueRowsStillRoundTrip() {
        StoreDataValue[] row = {new SafeStoreValue("ok")};

        StoreDataValue[] decoded = MvccInheritedRowCodec.INSTANCE.decode(
                MvccInheritedRowCodec.INSTANCE.encode(row));

        assertEquals("ok", ((SafeStoreValue) decoded[0]).value);
    }

    @Test
    void unexpectedSerializedClassesAreRejectedBeforeUse() throws Exception {
        byte[] encoded = serialize(new HashMap<>());

        assertThrows(IllegalStateException.class, () -> MvccInheritedRowCodec.INSTANCE.decode(encoded));
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static final class SafeStoreValue implements StoreDataValue, Serializable {
        private static final long serialVersionUID = 1L;
        private final String value;

        private SafeStoreValue(String value) {
            this.value = value;
        }
    }
}
