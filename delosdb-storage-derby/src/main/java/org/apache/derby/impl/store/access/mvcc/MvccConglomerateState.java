/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerateState

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.store.access.mvcc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.MvccRow;
import io.github.ggeorg.delosdb.storage.mvcc.MvccScan;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;

import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Shared state behind the inherited MVCC conglomerate provider.
 *
 * <p>MODULE9A turns the static map into a cache instead of the restart
 * authority. MODULE9B attaches MVCC transaction status durability to the
 * inherited Derby transaction lifecycle. Committed visible rows are snapshotted
 * under the Derby database service directory and transaction outcomes are
 * recorded beside that provider-owned state. This is not WAL, a checkpoint
 * engine, or a side SQL bridge.</p>
 */
final class MvccConglomerateState {
    private static final int SNAPSHOT_MAGIC = 0x444D5631; // DMV1
    private static final int SNAPSHOT_VERSION = 1;

    private final ContainerKey key;
    private final Path snapshotFile;
    private final Path transactionStatusFile;
    private final MvccTransactionStatusStore transactionStatusStore;
    private final MvccTable<Long, StoreDataValue[]> table = new MvccTable<>();
    private final MvccTransactionManager transactions;
    private long nextRowId = 1L;

    MvccConglomerateState(ContainerKey key, Path databaseDirectory) {
        this.key = key;
        this.snapshotFile = snapshotFile(databaseDirectory, key);
        this.transactionStatusFile = transactionStatusFile(databaseDirectory, key);
        this.transactionStatusStore = transactionStatusFile == null || key.getContainerId() == 0L
                ? MvccTransactionStatusStore.disabled()
                : MvccTransactionStatusStore.open(transactionStatusFile);
        this.transactions = new MvccTransactionManager(transactionStatusStore);
        loadCommittedSnapshot();
    }

    ContainerKey key() {
        return key;
    }

    MvccTable<Long, StoreDataValue[]> table() {
        return table;
    }

    MvccTransactionManager transactions() {
        return transactions;
    }

    synchronized long nextRowId() {
        return nextRowId++;
    }

