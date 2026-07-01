package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

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
    private final ReentrantReadWriteLock tableLock = new ReentrantReadWriteLock();
    private final Lock readLock = tableLock.readLock();
    private final Lock writeLock = tableLock.writeLock();
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
    public DelosStorageTransaction beginTransaction() {
        return writeLocked(() -> new MvccInheritedHandles.Transaction(transactions.begin()));
    }

    @Override
    public DelosStorageSnapshot snapshot(DelosStorageTransaction transaction) {
        return readLocked(() -> new MvccInheritedHandles.Snapshot(transactions.snapshot(nativeTransaction(transaction))));
    }

    @Override
    public DelosStorageScan openScan(DelosStorageSnapshot snapshot) {
        return readLocked(() -> new MvccInheritedScan(table.openScan(nativeSnapshot(snapshot), transactions)));
    }

    @Override
    public Optional<StoreDataValue[]> read(long rowId, DelosStorageSnapshot snapshot) {
        return readLocked(() -> table.read(rowId, nativeSnapshot(snapshot), transactions));
    }

    @Override
    public void insert(long rowId, StoreDataValue[] row, DelosStorageTransaction transaction) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            table.insert(rowId, cloneRowUnchecked(row), handle.nativeTransaction(), handle.nextCommandSequence());
        });
    }

    @Override
    public void update(
            long rowId,
            StoreDataValue[] replacement,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            table.update(
                    rowId,
                    cloneRowUnchecked(replacement),
                    handle.nativeTransaction(),
                    nativeSnapshot(snapshot),
                    transactions,
                    handle.nextCommandSequence());
        });
    }

    @Override
    public void delete(
            long rowId,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            table.delete(
                    rowId,
                    handle.nativeTransaction(),
                    nativeSnapshot(snapshot),
                    transactions,
                    handle.nextCommandSequence());
        });
    }

    @Override
    public void commit(DelosStorageTransaction transaction) {
        writeLocked(() -> {
            MvccTransaction nativeTx = nativeTransaction(transaction);
            try {
                pageVolumeStateStore.requireVisibleRowsCanBePersisted(visibleRows(nativeTx));
            } catch (RuntimeException failure) {
                abortIfActive(nativeTx, failure);
                throw failure;
            }
            transactions.commit(nativeTx);
        });
    }

    @Override
    public void abort(DelosStorageTransaction transaction) {
        writeLocked(() -> transactions.abort(nativeTransaction(transaction)));
    }

    @Override
    public void setSavepoint(DelosStorageTransaction transaction, String savepointName) {
        writeLocked(() -> nativeTransactionHandle(transaction).setSavepoint(savepointName));
    }

    @Override
    public void rollbackToSavepoint(DelosStorageTransaction transaction, String savepointName) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            MvccCommandSequence boundary = handle.rollbackToSavepoint(savepointName);
            table.rollbackTransactionChangesAfter(handle.nativeTransaction(), boundary);
        });
    }

    @Override
    public void releaseSavepoint(DelosStorageTransaction transaction, String savepointName) {
        writeLocked(() -> nativeTransactionHandle(transaction).releaseSavepoint(savepointName));
    }

    @Override
    public long nextRowId() {
        return writeLocked(() -> nextRowId++);
    }

    @Override
    public void persistCommittedState() {
        writeLocked(() -> {
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows = visibleRows();
            pageVolumeStateStore.persistVisibleRows(rows);
            candidateIndex.rebuildFromVisibleRows(toCandidateRows(rows));
        });
    }

    @Override
    public void dropDurableState() {
        writeLocked(() -> {
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
        });
    }

    @Override
    public DelosStorageRowHead rowHeadFor(long rowId) {
        return readLocked(() -> pageVolumeStateStore.rowHeadForInheritedRowId(rowId)
                .map(head -> DelosStorageRowHead.present(
                        rowId,
                        head.headLocator().pageId().value(),
                        head.headLocator().slotId()))
                .orElseGet(() -> DelosStorageRowHead.absent(rowId)));
    }

    @Override
    public Optional<List<Long>> candidateRowIdsFor(int column, String value) {
        return readLocked(() -> candidateIndex.candidatesFor(column, value));
    }

    @Override
    public int candidateIndexKeyCountForTesting() {
        return readLocked(candidateIndex::indexedKeyCountForTesting);
    }

    @Override
    public Path pageVolumeStateFileForTesting() {
        return readLocked(pageVolumeStateStore::pageFile);
    }

    @Override
    public Path rowDirectoryStateFileForTesting() {
        return readLocked(pageVolumeStateStore::rowDirectoryFile);
    }

    @Override
    public Path reusablePageIndexFileForTesting() {
        return readLocked(pageVolumeStateStore::reusablePageIndexFile);
    }

    @Override
    public Path pageMutationLogFileForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationLogFile);
    }

    @Override
    public Path writeAheadLogFileForTesting() {
        return readLocked(pageVolumeStateStore::writeAheadLogFile);
    }

    @Override
    public Path checkpointFileForTesting() {
        return readLocked(pageVolumeStateStore::checkpointFile);
    }

    @Override
    public String checkpointStatusForTesting() {
        return readLocked(pageVolumeStateStore::checkpointStatus);
    }

    @Override
    public int physicalVersionCountForTesting() {
        return readLocked(pageVolumeStateStore::physicalVersionCount);
    }

    @Override
    public int logicalRowCountForTesting() {
        return readLocked(pageVolumeStateStore::logicalRowCount);
    }

    @Override
    public List<String> pageBackedVisibleRowSummariesForTesting() {
        return readLocked(() -> pageVolumeStateStore.loadVisibleRows().stream()
                .map(row -> row.rowId() + "|" + String.join("|", valueKeys(row.values())))
                .sorted()
                .toList());
    }

    @Override
    public long pageCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCount);
    }

    @Override
    public long overflowPageCountForTesting() {
        return readLocked(pageVolumeStateStore::overflowPageCount);
    }

    @Override
    public long reusablePageCountForTesting() {
        return readLocked(pageVolumeStateStore::reusablePageCount);
    }

    @Override
    public long pageCacheMaxPageCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheMaxPageCount);
    }

    @Override
    public long pageCacheSizeForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheSize);
    }

    @Override
    public long pageCacheHitCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheHitCount);
    }

    @Override
    public long pageCacheMissCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheMissCount);
    }

    @Override
    public long pageCacheWriteCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheWriteCount);
    }

    @Override
    public long pageCacheEvictionCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheEvictionCount);
    }

    @Override
    public long pageCacheInvalidationCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheInvalidationCount);
    }

    @Override
    public int consistencyErrorCountForTesting() {
        return readLocked(pageVolumeStateStore::consistencyErrorCount);
    }

    @Override
    public String consistencySummaryForTesting() {
        return readLocked(pageVolumeStateStore::consistencySummary);
    }

    @Override
    public void assertConsistentForTesting() {
        readLocked(pageVolumeStateStore::assertConsistent);
    }

    @Override
    public DelosVacuumOutcome vacuumSafely() {
        return writeLocked(() -> {
            boolean hasRetainedInheritedSnapshot = transactions.activeTransactionCount() > 0
                    || transactions.retainedSnapshotCount() > 0;
            lastVacuumOutcome = vacuumOutcome(pageVolumeStateStore.vacuumSafely(hasRetainedInheritedSnapshot));
            return lastVacuumOutcome;
        });
    }

    @Override
    public DelosVacuumOutcome lastVacuumOutcomeForTesting() {
        return readLocked(() -> lastVacuumOutcome);
    }

    @Override
    public Path legacySnapshotFileForTesting() {
        return readLocked(() -> retiredSnapshotFile);
    }

    @Override
    public void close() {
        writeLocked(pageVolumeStateStore::close);
    }

    private <T> T readLocked(Supplier<T> operation) {
        readLock.lock();
        try {
            return operation.get();
        } finally {
            readLock.unlock();
        }
    }

    private void readLocked(Runnable operation) {
        readLock.lock();
        try {
            operation.run();
        } finally {
            readLock.unlock();
        }
    }

    private <T> T writeLocked(Supplier<T> operation) {
        writeLock.lock();
        try {
            return operation.get();
        } finally {
            writeLock.unlock();
        }
    }

    private void writeLocked(Runnable operation) {
        writeLock.lock();
        try {
            operation.run();
        } finally {
            writeLock.unlock();
        }
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
            return visibleRows(reader);
        } finally {
            transactions.abort(reader);
        }
    }

    private List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> visibleRows(MvccTransaction transaction) {
        try {
            MvccSnapshot snapshot = transactions.snapshot(transaction);
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
        }
    }

    private void abortIfActive(MvccTransaction transaction, RuntimeException failure) {
        try {
            transactions.abort(transaction);
        } catch (RuntimeException abortFailure) {
            failure.addSuppressed(abortFailure);
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
        if (value instanceof StoreValueOperations operations) {
            try {
                return valueKeyString(operations.getString());
            } catch (StandardException e) {
                throw new IllegalStateException("Cannot derive store value key from "
                        + value.getClass().getName(), e);
            }
        }
        Optional<Method> getString = publicNoArgMethod(value.getClass(), "getString");
        if (getString.isEmpty()) {
            return value.toString();
        }
        try {
            Object result = getString.get().invoke(value);
            return valueKeyString(result);
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

    private static String valueKeyString(Object value) {
        return value == null ? "<null>" : value.toString();
    }

    private static Optional<Method> publicNoArgMethod(Class<?> type, String name) {
        if (type == null) {
            return Optional.empty();
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            Optional<Method> method = publicNoArgMethod(interfaceType, name);
            if (method.isPresent()) {
                return method;
            }
        }
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                Method method = type.getMethod(name);
                if (method.getParameterCount() == 0
                        && Modifier.isPublic(method.getModifiers())
                        && Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                    return Optional.of(method);
                }
            } catch (NoSuchMethodException e) {
                // Keep searching public super types below.
            }
        }
        return publicNoArgMethod(type.getSuperclass(), name);
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
