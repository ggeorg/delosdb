/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry

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
package org.apache.derby.iapi.store.types;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Storage-api transaction-scoped writer registry.
 *
 * <p>The SQL engine owns commit/rollback timing, but storage providers own
 * their provider transactions. This registry is the provider-neutral handoff:
 * engine code calls {@link #commit(Object)} or {@link #abort(Object)} for the
 * Derby transaction object, while compatibility adapters register storage-api
 * writers against that object.</p>
 */
public final class DelosStorageTransactionRegistry {
    private static final Map<Object, List<Writer>> WRITERS = new IdentityHashMap<>();
    private static final Map<Object, Map<DelosStorageTable, Reader>> READERS = new IdentityHashMap<>();
    private static final Map<Object, List<SavepointMarker>> SAVEPOINTS = new IdentityHashMap<>();

    private DelosStorageTransactionRegistry() {
    }

    public static synchronized Writer register(
            Object ownerTransaction,
            DelosStorageTable table,
            DelosStorageTransaction transaction) {
        Writer writer = new Writer(ownerTransaction, table, transaction);
        for (SavepointMarker marker : savepointsFor(ownerTransaction)) {
            writer.setSavepoint(marker.name());
        }
        WRITERS.computeIfAbsent(ownerTransaction, ignored -> new ArrayList<>()).add(writer);
        return writer;
    }

    public static synchronized void complete(Writer writer) {
        if (writer == null) {
            return;
        }
        List<Writer> writers = WRITERS.get(writer.ownerTransaction);
        if (writers == null) {
            return;
        }
        writers.remove(writer);
        if (writers.isEmpty()) {
            WRITERS.remove(writer.ownerTransaction);
        }
    }

    public static synchronized Reader reader(Object ownerTransaction, DelosStorageTable table) {
        Map<DelosStorageTable, Reader> readers = READERS.computeIfAbsent(
                ownerTransaction,
                ignored -> new IdentityHashMap<>());
        return readers.computeIfAbsent(table, ignored -> {
            DelosStorageTransaction transaction = table.beginReadOnlyTransaction();
            DelosStorageSnapshot snapshot = table.snapshot(transaction);
            return new Reader(ownerTransaction, table, transaction, snapshot);
        });
    }

    public static synchronized DelosStorageTransaction activeWriterTransaction(
            Object ownerTransaction,
            DelosStorageTable table) {
        List<Writer> writers = WRITERS.get(ownerTransaction);
        if (writers == null || writers.isEmpty()) {
            return null;
        }
        for (Writer writer : writers) {
            if (!writer.completed && writer.table == table) {
                return writer.transaction;
            }
        }
        return null;
    }

    public static void commit(Object ownerTransaction) {
        clearSavepoints(ownerTransaction);
        for (Writer writer : drainWriters(ownerTransaction)) {
            writer.commit();
        }
        for (Reader reader : drainReaders(ownerTransaction)) {
            reader.close();
        }
    }

    public static void abort(Object ownerTransaction) {
        clearSavepoints(ownerTransaction);
        for (Writer writer : drainWriters(ownerTransaction)) {
            writer.abort();
        }
        for (Reader reader : drainReaders(ownerTransaction)) {
            reader.close();
        }
    }

    public static synchronized void setSavepoint(Object ownerTransaction, String savepointName) {
        String normalizedName = requireSavepointName(savepointName);
        List<SavepointMarker> savepoints = SAVEPOINTS.computeIfAbsent(
                ownerTransaction, ignored -> new ArrayList<>());
        removeSavepointAndFollowing(savepoints, normalizedName);
        SavepointMarker marker = new SavepointMarker(normalizedName);
        savepoints.add(marker);
        for (Writer writer : writersFor(ownerTransaction)) {
            writer.setSavepoint(normalizedName);
        }
    }

    public static synchronized void rollbackToSavepoint(Object ownerTransaction, String savepointName) {
        String normalizedName = requireSavepointName(savepointName);
        List<SavepointMarker> savepoints = SAVEPOINTS.get(ownerTransaction);
        if (savepoints != null) {
            truncateAfterSavepoint(savepoints, normalizedName);
        }
        for (Writer writer : writersFor(ownerTransaction)) {
            writer.rollbackToSavepoint(normalizedName);
        }
    }

    public static synchronized void releaseSavepoint(Object ownerTransaction, String savepointName) {
        String normalizedName = requireSavepointName(savepointName);
        List<SavepointMarker> savepoints = SAVEPOINTS.get(ownerTransaction);
        if (savepoints != null) {
            removeSavepointAndFollowing(savepoints, normalizedName);
            if (savepoints.isEmpty()) {
                SAVEPOINTS.remove(ownerTransaction);
            }
        }
        for (Writer writer : writersFor(ownerTransaction)) {
            writer.releaseSavepoint(normalizedName);
        }
    }

    public static synchronized int pendingCountForTesting(Object ownerTransaction) {
        List<Writer> writers = WRITERS.get(ownerTransaction);
        Map<DelosStorageTable, Reader> readers = READERS.get(ownerTransaction);
        int writerCount = writers == null ? 0 : writers.size();
        int readerCount = readers == null ? 0 : readers.size();
        return writerCount + readerCount;
    }

    public static synchronized int totalPendingCountForTesting() {
        int count = 0;
        for (List<Writer> writers : WRITERS.values()) {
            count += writers.size();
        }
        for (Map<DelosStorageTable, Reader> readers : READERS.values()) {
            count += readers.size();
        }
        return count;
    }

    public static synchronized void clearForTesting() {
        WRITERS.clear();
        READERS.clear();
        SAVEPOINTS.clear();
    }

    private static synchronized List<Writer> drainWriters(Object ownerTransaction) {
        List<Writer> writers = WRITERS.remove(ownerTransaction);
        if (writers == null || writers.isEmpty()) {
            return List.of();
        }
        return List.copyOf(writers);
    }

    private static synchronized List<Writer> writersFor(Object ownerTransaction) {
        List<Writer> writers = WRITERS.get(ownerTransaction);
        if (writers == null || writers.isEmpty()) {
            return List.of();
        }
        return List.copyOf(writers);
    }

    private static synchronized List<SavepointMarker> savepointsFor(Object ownerTransaction) {
        List<SavepointMarker> savepoints = SAVEPOINTS.get(ownerTransaction);
        if (savepoints == null || savepoints.isEmpty()) {
            return List.of();
        }
        return List.copyOf(savepoints);
    }

    private static synchronized void clearSavepoints(Object ownerTransaction) {
        SAVEPOINTS.remove(ownerTransaction);
    }

    private static synchronized List<Reader> drainReaders(Object ownerTransaction) {
        Map<DelosStorageTable, Reader> readers = READERS.remove(ownerTransaction);
        if (readers == null || readers.isEmpty()) {
            return List.of();
        }
        return List.copyOf(readers.values());
    }

    private static String requireSavepointName(String savepointName) {
        String normalizedName = Objects.requireNonNull(savepointName, "savepointName").trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("savepointName must not be blank");
        }
        return normalizedName;
    }

    private static void truncateAfterSavepoint(List<SavepointMarker> savepoints, String savepointName) {
        int index = indexOfSavepoint(savepoints, savepointName);
        if (index < 0) {
            return;
        }
        while (savepoints.size() > index + 1) {
            savepoints.remove(savepoints.size() - 1);
        }
    }

    private static void removeSavepointAndFollowing(List<SavepointMarker> savepoints, String savepointName) {
        int index = indexOfSavepoint(savepoints, savepointName);
        if (index < 0) {
            return;
        }
        while (savepoints.size() > index) {
            savepoints.remove(savepoints.size() - 1);
        }
    }

    private static int indexOfSavepoint(List<SavepointMarker> savepoints, String savepointName) {
        for (int i = 0; i < savepoints.size(); i++) {
            if (savepointName.equals(savepoints.get(i).name())) {
                return i;
            }
        }
        return -1;
    }


    public static final class Reader {
        private final Object ownerTransaction;
        private final DelosStorageTable table;
        private final DelosStorageTransaction transaction;
        private final DelosStorageSnapshot snapshot;
        private boolean completed;

        private Reader(
                Object ownerTransaction,
                DelosStorageTable table,
                DelosStorageTransaction transaction,
                DelosStorageSnapshot snapshot) {
            this.ownerTransaction = ownerTransaction;
            this.table = table;
            this.transaction = transaction;
            this.snapshot = snapshot;
        }

        public DelosStorageTransaction transaction() {
            return transaction;
        }

        public DelosStorageSnapshot snapshot() {
            return snapshot;
        }

        public void close() {
            if (!completed) {
                table.abort(transaction);
                completed = true;
            }
        }
    }

    public static final class Writer {
        private final Object ownerTransaction;
        private final DelosStorageTable table;
        private final DelosStorageTransaction transaction;
        private boolean completed;

        private Writer(
                Object ownerTransaction,
                DelosStorageTable table,
                DelosStorageTransaction transaction) {
            this.ownerTransaction = ownerTransaction;
            this.table = table;
            this.transaction = transaction;
        }

        public void commit() {
            if (!completed) {
                table.commit(transaction);
                completed = true;
            }
        }

        public void abort() {
            if (!completed) {
                table.abort(transaction);
                completed = true;
            }
        }

        private void setSavepoint(String savepointName) {
            if (!completed && table instanceof DelosStorageSavepointParticipant participant) {
                participant.setSavepoint(transaction, savepointName);
            }
        }

        private void rollbackToSavepoint(String savepointName) {
            if (!completed && table instanceof DelosStorageSavepointParticipant participant) {
                participant.rollbackToSavepoint(transaction, savepointName);
            }
        }

        private void releaseSavepoint(String savepointName) {
            if (!completed && table instanceof DelosStorageSavepointParticipant participant) {
                participant.releaseSavepoint(transaction, savepointName);
            }
        }
    }

    private record SavepointMarker(String name) {
        private SavepointMarker {
            name = requireSavepointName(name);
        }
    }
}
