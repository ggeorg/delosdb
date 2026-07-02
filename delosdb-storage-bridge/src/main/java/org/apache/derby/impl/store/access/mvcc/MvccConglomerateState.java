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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.DelosStorageCandidateIndex;
import org.apache.derby.iapi.store.types.DelosStorageCommittedRead;
import org.apache.derby.iapi.store.types.DelosStorageMaintenance;
import org.apache.derby.iapi.store.types.DelosStorageProviderFactory;
import org.apache.derby.iapi.store.types.DelosStorageRow;
import org.apache.derby.iapi.store.types.DelosStorageRowHead;
import org.apache.derby.iapi.store.types.DelosStorageRowLocator;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosVacuumOutcome;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Shared state behind the inherited MVCC conglomerate provider.
 *
 * <p>MODULE17M keeps Derby access-method compatibility here, but routes the
 * actual MVCC storage operations through {@code delosdb-storage-api}.  The
 * bridge no longer imports native MVCC implementation classes.</p>
 */
final class MvccConglomerateState {
    private static final String MVCC_PROVIDER_NAME = "delos_mvcc";

    private final ContainerKey key;
    private final DelosStorageTable table;
    private final DelosStorageMaintenance maintenance;
    private final DelosStorageRowLocator rowLocator;
    private final DelosStorageCandidateIndex candidateIndex;
    private final DelosStorageCommittedRead committedRead;
    private final DelosStorageTableDiagnostics diagnostics;

    MvccConglomerateState(ContainerKey key, Path databaseDirectory) {
        this.key = key;
        DelosStorageStore store = providerFactory().openStore(databaseDirectory);
        this.table = store.openTable(new DelosStorageTableKey(key.getSegmentId(), key.getContainerId()));
        this.maintenance = requireCapability(table, DelosStorageMaintenance.class);
        this.rowLocator = requireCapability(table, DelosStorageRowLocator.class);
        this.candidateIndex = requireCapability(table, DelosStorageCandidateIndex.class);
        this.committedRead = requireCapability(table, DelosStorageCommittedRead.class);
        this.diagnostics = requireCapability(table, DelosStorageTableDiagnostics.class);
    }

    ContainerKey key() {
        return key;
    }

    DelosStorageTable table() {
        return table;
    }

    DelosStorageTransaction beginTransaction() {
        return table.beginTransaction();
    }

    DelosStorageSnapshot snapshot(DelosStorageTransaction transaction) {
        return table.snapshot(transaction);
    }

    DelosStorageScan openScan(DelosStorageSnapshot snapshot) throws StandardException {
        return table.openScan(snapshot);
    }

    boolean canReadCommittedImage(DelosStorageSnapshot snapshot) {
        return committedRead.canReadCommittedImage(snapshot);
    }

    DelosStorageScan openCommittedImageScan(DelosStorageSnapshot snapshot) throws StandardException {
        return committedRead.openCommittedImageScan(snapshot);
    }

    Optional<StoreDataValue[]> readCommittedImage(long rowId, DelosStorageSnapshot snapshot) {
        return committedRead.readCommittedImage(rowId, snapshot);
    }

    Optional<StoreDataValue[]> read(long rowId, DelosStorageSnapshot snapshot) {
        return table.read(rowId, snapshot);
    }

    void insert(long rowId, StoreDataValue[] row, DelosStorageTransaction transaction) {
        table.insert(rowId, row, transaction);
    }

    void update(
            long rowId,
            StoreDataValue[] replacement,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        table.update(rowId, replacement, transaction, snapshot);
    }

    void delete(long rowId, DelosStorageTransaction transaction, DelosStorageSnapshot snapshot) {
        table.delete(rowId, transaction, snapshot);
    }

    void commit(DelosStorageTransaction transaction) {
        table.commit(transaction);
    }

    void abort(DelosStorageTransaction transaction) {
        table.abort(transaction);
    }

    synchronized long nextRowId() {
        return table.nextRowId();
    }

    synchronized void dropDurableState() {
        maintenance.dropDurableState();
    }

    Path pageVolumeStateFileForTesting() {
        return diagnostics.pageVolumeStateFileForTesting();
    }

    Path rowDirectoryStateFileForTesting() {
        return diagnostics.rowDirectoryStateFileForTesting();
    }

    Path reusablePageIndexFileForTesting() {
        return diagnostics.reusablePageIndexFileForTesting();
    }

    Path pageMutationLogFileForTesting() {
        return diagnostics.pageMutationLogFileForTesting();
    }

    Path writeAheadLogFileForTesting() {
        return diagnostics.writeAheadLogFileForTesting();
    }

    Path checkpointFileForTesting() {
        return diagnostics.checkpointFileForTesting();
    }

    String checkpointStatusForTesting() {
        return diagnostics.checkpointStatusForTesting();
    }

    synchronized int physicalVersionCountForTesting() {
        return diagnostics.physicalVersionCountForTesting();
    }

    synchronized int logicalRowCountForTesting() {
        return diagnostics.logicalRowCountForTesting();
    }

    synchronized List<String> pageBackedVisibleRowSummariesForTesting() {
        return diagnostics.pageBackedVisibleRowSummariesForTesting();
    }

    synchronized int lastCommittedChangedRowCountForTesting() {
        return diagnostics.lastCommittedChangedRowCountForTesting();
    }

    synchronized int lastCommittedWriteIntentCountForTesting() {
        return diagnostics.lastCommittedWriteIntentCountForTesting();
    }

    synchronized List<String> lastCommittedWriteIntentPayloadSummariesForTesting() {
        return diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting();
    }

