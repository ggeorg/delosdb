/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreConglomerateController

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.Properties;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.SpaceInfo;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodIndexBuildLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodReadCommittedUpdateRecheck;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodUniqueConstraintLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/** Controller for the isolated RawStore-backed MVCC table format. */
final class MvccRawStoreConglomerateController
        implements ConglomerateController, AccessMethodIndexBuildLifecycle,
                AccessMethodReadCommittedUpdateRecheck, AccessMethodUniqueConstraintLifecycle {
    private final MvccRawStoreRuntime runtime;
    private final MvccRawStoreTable.Descriptor table;
    private final TransactionManager transactionManager;
    private final Transaction rawTransaction;
    private final boolean forUpdate;
    private boolean readCommittedUpdateRecheck;
    private boolean closed;

    MvccRawStoreConglomerateController(
            MvccRawStoreRuntime runtime,
            MvccRawStoreTable.Descriptor table,
            TransactionManager transactionManager,
            Transaction rawTransaction,
            boolean forUpdate) {
        this.runtime = runtime;
        this.table = table;
        this.transactionManager = transactionManager;
        this.rawTransaction = rawTransaction;
        this.forUpdate = forUpdate;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            transactionManager.closeMe(this);
        }
    }

    @Override
    public void enableReadCommittedUpdateRecheck() {
        readCommittedUpdateRecheck = runtime.readCommittedUpdateRecheck();
    }

    @Override
    public boolean closeForEndTransaction(boolean closeHeldScan) {
        close();
        return true;
    }

    @Override
    public void checkConsistency() {
    }

    @Override
    public boolean delete(StoreRowLocation loc) throws StandardException {
        ensureOpen();
        MvccRawStoreTransactionContext context = runtime.context(transactionManager, rawTransaction);
        return MvccRawStoreTable.delete(
                rawTransaction,
                table,
                MvccRowLocation.from(loc),
                context);
    }

    @Override
    public boolean fetch(StoreRowLocation loc, StoreDataValue[] destRow, FormatableBitSet validColumns)
            throws StandardException {
        ensureOpen();
        MvccRawStoreTransactionContext context = runtime.context(transactionManager, rawTransaction);
        MvccRowLocation location = MvccRowLocation.from(loc);
        boolean readCommittedRecheck = forUpdate && readCommittedUpdateRecheck;
        boolean checkWriteVersion = forUpdate && location.getWriteVersion() != 0L;
        if (readCommittedRecheck) {
            // Index-to-base-row update plans arrive here with a RowLocation from
            // the secondary index and no MVCC write-version attached yet. Wait
            // on the stable logical row before returning the base row so both
            // restriction and SET-expression evaluation use the post-wait value.
            context.lockRowForUpdate(table, location.rowId());
        } else if (checkWriteVersion) {
            context.beforeRowWrite(table, location.rowId());
        }
        MvccRawStoreVersionRows.FetchProjection projection =
                MvccRawStoreVersionRows.projection(table, validColumns);
        MvccRawStoreTable.VisibleRow visible;
        if (readCommittedRecheck) {
            try (MvccRawStoreRuntime.SnapshotLease lease = runtime.openSnapshotLease();
                 MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
                visible = MvccRawStoreTable.readVisibleAt(
                        rawTransaction, table, location, lease.sequence(), projection, context);
            }
        } else {
            try (MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
                visible = checkWriteVersion
                        ? MvccRawStoreTable.readVisibleForWrite(
                                rawTransaction, table, location, projection, context)
                        : MvccRawStoreTable.readVisible(
                                rawTransaction, table, location, projection, context);
            }
        }
        if (visible == null) {
            return false;
        }
        if (readCommittedRecheck || location.getWriteVersion() == 0L) {
            location.setWriteVersion(visible.versionId());
        }
        StoreValueCopySupport.copyRow(visible.values(), destRow, validColumns);
        return true;
    }

    @Override
    public boolean fetch(
            StoreRowLocation loc,
            StoreDataValue[] destRow,
            FormatableBitSet validColumns,
            boolean waitForLock) throws StandardException {
        return fetch(loc, destRow, validColumns);
    }

    @Override
    public int insert(StoreDataValue[] row) throws StandardException {
        insertInternal(row, null);
        return 0;
    }

    @Override
    public void insertAndFetchLocation(StoreDataValue[] row, StoreRowLocation destRowLocation)
            throws StandardException {
        insertInternal(row, MvccRowLocation.from(destRowLocation));
    }

    @Override
    public boolean isKeyed() {
        return false;
    }

    @Override
    public boolean lockRow(StoreRowLocation loc, int lockOper, boolean wait, int lockDuration)
            throws StandardException {
        ensureOpen();
        if ((lockOper & ConglomerateController.LOCK_UPD) != 0) {
            MvccRawStoreTransactionContext context =
                    runtime.context(transactionManager, rawTransaction);
            MvccRowLocation location = MvccRowLocation.from(loc);
            context.lockRowForUpdate(table, location.rowId());
            if (location.getWriteVersion() != 0L) {
                MvccRawStoreTable.validateWriteVersion(
                        location,
                        MvccRawStoreRowDirectory.find(
                                rawTransaction, table, location).head().versionId());
            }
        }
        return true;
    }

    @Override
    public boolean lockRow(long pageNum, int recordId, int lockOper, boolean wait, int lockDuration)
            throws StandardException {
        ensureOpen();
        // Derby's inherited backing B-tree invokes this callback while it
        // maintains a constraint index. A physical page/record pair is not a
        // stable MVCC row identity, so semantic row locking is performed only
        // by the StoreRowLocation overload and the statement-time mutation
        // path. Returning true preserves the inherited callback contract
        // without creating a second, physically addressed lock namespace.
        return true;
    }

    @Override
    public boolean supportsLockBasedCommittedDeleteReclamation() {
        return false;
    }

    @Override
    public boolean requiresSecondaryIndexBaseRowLocking() {
        return false;
    }

    @Override
    public void unlockRowAfterRead(StoreRowLocation loc, boolean forUpdate, boolean rowQualified) {
        ensureOpen();
    }

    @Override
    public StoreRowLocation newRowLocationTemplate() {
        ensureOpen();
        return new MvccRowLocation();
    }

    @Override
    public boolean replace(StoreRowLocation loc, StoreDataValue[] row, FormatableBitSet validColumns)
            throws StandardException {
        ensureOpen();
        MvccRawStoreTransactionContext context = runtime.context(transactionManager, rawTransaction);
        return MvccRawStoreTable.replace(
                rawTransaction,
                table,
                MvccRowLocation.from(loc),
                row,
                validColumns,
                context);
    }

    @Override
    public void beforeIndexBuild() throws StandardException {
        ensureOpen();
        runtime.context(transactionManager, rawTransaction).beforeSchemaChange(table);
    }

    @Override
    public void validateUniqueConstraintDefinition(
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed,
            boolean deferrable) throws StandardException {
        ensureOpen();
        runtime.context(transactionManager, rawTransaction).beforeSchemaChange(table);
        MvccRawStoreTableMetadata.validateUniqueConstraintDefinition(
                table,
                baseColumnPositions,
                deferrable);
    }

    @Override
    public void addUniqueConstraint(
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed,
            boolean deferrable) throws StandardException {
        ensureOpen();
        MvccRawStoreTransactionContext context = runtime.context(transactionManager, rawTransaction);
        context.beforeSchemaChange(table);
        MvccRawStoreTableMetadata.addUniqueConstraint(
                rawTransaction,
                table,
                baseColumnPositions,
                duplicateNullsAllowed,
                deferrable,
                context);
    }

    @Override
    public void dropUniqueConstraint(
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed) throws StandardException {
        ensureOpen();
        MvccRawStoreTransactionContext context = runtime.context(transactionManager, rawTransaction);
        context.beforeSchemaChange(table);
        MvccRawStoreTableMetadata.dropUniqueConstraint(
                rawTransaction,
                table,
                baseColumnPositions,
                duplicateNullsAllowed,
                context);
    }

    @Override
    public SpaceInfo getSpaceInfo() {
        ensureOpen();
        return null;
    }

    @Override
    public void debugConglomerate() {
    }

    @Override
    public void getTableProperties(Properties prop) {
    }

    @Override
    public Properties getInternalTablePropertySet(Properties prop) {
        return prop == null ? new Properties() : prop;
    }

    private void insertInternal(StoreDataValue[] row, MvccRowLocation destination) throws StandardException {
        ensureOpen();
        MvccRawStoreTransactionContext context = runtime.context(transactionManager, rawTransaction);
        MvccRawStoreTable.insert(
                rawTransaction,
                table,
                row,
                context,
                destination);
    }


    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RawStore MVCC conglomerate controller is closed");
        }
    }
}
