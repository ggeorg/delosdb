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

import org.apache.derby.shared.common.error.StandardException;

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
    private static final Map<Object, WriteParticipation> WRITE_PARTICIPATION = new IdentityHashMap<>();

    private DelosStorageTransactionRegistry() {
    }

    /**
     * Declare one logical SQL write target before the statement can mutate it.
     * Read-only access never registers and remains unrestricted.
     */
    public static synchronized WriteParticipationResult registerWriteIntent(
            Object ownerTransaction,
            long logicalTableId,
            boolean mvcc,
            boolean globalTransaction) {
        Object requiredOwner = Objects.requireNonNull(ownerTransaction, "ownerTransaction");
        WriteParticipation current = WRITE_PARTICIPATION.get(requiredOwner);

        if (mvcc && globalTransaction) {
            return WriteParticipationResult.MVCC_XA_UNSUPPORTED;
        }

        boolean heapWrite = !mvcc || (current != null && current.heapWrite());
        boolean mvccWrite = mvcc || (current != null && current.mvccWrite());
        WRITE_PARTICIPATION.put(requiredOwner, new WriteParticipation(heapWrite, mvccWrite));
        return WriteParticipationResult.ALLOWED;
    }

    /** Whether the active local transaction requires one raw-store-backed decision. */
    public static synchronized boolean requiresRawStoreDecision(Object ownerTransaction) {
        WriteParticipation participation = WRITE_PARTICIPATION.get(ownerTransaction);
        return participation != null && participation.heapWrite() && participation.mvccWrite();
    }

    public static synchronized Writer register(
            Object ownerTransaction,
            DelosStorageTable table,
            DelosStorageTransaction transaction) {
        Object requiredOwner = Objects.requireNonNull(ownerTransaction, "ownerTransaction");
        DelosStorageTable requiredTable = Objects.requireNonNull(table, "table");
        DelosStorageTransaction requiredTransaction = Objects.requireNonNull(transaction, "transaction");
        Writer writer = new Writer(requiredOwner, requiredTable, requiredTransaction);
        try {
            for (SavepointMarker marker : savepointsFor(requiredOwner)) {
                writer.setSavepoint(marker.name());
            }
        } catch (RuntimeException | Error registrationFailure) {
            try {
                requiredTable.abort(requiredTransaction);
            } catch (RuntimeException | Error abortFailure) {
                registrationFailure.addSuppressed(abortFailure);
            }
            throw registrationFailure;
        }
        WRITERS.computeIfAbsent(requiredOwner, ignored -> new ArrayList<>()).add(writer);
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
        Object requiredOwner = Objects.requireNonNull(ownerTransaction, "ownerTransaction");
        DelosStorageTable requiredTable = Objects.requireNonNull(table, "table");
        Map<DelosStorageTable, Reader> readers = READERS.computeIfAbsent(
                requiredOwner,
                ignored -> new IdentityHashMap<>());
        Reader existing = readers.get(requiredTable);
        if (existing != null) {
            return existing;
        }

        DelosStorageTransaction transaction = requiredTable.beginReadOnlyTransaction();
        try {
            DelosStorageSnapshot snapshot = requiredTable.snapshot(transaction);
            Reader created = new Reader(requiredTable, transaction, snapshot);
            readers.put(requiredTable, created);
            return created;
        } catch (RuntimeException | Error creationFailure) {
            try {
                requiredTable.abort(transaction);
            } catch (RuntimeException | Error abortFailure) {
                creationFailure.addSuppressed(abortFailure);
            }
            if (readers.isEmpty()) {
                READERS.remove(requiredOwner);
            }
            throw creationFailure;
        }
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

    /**
     * Prepare provider work which must be coupled to the upcoming Derby raw-store commit.
     *
     * <p>Heap-only and MVCC-only transactions retain their existing paths. A mixed
     * heap/MVCC transaction durably stages every MVCC participant, then logs one
     * raw-store decision identity in the Derby transaction. Its filesystem marker
     * is materialized only after commit or by recovery redo. The caller must invoke
     * {@link #completeCommit(CommitPreparation)} only after the raw store commits,
     * or {@link #abortPreparedCommit(CommitPreparation)} after a successful raw-store
     * abort.</p>
     */
    public static CommitPreparation prepareCommit(
            Object ownerTransaction,
            DelosRawStoreCommitParticipant rawStoreParticipant)
            throws StandardException {
        Object requiredOwner = Objects.requireNonNull(ownerTransaction, "ownerTransaction");
        clearSavepoints(requiredOwner);
        List<Writer> writers = writersFor(requiredOwner);
        WriteParticipation participation = writeParticipationFor(requiredOwner);

        if (participation == null || !participation.heapWrite() || !participation.mvccWrite()) {
            Throwable failure = commitWriters(writers);
            rethrowFailure(failure);
            return new CommitPreparation(requiredOwner, List.of(), null);
        }

        if (rawStoreParticipant == null) {
            throw new IllegalStateException(
                    "mixed heap/MVCC commit requires a Derby raw-store decision participant");
        }
        DelosStorageCommitCoordinator coordinator = sharedCoordinator(writers);
        if (!(coordinator instanceof DelosStorageRawDecisionCommitCoordinator rawCoordinator)) {
            throw new IllegalStateException(
                    "mixed heap/MVCC commit requires one raw-decision-capable database coordinator");
        }

        DelosStorageRawDecisionCommitCoordinator.PreparedCommit prepared;
        try {
            prepared = rawCoordinator.prepareForRawStoreDecision(writers.stream()
                    .map(Writer::participant)
                    .toList());
        } catch (RuntimeException | Error preparationFailure) {
            completeWriters(writers);
            clearWriteParticipation(requiredOwner);
            throw preparationFailure;
        }
        try {
            rawStoreParticipant.stageDatabaseCommitDecision(prepared.decision());
        } catch (StandardException | RuntimeException | Error stageFailure) {
            try {
                prepared.abortBeforeRawStoreCommit();
            } catch (RuntimeException | Error abortFailure) {
                stageFailure.addSuppressed(abortFailure);
            } finally {
                completeWriters(writers);
                clearWriteParticipation(requiredOwner);
            }
            throw stageFailure;
        }
        return new CommitPreparation(requiredOwner, writers, prepared);
    }

    /** Enter the internal failure boundary immediately before raw-store commit. */
    public static void beforeRawStoreCommit(CommitPreparation preparation) {
        CommitPreparation required = Objects.requireNonNull(preparation, "preparation");
        if (required.preparedCommit != null) {
            required.preparedCommit.beforeRawStoreCommit();
        }
    }

    /** Return whether this prepared commit requested split timing evidence. */
    public static boolean databaseCommitTimingEnabled(CommitPreparation preparation) {
        CommitPreparation required = Objects.requireNonNull(preparation, "preparation");
        return required.preparedCommit != null
                && required.preparedCommit.databaseCommitTimingEnabled();
    }

    /** Record the synchronous Derby decision-force interval for this commit. */
    public static void recordRawStoreDecisionForceNanos(
            CommitPreparation preparation,
            long elapsedNanos) {
        CommitPreparation required = Objects.requireNonNull(preparation, "preparation");
        if (required.preparedCommit != null) {
            required.preparedCommit.recordRawStoreDecisionForceNanos(
                    Math.max(0L, elapsedNanos));
        }
    }

    /**
     * Enter the internal failure boundary immediately after raw-store commit.
     *
     * <p>If this boundary fails, the raw-store decision is already committed.
     * The prepared coordinator releases its database/table ownership, and this
     * registry detaches all participants so close/reopen recovery is the sole
     * remaining authority.</p>
     */
    public static void afterRawStoreCommit(CommitPreparation preparation) {
        CommitPreparation required = Objects.requireNonNull(preparation, "preparation");
        if (required.preparedCommit == null) {
            return;
        }
        try {
            required.preparedCommit.afterRawStoreCommit();
        } catch (RuntimeException | Error failure) {
            completeWriters(required.writers);
            Throwable terminalFailure = failure;
            for (Reader reader : readersFor(required.ownerTransaction)) {
                terminalFailure = completeParticipant(
                        terminalFailure,
                        reader::close,
                        () -> completeReader(required.ownerTransaction, reader));
            }
            clearWriteParticipation(required.ownerTransaction);
            rethrowFailure(terminalFailure);
        }
    }

    /** Complete external publication after the Derby raw-store decision commits. */
    public static void completeCommit(CommitPreparation preparation) {
        CommitPreparation required = Objects.requireNonNull(preparation, "preparation");
        Throwable failure = null;
        if (required.preparedCommit != null) {
            try {
                required.preparedCommit.publishAfterRawStoreCommit();
            } catch (RuntimeException | Error publicationFailure) {
                failure = publicationFailure;
            } finally {
                completeWriters(required.writers);
            }
        }
        for (Reader reader : readersFor(required.ownerTransaction)) {
            failure = completeParticipant(
                    failure,
                    reader::close,
                    () -> completeReader(required.ownerTransaction, reader));
        }
        clearWriteParticipation(required.ownerTransaction);
        rethrowFailure(failure);
    }

    /**
     * Detach prepared participants after the raw-store decision is durable but
     * commit completion returned ambiguously. No participant outcome is changed;
     * close/reopen recovery is the only remaining authority.
     */
    public static void releasePreparedCommitForRecovery(CommitPreparation preparation) {
        CommitPreparation required = Objects.requireNonNull(preparation, "preparation");
        Throwable failure = null;
        if (required.preparedCommit != null) {
            try {
                required.preparedCommit.releaseForRecovery();
            } catch (RuntimeException | Error releaseFailure) {
                failure = releaseFailure;
            } finally {
                completeWriters(required.writers);
            }
        }
        for (Reader reader : readersFor(required.ownerTransaction)) {
            failure = completeParticipant(
                    failure,
                    reader::close,
                    () -> completeReader(required.ownerTransaction, reader));
        }
        clearWriteParticipation(required.ownerTransaction);
        rethrowFailure(failure);
    }

    /** Abort prepared external participants after Derby raw-store rollback succeeds. */
    public static void abortPreparedCommit(CommitPreparation preparation) {
        CommitPreparation required = Objects.requireNonNull(preparation, "preparation");
        Throwable failure = null;
        if (required.preparedCommit != null) {
            try {
                required.preparedCommit.abortBeforeRawStoreCommit();
            } catch (RuntimeException | Error abortFailure) {
                failure = abortFailure;
            } finally {
                completeWriters(required.writers);
            }
        }
        for (Reader reader : readersFor(required.ownerTransaction)) {
            failure = completeParticipant(
                    failure,
                    reader::close,
                    () -> completeReader(required.ownerTransaction, reader));
        }
        clearWriteParticipation(required.ownerTransaction);
        rethrowFailure(failure);
    }

    public static void commit(Object ownerTransaction) {
        clearSavepoints(ownerTransaction);
        Throwable failure = commitWriters(writersFor(ownerTransaction));
        for (Reader reader : readersFor(ownerTransaction)) {
            failure = completeParticipant(
                    failure,
                    reader::close,
                    () -> completeReader(ownerTransaction, reader));
        }
        if (failure == null) {
            clearWriteParticipation(ownerTransaction);
        }
        rethrowFailure(failure);
    }

    private static Throwable commitWriters(List<Writer> writers) {
        if (writers.isEmpty()) {
            return null;
        }
        DelosStorageCommitCoordinator coordinator = sharedCoordinator(writers);
        if (coordinator == null) {
            if (writers.size() != 1) {
                return new IllegalStateException(
                        "multiple storage writers require one shared database commit coordinator");
            }
            return completeParticipant(null, writers.getFirst()::commit, () -> complete(writers.getFirst()));
        }

        Throwable failure = null;
        try {
            coordinator.commit(writers.stream()
                    .map(Writer::participant)
                    .toList());
        } catch (RuntimeException | Error coordinatorFailure) {
            failure = coordinatorFailure;
        } finally {
            for (Writer writer : writers) {
                writer.markCompleted();
                complete(writer);
            }
        }
        return failure;
    }

    private static DelosStorageCommitCoordinator sharedCoordinator(List<Writer> writers) {
        DelosStorageCommitCoordinator coordinator = null;
        for (Writer writer : writers) {
            if (!(writer.table instanceof DelosStorageCoordinatedCommitTable coordinatedTable)) {
                return null;
            }
            DelosStorageCommitCoordinator candidate = Objects.requireNonNull(
                    coordinatedTable.commitCoordinator(), "commitCoordinator");
            if (coordinator == null) {
                coordinator = candidate;
            } else if (coordinator != candidate) {
                return null;
            }
        }
        return coordinator;
    }

    public static void abort(Object ownerTransaction) {
        clearSavepoints(ownerTransaction);
        Throwable failure = null;
        for (Writer writer : writersFor(ownerTransaction)) {
            failure = completeParticipant(failure, writer::abort, () -> complete(writer));
        }
        for (Reader reader : readersFor(ownerTransaction)) {
            failure = completeParticipant(
                    failure,
                    reader::close,
                    () -> completeReader(ownerTransaction, reader));
        }
        if (failure == null) {
            clearWriteParticipation(ownerTransaction);
        }
        rethrowFailure(failure);
    }

    /**
     * Abort and detach every transaction participant that belongs to a table
     * which is about to be physically retired.
     *
     * <p>Derby table maintenance can replace a conglomerate inside a larger
     * transaction. The old provider table must release its reader snapshots and
     * writer transactions before its files and runtime state are closed, while
     * participants for the replacement table must remain enrolled for the
     * enclosing Derby commit.</p>
     *
     * <p>Every participant is attempted. Successfully aborted participants are
     * removed from their owner transaction. Failed participants remain
     * registered so the caller can retry or fail the physical retirement
     * without silently losing cleanup ownership.</p>
     */
    public static void abortTableParticipants(DelosStorageTable table) {
        DelosStorageTable requiredTable = Objects.requireNonNull(table, "table");
        Throwable failure = null;
        for (Writer writer : writersForTable(requiredTable)) {
            failure = completeParticipant(failure, writer::abort, () -> complete(writer));
        }
        for (OwnedReader ownedReader : readersForTable(requiredTable)) {
            Reader reader = ownedReader.reader();
            failure = completeParticipant(
                    failure,
                    reader::close,
                    () -> completeReader(ownedReader.ownerTransaction(), reader));
        }
        rethrowFailure(failure);
    }

    private static void completeWriters(List<Writer> writers) {
        for (Writer writer : writers) {
            writer.markCompleted();
            complete(writer);
        }
    }

    private static Throwable completeParticipant(
            Throwable failure,
            Runnable operation,
            Runnable removeCompletedParticipant) {
        try {
            operation.run();
            removeCompletedParticipant.run();
            return failure;
        } catch (RuntimeException | Error participantFailure) {
            if (failure == null) {
                return participantFailure;
            }
            failure.addSuppressed(participantFailure);
            return failure;
        }
    }

    private static void rethrowFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
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
        WRITE_PARTICIPATION.clear();
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

    private static synchronized List<Reader> readersFor(Object ownerTransaction) {
        Map<DelosStorageTable, Reader> readers = READERS.get(ownerTransaction);
        if (readers == null || readers.isEmpty()) {
            return List.of();
        }
        return List.copyOf(readers.values());
    }

    private static synchronized List<Writer> writersForTable(DelosStorageTable table) {
        List<Writer> matching = new ArrayList<>();
        for (List<Writer> writers : WRITERS.values()) {
            for (Writer writer : writers) {
                if (!writer.completed && writer.table == table) {
                    matching.add(writer);
                }
            }
        }
        return List.copyOf(matching);
    }

    private static synchronized List<OwnedReader> readersForTable(DelosStorageTable table) {
        List<OwnedReader> matching = new ArrayList<>();
        for (Map.Entry<Object, Map<DelosStorageTable, Reader>> entry : READERS.entrySet()) {
            Reader reader = entry.getValue().get(table);
            if (reader != null && !reader.completed) {
                matching.add(new OwnedReader(entry.getKey(), reader));
            }
        }
        return List.copyOf(matching);
    }

    private static synchronized void completeReader(Object ownerTransaction, Reader reader) {
        Map<DelosStorageTable, Reader> readers = READERS.get(ownerTransaction);
        if (readers == null) {
            return;
        }
        readers.values().removeIf(candidate -> candidate == reader);
        if (readers.isEmpty()) {
            READERS.remove(ownerTransaction);
        }
    }

    private static synchronized WriteParticipation writeParticipationFor(Object ownerTransaction) {
        return WRITE_PARTICIPATION.get(ownerTransaction);
    }

    private static synchronized void clearWriteParticipation(Object ownerTransaction) {
        WRITE_PARTICIPATION.remove(ownerTransaction);
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

    public static final class CommitPreparation {
        private final Object ownerTransaction;
        private final List<Writer> writers;
        private final DelosStorageRawDecisionCommitCoordinator.PreparedCommit preparedCommit;

        private CommitPreparation(
                Object ownerTransaction,
                List<Writer> writers,
                DelosStorageRawDecisionCommitCoordinator.PreparedCommit preparedCommit) {
            this.ownerTransaction = Objects.requireNonNull(ownerTransaction, "ownerTransaction");
            this.writers = List.copyOf(Objects.requireNonNull(writers, "writers"));
            this.preparedCommit = preparedCommit;
        }

        public boolean requiresRawStoreDecision() {
            return preparedCommit != null;
        }
    }

    public static final class Reader {
        private final DelosStorageTable table;
        private final DelosStorageTransaction transaction;
        private final DelosStorageSnapshot snapshot;
        private boolean completed;

        private Reader(
                DelosStorageTable table,
                DelosStorageTransaction transaction,
                DelosStorageSnapshot snapshot) {
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

        private DelosStorageCommitCoordinator.Participant participant() {
            return new DelosStorageCommitCoordinator.Participant(table, transaction);
        }

        private void markCompleted() {
            completed = true;
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

    private record OwnedReader(Object ownerTransaction, Reader reader) {
        private OwnedReader {
            Objects.requireNonNull(ownerTransaction, "ownerTransaction");
            Objects.requireNonNull(reader, "reader");
        }
    }

    public enum WriteParticipationResult {
        ALLOWED(""),
        MVCC_XA_UNSUPPORTED(
                "delos_mvcc writes in XA transactions are not supported");

        private final String description;

        WriteParticipationResult(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private record WriteParticipation(boolean heapWrite, boolean mvccWrite) {
    }

    private record SavepointMarker(String name) {
        private SavepointMarker {
            name = requireSavepointName(name);
        }
    }
}
