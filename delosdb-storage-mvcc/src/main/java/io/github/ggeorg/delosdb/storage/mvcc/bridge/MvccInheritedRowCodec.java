package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.StoreDataValue;

/** Durable StoreDataValue[] codec retained inside the MVCC provider module. */
final class MvccInheritedRowCodec implements PageVolumeMvccStateStore.RowCodec<StoreDataValue[]> {
    static final MvccInheritedRowCodec INSTANCE = new MvccInheritedRowCodec();

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
            Object value = input.readObject();
            if (value instanceof StoreDataValue[] row) {
                return row;
            }
            throw new IllegalStateException("Inherited MVCC row payload was not a StoreDataValue[]");
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Could not decode inherited MVCC row", e);
        }
    }
}
