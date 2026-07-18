package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.MvccStorageNames;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommandSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccCommitDurabilityMetrics;

import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionDiagnostics;
import org.apache.derby.iapi.store.types.StoreDataValue;

final class MvccInheritedHandles {
    private MvccInheritedHandles() {
    }

    static Transaction transaction(DelosStorageTransaction transaction) {
        if (!(transaction instanceof Transaction mvccTransaction)) {
            throw new IllegalArgumentException("Expected delos_mvcc transaction handle");
        }
        return mvccTransaction;
    }

    static Snapshot snapshot(DelosStorageSnapshot snapshot) {
        if (!(snapshot instanceof Snapshot mvccSnapshot)) {
            throw new IllegalArgumentException("Expected delos_mvcc snapshot handle");
        }
        return mvccSnapshot;
    }

    static final class Transaction implements DelosStorageTransaction, DelosStorageTransactionDiagnostics {
        private final MvccTransaction nativeTransaction;
        private final boolean readOnly;
        private final MvccCommitDurabilityMetrics.Snapshot beginDurability;
        private final Map<String, MvccCommandSequence> savepoints = new LinkedHashMap<>();
        private final Map<Long, List<WriteIntent>> writeIntents = new LinkedHashMap<>();
        private final List<WriteIntent> appendedWriteIntents = new ArrayList<>();
        private long nextCommandSequence = 1L;
        private long writeIntentRevision;

        Transaction(MvccTransaction nativeTransaction) {
            this(nativeTransaction, false, MvccCommitDurabilityMetrics.Snapshot.empty());
        }

        Transaction(MvccTransaction nativeTransaction, boolean readOnly) {
            this(nativeTransaction, readOnly, MvccCommitDurabilityMetrics.Snapshot.empty());
        }

        Transaction(
                MvccTransaction nativeTransaction,
                boolean readOnly,
                MvccCommitDurabilityMetrics.Snapshot beginDurability) {
            this.nativeTransaction = Objects.requireNonNull(nativeTransaction, "nativeTransaction");
            this.readOnly = readOnly;
            this.beginDurability = Objects.requireNonNull(beginDurability, "beginDurability");
        }

        @Override
        public MvccTransaction nativeTransaction() {
            return nativeTransaction;
        }

        @Override
        public long providerTransactionId() {
            return nativeTransaction.id().value();
        }

        @Override
        public synchronized DelosStorageTransactionDiagnostics.Values diagnosticValues() {
            return new DelosStorageTransactionDiagnostics.Values(
                    providerTransactionId(),
                    readOnly,
                    writeIntents.size(),
                    appendedWriteIntents.size(),
                    savepoints.size(),
                    writeIntentRevision);
        }

        synchronized MvccCommandSequence nextCommandSequence() {
            requireWritable();
            return MvccCommandSequence.of(nextCommandSequence++);
        }

        @Override
        public boolean readOnly() {
            return readOnly;
        }

        MvccCommitDurabilityMetrics.Snapshot beginDurability() {
            return beginDurability;
        }

        synchronized void setSavepoint(String savepointName) {
            savepoints.put(requireSavepointName(savepointName), lastCompletedCommandSequence());
        }

        synchronized void recordUpsertWriteIntent(
                long rowId,
                StoreDataValue[] row,
                MvccCommandSequence commandSequence) {
            recordWriteIntent(WriteIntent.upsert(rowId, row, commandSequence));
        }

        synchronized void recordDeleteWriteIntent(long rowId, MvccCommandSequence commandSequence) {
            recordWriteIntent(WriteIntent.delete(rowId, commandSequence));
        }

        synchronized List<WriteIntent> writeIntents() {
            List<WriteIntent> latest = new ArrayList<>(writeIntents.size());
            for (List<WriteIntent> history : writeIntents.values()) {
                if (!history.isEmpty()) {
                    latest.add(history.get(history.size() - 1));
                }
            }
            return List.copyOf(latest);
        }

        synchronized List<WriteIntent> appendedWriteIntents() {
            return List.copyOf(appendedWriteIntents);
        }

        synchronized boolean hasWriteIntents() {
            return !writeIntents.isEmpty();
        }

        synchronized boolean hasWriteIntentForRow(long rowId) {
            List<WriteIntent> history = writeIntents.get(rowId);
            return history != null && !history.isEmpty();
        }

        synchronized java.util.Optional<WriteIntent> latestVisibleWriteIntent(
                long rowId,
                MvccCommandSequence visibleThroughCommand) {
            List<WriteIntent> history = writeIntents.get(rowId);
            if (history == null || history.isEmpty()) {
                return java.util.Optional.empty();
            }
            for (int i = history.size() - 1; i >= 0; i--) {
                WriteIntent intent = history.get(i);
                if (intent.commandSequence().isAtOrBefore(visibleThroughCommand)) {
                    return java.util.Optional.of(intent);
                }
            }
            return java.util.Optional.empty();
        }

        @Override
        public synchronized int writeIntentCount() {
            return writeIntents().size();
        }

        @Override
        public synchronized int appendedWriteIntentCount() {
            return appendedWriteIntents.size();
        }

