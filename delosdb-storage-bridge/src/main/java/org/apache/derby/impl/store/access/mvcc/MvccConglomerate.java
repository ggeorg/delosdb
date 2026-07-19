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
import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;
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
    private static final long serialVersionUID = 1L;

    private transient MvccDatabaseRuntime runtime;
    private transient ContainerKey id;
    private transient MvccConglomerateState state;
    private transient MvccRawStoreRuntime rawStoreRuntime;
    private transient MvccRawStoreTable.Descriptor rawStoreTable;
    private int columnCount;
    private int[] collationIds = new int[0];
    private boolean temporary;

    public MvccConglomerate() {
        this.id = new ContainerKey(0L, 0L);
    }

    private MvccConglomerate(MvccDatabaseRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.id = new ContainerKey(0L, 0L);
    }

    private MvccConglomerate(MvccRawStoreRuntime rawStoreRuntime) {
        this.rawStoreRuntime = java.util.Objects.requireNonNull(rawStoreRuntime, "rawStoreRuntime");
        this.id = new ContainerKey(0L, 0L);
    }

    MvccConglomerate(
            MvccDatabaseRuntime runtime,
            int segment,
            long containerId,
            StoreDataValue[] template,
            int[] collationIds,
            int temporaryFlag) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.id = new ContainerKey(segment, containerId);
        this.state = runtime.stateFor(id);
        this.columnCount = template == null ? 0 : template.length;
        this.collationIds = collationIds == null ? new int[0] : collationIds.clone();
        this.temporary = (temporaryFlag & TransactionController.IS_TEMPORARY) == TransactionController.IS_TEMPORARY;
    }

    MvccConglomerate(MvccDatabaseRuntime runtime, ContainerKey key) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.id = java.util.Objects.requireNonNull(key, "key");
        this.state = runtime.stateFor(key);
    }

    MvccConglomerate(
            MvccRawStoreRuntime rawStoreRuntime,
            MvccRawStoreTable.Descriptor rawStoreTable) {
        this.rawStoreRuntime = java.util.Objects.requireNonNull(rawStoreRuntime, "rawStoreRuntime");
        this.rawStoreTable = java.util.Objects.requireNonNull(rawStoreTable, "rawStoreTable");
        this.id = rawStoreTable.metadataContainer();
        this.columnCount = rawStoreTable.columnCount();
        this.collationIds = rawStoreTable.collationIds().clone();
        this.temporary = rawStoreTable.temporary();
    }

    boolean rawStoreBacked() {
        return rawStoreTable != null;
    }

    MvccConglomerateState state() {
        return requireState();
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
    public void drop(TransactionManager xactManager) throws StandardException {
        if (xactManager.isGlobal()) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "delos_mvcc DDL in XA transactions");
        }
        if (rawStoreBacked()) {
            try {
                DelosStorageTransactionRegistry.registerRawStoreOwnedMvcc(xactManager);
            } catch (IllegalStateException mixedAuthorities) {
                throw StandardException.newException(
                        SQLState.NOT_IMPLEMENTED,
                        mixedAuthorities,
                        mixedAuthorities.getMessage());
            }
            MvccRawStoreTable.drop(xactManager.getRawStoreXact(), rawStoreTable);
            return;
        }
        requireState();
        MvccDatabaseRuntime currentRuntime = requireRuntime();
        DelosMvccConglomerateLifecycle lifecycle = new DelosMvccConglomerateLifecycle(
                DelosMvccConglomerateLifecycle.Operation.DROP,
                id.getSegmentId(),
                id.getContainerId());
        DelosStorageTransactionRegistry.registerLifecycleAction(
                xactManager,
                MvccConglomerateLifecycleAction.drop(currentRuntime, id, lifecycle));
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
        if (rawStoreBacked()) {
            return new MvccRawStoreConglomerateController(
                    rawStoreRuntime,
                    rawStoreTable,
                    xactManager,
                    rawtran);
        }
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
            DynamicCompiledOpenConglomInfo dynamicInfo) throws StandardException {
        if (rawStoreBacked()) {
            return new MvccRawStoreScanController(
                    rawStoreRuntime,
                    rawStoreTable,
                    xactManager,
                    rawtran,
                    hold,
                    scanColumnList,
                    qualifier);
        }
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
        if (rawStoreBacked()) {
            throw unsupported();
        }
        try {
            requireState().vacuumSafely();
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
        MvccConglomerate clone;
        if (rawStoreBacked()) {
            clone = new MvccConglomerate(rawStoreRuntime, rawStoreTable);
        } else if (runtime == null) {
            clone = new MvccConglomerate();
        } else if (id.getContainerId() == 0L) {
            clone = new MvccConglomerate(runtime);
        } else {
            clone = new MvccConglomerate(runtime, id);
        }
        clone.columnCount = columnCount;
        clone.collationIds = collationIds.clone();
        clone.temporary = temporary;
        return clone;
    }

    @Override
    public StoreDataValue getNewNull() {
        if (rawStoreRuntime != null) {
            return new MvccConglomerate(rawStoreRuntime);
        }
        return runtime == null ? new MvccConglomerate() : new MvccConglomerate(runtime);
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
        state = runtime == null || containerId == 0L ? null : runtime.stateFor(id);
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
        state = null;
        rawStoreTable = null;
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

    static int stateCountForDiagnostics() {
        return MvccRuntimeDiagnosticsDirectory.totalStateCount();
    }

    static int storeCountForDiagnostics() {
        return MvccRuntimeDiagnosticsDirectory.runtimeCount();
    }

    static Path pageVolumeStateFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageVolumeStateFileForTesting();
    }

    static Path rowDirectoryStateFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).rowDirectoryStateFileForTesting();
    }

    static Path reusablePageIndexFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).reusablePageIndexFileForTesting();
    }

    static Path freeSpaceMapFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapFileForTesting();
    }

    static Path visibilityMapFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapFileForTesting();
    }

    static Path purgeQueueFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeQueueFileForTesting();
    }

    static Path orderedIndexPagesFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexPagesFileForTesting();
    }

    static Path pageMutationLogFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationLogFileForTesting();
    }

    static Path writeAheadLogFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).writeAheadLogFileForTesting();
    }

    static Path checkpointFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).checkpointFileForTesting();
    }

    static Path subsystemRecoveryRecordsFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).subsystemRecoveryRecordsFileForTesting();
    }

    static String checkpointStatusForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).checkpointStatusForTesting();
    }

    static int physicalVersionCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).physicalVersionCountForTesting();
    }

    static int logicalRowCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).logicalRowCountForTesting();
    }

    static List<String> pageBackedVisibleRowSummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageBackedVisibleRowSummariesForTesting();
    }

    static int lastCommittedChangedRowCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).lastCommittedChangedRowCountForTesting();
    }

    static int lastCommittedWriteIntentCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).lastCommittedWriteIntentCountForTesting();
    }

    static List<String> lastCommittedWriteIntentPayloadSummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).lastCommittedWriteIntentPayloadSummariesForTesting();
    }

    static int activeProviderWriteAppendCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).activeProviderWriteAppendCountForTesting();
    }

    static List<String> activeProviderWriteAppendPayloadSummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).activeProviderWriteAppendPayloadSummariesForTesting();
    }

    static int activeProviderSurvivingWriteIntentCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).activeProviderSurvivingWriteIntentCountForTesting();
    }

    static List<String> activeProviderSurvivingWriteIntentPayloadSummariesForDiagnostics(
            MvccDatabaseRuntime runtime,
            int segment,
            long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId))
                .activeProviderSurvivingWriteIntentPayloadSummariesForTesting();
    }

    static int providerFirstWriteAppendCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).providerFirstWriteAppendCountForTesting();
    }

    static int legacyWriteFrontShadowMutationCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).legacyWriteFrontShadowMutationCountForTesting();
    }

    static int legacyWriteFrontShadowBypassCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).legacyWriteFrontShadowBypassCountForTesting();
    }

    static boolean legacyWriteFrontShadowEnabledForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).legacyWriteFrontShadowEnabledForTesting();
    }

    static int legacyWriteFrontQuarantineViolationCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).legacyWriteFrontQuarantineViolationCountForTesting();
    }

    static int providerFirstWriteAppendFailureRollbackCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).providerFirstWriteAppendFailureRollbackCountForTesting();
    }

    static int transactionLocalWriteIntentReadCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).transactionLocalWriteIntentReadCountForTesting();
    }

    static int transactionLocalWriteIntentScanCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).transactionLocalWriteIntentScanCountForTesting();
    }

    static int transactionLocalPageBackedBaseReadCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).transactionLocalPageBackedBaseReadCountForTesting();
    }

    static int transactionLocalPageBackedBaseScanCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).transactionLocalPageBackedBaseScanCountForTesting();
    }

    static int pageBackedHistoricalSnapshotReadCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageBackedHistoricalSnapshotReadCountForTesting();
    }

    static int pageBackedHistoricalSnapshotScanCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageBackedHistoricalSnapshotScanCountForTesting();
    }

    static int legacySnapshotFallbackReadCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).legacySnapshotFallbackReadCountForTesting();
    }

    static int legacySnapshotFallbackScanCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).legacySnapshotFallbackScanCountForTesting();
    }

    static int pageBackedCandidateIndexRebuildCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageBackedCandidateIndexRebuildCountForTesting();
    }

    static int legacyCandidateIndexRebuildCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).legacyCandidateIndexRebuildCountForTesting();
    }

    static int candidateIndexKeyCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).candidateIndexKeyCountForTesting();
    }

    static boolean candidateIndexDiagnosticFallbackEnabledForDiagnostics(MvccDatabaseRuntime runtime) {
        return MvccConglomerateState.candidateIndexDiagnosticFallbackEnabledForTesting();
    }

    static long pageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCountForTesting();
    }

    static long overflowPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).overflowPageCountForTesting();
    }

    static long reusablePageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).reusablePageCountForTesting();
    }

    static long freeSpaceMapPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapPageCountForTesting();
    }

    static int freeSpaceMapMaxFreeBytesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapMaxFreeBytesForTesting();
    }

    static long freeSpaceMapLookupCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapLookupCountForTesting();
    }

    static long freeSpaceMapHitCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapHitCountForTesting();
    }

    static long freeSpaceMapNonLastHitCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapNonLastHitCountForTesting();
    }

    static long freeSpaceMapMissCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapMissCountForTesting();
    }

    static long freeSpaceMapStaleEntryCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapStaleEntryCountForTesting();
    }

    static long freeSpaceMapUpdateCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapUpdateCountForTesting();
    }

    static long freeSpaceMapRebuildCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapRebuildCountForTesting();
    }

    static List<String> freeSpaceMapPageSummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapPageSummariesForTesting();
    }

    static long visibilityMapPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapPageCountForTesting();
    }

    static long visibilityMapOldVersionPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapOldVersionPageCountForTesting();
    }

    static long visibilityMapPrunablePageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapPrunablePageCountForTesting();
    }

    static long visibilityMapTombstonePageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapTombstonePageCountForTesting();
    }

    static long visibilityMapAllVisiblePageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapAllVisiblePageCountForTesting();
    }

    static long visibilityMapOverflowPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapOverflowPageCountForTesting();
    }

    static long visibilityMapNeedsCheckerPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapNeedsCheckerPageCountForTesting();
    }

    static long visibilityMapUpdateCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapUpdateCountForTesting();
    }

    static long visibilityMapRebuildCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapRebuildCountForTesting();
    }

    static List<String> visibilityMapPageSummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).visibilityMapPageSummariesForTesting();
    }

    static long pageLocalPruneAttemptCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageLocalPruneAttemptCountForTesting();
    }

    static long pageLocalPruneSuccessCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageLocalPruneSuccessCountForTesting();
    }

    static long pageLocalPruneFallbackCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageLocalPruneFallbackCountForTesting();
    }

    static long pageLocalPruneRemovedVersionCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageLocalPruneRemovedVersionCountForTesting();
    }

    static long pageMutationContextBeginCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationContextBeginCountForTesting();
    }

    static long pageMutationContextCommitCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationContextCommitCountForTesting();
    }

    static long pageMutationContextAbortCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationContextAbortCountForTesting();
    }

    static long pageMutationContextPageReservationCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationContextPageReservationCountForTesting();
    }

    static long pageMutationContextReservedBytesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationContextReservedBytesForTesting();
    }

    static long pageMutationContextPageWriteCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationContextPageWriteCountForTesting();
    }

    static long pageMutationContextFreeSpaceMapUpdateCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationContextFreeSpaceMapUpdateCountForTesting();
    }

    static long pageMutationContextReusableIndexUpdateCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageMutationContextReusableIndexUpdateCountForTesting();
    }

    static String lastPageMutationContextOperationForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).lastPageMutationContextOperationForTesting();
    }

    static long purgeQueuePendingCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeQueuePendingCountForTesting();
    }

    static long purgeQueueEnqueueCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeQueueEnqueueCountForTesting();
    }

    static long purgeQueueDrainCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeQueueDrainCountForTesting();
    }

    static long purgeQueueLastDrainCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeQueueLastDrainCountForTesting();
    }

    static List<String> purgeQueueEntrySummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeQueueEntrySummariesForTesting();
    }

    static long purgeDaemonScheduleCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeDaemonScheduleCountForTesting();
    }

    static long purgeDaemonRunCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeDaemonRunCountForTesting();
    }

    static long purgeDaemonSkipCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeDaemonSkipCountForTesting();
    }

    static long purgeDaemonLastTriggerChangedRowsForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeDaemonLastTriggerChangedRowsForTesting();
    }

    static String purgeDaemonLastDecisionForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeDaemonLastDecisionForTesting();
    }

    static long purgeDaemonLastVisibilityDebtScoreForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeDaemonLastVisibilityDebtScoreForTesting();
    }

    static String purgeDaemonLastVisibilityDebtSummaryForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).purgeDaemonLastVisibilityDebtSummaryForTesting();
    }

    static int databaseMaintenanceWorkerCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceWorkerCountForTesting();
    }

    static int databaseMaintenanceRegisteredTableCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceRegisteredTableCountForTesting();
    }

    static int databaseMaintenanceQueuedTaskCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceQueuedTaskCountForTesting();
    }

    static long databaseMaintenanceCommitWakeupCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceCommitWakeupCountForTesting();
    }

    static long databaseMaintenancePeriodicScanCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenancePeriodicScanCountForTesting();
    }

    static long databaseMaintenanceRunCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceRunCountForTesting();
    }

    static long databaseMaintenanceFailureCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceFailureCountForTesting();
    }

    static int databaseMaintenanceMaximumActiveWorkerCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceMaximumActiveWorkerCountForTesting();
    }

    static boolean databaseMaintenanceAcceptingForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).databaseMaintenanceAcceptingForTesting();
    }

    static long orderedIndexPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexPageCountForTesting();
    }

    static long orderedIndexEntryCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexEntryCountForTesting();
    }

    static int orderedIndexDistinctKeyCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexDistinctKeyCountForTesting();
    }

    static long orderedIndexRebuildCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexRebuildCountForTesting();
    }

    static List<String> orderedIndexEntrySummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexEntrySummariesForTesting();
    }

    static long orderedIndexLookupCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexLookupCountForTesting();
    }

    static long orderedIndexHitCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexHitCountForTesting();
    }

    static long orderedIndexFallbackCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexFallbackCountForTesting();
    }

    static long orderedIndexFallbackReasonCountForDiagnostics(
            MvccDatabaseRuntime runtime,
            int segment,
            long containerId,
            DelosStorageOrderedIndexFallbackReason reason) {
        return runtime.stateFor(new ContainerKey(segment, containerId))
                .orderedIndexFallbackReasonCountForTesting(reason);
    }

    static List<String> orderedIndexFallbackReasonSummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexFallbackReasonSummariesForTesting();
    }

    static long orderedIndexRowIdCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexRowIdCountForTesting();
    }

    static int orderedIndexCandidateParityErrorCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexCandidateParityErrorCountForTesting();
    }

    static List<String> orderedIndexCandidateParityErrorSummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexCandidateParityErrorSummariesForTesting();
    }

    static DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForDiagnostics(
            MvccDatabaseRuntime runtime,
            int segment,
            long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).orderedIndexAuthorityModeForTesting();
    }

    static long pageCacheMaxPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheMaxPageCountForTesting();
    }

    static long pageCacheSizeForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheSizeForTesting();
    }

    static long pageCacheHitCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheHitCountForTesting();
    }

    static long pageCacheMissCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheMissCountForTesting();
    }

    static long pageCacheWriteCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheWriteCountForTesting();
    }

    static long pageCacheEvictionCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheEvictionCountForTesting();
    }

    static long pageCacheInvalidationCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheInvalidationCountForTesting();
    }

    static long pageCachePinCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCachePinCountForTesting();
    }

    static long pageCacheUnpinCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheUnpinCountForTesting();
    }

    static long pageCachePinnedPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCachePinnedPageCountForTesting();
    }

    static long pageCacheDirtyPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheDirtyPageCountForTesting();
    }

    static long pageCacheFlushListPageCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheFlushListPageCountForTesting();
    }

    static long pageCacheFlushCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheFlushCountForTesting();
    }

    static long pageCachePinnedEvictionSkipCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCachePinnedEvictionSkipCountForTesting();
    }

    static long pageCacheLastPageGenerationForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).pageCacheLastPageGenerationForTesting();
    }

    static long attributeOverflowWriteCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).attributeOverflowWriteCountForTesting();
    }

    static long attributeOverflowReadCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).attributeOverflowReadCountForTesting();
    }

    static long attributeOverflowInlineRowBytesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).attributeOverflowInlineRowBytesForTesting();
    }

    static long attributeOverflowValueBytesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).attributeOverflowValueBytesForTesting();
    }

    static long subsystemRecoveryRecordCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).subsystemRecoveryRecordCountForTesting();
    }

    static long subsystemRecoveryLastSequenceForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).subsystemRecoveryLastSequenceForTesting();
    }

    static long rowPageRedoRecordCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).rowPageRedoRecordCountForTesting();
    }

    static long indexPageRedoRecordCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).indexPageRedoRecordCountForTesting();
    }

    static long overflowPageRedoRecordCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).overflowPageRedoRecordCountForTesting();
    }

    static long freeSpaceMapRedoRecordCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).freeSpaceMapRedoRecordCountForTesting();
    }

    static long transactionOutcomeRedoRecordCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).transactionOutcomeRedoRecordCountForTesting();
    }

    static long checkpointRecoveryRecordCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).checkpointRecoveryRecordCountForTesting();
    }

    static List<String> subsystemRecoveryRecordSummariesForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).subsystemRecoveryRecordSummariesForTesting();
    }

    static int consistencyErrorCountForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).consistencyErrorCountForTesting();
    }

    static String consistencySummaryForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).consistencySummaryForTesting();
    }

    static void assertConsistentForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        runtime.stateFor(new ContainerKey(segment, containerId)).assertConsistentForTesting();
    }

    static boolean lastVacuumSkippedForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).lastVacuumOutcomeForTesting().skipped();
    }

    static String lastVacuumReasonForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).lastVacuumOutcomeForTesting().reason();
    }

    static int lastVacuumRemovedVersionsForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).lastVacuumOutcomeForTesting().removedVersions();
    }

    static int lastVacuumRemainingVersionsForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).lastVacuumOutcomeForTesting().remainingVersions();
    }

    static Path legacySnapshotFileForDiagnostics(MvccDatabaseRuntime runtime, int segment, long containerId) {
        return runtime.stateFor(new ContainerKey(segment, containerId)).legacySnapshotFileForTesting();
    }

    private MvccDatabaseRuntime requireRuntime() {
        MvccDatabaseRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            throw new IllegalStateException("delos_mvcc conglomerate is not attached to a database runtime");
        }
        return currentRuntime;
    }

    private MvccConglomerateState requireState() {
        MvccConglomerateState currentState = state;
        if (currentState == null) {
            if (id.getContainerId() == 0L) {
                throw new IllegalStateException("delos_mvcc null conglomerate has no table state");
            }
            currentState = requireRuntime().stateFor(id);
            state = currentState;
        }
        return currentState;
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
