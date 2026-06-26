/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.InheritedMvccPageVolumeStateStore

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowDirectoryStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowPayload;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;

import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.StoreDataValue;

/**
 * Page-volume backed committed-state store for the inherited MVCC conglomerate.
 *
 * <p>MODULE11A keeps Derby integration as the gate: callers still enter through
 * {@link MvccConglomerateState}, {@link MvccConglomerateController}, and
 * {@link MvccScanController}. This class only replaces the MODULE9A ad-hoc
 * snapshot file as the committed-row reload authority with the existing Delos
 * page-volume backed MVCC table.</p>
 */
final class InheritedMvccPageVolumeStateStore {
    private static final String ROW_KEY_PREFIX = "row:";
    private static final MvccCommitSequence LATEST_COMMITTED = new MvccCommitSequence(Long.MAX_VALUE);

    private final Path pageFile;
    private final PageBackedMvccTable table;
    private long nextTransactionId;
    private long nextCommitSequence;

    private InheritedMvccPageVolumeStateStore(Path pageFile, PageBackedMvccTable table) {
        this.pageFile = pageFile;
        this.table = table;
        long nextSequence = 1L;
        if (table != null) {
            nextSequence = Math.max(nextSequence, table.physicalVersionCount() + 1L);
        }
        this.nextTransactionId = nextSequence;
        this.nextCommitSequence = nextSequence;
    }

