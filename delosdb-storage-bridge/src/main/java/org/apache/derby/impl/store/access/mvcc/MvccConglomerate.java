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
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreStringDataValue;
import org.apache.derby.iapi.store.types.StoreDataValueBase;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * MODULE6C inherited Conglomerate skeleton for Delos MVCC.
 *
 * <p>This class proves that an MVCC access method can produce the inherited
 * Derby store/access objects: a conglomerate, scan controller, conglomerate
 * controller, and logical row-location template. It deliberately does not route
 * SQL execution or persist data yet.</p>
 */
public final class MvccConglomerate
        extends StoreDataValueBase
        implements org.apache.derby.iapi.store.access.conglomerate.Conglomerate, StaticCompiledOpenConglomInfo {
    private static final Map<StateIdentity, MvccConglomerateState> STATES = new ConcurrentHashMap<>();
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
        state.dropDurableState();
        STATES.remove(new StateIdentity(databaseDirectory, id));
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
        STATES.entrySet().removeIf(entry -> {
            if (!Objects.equals(entry.getKey().databaseDirectory(), normalized)) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    static void clearStatesForDiagnostics() {
        for (MvccConglomerateState state : STATES.values()) {
            state.close();
        }
        STATES.clear();
    }

    static int stateCountForDiagnostics() {
        return STATES.size();
    }

    static Path pageVolumeStateFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageVolumeStateFileForTesting();
    }

    static Path rowDirectoryStateFileForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).rowDirectoryStateFileForTesting();
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

    static String checkpointStatusForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).checkpointStatusForTesting();
    }

    static int physicalVersionCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).physicalVersionCountForTesting();
    }

    static int logicalRowCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).logicalRowCountForTesting();
    }

    static long pageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).pageCountForTesting();
    }

    static long overflowPageCountForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId)).overflowPageCountForTesting();
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
        return STATES.computeIfAbsent(identity, ignored -> new MvccConglomerateState(key, identity.databaseDirectory()));
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
     * MODULE6F minimal dynamic compiled info.
     *
     * <p>Inherited Derby compiled scans require non-null dynamic compiled
     * information even when this MVCC preflight does not need scratch state yet.
     * The object intentionally carries no behavior; MvccScanController remains
     * the authority for row visibility and projection.</p>
     */
    private static final class MvccDynamicCompiledOpenConglomInfo
            implements DynamicCompiledOpenConglomInfo {
    }

}