        @Override
        public synchronized long writeIntentRevision() {
            return writeIntentRevision;
        }

        @Override
        public synchronized int savepointCount() {
            return savepoints.size();
        }


        synchronized boolean hasAppendedWriteIntent(
                long rowId,
                MvccCommandSequence commandSequence,
                boolean delete) {
            for (WriteIntent intent : appendedWriteIntents) {
                if (intent.rowId() == rowId
                        && intent.commandSequence().equals(commandSequence)
                        && intent.delete() == delete) {
                    return true;
                }
            }
            return false;
        }

        synchronized void clearWriteIntents() {
            writeIntents.clear();
            appendedWriteIntents.clear();
            writeIntentRevision++;
        }

        synchronized MvccCommandSequence rollbackCurrentCommand(MvccCommandSequence commandSequence) {
            MvccCommandSequence boundary = MvccCommandSequence.of(
                    Objects.requireNonNull(commandSequence, "commandSequence").value() - 1L);
            removeWriteIntentsAfter(boundary);
            nextCommandSequence = boundary.value() + 1L;
            writeIntentRevision++;
            return boundary;
        }

        synchronized MvccCommandSequence rollbackToSavepoint(String savepointName) {
            String normalizedName = requireSavepointName(savepointName);
            MvccCommandSequence boundary = savepoints.get(normalizedName);
            if (boundary == null) {
                throw new IllegalStateException("Unknown delos_mvcc savepoint: " + normalizedName);
            }
            removeSavepointsAfter(normalizedName);
            removeWriteIntentsAfter(boundary);
            nextCommandSequence = boundary.value() + 1L;
            writeIntentRevision++;
            return boundary;
        }

        synchronized void releaseSavepoint(String savepointName) {
            String normalizedName = requireSavepointName(savepointName);
            boolean remove = false;
            var iterator = savepoints.keySet().iterator();
            while (iterator.hasNext()) {
                String current = iterator.next();
                if (remove || current.equals(normalizedName)) {
                    iterator.remove();
                    remove = true;
                }
            }
        }

        @Override
        public String providerName() {
            return MvccStorageNames.PROVIDER_NAME;
        }

        private MvccCommandSequence lastCompletedCommandSequence() {
            return MvccCommandSequence.of(Math.max(0L, nextCommandSequence - 1L));
        }

        private void removeWriteIntentsAfter(MvccCommandSequence boundary) {
            appendedWriteIntents.removeIf(intent -> intent.commandSequence().compareTo(boundary) > 0);
            var iterator = writeIntents.entrySet().iterator();
            while (iterator.hasNext()) {
                List<WriteIntent> history = iterator.next().getValue();
                history.removeIf(intent -> intent.commandSequence().compareTo(boundary) > 0);
                if (history.isEmpty()) {
                    iterator.remove();
                }
            }
        }

        private void recordWriteIntent(WriteIntent intent) {
            requireWritable();
            appendedWriteIntents.add(intent);
            writeIntents.computeIfAbsent(intent.rowId(), ignored -> new ArrayList<>()).add(intent);
            writeIntentRevision++;
        }

        private void removeSavepointsAfter(String savepointName) {
            boolean remove = false;
            var iterator = savepoints.keySet().iterator();
            while (iterator.hasNext()) {
                String current = iterator.next();
                if (remove) {
                    iterator.remove();
                } else if (current.equals(savepointName)) {
                    remove = true;
                }
            }
        }

        private void requireWritable() {
            if (readOnly) {
                throw new IllegalStateException("read-only delos_mvcc transaction cannot record writes");
            }
        }

        private static String requireSavepointName(String savepointName) {
            String normalizedName = Objects.requireNonNull(savepointName, "savepointName").trim();
            if (normalizedName.isEmpty()) {
                throw new IllegalArgumentException("savepointName must not be blank");
            }
            return normalizedName;
        }

        record WriteIntent(
                long rowId,
                StoreDataValue[] row,
                boolean delete,
                MvccCommandSequence commandSequence) {
            WriteIntent {
                if (rowId <= 0L) {
                    throw new IllegalArgumentException("rowId must be positive: " + rowId);
                }
                if (!delete) {
                    row = Objects.requireNonNull(row, "row");
                }
                commandSequence = Objects.requireNonNull(commandSequence, "commandSequence");
            }

            static WriteIntent upsert(
                    long rowId,
                    StoreDataValue[] row,
                    MvccCommandSequence commandSequence) {
                return new WriteIntent(rowId, row, false, commandSequence);
            }

            static WriteIntent delete(long rowId, MvccCommandSequence commandSequence) {
                return new WriteIntent(rowId, null, true, commandSequence);
            }
        }
    }

    record Snapshot(Transaction transaction, MvccSnapshot nativeSnapshot) implements DelosStorageSnapshot {
        Snapshot {
            transaction = Objects.requireNonNull(transaction, "transaction");
            nativeSnapshot = Objects.requireNonNull(nativeSnapshot, "nativeSnapshot");
        }

        @Override
        public String providerName() {
            return MvccStorageNames.PROVIDER_NAME;
        }
    }
}
