package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommandSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccRow;
import io.github.ggeorg.delosdb.storage.mvcc.MvccScan;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.storage.mvcc.store.MvccCandidateIndex;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.DelosStorageCandidateIndex;
import org.apache.derby.iapi.store.types.DelosStorageMaintenance;
import org.apache.derby.iapi.store.types.DelosStorageRowHead;
import org.apache.derby.iapi.store.types.DelosStorageRowLocator;
import org.apache.derby.iapi.store.types.DelosStorageSavepointParticipant;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosVacuumOutcome;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueOperations;
import org.apache.derby.shared.common.error.StandardException;

final class MvccInheritedTable implements DelosStorageTable,
        DelosStorageMaintenance,
        DelosStorageRowLocator,
        DelosStorageCandidateIndex,
        DelosStorageSavepointParticipant,
        DelosStorageTableDiagnostics {
    private final long segmentId;
    private final long containerId;
    private final Path retiredSnapshotFile;
    private final Path transactionStatusFile;
    private final PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore;
    private final MvccTransactionStatusStore transactionStatusStore;
    private final MvccTable<Long, StoreDataValue[]> table = new MvccTable<>();
    private final MvccTransactionManager transactions;
    private final MvccCandidateIndex candidateIndex = new MvccCandidateIndex();
    private long nextRowId = 1L;
    private DelosVacuumOutcome lastVacuumOutcome = DelosVacuumOutcome.disabled();

    MvccInheritedTable(long segmentId, long containerId, Path databaseDirectory) {
        this.segmentId = segmentId;
        this.containerId = containerId;
        this.retiredSnapshotFile = retiredSnapshotFile(databaseDirectory, segmentId, containerId);
        this.transactionStatusFile = transactionStatusFile(databaseDirectory, segmentId, containerId);
        this.pageVolumeStateStore = PageVolumeMvccStateStore.open(
                databaseDirectory,
                storageId(segmentId, containerId),
                MvccInheritedRowCodec.INSTANCE);
        this.transactionStatusStore = transactionStatusFile == null || containerId == 0L
                ? MvccTransactionStatusStore.disabled()
                : MvccTransactionStatusStore.open(transactionStatusFile);
        this.transactions = new MvccTransactionManager(transactionStatusStore);
        loadCommittedState();
    }

    @Override
    public synchronized DelosStorageTransaction beginTransaction() {
        return new MvccInheritedHandles.Transaction(transactions.begin());
    }

    @Override
    public synchronized DelosStorageSnapshot snapshot(DelosStorageTransaction transaction) {
        return new MvccInheritedHandles.Snapshot(transactions.snapshot(nativeTransaction(transaction)));
    }

    @Override
    public synchronized DelosStorageScan openScan(DelosStorageSnapshot snapshot) {
        return new MvccInheritedScan(table.openScan(nativeSnapshot(snapshot), transactions));
    }

    @Override
    public synchronized Optional<StoreDataValue[]> read(long rowId, DelosStorageSnapshot snapshot) {
        return table.read(rowId, nativeSnapshot(snapshot), transactions);
    }

    @Override
    public synchronized void insert(long rowId, StoreDataValue[] row, DelosStorageTransaction transaction) {
        MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
        table.insert(rowId, cloneRowUnchecked(row), handle.nativeTransaction(), handle.nextCommandSequence());
    }

    @Override
    public synchronized void update(
            long rowId,
            StoreDataValue[] replacement,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
        table.update(
                rowId,
                cloneRowUnchecked(replacement),
                handle.nativeTransaction(),
                nativeSnapshot(snapshot),
                transactions,
                handle.nextCommandSequence());
    }

    @Override
    public synchronized void delete(
            long rowId,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
        table.delete(
                rowId,
                handle.nativeTransaction(),
                nativeSnapshot(snapshot),
                transactions,
                handle.nextCommandSequence());
    }

    @Override
    public synchronized void commit(DelosStorageTransaction transaction) {
        transactions.commit(nativeTransaction(transaction));
    }

    @Override
    public synchronized void abort(DelosStorageTransaction transaction) {
        transactions.abort(nativeTransaction(transaction));
    }

    @Override
    public synchronized void setSavepoint(DelosStorageTransaction transaction, String savepointName) {
        nativeTransactionHandle(transaction).setSavepoint(savepointName);
    }

    @Override
    public synchronized void rollbackToSavepoint(DelosStorageTransaction transaction, String savepointName) {
        MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
        MvccCommandSequence boundary = handle.rollbackToSavepoint(savepointName);
        table.rollbackTransactionChangesAfter(handle.nativeTransaction(), boundary);
    }

    @Override
    public synchronized void releaseSavepoint(DelosStorageTransaction transaction, String savepointName) {
        nativeTransactionHandle(transaction).releaseSavepoint(savepointName);
    }

    @Override
    public synchronized long nextRowId() {
        return nextRowId++;
    }

    @Override
    public synchronized void persistCommittedState() {
        List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows = visibleRows();
        pageVolumeStateStore.persistVisibleRows(rows);
        candidateIndex.recordVisibleRows(toCandidateRows(rows));
    }

    @Override
    public synchronized void dropDurableState() {
        candidateIndex.clear();
        try {
            pageVolumeStateStore.drop();
            if (retiredSnapshotFile != null) {
                Files.deleteIfExists(retiredSnapshotFile);
            }
            if (transactionStatusFile != null) {
                Files.deleteIfExists(transactionStatusFile);
            }
            Path pageMutationLogFile = pageVolumeStateStore.pageMutationLogFile();
            if (pageMutationLogFile != null) {
                Files.deleteIfExists(pageMutationLogFile);
            }
            Path writeAheadLogFile = pageVolumeStateStore.writeAheadLogFile();
            if (writeAheadLogFile != null) {
                Files.deleteIfExists(writeAheadLogFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete inherited MVCC state for "
                    + segmentId + ":" + containerId, e);
        }
    }

    @Override
    public synchronized DelosStorageRowHead rowHeadFor(long rowId) {
        return pageVolumeStateStore.rowHeadForInheritedRowId(rowId)
                .map(head -> DelosStorageRowHead.present(
                        rowId,
                        head.headLocator().pageId().value(),
                        head.headLocator().slotId()))
                .orElseGet(() -> DelosStorageRowHead.absent(rowId));
    }

    @Override
    public synchronized Optional<List<Long>> candidateRowIdsFor(int column, String value) {
        return candidateIndex.candidatesFor(column, value);
    }

    @Override
    public synchronized int candidateIndexKeyCountForTesting() {
        return candidateIndex.indexedKeyCountForTesting();
    }

    @Override
    public Path pageVolumeStateFileForTesting() {
        return pageVolumeStateStore.pageFile();
    }

    @Override
    public Path rowDirectoryStateFileForTesting() {
        return pageVolumeStateStore.rowDirectoryFile();
    }

    @Override
    public Path pageMutationLogFileForTesting() {
        return pageVolumeStateStore.pageMutationLogFile();
    }

    @Override
    public Path writeAheadLogFileForTesting() {
        return pageVolumeStateStore.writeAheadLogFile();
    }

    @Override
    public Path checkpointFileForTesting() {
        return pageVolumeStateStore.checkpointFile();
    }

    @Override
    public String checkpointStatusForTesting() {
        return pageVolumeStateStore.checkpointStatus();
    }

    @Override
    public synchronized int physicalVersionCountForTesting() {
        return pageVolumeStateStore.physicalVersionCount();
    }

    @Override
    public synchronized int logicalRowCountForTesting() {
        return pageVolumeStateStore.logicalRowCount();
    }

    @Override
    public synchronized int consistencyErrorCountForTesting() {
        return pageVolumeStateStore.consistencyErrorCount();
    }

    @Override
    public synchronized String consistencySummaryForTesting() {
        return pageVolumeStateStore.consistencySummary();
    }

    @Override
    public synchronized void assertConsistentForTesting() {
        pageVolumeStateStore.assertConsistent();
    }

    @Override
    public synchronized DelosVacuumOutcome vacuumSafely() {
        boolean hasRetainedInheritedSnapshot = transactions.activeTransactionCount() > 0
                || transactions.retainedSnapshotCount() > 0;
        lastVacuumOutcome = vacuumOutcome(pageVolumeStateStore.vacuumSafely(hasRetainedInheritedSnapshot));
        return lastVacuumOutcome;
    }

    @Override
    public synchronized DelosVacuumOutcome lastVacuumOutcomeForTesting() {
        return lastVacuumOutcome;
    }

    @Override
    public Path legacySnapshotFileForTesting() {
        return retiredSnapshotFile;
    }

    @Override
    public synchronized void close() {
        pageVolumeStateStore.close();
    }

    private void loadCommittedState() {
        if (pageVolumeStateStore.hasDurableState()) {
            hydrateCommittedRows(
                    pageVolumeStateStore.loadVisibleRows(),
                    pageVolumeStateStore.nextInheritedRowId());
        }
    }

    private void hydrateCommittedRows(
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows,
            long storedNextRowId) {
        candidateIndex.rebuildFromVisibleRows(toCandidateRows(rows));
        if (rows.isEmpty()) {
            nextRowId = Math.max(nextRowId, storedNextRowId);
            return;
        }
        MvccTransaction hydrator = transactions.begin();
        try {
            long maxRowId = 0L;
            for (PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row : rows) {
                table.insert(row.rowId(), cloneRowUnchecked(row.values()), hydrator);
                maxRowId = Math.max(maxRowId, row.rowId());
            }
            transactions.commit(hydrator);
            nextRowId = Math.max(storedNextRowId, maxRowId + 1L);
        } catch (RuntimeException failure) {
            transactions.abort(hydrator);
            throw failure;
        }
    }

    private List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> visibleRows() {
        MvccTransaction reader = transactions.begin();
        try {
            MvccSnapshot snapshot = transactions.snapshot(reader);
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows = new ArrayList<>();
            try (MvccScan<Long, StoreDataValue[]> scan = table.openScan(snapshot, transactions)) {
                while (scan.next()) {
                    MvccRow<Long, StoreDataValue[]> row = scan.row();
                    rows.add(new PageVolumeMvccStateStore.PersistedRow<>(
                            row.key(),
                            cloneRow(row.value())));
                }
            }
            return List.copyOf(rows);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not clone inherited MVCC row for persistence", e);
        } finally {
            transactions.abort(reader);
        }
    }

    private static MvccInheritedHandles.Transaction nativeTransactionHandle(DelosStorageTransaction transaction) {
        return MvccInheritedHandles.transaction(transaction);
    }

    private static MvccTransaction nativeTransaction(DelosStorageTransaction transaction) {
        return nativeTransactionHandle(transaction).nativeTransaction();
    }

    private static MvccSnapshot nativeSnapshot(DelosStorageSnapshot snapshot) {
        return MvccInheritedHandles.snapshot(snapshot).nativeSnapshot();
    }

    private static List<MvccCandidateIndex.CandidateRow> toCandidateRows(
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        if (rows == null) {
            return List.of();
        }
        List<MvccCandidateIndex.CandidateRow> candidates = new ArrayList<>(rows.size());
        for (PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row : rows) {
            candidates.add(new MvccCandidateIndex.CandidateRow(row.rowId(), valueKeys(row.values())));
        }
        return List.copyOf(candidates);
    }

    private static List<String> valueKeys(StoreDataValue[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(values.length);
        for (StoreDataValue value : values) {
            keys.add(value == null ? null : valueKey(value));
        }
        return List.copyOf(keys);
    }

    private static String valueKey(StoreDataValue value) {
        try {
            Method getString = value.getClass().getMethod("getString");
            Object result = getString.invoke(value);
            return result == null ? "<null>" : result.toString();
        } catch (NoSuchMethodException e) {
            return value.toString();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access store value key operation on "
                    + value.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return value.toString();
        }
    }

    private static StoreDataValue[] cloneRowUnchecked(StoreDataValue[] row) {
        try {
            return cloneRow(row);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not clone inherited MVCC row", e);
        }
    }

    private static StoreDataValue[] cloneRow(StoreDataValue[] row) throws StandardException {
        if (row == null) {
            return new StoreDataValue[0];
        }
        StoreDataValue[] copy = new StoreDataValue[row.length];
        for (int i = 0; i < row.length; i++) {
            copy[i] = cloneValue(row[i]);
        }
        return copy;
    }

    private static StoreDataValue cloneValue(StoreDataValue value) throws StandardException {
        if (value == null) {
            return null;
        }
        if (value instanceof StoreValueOperations operations) {
            return operations.cloneValue(false);
        }
        StoreDataValue reflected = cloneSqlValueReflectively(value);
        if (reflected != null) {
            return reflected;
        }
        throw new IllegalArgumentException("MVCC storage provider requires cloneable StoreDataValue: "
                + value.getClass().getName());
    }

    private static StoreDataValue cloneSqlValueReflectively(StoreDataValue value) throws StandardException {
        try {
            Method cloneValue = value.getClass().getMethod("cloneValue", boolean.class);
            Object cloned = cloneValue.invoke(value, false);
            if (cloned instanceof StoreDataValue storeDataValue) {
                return storeDataValue;
            }
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access SQL value clone operation on "
                    + value.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StandardException standardException) {
                throw standardException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static DelosVacuumOutcome vacuumOutcome(PageVolumeMvccStateStore.VacuumOutcome outcome) {
        return new DelosVacuumOutcome(
                outcome.skipped(),
                outcome.reason(),
                outcome.removedVersions(),
                outcome.remainingVersions());
    }

    private static String storageId(long segmentId, long containerId) {
        return PageVolumeMvccPaths.conglomerateStorageId(segmentId, containerId);
    }

    private static Path retiredSnapshotFile(Path databaseDirectory, long segmentId, long containerId) {
        Path directory = PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + segmentId + "-" + containerId + ".snapshot");
    }

    private static Path transactionStatusFile(Path databaseDirectory, long segmentId, long containerId) {
        Path directory = PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + segmentId + "-" + containerId + ".txstatus");
    }
}