    static InheritedMvccPageVolumeStateStore open(Path databaseDirectory, ContainerKey key) {
        Path pageFile = pageFile(databaseDirectory, key);
        if (pageFile == null || key.getContainerId() == 0L) {
            return disabled();
        }
        try {
            return new InheritedMvccPageVolumeStateStore(pageFile, PageBackedMvccTable.open(pageFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open inherited MVCC page-volume state for " + key, e);
        }
    }

    static InheritedMvccPageVolumeStateStore disabled() {
        return new InheritedMvccPageVolumeStateStore(null, null);
    }

    boolean enabled() {
        return table != null;
    }

    Path pageFile() {
        return pageFile;
    }

    Path rowDirectoryFile() {
        return pageFile == null ? null : PageBackedMvccTable.rowDirectoryPath(pageFile);
    }

    boolean hasDurableState() {
        return enabled() && table.physicalVersionCount() > 0;
    }

    List<PersistedRow> loadVisibleRows() {
        if (!enabled()) {
            return List.of();
        }
        List<PersistedRow> rows = new ArrayList<>();
        for (MvccRowPayload payload : table.visibleRows(LATEST_COMMITTED)) {
            rows.add(new PersistedRow(rowIdFromKey(payload.key()), decodeRow(payload.value())));
        }
        rows.sort(java.util.Comparator.comparingLong(PersistedRow::rowId));
        return List.copyOf(rows);
    }

    long nextInheritedRowId() {
        if (!enabled()) {
            return 1L;
        }
        long maxRowId = 0L;
        for (MvccRowDirectoryStore.RowHeadRecord head : table.durableRowDirectoryHeads().values()) {
            maxRowId = Math.max(maxRowId, rowIdFromKey(head.key()));
        }
        return maxRowId + 1L;
    }

    Optional<MvccRowDirectoryStore.RowHeadRecord> rowHeadForInheritedRowId(long rowId) {
        if (!enabled() || rowId <= 0L) {
            return Optional.empty();
        }
        return table.rowDirectoryHeadForRowId(new MvccRowId(rowId));
    }

    void persistVisibleRows(List<PersistedRow> visibleRows) {
        Objects.requireNonNull(visibleRows, "visibleRows");
        if (!enabled()) {
            return;
        }
        Map<String, MvccRowDirectoryStore.RowHeadRecord> existingHeads = new LinkedHashMap<>();
        for (MvccRowDirectoryStore.RowHeadRecord head : table.durableRowDirectoryHeads().values()) {
            existingHeads.put(head.key(), head);
        }
        Set<String> liveKeys = visibleRows.stream()
                .map(row -> keyFor(row.rowId()))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        try {
            for (PersistedRow row : visibleRows) {
                String key = keyFor(row.rowId());
                byte[] encoded = encodeRow(row.values());
                if (existingHeads.containsKey(key)) {
                    if (table.readPayload(key, LATEST_COMMITTED)
                            .map(payload -> !java.util.Arrays.equals(payload.value(), encoded))
                            .orElse(true)) {
                        table.updateCommitted(key, encoded, nextTransactionId(), nextCommitSequence());
                    }
                } else {
                    table.insertCommitted(key, encoded, nextTransactionId(), nextCommitSequence());
                }
            }
            for (MvccRowDirectoryStore.RowHeadRecord head : existingHeads.values()) {
                if (!liveKeys.contains(head.key()) && !head.tombstone()) {
                    table.deleteCommitted(head.key(), nextTransactionId(), nextCommitSequence());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist inherited MVCC state to page volume " + pageFile, e);
        }
    }

    void drop() {
        if (!enabled()) {
            return;
        }
        try {
            table.close();
            Files.deleteIfExists(pageFile);
            Path rowDirectory = rowDirectoryFile();
            if (rowDirectory != null) {
                Files.deleteIfExists(rowDirectory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete inherited MVCC page-volume state " + pageFile, e);
        }
    }

    void close() {
        if (!enabled()) {
            return;
        }
        try {
            table.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not close inherited MVCC page-volume state " + pageFile, e);
        }
    }

    private long nextTransactionId() {
        return nextTransactionId++;
    }

    private long nextCommitSequence() {
        return nextCommitSequence++;
    }

    private static Path pageFile(Path databaseDirectory, ContainerKey key) {
        Path directory = inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + key.getSegmentId() + "-" + key.getContainerId() + ".pages");
    }

    static Path inheritedStoreDirectory(Path databaseDirectory) {
        if (databaseDirectory == null) {
            return null;
        }
        return databaseDirectory
                .resolve(DelosMvccStorageProvider.DATABASE_STORAGE_DIRECTORY_NAME)
                .resolve("inherited-store");
    }

    private static String keyFor(long rowId) {
        if (rowId <= 0L) {
            throw new IllegalArgumentException("inherited MVCC row id must be positive: " + rowId);
        }
        return ROW_KEY_PREFIX + rowId;
    }

    private static long rowIdFromKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!key.startsWith(ROW_KEY_PREFIX)) {
            throw new IllegalStateException("Unsupported inherited MVCC page-volume row key: " + key);
        }
        try {
            return Long.parseLong(key.substring(ROW_KEY_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid inherited MVCC page-volume row key: " + key, e);
        }
    }

    private static byte[] encodeRow(StoreDataValue[] values) throws IOException {
        StoreDataValue[] row = values == null ? new StoreDataValue[0] : values;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(row.length);
            for (StoreDataValue value : row) {
                writeValue(out, value);
            }
        }
        return bytes.toByteArray();
    }

    private static StoreDataValue[] decodeRow(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int columnCount = in.readInt();
            if (columnCount < 0) {
                throw new IOException("negative inherited MVCC page-volume column count: " + columnCount);
            }
            StoreDataValue[] row = new StoreDataValue[columnCount];
            for (int column = 0; column < columnCount; column++) {
                row[column] = readValue(in);
            }
            return row;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode inherited MVCC page-volume row", e);
        }
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
            throw new IOException("Negative inherited MVCC page-volume value length for " + className + ": " + length);
        }
        byte[] encoded = in.readNBytes(length);
        if (encoded.length != length) {
            throw new IOException("Short inherited MVCC page-volume value read for " + className);
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
            throw new IllegalArgumentException("Inherited MVCC page-volume persistence requires externalizable store value: "
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
                throw new IllegalStateException("Inherited MVCC page-volume value is not a StoreDataValue: " + className);
            }
            Method readExternal = valueClass.getMethod("readExternal", ObjectInput.class);
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(encoded))) {
                readExternal.invoke(storeValue, in);
            }
            return storeValue;
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot restore inherited MVCC page-volume store value: " + className, e);
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

    record PersistedRow(long rowId, StoreDataValue[] values) {
        PersistedRow {
            if (rowId <= 0L) {
                throw new IllegalArgumentException("inherited MVCC row id must be positive: " + rowId);
            }
            values = values == null ? new StoreDataValue[0] : values.clone();
        }
    }
}
