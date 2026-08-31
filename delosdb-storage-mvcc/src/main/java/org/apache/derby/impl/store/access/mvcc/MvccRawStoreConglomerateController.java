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
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodBaseFetchPagePrefetch;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodIndexBuildLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodReadCommittedUpdateRecheck;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodUniqueConstraintLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/** Controller for the isolated RawStore-backed MVCC table format. */
final class MvccRawStoreConglomerateController
        implements ConglomerateController, AccessMethodBaseFetchPagePrefetch,
                AccessMethodIndexBuildLifecycle, AccessMethodReadCommittedUpdateRecheck,
                AccessMethodUniqueConstraintLifecycle {
    private final MvccRawStoreRuntime runtime;
    private final MvccRawStoreTable.Descriptor table;
    private final TransactionManager transactionManager;
    private final Transaction rawTransaction;
    private static final String BASE_FETCH_PAGE_PREFETCH_PROPERTY =
            "delosdb.experimental.mvccBaseFetchPagePrefetch";

    private final boolean forUpdate;
    private final MvccRawStoreRuntime.SnapshotLease statementSnapshotLease;
    private final long statementSnapshotSequence;
    // IndexRowToBaseRow owns this controller for the statement. Keep the
    // read-only RawStore lookup resources at that same lifetime instead of
    // reopening both physical containers for every qualifying base row.
    private ContainerHandle readDirectoryContainer;
    private MvccRawStoreVersionReader readVersionReader;
    private FormatableBitSet readProjectionColumns;
    private MvccRawStoreVersionRows.FetchProjection readProjection;
    private final boolean baseFetchPagePrefetchRequested;
    private long[] prefetchedDirectoryRowIds;
    private MvccRawStoreTable.DirectoryRecord[] prefetchedDirectories;
    private int prefetchedDirectoryCount;
    private boolean readCommittedUpdateRecheck;
    private boolean closed;

    MvccRawStoreConglomerateController(
            MvccRawStoreRuntime runtime,
            MvccRawStoreTable.Descriptor table,
            TransactionManager transactionManager,
            Transaction rawTransaction,
            boolean forUpdate,
            LockingPolicy lockingPolicy) {
        this.runtime = runtime;
        this.table = table;
        this.transactionManager = transactionManager;
        this.rawTransaction = rawTransaction;
        this.forUpdate = forUpdate;
        this.baseFetchPagePrefetchRequested =
                !forUpdate && Boolean.getBoolean(BASE_FETCH_PAGE_PREFETCH_PROPERTY);
        // IndexRowToBaseRowResultSet opens one base ConglomerateController for
        // the SQL statement. Cursor-stability row locking identifies the
        // READ COMMITTED base-fetch path, which must observe a fresh committed
        // horizon for each statement just like MvccRawStoreScanController.
        // Retain the horizon until close so vacuum cannot cross the statement.
        if (!forUpdate && lockingPolicy != null && lockingPolicy.supportsImmutablePageRead()) {
            statementSnapshotLease = runtime.openSnapshotLease();
            statementSnapshotSequence = statementSnapshotLease.sequence();
        } else {
            statementSnapshotLease = null;
            statementSnapshotSequence = Long.MIN_VALUE;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            clearPrefetchedDirectories();
            if (readVersionReader != null) {
                readVersionReader.close();
            }
            if (readDirectoryContainer != null) {
                readDirectoryContainer.close();
            }
            if (statementSnapshotLease != null) {
                statementSnapshotLease.close();
            }
            transactionManager.closeMe(this);
        }
    }

    @Override
    public boolean baseFetchPagePrefetchEnabled() {
        // Limit the first causal experiment to the established read-only
        // statement-snapshot path. Other isolation/update paths remain A3.
        return baseFetchPagePrefetchRequested && statementSnapshotLease != null;
    }

    @Override
    public void prefetchBaseRows(StoreRowLocation[] rowLocations, int count)
            throws StandardException {
        ensureOpen();
        clearPrefetchedDirectories();
        if (!baseFetchPagePrefetchEnabled() || rowLocations == null || count < 2) {
            return;
        }
        int limit = Math.min(count, rowLocations.length);
        ensurePrefetchCapacity(limit);
        try (MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
            int index = 0;
            while (index < limit) {
                StoreRowLocation candidate = rowLocations[index];
                if (candidate == null) {
                    index++;
                    continue;
                }
                MvccRowLocation first = MvccRowLocation.from(candidate);
                if (!first.hasLocatorHint()) {
                    index++;
                    continue;
                }
                long pageNumber = first.locatorPageId();
                int groupEnd = index + 1;
                while (groupEnd < limit) {
                    StoreRowLocation groupedCandidate = rowLocations[groupEnd];
                    if (groupedCandidate == null) {
                        break;
                    }
                    MvccRowLocation grouped = MvccRowLocation.from(groupedCandidate);
                    if (!grouped.hasLocatorHint() || grouped.locatorPageId() != pageNumber) {
                        break;
                    }
                    groupEnd++;
                }
                if (groupEnd - index > 1) {
                    prefetchDirectoryPage(rowLocations, index, groupEnd, pageNumber);
                }
                index = groupEnd;
            }
        }
    }

    @Override
    public void enableReadCommittedUpdateRecheck() {
        readCommittedUpdateRecheck = true;
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
            context.lockRowForReadCommittedUpdate(table, location);
        } else if (checkWriteVersion) {
            context.beforeRowWrite(table, location.rowId());
        }
        MvccRawStoreVersionRows.FetchProjection projection = !forUpdate
                ? readProjection(validColumns)
                : MvccRawStoreVersionRows.projection(table, validColumns);
        MvccRawStoreTable.DirectoryRecord prefetchedDirectory = !forUpdate
                ? takePrefetchedDirectory(location.rowId())
                : null;
        MvccRawStoreTable.VisibleRow visible;
        if (readCommittedRecheck) {
            try (MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
                visible = MvccRawStoreTable.readLockedCurrentForWrite(
                        rawTransaction, table, location, projection);
            }
        } else {
            try (MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
                visible = checkWriteVersion
                        ? MvccRawStoreTable.readVisibleForWrite(
                                rawTransaction, table, location, projection, context)
                        : !forUpdate
                                ? prefetchedDirectory != null
                                        ? MvccRawStoreTable.readVisibleAtResolvedDirectory(
                                                rawTransaction,
                                                table,
                                                location,
                                                statementSnapshotLease != null
                                                        ? statementSnapshotSequence
                                                        : context.snapshotSequence(),
                                                projection,
                                                context,
                                                prefetchedDirectory,
                                                readDirectoryContainer(),
                                                readVersionReader())
                                        : MvccRawStoreTable.readVisibleAt(
                                                rawTransaction,
                                                table,
                                                location,
                                                statementSnapshotLease != null
                                                        ? statementSnapshotSequence
                                                        : context.snapshotSequence(),
                                                projection,
                                                context,
                                                readDirectoryContainer(),
                                                readVersionReader())
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
                context,
                forUpdate && readCommittedUpdateRecheck);
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

    private void prefetchDirectoryPage(
            StoreRowLocation[] rowLocations,
            int start,
            int end,
            long pageNumber) throws StandardException {
        Page page = null;
        try {
            page = readDirectoryContainer().getPage(pageNumber);
            if (page == null) {
                return;
            }
            for (int index = start; index < end; index++) {
                MvccRowLocation rowLocation = MvccRowLocation.from(rowLocations[index]);
                MvccRawStoreTable.DirectoryRecord directory =
                        MvccRawStoreRowDirectory.findByHint(rawTransaction, rowLocation, page);
                if (directory != null) {
                    prefetchedDirectoryRowIds[prefetchedDirectoryCount] = rowLocation.rowId();
                    prefetchedDirectories[prefetchedDirectoryCount] = directory;
                    prefetchedDirectoryCount++;
                }
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private MvccRawStoreTable.DirectoryRecord takePrefetchedDirectory(long rowId) {
        for (int index = 0; index < prefetchedDirectoryCount; index++) {
            if (prefetchedDirectoryRowIds[index] != rowId) {
                continue;
            }
            MvccRawStoreTable.DirectoryRecord directory = prefetchedDirectories[index];
            prefetchedDirectoryRowIds[index] = 0L;
            prefetchedDirectories[index] = null;
            return directory;
        }
        return null;
    }

    private void ensurePrefetchCapacity(int capacity) {
        if (prefetchedDirectories != null && prefetchedDirectories.length >= capacity) {
            return;
        }
        prefetchedDirectoryRowIds = new long[capacity];
        prefetchedDirectories = new MvccRawStoreTable.DirectoryRecord[capacity];
    }

    private void clearPrefetchedDirectories() {
        if (prefetchedDirectories != null) {
            for (int index = 0; index < prefetchedDirectoryCount; index++) {
                prefetchedDirectories[index] = null;
                prefetchedDirectoryRowIds[index] = 0L;
            }
        }
        prefetchedDirectoryCount = 0;
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

    private ContainerHandle readDirectoryContainer() throws StandardException {
        if (readDirectoryContainer == null) {
            readDirectoryContainer = rawTransaction.openContainer(
                    table.metadataContainer(),
                    MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                    ContainerHandle.MODE_READONLY);
        }
        return readDirectoryContainer;
    }

    private MvccRawStoreVersionRows.FetchProjection readProjection(
            FormatableBitSet validColumns) {
        if (validColumns == null) {
            return null;
        }
        if (readProjection != null && validColumns.equals(readProjectionColumns)) {
            return readProjection;
        }
        readProjectionColumns = (FormatableBitSet) validColumns.clone();
        readProjection = MvccRawStoreVersionRows.projection(table, validColumns);
        return readProjection;
    }

    private MvccRawStoreVersionReader readVersionReader() throws StandardException {
        if (readVersionReader == null) {
            readVersionReader = new MvccRawStoreVersionReader(rawTransaction, table);
        }
        return readVersionReader;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RawStore MVCC conglomerate controller is closed");
        }
    }
}