    synchronized void persistCommittedSnapshot() {
        if (snapshotFile == null || key.getContainerId() == 0L) {
            return;
        }
        List<PersistedRow> rows = visibleRows();
        Path tmp = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(snapshotFile.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(SNAPSHOT_MAGIC);
                out.writeInt(SNAPSHOT_VERSION);
                out.writeLong(nextRowId);
                out.writeInt(rows.size());
                for (PersistedRow row : rows) {
                    out.writeLong(row.rowId());
                    out.writeInt(row.values().length);
                    for (StoreDataValue value : row.values()) {
                        writeValue(out, value);
                    }
                }
            }
            Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist inherited MVCC state for " + key, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Best effort cleanup only. The snapshot file is replaced atomically above.
            }
        }
    }

    synchronized void dropDurableState() {
        try {
            if (snapshotFile != null) {
                Files.deleteIfExists(snapshotFile);
            }
            if (transactionStatusFile != null) {
                Files.deleteIfExists(transactionStatusFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete inherited MVCC state for " + key, e);
        }
    }


    private void loadCommittedSnapshot() {
        if (snapshotFile == null || !Files.exists(snapshotFile)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(snapshotFile))) {
            int magic = in.readInt();
            if (magic != SNAPSHOT_MAGIC) {
                throw new IllegalStateException("Unsupported inherited MVCC state snapshot magic for " + key);
            }
            int version = in.readInt();
            if (version != SNAPSHOT_VERSION) {
                throw new IllegalStateException("Unsupported inherited MVCC state snapshot version "
                        + version + " for " + key);
            }
            long storedNextRowId = in.readLong();
            int rowCount = in.readInt();
            MvccTransaction hydrator = transactions.begin();
            try {
                long maxRowId = 0L;
                for (int row = 0; row < rowCount; row++) {
                    long rowId = in.readLong();
                    int columnCount = in.readInt();
                    StoreDataValue[] values = new StoreDataValue[columnCount];
                    for (int column = 0; column < columnCount; column++) {
                        values[column] = readValue(in);
                    }
                    table.insert(rowId, values, hydrator);
                    maxRowId = Math.max(maxRowId, rowId);
                }
                transactions.commit(hydrator);
                nextRowId = Math.max(storedNextRowId, maxRowId + 1L);
            } catch (RuntimeException failure) {
                transactions.abort(hydrator);
                throw failure;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load inherited MVCC state for " + key, e);
        }
    }

    private List<PersistedRow> visibleRows() {
        MvccTransaction reader = transactions.begin();
        try {
            MvccSnapshot snapshot = transactions.snapshot(reader);
            List<PersistedRow> rows = new ArrayList<>();
            try (MvccScan<Long, StoreDataValue[]> scan = table.openScan(snapshot, transactions)) {
                while (scan.next()) {
                    MvccRow<Long, StoreDataValue[]> row = scan.row();
                    rows.add(new PersistedRow(row.key(), MvccConglomerateController.cloneRow(row.value())));
                }
            } catch (StandardException e) {
                throw new IllegalStateException("Could not clone inherited MVCC row for persistence", e);
            }
            return List.copyOf(rows);
        } finally {
            transactions.abort(reader);
        }
    }

    private static Path snapshotFile(Path databaseDirectory, ContainerKey key) {
        Path directory = inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + key.getSegmentId() + "-" + key.getContainerId() + ".snapshot");
    }

    private static Path transactionStatusFile(Path databaseDirectory, ContainerKey key) {
        Path directory = inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + key.getSegmentId() + "-" + key.getContainerId() + ".txstatus");
    }

    private static Path inheritedStoreDirectory(Path databaseDirectory) {
        if (databaseDirectory == null) {
            return null;
        }
        return databaseDirectory
                .resolve(DelosMvccStorageProvider.DATABASE_STORAGE_DIRECTORY_NAME)
                .resolve("inherited-store");
    }

    private static void writeValue(DataOutputStream out, StoreDataValue value) throws IOException {
        out.writeBoolean(value != null);
        if (value == null) {
            return;
        }
        out.writeUTF(value.getClass().getName());
        byte[] encoded = encodeExternalValue(value);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static StoreDataValue readValue(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        String className = in.readUTF();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative inherited MVCC value length for " + className + ": " + length);
        }
        byte[] encoded = in.readNBytes(length);
        if (encoded.length != length) {
            throw new IOException("Short inherited MVCC value read for " + className);
        }
        return decodeExternalValue(className, encoded);
    }

    private static byte[] encodeExternalValue(StoreDataValue value) throws IOException {
        try {
            Method writeExternal = value.getClass().getMethod("writeExternal", ObjectOutput.class);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                writeExternal.invoke(value, out);
            }
            return bytes.toByteArray();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Inherited MVCC persistence requires externalizable store value: "
                    + value.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access store value writer: " + value.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            throw unwrapIoOrRuntime(e);
        }
    }

    private static StoreDataValue decodeExternalValue(String className, byte[] encoded) throws IOException {
        try {
            Class<?> valueClass = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            Constructor<?> constructor = valueClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            if (!(instance instanceof StoreDataValue storeValue)) {
                throw new IllegalStateException("Inherited MVCC snapshot value is not a StoreDataValue: " + className);
            }
            Method readExternal = valueClass.getMethod("readExternal", ObjectInput.class);
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(encoded))) {
                readExternal.invoke(storeValue, in);
            }
            return storeValue;
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot restore inherited MVCC store value: " + className, e);
        } catch (InvocationTargetException e) {
            throw unwrapIoOrRuntime(e);
        }
    }

    private static IOException unwrapIoOrRuntime(InvocationTargetException e) throws IOException {
        Throwable cause = e.getCause();
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(cause);
    }

    private record PersistedRow(long rowId, StoreDataValue[] values) {
    }
}
