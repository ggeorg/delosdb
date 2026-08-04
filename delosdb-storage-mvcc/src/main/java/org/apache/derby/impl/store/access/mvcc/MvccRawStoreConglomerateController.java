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
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodUniqueConstraintLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/** Controller for the isolated RawStore-backed MVCC table format. */
final class MvccRawStoreConglomerateController
        implements ConglomerateController, AccessMethodUniqueConstraintLifecycle {
    private final MvccRawStoreRuntime runtime;
    private final MvccRawStoreTable.Descriptor table;
    private final TransactionManager transactionManager;
    private final Transaction rawTransaction;
    private boolean closed;

    MvccRawStoreConglomerateController(
            MvccRawStoreRuntime runtime,
            MvccRawStoreTable.Descriptor table,
            TransactionManager transactionManager,
            Transaction rawTransaction) {
        this.runtime = runtime;
        this.table = table;
        this.transactionManager = transactionManager;
        this.rawTransaction = rawTransaction;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            transactionManager.closeMe(this);
        }
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
                MvccRowLocation.from(loc).rowId(),
                context);
    }

    @Override
    public boolean fetch(StoreRowLocation loc, StoreDataValue[] destRow, FormatableBitSet validColumns)
            throws StandardException {
        ensureOpen();
        MvccRawStoreTransactionContext context = runtime.context(transactionManager, rawTransaction);
        try (MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
            MvccRawStoreTable.VisibleRow visible = MvccRawStoreTable.readVisible(
                    rawTransaction,
                    table,
                    MvccRowLocation.from(loc).rowId(),
                    validColumns,
                    context);
            if (visible == null) {
                return false;
            }
            StoreValueCopySupport.copyRow(visible.values(), destRow, validColumns);
            return true;
        }
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
            context.lockRowForUpdate(table, MvccRowLocation.from(loc).rowId());
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
                MvccRowLocation.from(loc).rowId(),
                row,
                validColumns,
                context);
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
