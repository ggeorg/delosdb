/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerate

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

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.derby.iapi.services.io.CompressedNumber;
import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.services.io.Storable;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.access.ColumnOrdering;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.RowLocationRetRowSource;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.StoreCostController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.ScanManager;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;
import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreStringDataValue;
import org.apache.derby.iapi.store.types.StoreDataValueBase;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Derby-compatible conglomerate implementation for {@code delos_mvcc} tables.
 *
 * <p>The class adapts the DelosDB MVCC table implementation to Derby's
 * conglomerate, scan, controller, compiled-information, and row-location
 * contracts.</p>
 */
public final class MvccConglomerate
        extends StoreDataValueBase
        implements org.apache.derby.iapi.store.access.conglomerate.Conglomerate, StaticCompiledOpenConglomInfo {
    private static final Map<StateIdentity, MvccConglomerateState> STATES = new ConcurrentHashMap<>();
    private static final Map<DatabaseIdentity, DelosStorageStore> STORES = new ConcurrentHashMap<>();
    private static volatile Path databaseDirectory;

    private ContainerKey id;
    private MvccConglomerateState state;
    private int columnCount;
    private int[] collationIds = new int[0];
    private boolean temporary;

    public MvccConglomerate() {
        this.id = new ContainerKey(0L, 0L);
        this.state = stateFor(id);
    }

    MvccConglomerate(int segment, long containerId, StoreDataValue[] template, int[] collationIds, int temporaryFlag) {
        this.id = new ContainerKey(segment, containerId);
        this.state = stateFor(id);
        this.columnCount = template == null ? 0 : template.length;
        this.collationIds = collationIds == null ? new int[0] : collationIds.clone();
        this.temporary = (temporaryFlag & TransactionController.IS_TEMPORARY) == TransactionController.IS_TEMPORARY;
    }

    MvccConglomerate(ContainerKey key) {
        this.id = key;
        this.state = stateFor(key);
    }

    MvccConglomerateState state() {
        return state;
    }

    @Override
    public void addColumn(
            TransactionManager xactManager,
            int columnId,
            Storable templateColumn,
            int collationId) throws StandardException {
        throw unsupported();
    }

    @Override
    public void drop(TransactionManager xactManager) {
        DelosStorageTransactionRegistry.abortTableParticipants(state.table());
        state.dropDurableState();
        MvccConglomerateState removed = STATES.remove(new StateIdentity(databaseDirectory, id));
        if (removed != null) {
            removed.close();
        }
    }

    @Override
    public boolean fetchMaxOnBTree(
            TransactionManager xactManager,
            Transaction rawtran,
            long conglomId,
            int openMode,
            int lockLevel,
            LockingPolicy lockingPolicy,
            int isolationLevel,
            FormatableBitSet scanColumnList,
            StoreDataValue[] fetchRow) throws StandardException {
        throw unsupported();
    }

    @Override
    public long getContainerid() {
        return id.getContainerId();
    }

    @Override
    public ContainerKey getId() {
        return id;
    }

    @Override
    public StaticCompiledOpenConglomInfo getStaticCompiledConglomInfo(TransactionController tc, long conglomId) {
        return this;
    }

    @Override
    public DynamicCompiledOpenConglomInfo getDynamicCompiledConglomInfo() {
        return new MvccDynamicCompiledOpenConglomInfo();
    }

    @Override
    public boolean isTemporary() {
        return temporary;
    }

    @Override
    public long load(TransactionManager xactManager, boolean createConglom, RowLocationRetRowSource rowSource)
            throws StandardException {
        throw unsupported();
    }

    @Override
    public ConglomerateController open(
            TransactionManager xactManager,
            Transaction rawtran,
            boolean hold,
            int openMode,
            int lockLevel,
            LockingPolicy lockingPolicy,
            StaticCompiledOpenConglomInfo staticInfo,
            DynamicCompiledOpenConglomInfo dynamicInfo) {
        return new MvccConglomerateController(this, xactManager, openMode);
    }

    @Override
    public ScanManager openScan(
            TransactionManager xactManager,
            Transaction rawtran,
            boolean hold,
            int openMode,
            int lockLevel,
            LockingPolicy lockingPolicy,
            int isolationLevel,
            FormatableBitSet scanColumnList,
            StoreDataValue[] startKeyValue,
            int startSearchOperator,
            Qualifier[][] qualifier,
            StoreDataValue[] stopKeyValue,
            int stopSearchOperator,
            StaticCompiledOpenConglomInfo staticInfo,
            DynamicCompiledOpenConglomInfo dynamicInfo) {
        return new MvccScanController(this, xactManager, hold, openMode, isolationLevel, scanColumnList, qualifier);
    }

    @Override
    public ScanManager defragmentConglomerate(
            TransactionManager xactManager,
            Transaction rawtran,
            boolean hold,
            int openMode,
            int lockLevel,
            LockingPolicy lockingPolicy,
            int isolationLevel) throws StandardException {
        throw unsupported();
    }

    @Override
    public void purgeConglomerate(TransactionManager xactManager, Transaction rawtran) throws StandardException {
        try {
            state.vacuumSafely();
        } catch (RuntimeException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    @Override
    public void compressConglomerate(TransactionManager xactManager, Transaction rawtran) throws StandardException {
        purgeConglomerate(xactManager, rawtran);
    }

    @Override
    public StoreCostController openStoreCost(TransactionManager xactManager, Transaction rawtran) {
        return new MvccStoreCostController(this);
    }

    @Override
    public StoreDataValue cloneValue(boolean forceMaterialization) {
        MvccConglomerate clone = new MvccConglomerate(id);
        clone.columnCount = columnCount;
        clone.collationIds = collationIds.clone();
        clone.temporary = temporary;
        return clone;
    }

    @Override
    public StoreDataValue getNewNull() {
        return new MvccConglomerate();
    }

    public String getTypeName() {
        return "MvccConglomerate";
    }

    @Override
    public StoreDataValue getConglom() {
        return this;
    }

    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.ACCESS_MVCC_V1_ID;
    }

    @Override
    public boolean isNull() {
        return id.getContainerId() == 0L;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        CompressedNumber.writeLong(out, id.getSegmentId());
        CompressedNumber.writeLong(out, id.getContainerId());
        CompressedNumber.writeInt(out, columnCount);
        CompressedNumber.writeInt(out, collationIds.length);
        for (int collationId : collationIds) {
            CompressedNumber.writeInt(out, collationId);
        }
        out.writeBoolean(temporary);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException {
        long segmentId = CompressedNumber.readLong(in);
        long containerId = CompressedNumber.readLong(in);
        id = new ContainerKey(segmentId, containerId);
        state = stateFor(id);
        columnCount = CompressedNumber.readInt(in);
        int collationCount = CompressedNumber.readInt(in);
        collationIds = new int[collationCount];
        for (int i = 0; i < collationIds.length; i++) {
            collationIds[i] = CompressedNumber.readInt(in);
        }
        temporary = in.readBoolean();
    }

    @Override
    public void restoreToNull() {
        id = new ContainerKey(0L, 0L);
        state = stateFor(id);
        columnCount = 0;
        collationIds = new int[0];
        temporary = false;
    }

    public int columnCount() {
        return columnCount;
    }

    public boolean hasCollatedTypes() {
        for (int collationId : collationIds) {
            if (collationId != StoreStringDataValue.COLLATION_TYPE_UCS_BASIC) {
                return true;
            }
        }
        return false;
    }

    static void configureDatabaseDirectory(Path directory) {
        databaseDirectory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    static void clearStatesForDatabase(Path directory) {
        Path normalized = directory == null ? null : directory.toAbsolutePath().normalize();
        List<MvccConglomerateState> removedStates = STATES.entrySet().stream()
                .filter(entry -> Objects.equals(entry.getKey().databaseDirectory(), normalized))
                .map(Map.Entry::getValue)
                .toList();
        STATES.entrySet().removeIf(entry ->
                Objects.equals(entry.getKey().databaseDirectory(), normalized));
        DelosStorageStore store = STORES.remove(new DatabaseIdentity(normalized));
        if (store != null) {
            store.close();
        } else {
            removedStates.forEach(MvccConglomerateState::close);
        }
    }

    static void clearStatesForDiagnostics() {
        List<MvccConglomerateState> orphanedStates = List.copyOf(STATES.values());
        STATES.clear();
        List<DelosStorageStore> stores = List.copyOf(STORES.values());
        STORES.clear();
        stores.forEach(DelosStorageStore::close);
        orphanedStates.forEach(MvccConglomerateState::close);
    }

    static int stateCountForDiagnostics() {
        return STATES.size();
    }

    static int storeCountForDiagnostics() {
        return STORES.size();
    }

    static Path pageVolumeStateFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageVolumeStateFileForTesting();
    }

    static Path rowDirectoryStateFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).rowDirectoryStateFileForTesting();
    }

    static Path reusablePageIndexFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).reusablePageIndexFileForTesting();
    }

    static Path freeSpaceMapFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapFileForTesting();
    }

    static Path visibilityMapFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapFileForTesting();
    }

    static Path purgeQueueFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeQueueFileForTesting();
    }

    static Path orderedIndexPagesFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexPagesFileForTesting();
    }

    static Path pageMutationLogFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationLogFileForTesting();
    }

    static Path writeAheadLogFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).writeAheadLogFileForTesting();
    }

    static Path checkpointFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).checkpointFileForTesting();
    }

    static Path subsystemRecoveryRecordsFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).subsystemRecoveryRecordsFileForTesting();
    }

    static String checkpointStatusForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).checkpointStatusForTesting();
    }

    static int physicalVersionCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).physicalVersionCountForTesting();
    }

    static int logicalRowCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).logicalRowCountForTesting();
    }

    static List<String> pageBackedVisibleRowSummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageBackedVisibleRowSummariesForTesting();
    }

    static int lastCommittedChangedRowCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).lastCommittedChangedRowCountForTesting();
    }

    static int lastCommittedWriteIntentCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).lastCommittedWriteIntentCountForTesting();
    }

    static List<String> lastCommittedWriteIntentPayloadSummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).lastCommittedWriteIntentPayloadSummariesForTesting();
    }

    static int activeProviderWriteAppendCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).activeProviderWriteAppendCountForTesting();
    }

    static List<String> activeProviderWriteAppendPayloadSummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).activeProviderWriteAppendPayloadSummariesForTesting();
    }

    static int activeProviderSurvivingWriteIntentCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).activeProviderSurvivingWriteIntentCountForTesting();
    }

    static List<String> activeProviderSurvivingWriteIntentPayloadSummariesForDiagnostics(
            int segment,
            long containerId) {
        return stateFor(new ContainerKey(segment, containerId))
                .activeProviderSurvivingWriteIntentPayloadSummariesForTesting();
    }

    static int providerFirstWriteAppendCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).providerFirstWriteAppendCountForTesting();
    }

    static int legacyWriteFrontShadowMutationCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).legacyWriteFrontShadowMutationCountForTesting();
    }

    static int legacyWriteFrontShadowBypassCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).legacyWriteFrontShadowBypassCountForTesting();
    }

    static boolean legacyWriteFrontShadowEnabledForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).legacyWriteFrontShadowEnabledForTesting();
    }

    static int legacyWriteFrontQuarantineViolationCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).legacyWriteFrontQuarantineViolationCountForTesting();
    }

    static int providerFirstWriteAppendFailureRollbackCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).providerFirstWriteAppendFailureRollbackCountForTesting();
    }

    static int transactionLocalWriteIntentReadCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).transactionLocalWriteIntentReadCountForTesting();
    }

    static int transactionLocalWriteIntentScanCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).transactionLocalWriteIntentScanCountForTesting();
    }

    static int transactionLocalPageBackedBaseReadCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).transactionLocalPageBackedBaseReadCountForTesting();
    }

    static int transactionLocalPageBackedBaseScanCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).transactionLocalPageBackedBaseScanCountForTesting();
    }

    static int pageBackedHistoricalSnapshotReadCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageBackedHistoricalSnapshotReadCountForTesting();
    }

    static int pageBackedHistoricalSnapshotScanCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageBackedHistoricalSnapshotScanCountForTesting();
    }

    static int legacySnapshotFallbackReadCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).legacySnapshotFallbackReadCountForTesting();
    }

    static int legacySnapshotFallbackScanCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).legacySnapshotFallbackScanCountForTesting();
    }

    static int pageBackedCandidateIndexRebuildCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageBackedCandidateIndexRebuildCountForTesting();
    }

    static int legacyCandidateIndexRebuildCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).legacyCandidateIndexRebuildCountForTesting();
    }

    static int candidateIndexKeyCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).candidateIndexKeyCountForTesting();
    }

    static boolean candidateIndexDiagnosticFallbackEnabledForDiagnostics() {
        return MvccConglomerateState.candidateIndexDiagnosticFallbackEnabledForTesting();
    }

    static long pageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCountForTesting();
    }

    static long overflowPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).overflowPageCountForTesting();
    }

    static long reusablePageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).reusablePageCountForTesting();
    }

    static long freeSpaceMapPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapPageCountForTesting();
    }

    static int freeSpaceMapMaxFreeBytesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapMaxFreeBytesForTesting();
    }

    static long freeSpaceMapLookupCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapLookupCountForTesting();
    }

    static long freeSpaceMapHitCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapHitCountForTesting();
    }

    static long freeSpaceMapNonLastHitCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapNonLastHitCountForTesting();
    }

    static long freeSpaceMapMissCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapMissCountForTesting();
    }

    static long freeSpaceMapStaleEntryCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapStaleEntryCountForTesting();
    }

    static long freeSpaceMapUpdateCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapUpdateCountForTesting();
    }

    static long freeSpaceMapRebuildCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapRebuildCountForTesting();
    }

    static List<String> freeSpaceMapPageSummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapPageSummariesForTesting();
    }

    static long visibilityMapPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapPageCountForTesting();
    }

    static long visibilityMapOldVersionPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapOldVersionPageCountForTesting();
    }

    static long visibilityMapPrunablePageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapPrunablePageCountForTesting();
    }

    static long visibilityMapTombstonePageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapTombstonePageCountForTesting();
    }

    static long visibilityMapAllVisiblePageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapAllVisiblePageCountForTesting();
    }

    static long visibilityMapOverflowPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapOverflowPageCountForTesting();
    }

    static long visibilityMapNeedsCheckerPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapNeedsCheckerPageCountForTesting();
    }

    static long visibilityMapUpdateCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapUpdateCountForTesting();
    }

    static long visibilityMapRebuildCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapRebuildCountForTesting();
    }

    static List<String> visibilityMapPageSummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).visibilityMapPageSummariesForTesting();
    }

    static long pageLocalPruneAttemptCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageLocalPruneAttemptCountForTesting();
    }

    static long pageLocalPruneSuccessCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageLocalPruneSuccessCountForTesting();
    }

    static long pageLocalPruneFallbackCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageLocalPruneFallbackCountForTesting();
    }

    static long pageLocalPruneRemovedVersionCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageLocalPruneRemovedVersionCountForTesting();
    }

    static long pageMutationContextBeginCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationContextBeginCountForTesting();
    }

    static long pageMutationContextCommitCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationContextCommitCountForTesting();
    }

    static long pageMutationContextAbortCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationContextAbortCountForTesting();
    }

    static long pageMutationContextPageReservationCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationContextPageReservationCountForTesting();
    }

    static long pageMutationContextReservedBytesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationContextReservedBytesForTesting();
    }

    static long pageMutationContextPageWriteCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationContextPageWriteCountForTesting();
    }

    static long pageMutationContextFreeSpaceMapUpdateCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationContextFreeSpaceMapUpdateCountForTesting();
    }

    static long pageMutationContextReusableIndexUpdateCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageMutationContextReusableIndexUpdateCountForTesting();
    }

    static String lastPageMutationContextOperationForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).lastPageMutationContextOperationForTesting();
    }

    static long purgeQueuePendingCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeQueuePendingCountForTesting();
    }

    static long purgeQueueEnqueueCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeQueueEnqueueCountForTesting();
    }

    static long purgeQueueDrainCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeQueueDrainCountForTesting();
    }

    static long purgeQueueLastDrainCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeQueueLastDrainCountForTesting();
    }

    static List<String> purgeQueueEntrySummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeQueueEntrySummariesForTesting();
    }

    static long purgeDaemonScheduleCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeDaemonScheduleCountForTesting();
    }

    static long purgeDaemonRunCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeDaemonRunCountForTesting();
    }

    static long purgeDaemonSkipCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeDaemonSkipCountForTesting();
    }

    static long purgeDaemonLastTriggerChangedRowsForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeDaemonLastTriggerChangedRowsForTesting();
    }

    static String purgeDaemonLastDecisionForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeDaemonLastDecisionForTesting();
    }

    static long purgeDaemonLastVisibilityDebtScoreForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeDaemonLastVisibilityDebtScoreForTesting();
    }

    static String purgeDaemonLastVisibilityDebtSummaryForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).purgeDaemonLastVisibilityDebtSummaryForTesting();
    }

    static int databaseMaintenanceWorkerCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceWorkerCountForTesting();
    }

    static int databaseMaintenanceRegisteredTableCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceRegisteredTableCountForTesting();
    }

    static int databaseMaintenanceQueuedTaskCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceQueuedTaskCountForTesting();
    }

    static long databaseMaintenanceCommitWakeupCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceCommitWakeupCountForTesting();
    }

    static long databaseMaintenancePeriodicScanCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenancePeriodicScanCountForTesting();
    }

    static long databaseMaintenanceRunCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceRunCountForTesting();
    }

    static long databaseMaintenanceFailureCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceFailureCountForTesting();
    }

    static int databaseMaintenanceMaximumActiveWorkerCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceMaximumActiveWorkerCountForTesting();
    }

    static boolean databaseMaintenanceAcceptingForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceAcceptingForTesting();
    }

    static long orderedIndexPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexPageCountForTesting();
    }

    static long orderedIndexEntryCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexEntryCountForTesting();
    }

    static int orderedIndexDistinctKeyCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexDistinctKeyCountForTesting();
    }

    static long orderedIndexRebuildCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexRebuildCountForTesting();
    }

    static List<String> orderedIndexEntrySummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexEntrySummariesForTesting();
    }

    static long orderedIndexLookupCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexLookupCountForTesting();
    }

    static long orderedIndexHitCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexHitCountForTesting();
    }

    static long orderedIndexFallbackCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexFallbackCountForTesting();
    }

    static long orderedIndexFallbackReasonCountForDiagnostics(
            int segment,
            long containerId,
            DelosStorageOrderedIndexFallbackReason reason) {
        return stateFor(new ContainerKey(segment, containerId))
                .orderedIndexFallbackReasonCountForTesting(reason);
    }

    static List<String> orderedIndexFallbackReasonSummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexFallbackReasonSummariesForTesting();
    }

    static long orderedIndexRowIdCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexRowIdCountForTesting();
    }

    static int orderedIndexCandidateParityErrorCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexCandidateParityErrorCountForTesting();
    }

    static List<String> orderedIndexCandidateParityErrorSummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexCandidateParityErrorSummariesForTesting();
    }

    static DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForDiagnostics(
            int segment,
            long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).orderedIndexAuthorityModeForTesting();
    }

    static long pageCacheMaxPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheMaxPageCountForTesting();
    }

    static long pageCacheSizeForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheSizeForTesting();
    }

    static long pageCacheHitCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheHitCountForTesting();
    }

    static long pageCacheMissCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheMissCountForTesting();
    }

    static long pageCacheWriteCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheWriteCountForTesting();
    }

    static long pageCacheEvictionCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheEvictionCountForTesting();
    }

    static long pageCacheInvalidationCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheInvalidationCountForTesting();
    }

    static long pageCachePinCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCachePinCountForTesting();
    }

    static long pageCacheUnpinCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheUnpinCountForTesting();
    }

    static long pageCachePinnedPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCachePinnedPageCountForTesting();
    }

    static long pageCacheDirtyPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheDirtyPageCountForTesting();
    }

    static long pageCacheFlushListPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheFlushListPageCountForTesting();
    }

    static long pageCacheFlushCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheFlushCountForTesting();
    }

    static long pageCachePinnedEvictionSkipCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCachePinnedEvictionSkipCountForTesting();
    }

    static long pageCacheLastPageGenerationForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCacheLastPageGenerationForTesting();
    }

    static long attributeOverflowWriteCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).attributeOverflowWriteCountForTesting();
    }

    static long attributeOverflowReadCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).attributeOverflowReadCountForTesting();
    }

    static long attributeOverflowInlineRowBytesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).attributeOverflowInlineRowBytesForTesting();
    }

    static long attributeOverflowValueBytesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).attributeOverflowValueBytesForTesting();
    }

    static long subsystemRecoveryRecordCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).subsystemRecoveryRecordCountForTesting();
    }

    static long subsystemRecoveryLastSequenceForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).subsystemRecoveryLastSequenceForTesting();
    }

    static long rowPageRedoRecordCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).rowPageRedoRecordCountForTesting();
    }

    static long indexPageRedoRecordCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).indexPageRedoRecordCountForTesting();
    }

    static long overflowPageRedoRecordCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).overflowPageRedoRecordCountForTesting();
    }

    static long freeSpaceMapRedoRecordCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).freeSpaceMapRedoRecordCountForTesting();
    }

    static long transactionOutcomeRedoRecordCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).transactionOutcomeRedoRecordCountForTesting();
    }

    static long checkpointRecoveryRecordCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).checkpointRecoveryRecordCountForTesting();
    }

    static List<String> subsystemRecoveryRecordSummariesForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).subsystemRecoveryRecordSummariesForTesting();
    }

    static int consistencyErrorCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).consistencyErrorCountForTesting();
    }

    static String consistencySummaryForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).consistencySummaryForTesting();
    }

    static void assertConsistentForDiagnostics(int segment, long containerId) {
        stateFor(new ContainerKey(segment, containerId)).assertConsistentForTesting();
    }

    static boolean lastVacuumSkippedForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).lastVacuumOutcomeForTesting().skipped();
    }

    static String lastVacuumReasonForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).lastVacuumOutcomeForTesting().reason();
    }

    static int lastVacuumRemovedVersionsForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).lastVacuumOutcomeForTesting().removedVersions();
    }

    static int lastVacuumRemainingVersionsForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).lastVacuumOutcomeForTesting().remainingVersions();
    }

    static Path legacySnapshotFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).legacySnapshotFileForTesting();
    }

    private static MvccConglomerateState stateFor(ContainerKey key) {
        StateIdentity identity = new StateIdentity(databaseDirectory, key);
        return STATES.computeIfAbsent(identity, ignored ->
                new MvccConglomerateState(key, storeFor(identity.databaseDirectory())));
    }

    private static DelosStorageStore storeFor(Path directory) {
        DatabaseIdentity identity = new DatabaseIdentity(directory);
        return STORES.computeIfAbsent(identity, ignored -> MvccConglomerateState.openStore(directory));
    }

    private record DatabaseIdentity(Path databaseDirectory) {
        private DatabaseIdentity {
            databaseDirectory = databaseDirectory == null
                    ? null
                    : databaseDirectory.toAbsolutePath().normalize();
        }
    }

    private record StateIdentity(Path databaseDirectory, ContainerKey key) {
        private StateIdentity {
            key = java.util.Objects.requireNonNull(key, "key");
        }
    }

    private static StandardException unsupported() {
        return StandardException.newException(SQLState.STORE_FEATURE_NOT_IMPLEMENTED);
    }

    /**
     * Dynamic compiled information required by Derby's access-method contract.
     *
     * <p>The MVCC access method does not require additional mutable compiled
     * state. Row visibility and projection remain owned by
     * {@link MvccScanController}.</p>
     */
    private static final class MvccDynamicCompiledOpenConglomInfo
            implements DynamicCompiledOpenConglomInfo {
    }

}