    synchronized int transactionLocalWriteIntentReadCountForTesting() {
        return diagnostics.transactionLocalWriteIntentReadCountForTesting();
    }

    synchronized int transactionLocalWriteIntentScanCountForTesting() {
        return diagnostics.transactionLocalWriteIntentScanCountForTesting();
    }

    synchronized int transactionLocalPageBackedBaseReadCountForTesting() {
        return diagnostics.transactionLocalPageBackedBaseReadCountForTesting();
    }

    synchronized int transactionLocalPageBackedBaseScanCountForTesting() {
        return diagnostics.transactionLocalPageBackedBaseScanCountForTesting();
    }

    synchronized int pageBackedHistoricalSnapshotReadCountForTesting() {
        return diagnostics.pageBackedHistoricalSnapshotReadCountForTesting();
    }

    synchronized int pageBackedHistoricalSnapshotScanCountForTesting() {
        return diagnostics.pageBackedHistoricalSnapshotScanCountForTesting();
    }

    synchronized int legacySnapshotFallbackReadCountForTesting() {
        return diagnostics.legacySnapshotFallbackReadCountForTesting();
    }

    synchronized int legacySnapshotFallbackScanCountForTesting() {
        return diagnostics.legacySnapshotFallbackScanCountForTesting();
    }

    synchronized long pageCountForTesting() {
        return diagnostics.pageCountForTesting();
    }

    synchronized long overflowPageCountForTesting() {
        return diagnostics.overflowPageCountForTesting();
    }

    synchronized long reusablePageCountForTesting() {
        return diagnostics.reusablePageCountForTesting();
    }

    synchronized long pageCacheMaxPageCountForTesting() {
        return diagnostics.pageCacheMaxPageCountForTesting();
    }

    synchronized long pageCacheSizeForTesting() {
        return diagnostics.pageCacheSizeForTesting();
    }

    synchronized long pageCacheHitCountForTesting() {
        return diagnostics.pageCacheHitCountForTesting();
    }

    synchronized long pageCacheMissCountForTesting() {
        return diagnostics.pageCacheMissCountForTesting();
    }

    synchronized long pageCacheWriteCountForTesting() {
        return diagnostics.pageCacheWriteCountForTesting();
    }

    synchronized long pageCacheEvictionCountForTesting() {
        return diagnostics.pageCacheEvictionCountForTesting();
    }

    synchronized long pageCacheInvalidationCountForTesting() {
        return diagnostics.pageCacheInvalidationCountForTesting();
    }

    synchronized int consistencyErrorCountForTesting() {
        return diagnostics.consistencyErrorCountForTesting();
    }

    synchronized String consistencySummaryForTesting() {
        return diagnostics.consistencySummaryForTesting();
    }

    synchronized void assertConsistentForTesting() {
        diagnostics.assertConsistentForTesting();
    }

    synchronized DelosVacuumOutcome lastVacuumOutcomeForTesting() {
        return diagnostics.lastVacuumOutcomeForTesting();
    }

    synchronized DelosVacuumOutcome vacuumSafely() {
        return maintenance.vacuumSafely();
    }

    Path legacySnapshotFileForTesting() {
        return diagnostics.legacySnapshotFileForTesting();
    }

    synchronized MvccRowLocation rowLocationFor(long rowId) {
        DelosStorageRowHead head = rowLocator.rowHeadFor(rowId);
        if (head.present()) {
            return new MvccRowLocation(rowId, head.pageId(), head.slotId());
        }
        return new MvccRowLocation(rowId);
    }

    synchronized Optional<List<Long>> candidateRowIdsFor(Qualifier[][] qualifiers) {
        Optional<ColumnValueKey> key = equalityCandidateKey(qualifiers);
        return key.flatMap(columnValueKey -> candidateIndex.candidateRowIdsFor(
                columnValueKey.column(), columnValueKey.value()));
    }

    synchronized int candidateIndexKeyCountForTesting() {
        return candidateIndex.candidateIndexKeyCountForTesting();
    }

    synchronized void close() {
        table.close();
    }

    private static Optional<ColumnValueKey> equalityCandidateKey(Qualifier[][] qualifiers) {
        if (qualifiers == null) {
            return Optional.empty();
        }
        for (Qualifier[] andTerm : qualifiers) {
            if (andTerm == null || andTerm.length != 1 || andTerm[0] == null) {
                continue;
            }
            Qualifier qualifier = andTerm[0];
            if (qualifier.getColumnId() < 0
                    || qualifier.getOperator() != StoreOrderable.ORDER_OP_EQUALS
                    || qualifier.negateCompareResult()) {
                continue;
            }
            try {
                StoreDataValue orderable = qualifier.getOrderable();
                if (orderable == null) {
                    return Optional.empty();
                }
                return Optional.of(new ColumnValueKey(qualifier.getColumnId(), valueKey(orderable)));
            } catch (StandardException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
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

    private static <T> T requireCapability(DelosStorageTable table, Class<T> capability) {
        if (capability.isInstance(table)) {
            return capability.cast(table);
        }
        throw new IllegalStateException("Storage table " + table.getClass().getName()
                + " does not implement required capability " + capability.getName());
    }

    private static DelosStorageProviderFactory providerFactory() {
        for (DelosStorageProviderFactory factory : ServiceLoader.load(DelosStorageProviderFactory.class)) {
            if (MVCC_PROVIDER_NAME.equals(factory.providerName())) {
                return factory;
            }
        }
        throw new IllegalStateException("No storage-api provider registered for " + MVCC_PROVIDER_NAME);
    }

    private record ColumnValueKey(int column, String value) {
    }
}
