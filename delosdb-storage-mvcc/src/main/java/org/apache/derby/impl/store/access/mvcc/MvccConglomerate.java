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

import org.apache.derby.iapi.services.io.CompressedNumber;
import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.services.io.Storable;
import org.apache.derby.iapi.services.io.StoredFormatIds;
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
import org.apache.derby.iapi.store.types.StoreDataValueBase;
import org.apache.derby.iapi.store.types.StoreStringDataValue;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** RawStore-backed Derby conglomerate for {@code delos_mvcc} tables. */
public final class MvccConglomerate
        extends StoreDataValueBase
        implements org.apache.derby.iapi.store.access.conglomerate.Conglomerate,
                StaticCompiledOpenConglomInfo {
    private static final long serialVersionUID = 1L;

    private transient MvccRawStoreRuntime runtime;
    private transient MvccRawStoreTable.Descriptor table;
    private ContainerKey id = new ContainerKey(0L, 0L);
    private int columnCount;
    private int[] collationIds = new int[0];
    private boolean temporary;

    public MvccConglomerate() {
    }

    private MvccConglomerate(MvccRawStoreRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    MvccConglomerate(
            MvccRawStoreRuntime runtime,
            MvccRawStoreTable.Descriptor table) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.table = java.util.Objects.requireNonNull(table, "table");
        this.id = table.metadataContainer();
        this.columnCount = table.columnCount();
        this.collationIds = table.collationIds().clone();
        this.temporary = table.temporary();
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
        MvccRawStoreTable.Descriptor currentTable = requireTable();
        MvccRawStoreTransactionContext context = requireRuntime().context(
                xactManager,
                xactManager.getRawStoreXact());
        context.beforeDrop(currentTable);
        MvccRawStoreTable.drop(xactManager.getRawStoreXact(), currentTable);
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
    public StaticCompiledOpenConglomInfo getStaticCompiledConglomInfo(
            TransactionController transaction,
            long conglomId) {
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
    public long load(
            TransactionManager xactManager,
            boolean createConglom,
            RowLocationRetRowSource rowSource) throws StandardException {
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
        return new MvccRawStoreConglomerateController(
                requireRuntime(),
                requireTable(),
                xactManager,
                rawtran);
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
        return new MvccRawStoreScanController(
                requireRuntime(),
                requireTable(),
                xactManager,
                rawtran,
                hold,
                scanColumnList,
                qualifier);
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
    public void purgeConglomerate(
            TransactionManager xactManager,
            Transaction rawtran) throws StandardException {
        MvccRawStoreTable.Descriptor currentTable = requireTable();
        MvccRawStoreTransactionContext context = requireRuntime().context(
                xactManager,
                rawtran);
        context.beforeVacuum(currentTable);
        MvccRawStoreVacuum.Result result = MvccRawStoreVacuum.vacuum(
                rawtran,
                currentTable,
                context.vacuumHorizon());
        if (result.requiresOrderedIndexReplacement()) {
            context.prepareOrderedIndexReplacement(currentTable);
        }
        if (result.mutated()) {
            context.markVacuumMutation();
        }
    }

    @Override
    public void compressConglomerate(
            TransactionManager xactManager,
            Transaction rawtran) throws StandardException {
        purgeConglomerate(xactManager, rawtran);
    }

    @Override
    public StoreCostController openStoreCost(
            TransactionManager xactManager,
            Transaction rawtran) {
        return new MvccStoreCostController(this);
    }

    @Override
    public StoreDataValue cloneValue(boolean forceMaterialization) {
        MvccConglomerate clone = table == null
                ? (runtime == null ? new MvccConglomerate() : new MvccConglomerate(runtime))
                : new MvccConglomerate(runtime, table);
        clone.id = id;
        clone.columnCount = columnCount;
        clone.collationIds = collationIds.clone();
        clone.temporary = temporary;
        return clone;
    }

    @Override
    public StoreDataValue getNewNull() {
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
        id = new ContainerKey(
                CompressedNumber.readLong(in),
                CompressedNumber.readLong(in));
        columnCount = CompressedNumber.readInt(in);
        int collationCount = CompressedNumber.readInt(in);
        collationIds = new int[collationCount];
        for (int index = 0; index < collationCount; index++) {
            collationIds[index] = CompressedNumber.readInt(in);
        }
        temporary = in.readBoolean();
        table = null;
    }

    @Override
    public void restoreToNull() {
        id = new ContainerKey(0L, 0L);
        table = null;
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

    private MvccRawStoreRuntime requireRuntime() {
        MvccRawStoreRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException(
                    "delos_mvcc conglomerate is not attached to its RawStore runtime");
        }
        return current;
    }

    private MvccRawStoreTable.Descriptor requireTable() {
        MvccRawStoreTable.Descriptor current = table;
        if (current == null) {
            throw new IllegalStateException(
                    "delos_mvcc conglomerate is not attached to a RawStore table descriptor");
        }
        return current;
    }

    private static StandardException unsupported() {
        return StandardException.newException(SQLState.STORE_FEATURE_NOT_IMPLEMENTED);
    }

    private static final class MvccDynamicCompiledOpenConglomInfo
            implements DynamicCompiledOpenConglomInfo {
    }
}
