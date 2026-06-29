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

    private DelosStorageTransactionRegistry() {
    }

    public static synchronized Writer register(
            Object ownerTransaction,
            DelosStorageTable table,
            DelosStorageTransaction transaction) {
        return register(ownerTransaction, table, transaction, () -> { });
    }

    public static synchronized Writer register(
            Object ownerTransaction,
            DelosStorageTable table,
            DelosStorageTransaction transaction,
            Runnable afterCommit) {
        Writer writer = new Writer(ownerTransaction, table, transaction, afterCommit);
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
            DelosStorageTransaction transaction = table.beginTransaction();
            DelosStorageSnapshot snapshot = table.snapshot(transaction);
            return new Reader(ownerTransaction, table, transaction, snapshot);
        });
    }

    public static void commit(Object ownerTransaction) {
        for (Writer writer : drainWriters(ownerTransaction)) {
            writer.commit();
        }
        for (Reader reader : drainReaders(ownerTransaction)) {
            reader.close();
        }
    }

    public static void abort(Object ownerTransaction) {
        for (Writer writer : drainWriters(ownerTransaction)) {
            writer.abort();
        }
        for (Reader reader : drainReaders(ownerTransaction)) {
            reader.close();
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
    }

    private static synchronized List<Writer> drainWriters(Object ownerTransaction) {
        List<Writer> writers = WRITERS.remove(ownerTransaction);
        if (writers == null || writers.isEmpty()) {
            return List.of();
        }
        return List.copyOf(writers);
    }

    private static synchronized List<Reader> drainReaders(Object ownerTransaction) {
        Map<DelosStorageTable, Reader> readers = READERS.remove(ownerTransaction);
        if (readers == null || readers.isEmpty()) {
            return List.of();
        }
        return List.copyOf(readers.values());
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
        private final Runnable afterCommit;
        private boolean completed;

        private Writer(
                Object ownerTransaction,
                DelosStorageTable table,
                DelosStorageTransaction transaction,
                Runnable afterCommit) {
            this.ownerTransaction = ownerTransaction;
            this.table = table;
            this.transaction = transaction;
            this.afterCommit = afterCommit == null ? () -> { } : afterCommit;
        }

        public void commit() {
            if (!completed) {
                table.commit(transaction);
                afterCommit.run();
                completed = true;
            }
        }

        public void abort() {
            if (!completed) {
                table.abort(transaction);
                completed = true;
            }
        }
    }
}
