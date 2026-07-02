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

    DelosStorageSnapshot snapshot(
            DelosStorageTransaction transaction,
            DelosStorageSnapshot visibilitySnapshot) {
        return table.snapshot(transaction, visibilitySnapshot);
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

    Path freeSpaceMapFileForTesting() {
        return diagnostics.freeSpaceMapFileForTesting();
    }

    Path visibilityMapFileForTesting() {
        return diagnostics.visibilityMapFileForTesting();
    }

    Path purgeQueueFileForTesting() {
        return diagnostics.purgeQueueFileForTesting();
    }

    Path orderedIndexPagesFileForTesting() {
        return diagnostics.orderedIndexPagesFileForTesting();
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

    synchronized int activeProviderWriteAppendCountForTesting() {
        return diagnostics.activeProviderWriteAppendCountForTesting();
    }

    synchronized List<String> activeProviderWriteAppendPayloadSummariesForTesting() {
        return diagnostics.activeProviderWriteAppendPayloadSummariesForTesting();
    }

    synchronized int activeProviderSurvivingWriteIntentCountForTesting() {
        return diagnostics.activeProviderSurvivingWriteIntentCountForTesting();
    }

    synchronized List<String> activeProviderSurvivingWriteIntentPayloadSummariesForTesting() {
        return diagnostics.activeProviderSurvivingWriteIntentPayloadSummariesForTesting();
    }

    synchronized int providerFirstWriteAppendCountForTesting() {
        return diagnostics.providerFirstWriteAppendCountForTesting();
    }

    synchronized int legacyWriteFrontShadowMutationCountForTesting() {
        return diagnostics.legacyWriteFrontShadowMutationCountForTesting();
    }

    synchronized int legacyWriteFrontShadowBypassCountForTesting() {
        return diagnostics.legacyWriteFrontShadowBypassCountForTesting();
    }

    synchronized boolean legacyWriteFrontShadowEnabledForTesting() {
        return diagnostics.legacyWriteFrontShadowEnabledForTesting();
    }

    synchronized int legacyWriteFrontQuarantineViolationCountForTesting() {
        return diagnostics.legacyWriteFrontQuarantineViolationCountForTesting();
    }

    synchronized int providerFirstWriteAppendFailureRollbackCountForTesting() {
        return diagnostics.providerFirstWriteAppendFailureRollbackCountForTesting();
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

    synchronized int pageBackedCandidateIndexRebuildCountForTesting() {
        return diagnostics.pageBackedCandidateIndexRebuildCountForTesting();
    }

    synchronized int legacyCandidateIndexRebuildCountForTesting() {
        return diagnostics.legacyCandidateIndexRebuildCountForTesting();
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

    synchronized long freeSpaceMapPageCountForTesting() {
        return diagnostics.freeSpaceMapPageCountForTesting();
    }

    synchronized int freeSpaceMapMaxFreeBytesForTesting() {
        return diagnostics.freeSpaceMapMaxFreeBytesForTesting();
    }

    synchronized long freeSpaceMapLookupCountForTesting() {
        return diagnostics.freeSpaceMapLookupCountForTesting();
    }

    synchronized long freeSpaceMapHitCountForTesting() {
        return diagnostics.freeSpaceMapHitCountForTesting();
    }

    synchronized long freeSpaceMapNonLastHitCountForTesting() {
        return diagnostics.freeSpaceMapNonLastHitCountForTesting();
    }

    synchronized long freeSpaceMapMissCountForTesting() {
        return diagnostics.freeSpaceMapMissCountForTesting();
    }

    synchronized long freeSpaceMapStaleEntryCountForTesting() {
        return diagnostics.freeSpaceMapStaleEntryCountForTesting();
    }

    synchronized long freeSpaceMapUpdateCountForTesting() {
        return diagnostics.freeSpaceMapUpdateCountForTesting();
    }

    synchronized long freeSpaceMapRebuildCountForTesting() {
        return diagnostics.freeSpaceMapRebuildCountForTesting();
    }

    synchronized List<String> freeSpaceMapPageSummariesForTesting() {
        return diagnostics.freeSpaceMapPageSummariesForTesting();
    }

    synchronized long visibilityMapPageCountForTesting() {
        return diagnostics.visibilityMapPageCountForTesting();
    }

    synchronized long visibilityMapOldVersionPageCountForTesting() {
        return diagnostics.visibilityMapOldVersionPageCountForTesting();
    }

    synchronized long visibilityMapPrunablePageCountForTesting() {
        return diagnostics.visibilityMapPrunablePageCountForTesting();
    }

    synchronized long visibilityMapTombstonePageCountForTesting() {
        return diagnostics.visibilityMapTombstonePageCountForTesting();
    }

    synchronized long visibilityMapAllVisiblePageCountForTesting() {
        return diagnostics.visibilityMapAllVisiblePageCountForTesting();
    }

    synchronized long visibilityMapOverflowPageCountForTesting() {
        return diagnostics.visibilityMapOverflowPageCountForTesting();
    }

    synchronized long visibilityMapNeedsCheckerPageCountForTesting() {
        return diagnostics.visibilityMapNeedsCheckerPageCountForTesting();
    }

    synchronized long visibilityMapUpdateCountForTesting() {
        return diagnostics.visibilityMapUpdateCountForTesting();
    }

    synchronized long visibilityMapRebuildCountForTesting() {
        return diagnostics.visibilityMapRebuildCountForTesting();
    }

    synchronized List<String> visibilityMapPageSummariesForTesting() {
        return diagnostics.visibilityMapPageSummariesForTesting();
    }

    synchronized long pageLocalPruneAttemptCountForTesting() {
        return diagnostics.pageLocalPruneAttemptCountForTesting();
    }

    synchronized long pageLocalPruneSuccessCountForTesting() {
        return diagnostics.pageLocalPruneSuccessCountForTesting();
    }

    synchronized long pageLocalPruneFallbackCountForTesting() {
        return diagnostics.pageLocalPruneFallbackCountForTesting();
    }

    synchronized long pageLocalPruneRemovedVersionCountForTesting() {
        return diagnostics.pageLocalPruneRemovedVersionCountForTesting();
    }

    synchronized long pageMutationContextBeginCountForTesting() {
        return diagnostics.pageMutationContextBeginCountForTesting();
    }

    synchronized long pageMutationContextCommitCountForTesting() {
        return diagnostics.pageMutationContextCommitCountForTesting();
    }

    synchronized long pageMutationContextAbortCountForTesting() {
        return diagnostics.pageMutationContextAbortCountForTesting();
    }

    synchronized long pageMutationContextPageReservationCountForTesting() {
        return diagnostics.pageMutationContextPageReservationCountForTesting();
    }

    synchronized long pageMutationContextReservedBytesForTesting() {
        return diagnostics.pageMutationContextReservedBytesForTesting();
    }

    synchronized long pageMutationContextPageWriteCountForTesting() {
        return diagnostics.pageMutationContextPageWriteCountForTesting();
    }

    synchronized long pageMutationContextFreeSpaceMapUpdateCountForTesting() {
        return diagnostics.pageMutationContextFreeSpaceMapUpdateCountForTesting();
    }

    synchronized long pageMutationContextReusableIndexUpdateCountForTesting() {
        return diagnostics.pageMutationContextReusableIndexUpdateCountForTesting();
    }

    synchronized String lastPageMutationContextOperationForTesting() {
        return diagnostics.lastPageMutationContextOperationForTesting();
    }

    synchronized long purgeQueuePendingCountForTesting() {
        return diagnostics.purgeQueuePendingCountForTesting();
    }

    synchronized long purgeQueueEnqueueCountForTesting() {
        return diagnostics.purgeQueueEnqueueCountForTesting();
    }

    synchronized long purgeQueueDrainCountForTesting() {
        return diagnostics.purgeQueueDrainCountForTesting();
    }

    synchronized long purgeQueueLastDrainCountForTesting() {
        return diagnostics.purgeQueueLastDrainCountForTesting();
    }

    synchronized List<String> purgeQueueEntrySummariesForTesting() {
        return diagnostics.purgeQueueEntrySummariesForTesting();
    }

    synchronized long orderedIndexPageCountForTesting() {
        return diagnostics.orderedIndexPageCountForTesting();
    }

    synchronized long orderedIndexEntryCountForTesting() {
        return diagnostics.orderedIndexEntryCountForTesting();
    }

    synchronized int orderedIndexDistinctKeyCountForTesting() {
        return diagnostics.orderedIndexDistinctKeyCountForTesting();
    }

    synchronized long orderedIndexRebuildCountForTesting() {
        return diagnostics.orderedIndexRebuildCountForTesting();
    }

    synchronized List<String> orderedIndexEntrySummariesForTesting() {
        return diagnostics.orderedIndexEntrySummariesForTesting();
    }

    synchronized long orderedIndexLookupCountForTesting() {
        return diagnostics.orderedIndexLookupCountForTesting();
    }

    synchronized long orderedIndexHitCountForTesting() {
        return diagnostics.orderedIndexHitCountForTesting();
    }

    synchronized long orderedIndexFallbackCountForTesting() {
        return diagnostics.orderedIndexFallbackCountForTesting();
    }

    synchronized long orderedIndexRowIdCountForTesting() {
        return diagnostics.orderedIndexRowIdCountForTesting();
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
        if (key.isPresent()) {
            ColumnValueKey columnValueKey = key.get();
            Optional<List<Long>> ordered = candidateIndex.orderedIndexCandidateRowIdsFor(
                    columnValueKey.column(), columnValueKey.value());
            if (ordered.isPresent()) {
                return ordered;
            }
            return candidateIndex.candidateRowIdsFor(columnValueKey.column(), columnValueKey.value());
        }

        Optional<ColumnRangeKey> range = rangeCandidateKey(qualifiers);
        if (range.isEmpty()) {
            return Optional.empty();
        }
        ColumnRangeKey columnRangeKey = range.get();
        return candidateIndex.orderedIndexCandidateRowIdsInRangeFor(
                columnRangeKey.column(),
                columnRangeKey.lowerValue(),
                columnRangeKey.lowerInclusive(),
                columnRangeKey.upperValue(),
                columnRangeKey.upperInclusive());
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

    private static Optional<ColumnRangeKey> rangeCandidateKey(Qualifier[][] qualifiers) {
        if (qualifiers == null || qualifiers.length == 0) {
            return Optional.empty();
        }
        int column = -1;
        String lowerValue = null;
        boolean lowerInclusive = true;
        String upperValue = null;
        boolean upperInclusive = true;
        boolean sawRangeBound = false;

        for (int andTermIndex = 0; andTermIndex < qualifiers.length; andTermIndex++) {
            Qualifier[] andTerm = qualifiers[andTermIndex];
            if (andTerm == null || andTerm.length == 0) {
                return Optional.empty();
            }
            if (andTermIndex > 0 && andTerm.length != 1) {
                // Additional Derby qualifier groups are OR terms. A group with
                // more than one alternative is not a simple single-column range,
                // so keep the existing full/candidate fallback path.
                return Optional.empty();
            }
            for (Qualifier qualifier : andTerm) {
                if (qualifier == null || qualifier.getColumnId() < 0 || qualifier.negateCompareResult()) {
                    return Optional.empty();
                }
                if (column == -1) {
                    column = qualifier.getColumnId();
                } else if (column != qualifier.getColumnId()) {
                    return Optional.empty();
                }
                String value;
                try {
                    StoreDataValue orderable = qualifier.getOrderable();
                    if (orderable == null) {
                        return Optional.empty();
                    }
                    value = valueKey(orderable);
                } catch (StandardException e) {
                    return Optional.empty();
                }
                switch (qualifier.getOperator()) {
                    case StoreOrderable.ORDER_OP_GREATERTHAN -> {
                        BoundChoice choice = chooseLowerBound(lowerValue, lowerInclusive, value, false);
                        lowerValue = choice.value();
                        lowerInclusive = choice.inclusive();
                        sawRangeBound = true;
                    }
                    case StoreOrderable.ORDER_OP_GREATEROREQUALS -> {
                        BoundChoice choice = chooseLowerBound(lowerValue, lowerInclusive, value, true);
                        lowerValue = choice.value();
                        lowerInclusive = choice.inclusive();
                        sawRangeBound = true;
                    }
                    case StoreOrderable.ORDER_OP_LESSTHAN -> {
                        BoundChoice choice = chooseUpperBound(upperValue, upperInclusive, value, false);
                        upperValue = choice.value();
                        upperInclusive = choice.inclusive();
                        sawRangeBound = true;
                    }
                    case StoreOrderable.ORDER_OP_LESSOREQUALS -> {
                        BoundChoice choice = chooseUpperBound(upperValue, upperInclusive, value, true);
                        upperValue = choice.value();
                        upperInclusive = choice.inclusive();
                        sawRangeBound = true;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }
        }

        if (!sawRangeBound || column < 0) {
            return Optional.empty();
        }
        return Optional.of(new ColumnRangeKey(
                column, lowerValue, lowerInclusive, upperValue, upperInclusive));
    }

    private static BoundChoice chooseLowerBound(
            String currentValue, boolean currentInclusive, String candidateValue, boolean candidateInclusive) {
        if (currentValue == null) {
            return new BoundChoice(candidateValue, candidateInclusive);
        }
        int comparison = candidateValue.compareTo(currentValue);
        if (comparison > 0 || (comparison == 0 && currentInclusive && !candidateInclusive)) {
            return new BoundChoice(candidateValue, candidateInclusive);
        }
        return new BoundChoice(currentValue, currentInclusive);
    }

    private static BoundChoice chooseUpperBound(
            String currentValue, boolean currentInclusive, String candidateValue, boolean candidateInclusive) {
        if (currentValue == null) {
            return new BoundChoice(candidateValue, candidateInclusive);
        }
        int comparison = candidateValue.compareTo(currentValue);
        if (comparison < 0 || (comparison == 0 && currentInclusive && !candidateInclusive)) {
            return new BoundChoice(candidateValue, candidateInclusive);
        }
        return new BoundChoice(currentValue, currentInclusive);
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

    private record ColumnRangeKey(
            int column,
            String lowerValue,
            boolean lowerInclusive,
            String upperValue,
            boolean upperInclusive) {
    }

    private record BoundChoice(String value, boolean inclusive) {
    }
}
