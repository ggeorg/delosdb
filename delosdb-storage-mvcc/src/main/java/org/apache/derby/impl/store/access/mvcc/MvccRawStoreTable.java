/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreTable

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodConglomerateProperties;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** One table encoded entirely as ordinary RawStore rows and containers. */
final class MvccRawStoreTable {
    private static final int OVERFLOW_THRESHOLD = 100;

    static final class Descriptor {
        private final ContainerKey metadataContainer;
        private final ContainerKey versionContainer;
        private final int[] formatIds;
        private final int[] collationIds;
        private final boolean temporary;
        private final int controlFormatVersion;
        private final boolean projectedCurrentRead;
        private volatile long accessConglomerateId;
        private volatile List<UniqueConstraint> uniqueConstraints;
        private volatile ContainerKey orderedIndexContainer;
        private volatile OrderedIndexGeneration orderedIndexGeneration;

        Descriptor(
                ContainerKey metadataContainer,
                ContainerKey versionContainer,
                ContainerKey orderedIndexContainer,
                int[] formatIds,
                int[] collationIds,
                boolean temporary,
                int controlFormatVersion,
                List<UniqueConstraint> uniqueConstraints) {
            this.metadataContainer = java.util.Objects.requireNonNull(
                    metadataContainer, "metadataContainer");
            this.versionContainer = java.util.Objects.requireNonNull(
                    versionContainer, "versionContainer");
            this.orderedIndexContainer = orderedIndexContainer;
            this.formatIds = formatIds.clone();
            this.collationIds = collationIds.clone();
            this.temporary = temporary;
            this.controlFormatVersion = controlFormatVersion;
            this.projectedCurrentRead = Boolean.getBoolean(
                    MvccRawStoreFormat.GEN2_PROJECTED_CURRENT_READ_ENABLED_PROPERTY);
            this.accessConglomerateId = temporary
                    ? ContainerHandle.DEFAULT_ASSIGN_ID
                    : metadataContainer.getContainerId();
            this.uniqueConstraints = List.copyOf(uniqueConstraints);
        }

        ContainerKey metadataContainer() {
            return metadataContainer;
        }

        ContainerKey versionContainer() {
            return versionContainer;
        }

        ContainerKey orderedIndexContainer() {
            return orderedIndexContainer;
        }

        void observeOrderedIndexContainer(ContainerKey container) {
            ContainerKey previous = orderedIndexContainer;
            orderedIndexContainer = container;
            if (!java.util.Objects.equals(previous, container)) {
                orderedIndexGeneration = null;
            }
        }

        OrderedIndexGeneration orderedIndexGeneration(ContainerKey container) {
            OrderedIndexGeneration current = orderedIndexGeneration;
            return current != null && current.belongsTo(container) ? current : null;
        }

        void observeOrderedIndexGeneration(ContainerKey container, long[] btrees) {
            if (container != null
                    && btrees != null
                    && container.equals(orderedIndexContainer)) {
                orderedIndexGeneration = new OrderedIndexGeneration(container, btrees);
            }
        }

        void invalidateOrderedIndexGeneration() {
            orderedIndexGeneration = null;
        }

        static final class OrderedIndexGeneration {
            private final ContainerKey container;
            private final long[] btrees;

            private OrderedIndexGeneration(ContainerKey container, long[] btrees) {
                this.container = container;
                this.btrees = btrees.clone();
            }

            private boolean belongsTo(ContainerKey candidate) {
                return container.equals(candidate);
            }

            long btree(int column) {
                return btrees[column];
            }
        }

        int[] formatIds() {
            return formatIds;
        }

        int[] collationIds() {
            return collationIds;
        }

        boolean temporary() {
            return temporary;
        }

        int controlFormatVersion() {
            return controlFormatVersion;
        }

        boolean gen2A1() {
            return controlFormatVersion >= MvccRawStoreFormat.GEN2_A1_CONTROL_FORMAT_VERSION;
        }

        boolean gen2History() {
            return controlFormatVersion == MvccRawStoreFormat.GEN2_C1_CONTROL_FORMAT_VERSION
                    || controlFormatVersion
                            == MvccRawStoreFormat.GEN2_C3_PK_HISTORY_CONTROL_FORMAT_VERSION;
        }

        boolean gen2PkHistory() {
            return controlFormatVersion
                    == MvccRawStoreFormat.GEN2_C3_PK_HISTORY_CONTROL_FORMAT_VERSION;
        }

        boolean projectedCurrentRead() {
            return projectedCurrentRead;
        }

        long accessConglomerateId() {
            long current = accessConglomerateId;
            if (current == ContainerHandle.DEFAULT_ASSIGN_ID) {
                throw new IllegalStateException(
                        "Temporary MVCC conglomerate id has not been assigned");
            }
            return current;
        }

        void observeAccessConglomerateId(long conglomId) {
            if ((temporary && conglomId >= 0L) || (!temporary && conglomId < 0L)) {
                throw new IllegalArgumentException("MVCC conglomerate id kind mismatch");
            }
            long current = accessConglomerateId;
            if (current != ContainerHandle.DEFAULT_ASSIGN_ID && current != conglomId) {
                throw new IllegalStateException(
                        "MVCC conglomerate id already assigned: " + current);
            }
            accessConglomerateId = conglomId;
        }

        int columnCount() {
            return formatIds.length;
        }

        int formatId(int column) {
            return formatIds[column];
        }

        int collationId(int column) {
            return collationIds[column];
        }

        List<UniqueConstraint> uniqueConstraints() {
            return uniqueConstraints;
        }

        void observeUniqueConstraints(List<UniqueConstraint> constraints) {
            uniqueConstraints = List.copyOf(constraints);
        }
    }

    record UniqueConstraint(int ordinal, int[] columns, boolean duplicateNullsAllowed) {
        UniqueConstraint {
            columns = columns.clone();
        }

        @Override
        public int[] columns() {
            return columns.clone();
        }

        boolean matches(int[] candidateColumns, boolean candidateDuplicateNullsAllowed) {
            return duplicateNullsAllowed == candidateDuplicateNullsAllowed
                    && java.util.Arrays.equals(columns, candidateColumns);
        }

        String displayName() {
            return "DELOS_MVCC_UNIQUE_" + ordinal;
        }
    }

    record VisibleRow(
            long rowId,
            long versionId,
            StoreDataValue[] values,
            RecordHandle versionHandle,
            MvccRowLocation directoryLocation) {
    }

    record PendingVersion(
            Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long previousVersionId,
            RecordHint previousHint,
            int flags,
            RecordHandle handle,
            MvccRowLocation directoryLocation,
            boolean inlineCurrent) {
        boolean tombstone() {
            return (flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0;
        }
    }

    /**
     * Use Derby's canonical long-row insertion policy. A populated page must
     * reject a row which does not fit so the caller can advance to another
     * normal page. Overflow is permitted only on an empty page, where RawStore
     * can root the complete long-row chain without mixing it with existing
     * control or data records.
     */
    private static byte insertFlags(Page page) throws StandardException {
        return (byte) (Page.INSERT_UNDO_WITH_PURGE
                | (page.recordCount() == 0
                        ? Page.INSERT_OVERFLOW
                        : Page.INSERT_DEFAULT));
    }

    private MvccRawStoreTable() {
    }

    static Descriptor create(
            TransactionManager transactionManager,
            int segment,
            long requestedContainerId,
            StoreDataValue[] template,
            int[] suppliedCollationIds,
            Properties properties,
            int temporaryFlag) throws StandardException {
        Transaction rawTransaction = transactionManager.getRawStoreXact();
        if (template == null || template.length == 0) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "delos_mvcc RawStore tables require a non-empty row template");
        }

        int[] formatIds = new int[template.length];
        int[] collationIds = new int[template.length];
        for (int index = 0; index < template.length; index++) {
            formatIds[index] = MvccRawStoreFormat.formatId(template[index]);
            int supplied = suppliedCollationIds != null && index < suppliedCollationIds.length
                    ? suppliedCollationIds[index]
                    : 0;
            collationIds[index] = MvccRawStoreFormat.collationId(template[index], supplied);
        }

        List<UniqueConstraint> uniqueConstraints = MvccRawStoreTableMetadata.parseUniqueConstraints(
                properties == null ? null : properties.getProperty(
                        AccessMethodConglomerateProperties.UNIQUE_CONSTRAINTS),
                template.length);
        boolean gen2BPrimaryKey = Boolean.getBoolean(
                MvccRawStoreFormat.GEN2_B_PK_ENABLED_PROPERTY);
        boolean gen2C1History = Boolean.getBoolean(
                MvccRawStoreFormat.GEN2_C1_HISTORY_ENABLED_PROPERTY);
        boolean gen2A1 = Boolean.getBoolean(MvccRawStoreFormat.GEN2_A1_ENABLED_PROPERTY)
                || gen2BPrimaryKey
                || gen2C1History;
        validateGen2CreateShape(
                gen2A1, gen2BPrimaryKey, gen2C1History, uniqueConstraints, temporaryFlag);

        long metadataId = rawTransaction.addContainer(
                segment,
                requestedContainerId,
                ContainerHandle.MODE_DEFAULT,
                properties,
                temporaryFlag);
        if (metadataId < 0L) {
            throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
        }
        long versionId = rawTransaction.addContainer(
                segment,
                0L,
                ContainerHandle.MODE_DEFAULT,
                properties,
                temporaryFlag);
        if (versionId < 0L) {
            throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
        }
        ContainerKey orderedIndex = null;
        if (!gen2A1) {
            long orderedIndexId = rawTransaction.addContainer(
                    segment,
                    0L,
                    ContainerHandle.MODE_DEFAULT,
                    properties,
                    temporaryFlag);
            if (orderedIndexId < 0L) {
                throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
            }
            orderedIndex = new ContainerKey(segment, orderedIndexId);
        }

        Descriptor descriptor = new Descriptor(
                new ContainerKey(segment, metadataId),
                new ContainerKey(segment, versionId),
                orderedIndex,
                formatIds,
                collationIds,
                (temporaryFlag & TransactionController.IS_TEMPORARY) == TransactionController.IS_TEMPORARY,
                gen2ControlFormatVersion(gen2A1, gen2BPrimaryKey, gen2C1History),
                uniqueConstraints);
        initializeMetadataContainer(rawTransaction, descriptor);
        initializeVersionContainer(rawTransaction, descriptor);
        if (!gen2A1) {
            MvccRawStoreOrderedIndexGeneration.initializeDirectory(
                    rawTransaction, descriptor, descriptor.orderedIndexContainer());
        }
        return descriptor;
    }

    private static void validateGen2CreateShape(
            boolean gen2A1,
            boolean gen2BPrimaryKey,
            boolean gen2C1History,
            List<UniqueConstraint> uniqueConstraints,
            int temporaryFlag) throws StandardException {
        if (gen2A1
                && (temporaryFlag & TransactionController.IS_TEMPORARY)
                        == TransactionController.IS_TEMPORARY) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2-A1 temporary tables are deferred beyond the first physical slice");
        }
        if (gen2C1History && !gen2BPrimaryKey && !uniqueConstraints.isEmpty()) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2-C1 history slice supports bare tables only; "
                            + "enable Gen2-B PK integration for indexed history updates");
        }
        if (gen2BPrimaryKey && gen2C1History && uniqueConstraints.isEmpty()) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2 PK/history integration requires SQL uniqueness metadata");
        }
        if (gen2A1 && !gen2BPrimaryKey && !gen2C1History && !uniqueConstraints.isEmpty()) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2-A1 supports bare tables only; unique constraints are deferred to Gen2-B");
        }
    }

    private static int gen2ControlFormatVersion(
            boolean gen2A1, boolean gen2BPrimaryKey, boolean gen2C1History) {
        if (gen2C1History && gen2BPrimaryKey) {
            return MvccRawStoreFormat.GEN2_C3_PK_HISTORY_CONTROL_FORMAT_VERSION;
        }
        if (gen2C1History) {
            return MvccRawStoreFormat.GEN2_C1_CONTROL_FORMAT_VERSION;
        }
        return gen2A1
                ? MvccRawStoreFormat.GEN2_A1_CONTROL_FORMAT_VERSION
                : MvccRawStoreFormat.FORMAT_VERSION;
    }

    static java.util.Optional<List<MvccRawStoreOrderedIndex.Candidate>>
            orderedIndexCandidatesForAt(
                    Descriptor table,
                    org.apache.derby.iapi.store.access.Qualifier[][] qualifiers,
                    MvccRawStoreTransactionContext context,
                    MvccConglomerate.MvccDynamicCompiledOpenConglomInfo compiledInfo)
                    throws StandardException {
        if (table.gen2A1()) {
            return java.util.Optional.empty();
        }
        ContainerKey orderedIndex = context.orderedIndexForRead(table);
        return MvccRawStoreOrderedIndex.candidatesForAt(
                context.transactionManager(),
                table,
                orderedIndex,
                qualifiers,
                context,
                compiledInfo);
    }

    static void ensureOrderedIndex(
            TransactionManager transactionManager, Descriptor table)
            throws StandardException {
        Transaction transaction = transactionManager.getRawStoreXact();
        ContainerKey existing = MvccRawStoreTableMetadata.discoverOrderedIndexContainer(
                transaction, table, false);
        if (existing != null) {
            return;
        }
        // Upgrade only for the compatibility rebuild. Existing indexed tables
        // must not take a metadata update lock before UPDATE/DELETE has checked
        // the visible head and can fail a stale writer without waiting behind a
        // long-running historical reader.
        existing = MvccRawStoreTableMetadata.discoverOrderedIndexContainer(
                transaction, table, true);
        if (existing != null) {
            return;
        }

        int temporaryFlag = table.temporary()
                ? TransactionController.IS_TEMPORARY
                : TransactionController.IS_DEFAULT;
        long containerId = transaction.addContainer(
                table.metadataContainer().getSegmentId(),
                0L,
                ContainerHandle.MODE_DEFAULT,
                null,
                temporaryFlag);
        if (containerId < 0L) {
            throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
        }

        ContainerKey orderedIndex = new ContainerKey(
                table.metadataContainer().getSegmentId(),
                containerId);
        table.observeOrderedIndexContainer(orderedIndex);
        try {
            MvccRawStoreOrderedIndexGeneration.initialize(transactionManager, table);
            rebuildOrderedIndex(transactionManager, table);
            MvccRawStoreTableMetadata.rewriteControlRow(transaction, table);
        } catch (StandardException | RuntimeException | Error failure) {
            table.observeOrderedIndexContainer(null);
            throw failure;
        }
    }

    private static void rebuildOrderedIndex(
            TransactionManager transactionManager, Descriptor table)
            throws StandardException {
        Transaction transaction = transactionManager.getRawStoreXact();
        Map<Long, MvccRowLocation> directoryLocations = MvccRawStoreRowDirectory.locations(
                transaction, table);
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC version container is absent: " + table.versionContainer());
        }
        List<MvccRawStoreOrderedIndex.VersionInput> versions = new ArrayList<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    VersionRecord version = MvccRawStoreVersionRows.decodeAtSlot(transaction, table, page, slot);
                    if (version == null || version.tombstone()) {
                        continue;
                    }
                    versions.add(new MvccRawStoreOrderedIndex.VersionInput(
                            version.rowId(),
                            version.versionId(),
                            MvccRawStoreRowDirectory.requireLocation(
                                    directoryLocations, version.rowId()),
                            version.values()));
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        MvccRawStoreOrderedIndex.rebuild(
                transactionManager, table, table.orderedIndexContainer(), versions);
    }

    static PendingVersion insert(
            Transaction rawTransaction,
            Descriptor table,
            StoreDataValue[] values,
            MvccRawStoreTransactionContext context,
            MvccRowLocation destination) throws StandardException {
        if (values == null || values.length != table.columnCount()) {
            throw new IllegalArgumentException(
                    "RawStore MVCC row width mismatch: expected " + table.columnCount());
        }
        context.beforeTableWrite(table);
        long creatorTransactionId = context.transactionId();
        if (table.gen2A1()) {
            return insertGen2A1(
                    rawTransaction, table, values, context, destination, creatorTransactionId);
        }
        // A preceding same-transaction delete already scanned/locked every
        // unchanged unique key. Reuse that transaction-local proof instead of
        // rescanning the candidate B-trees; a new logical row id is still
        // allocated below, preserving the v1 identity contract.
        MvccRawStoreTransactionContext.DeletedKeyProof deletedKeyProof =
                context.deletedKeyProof(table, values);
        if (deletedKeyProof == null) {
            MvccRawStoreOrderedIndex.assertUnique(
                    rawTransaction,
                    table,
                    null,
                    values,
                    0L,
                    context);
        }
        Allocation allocation = context.reserveInsertIdentifiers(table);
        Object[] versionRow = versionRow(
                rawTransaction,
                table,
                allocation.rowId(),
                allocation.versionId(),
                creatorTransactionId,
                MvccRawStoreFormat.NO_PREVIOUS_VERSION,
                RecordHint.NONE,
                MvccRawStoreFormat.LIVE_FLAGS,
                values);
        RecordHandle versionHandle = insertRow(rawTransaction, table.versionContainer(), versionRow);
        Object[] directoryRow = directoryRow(
                rawTransaction,
                allocation.rowId(),
                allocation.versionId(),
                RecordHint.of(versionHandle),
                creatorTransactionId,
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                MvccRawStoreFormat.LIVE_FLAGS);
        RecordHandle directoryHandle = insertRow(
                rawTransaction, table.metadataContainer(), directoryRow);
        MvccRowLocation directoryLocation = MvccRawStoreRowDirectory.location(
                allocation.rowId(), directoryHandle);
        MvccRawStoreOrderedIndex.insertVersion(
                context.transactionManager(),
                table,
                allocation.rowId(),
                allocation.versionId(),
                directoryLocation,
                null,
                values,
                context);
        PendingVersion pending = new PendingVersion(
                table,
                allocation.rowId(),
                allocation.versionId(),
                creatorTransactionId,
                MvccRawStoreFormat.NO_PREVIOUS_VERSION,
                RecordHint.NONE,
                MvccRawStoreFormat.LIVE_FLAGS,
                versionHandle,
                directoryLocation,
                false);
        context.addPending(pending);
        if (deletedKeyProof != null) {
            context.markDeletedKeyProofConsumed(deletedKeyProof, pending);
        }
        if (destination != null) {
            // SQL secondary indexes persist this RowLocation and later resolve it
            // through the stable-row directory. Its physical hint must therefore
            // address the metadata/directory record, not the version record.
            destination.copyFrom(directoryLocation);
        }
        return pending;
    }

    private static PendingVersion insertGen2A1(
            Transaction rawTransaction,
            Descriptor table,
            StoreDataValue[] values,
            MvccRawStoreTransactionContext context,
            MvccRowLocation destination,
            long creatorTransactionId) throws StandardException {
        // Gen2-B PK tables deliberately rely on the SQL backing constraint
        // B-tree as the single physical uniqueness/index authority. Do not
        // recreate Gen1 native candidate-index maintenance here.
        Allocation allocation = context.reserveInsertIdentifiers(table);
        Object[] currentRow = gen2A1CurrentRow(
                rawTransaction,
                table,
                allocation.rowId(),
                allocation.versionId(),
                creatorTransactionId,
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                MvccRawStoreFormat.LIVE_FLAGS,
                values);
        RecordHandle currentHandle = insertRow(
                rawTransaction, table.metadataContainer(), currentRow);
        MvccRowLocation currentLocation = MvccRawStoreRowDirectory.location(
                allocation.rowId(), currentHandle);
        PendingVersion pending = new PendingVersion(
                table,
                allocation.rowId(),
                allocation.versionId(),
                creatorTransactionId,
                MvccRawStoreFormat.NO_PREVIOUS_VERSION,
                RecordHint.NONE,
                MvccRawStoreFormat.LIVE_FLAGS,
                currentHandle,
                currentLocation,
                true);
        context.addPending(pending);
        if (destination != null) {
            destination.copyFrom(currentLocation);
        }
        return pending;
    }

    static VisibleRow readVisible(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            FormatableBitSet payloadColumns,
            MvccRawStoreTransactionContext context) throws StandardException {
        return readVisible(
                rawTransaction,
                table,
                new MvccRowLocation(rowId),
                MvccRawStoreVersionRows.projection(table, payloadColumns),
                context);
    }

    static VisibleRow readVisible(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        return readVisibleAt(
                rawTransaction,
                table,
                rowLocation,
                context.snapshotSequence(),
                projection,
                context,
                false);
    }

    static VisibleRow readVisibleForWrite(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        return readVisibleAt(
                rawTransaction,
                table,
                rowLocation,
                context.snapshotSequence(),
                projection,
                context,
                true);
    }

    // Caller must hold the transaction-duration logical row X-lock. That lock
    // proves any previous row writer has completed RawStore commit/rollback, so
    // READ COMMITTED recheck may consume the physical head even when ordered
    // database publication still trails it.
    static VisibleRow readLockedCurrentForWrite(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        if (table.gen2A1()) {
            if (!table.gen2History()) {
                throw StandardException.newException(
                        SQLState.NOT_IMPLEMENTED,
                        "MVCC Gen2-A1 UPDATE/DELETE is deferred to the history slice");
            }
            Gen2A1CurrentRecord current = findGen2CurrentAt(
                    rawTransaction, table, rowLocation, null);
            if (current == null
                    || (current.flags() & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0) {
                return null;
            }
            validateWriteVersion(rowLocation, current.versionId());
            return new VisibleRow(
                    current.rowId(),
                    current.versionId(),
                    current.values(),
                    current.handle(),
                    MvccRawStoreRowDirectory.location(current.rowId(), current.handle()));
        }
        DirectoryRecord directory = MvccRawStoreRowDirectory.find(
                rawTransaction, table, rowLocation);
        validateWriteVersion(rowLocation, directory.head().versionId());
        VersionRecord version = MvccRawStoreVersionReader.find(
                rawTransaction,
                table,
                rowLocation.rowId(),
                directory.head().versionId(),
                directory.head().hint(),
                projection);
        if (version == null || version.tombstone()) {
            return null;
        }
        return new VisibleRow(
                rowLocation.rowId(),
                version.versionId(),
                version.values(),
                version.handle(),
                MvccRawStoreRowDirectory.location(
                        rowLocation.rowId(), directory.handle()));
    }

    static VisibleRow readVisibleAt(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        return readVisibleAt(
                rawTransaction,
                table,
                rowLocation,
                snapshotSequence,
                projection,
                context,
                false);
    }

    static VisibleRow readVisibleAt(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context,
            ContainerHandle directoryContainer,
            MvccRawStoreVersionReader versionReader) throws StandardException {
        if (table.gen2A1()) {
            return readGen2A1CurrentAt(
                    rawTransaction,
                    table,
                    rowLocation,
                    projection,
                    snapshotSequence,
                    context.transactionId(),
                    directoryContainer);
        }
        return readVisibleAtResolvedDirectory(
                rawTransaction,
                table,
                rowLocation,
                snapshotSequence,
                projection,
                context,
                MvccRawStoreRowDirectory.find(rawTransaction, rowLocation, directoryContainer),
                directoryContainer,
                versionReader);
    }

    static VisibleRow readVisibleAtResolvedDirectory(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context,
            DirectoryRecord resolvedDirectory,
            ContainerHandle directoryContainer,
            MvccRawStoreVersionReader versionReader) throws StandardException {
        if (table.gen2A1()) {
            return readGen2A1CurrentFromDirectory(
                    rawTransaction,
                    table,
                    resolvedDirectory,
                    projection,
                    snapshotSequence,
                    context.transactionId(),
                    directoryContainer);
        }
        DirectoryRecord directory = resolvedDirectory;
        while (true) {
            try {
                VersionRecord version = versionReader.findVisible(
                        rowLocation.rowId(),
                        directory.head(),
                        context.transactionId(),
                        snapshotSequence,
                        projection);
                if (version == null || version.tombstone()) {
                    return null;
                }
                return new VisibleRow(
                        rowLocation.rowId(),
                        version.versionId(),
                        version.values(),
                        version.handle(),
                        MvccRawStoreRowDirectory.location(
                                rowLocation.rowId(), directory.handle()));
            } catch (MvccRawStoreVersionReader.MissingVersionException missing) {
                // A concurrent rollback can change the directory after this read
                // captured an uncommitted head and before it follows that head into
                // the version container. Retry only if the head moved.
                DirectoryRecord refreshed = MvccRawStoreRowDirectory.find(
                        rawTransaction, rowLocation, directoryContainer);
                if (refreshed.head().versionId() == directory.head().versionId()) {
                    throw missing;
                }
                directory = refreshed;
            }
        }
    }

    private static VisibleRow readVisibleAt(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context,
            boolean checkWriteVersion) throws StandardException {
        if (table.gen2A1()) {
            VisibleRow visible = readGen2A1CurrentAt(
                    rawTransaction,
                    table,
                    rowLocation,
                    projection,
                    snapshotSequence,
                    context.transactionId(),
                    null);
            if (checkWriteVersion && visible != null) {
                validateWriteVersion(rowLocation, visible.versionId());
            }
            return visible;
        }
        DirectoryRecord directory = MvccRawStoreRowDirectory.find(
                rawTransaction, table, rowLocation);
        while (true) {
            if (checkWriteVersion) {
                validateWriteVersion(rowLocation, directory.head().versionId());
            }
            try {
                VersionRecord version = MvccRawStoreVersionReader.findVisible(
                        rawTransaction,
                        table,
                        rowLocation.rowId(),
                        directory.head(),
                        context.transactionId(),
                        snapshotSequence,
                        projection);
                if (version == null || version.tombstone()) {
                    return null;
                }
                return new VisibleRow(
                        rowLocation.rowId(),
                        version.versionId(),
                        version.values(),
                        version.handle(),
                        MvccRawStoreRowDirectory.location(
                                rowLocation.rowId(), directory.handle()));
            } catch (MvccRawStoreVersionReader.MissingVersionException missing) {
                // A concurrent rollback can change the directory after this read
                // captured an uncommitted head and before it follows that head into
                // the version container. Retry only if the head moved.
                DirectoryRecord refreshed = MvccRawStoreRowDirectory.find(
                        rawTransaction, table, rowLocation);
                if (refreshed.head().versionId() == directory.head().versionId()) {
                    throw missing;
                }
                directory = refreshed;
            }
        }
    }

    static boolean replace(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            StoreDataValue[] replacement,
            FormatableBitSet validColumns,
            MvccRawStoreTransactionContext context) throws StandardException {
        return replace(
                rawTransaction,
                table,
                new MvccRowLocation(rowId),
                replacement,
                validColumns,
                context);
    }

    static boolean replace(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            StoreDataValue[] replacement,
            FormatableBitSet validColumns,
            MvccRawStoreTransactionContext context) throws StandardException {
        return replace(
                rawTransaction, table, rowLocation, replacement, validColumns, context, false);
    }

    static boolean replace(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            StoreDataValue[] replacement,
            FormatableBitSet validColumns,
            MvccRawStoreTransactionContext context,
            boolean useLockedCurrentHead) throws StandardException {
        if (table.gen2A1()) {
            if (!table.gen2History()) {
                rejectGen2A1HistoryMutation(table);
            }
            return replaceGen2C1(
                    rawTransaction, table, rowLocation, replacement, validColumns, context);
        }
        // Reserve the database-wide transaction identity before acquiring table
        // container locks. INSERT follows the same database-metadata -> table
        // ordering, which prevents an UPDATE/DELETE lock-order inversion.
        long rowId = rowLocation.rowId();
        context.beforeRowWrite(table, rowId);
        MutationTarget target = mutationTarget(
                rawTransaction, table, rowLocation, context, useLockedCurrentHead);
        if (target == null) {
            return false;
        }
        StoreDataValue[] values = StoreValueCopySupport.replacementRow(
                target.visible().values(),
                replacement,
                validColumns);
        MvccRawStoreOrderedIndex.assertUnique(
                rawTransaction,
                table,
                target.visible().values(),
                values,
                rowId,
                context);
        appendVersion(
                rawTransaction,
                table,
                rowId,
                target.directory().head(),
                target.directoryLocation(),
                target.visible(),
                MvccRawStoreFormat.LIVE_FLAGS,
                values,
                context);
        rowLocation.setWriteVersion(0L);
        return true;
    }

    private static boolean replaceGen2C1(
            Transaction transaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            StoreDataValue[] replacement,
            FormatableBitSet validColumns,
            MvccRawStoreTransactionContext context) throws StandardException {
        rejectGen2PkKeyMutation(table, validColumns);
        long rowId = rowLocation.rowId();
        context.beforeRowWrite(table, rowId);
        if (context.hasPendingVersion(table, rowId)) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2-C1 supports one mutation per logical row per transaction");
        }

        Gen2A1CurrentRecord current = findGen2CurrentAt(
                transaction, table, rowLocation, null);
        if (current == null
                || (current.flags() & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0) {
            return false;
        }
        validateWriteVersion(rowLocation, current.versionId());
        if (current.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2-C1 cannot archive an uncommitted current image");
        }

        StoreDataValue[] values = StoreValueCopySupport.replacementRow(
                current.values(),
                replacement,
                validColumns);
        long newVersionId = context.reserveVersionIdentifier(table);
        Object[] historyRow = gen2HistoryRow(transaction, table, current);
        RecordHandle historyHandle = insertRow(
                transaction, table.versionContainer(), historyRow);
        RecordHint historyHint = RecordHint.of(historyHandle);

        Object[] newCurrentRow = gen2CurrentRow(
                transaction,
                table,
                rowId,
                newVersionId,
                context.transactionId(),
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                MvccRawStoreFormat.LIVE_FLAGS,
                current.versionId(),
                historyHint,
                values);
        updateGen2Current(
                transaction,
                table,
                current,
                newCurrentRow,
                gen2C1UpdateColumns(table.columnCount(), validColumns),
                historyHandle);

        MvccRowLocation currentLocation = MvccRawStoreRowDirectory.location(
                rowId, current.handle());
        PendingVersion pending = new PendingVersion(
                table,
                rowId,
                newVersionId,
                context.transactionId(),
                current.versionId(),
                historyHint,
                MvccRawStoreFormat.LIVE_FLAGS,
                current.handle(),
                currentLocation,
                true);
        context.addPending(pending);
        rowLocation.setWriteVersion(0L);
        return true;
    }

    private static void rejectGen2PkKeyMutation(
            Descriptor table, FormatableBitSet validColumns) throws StandardException {
        if (!table.gen2PkHistory()) {
            return;
        }
        if (validColumns == null) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2 PK/history full-row replacement is deferred; "
                            + "indexed key changes require historical-key reachability");
        }
        for (UniqueConstraint constraint : table.uniqueConstraints()) {
            for (int column : constraint.columns()) {
                if (column < validColumns.getLength() && validColumns.isSet(column)) {
                    throw StandardException.newException(
                            SQLState.NOT_IMPLEMENTED,
                            "MVCC Gen2 PK/history key-changing UPDATE is deferred; "
                                    + "historical-key reachability is not yet implemented");
                }
            }
        }
    }

    private static Object[] gen2HistoryRow(
            Transaction transaction,
            Descriptor table,
            Gen2A1CurrentRecord current) throws StandardException {
        Object[] row = MvccRawStoreVersionRows.template(transaction, table, true);
        row[MvccRawStoreFormat.VERSION_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.VERSION_KIND);
        row[MvccRawStoreFormat.VERSION_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.VERSION_ROW_ID] =
                MvccRawStoreFormat.longValue(transaction, current.rowId());
        row[MvccRawStoreFormat.VERSION_ID] =
                MvccRawStoreFormat.longValue(transaction, current.versionId());
        row[MvccRawStoreFormat.VERSION_CREATOR_TRANSACTION_ID] =
                MvccRawStoreFormat.longValue(
                        transaction, current.creatorTransactionId());
        row[MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE] =
                MvccRawStoreFormat.longValue(
                        transaction, current.beginSequence());
        row[MvccRawStoreFormat.VERSION_END_SEQUENCE] =
                MvccRawStoreFormat.longValue(
                        transaction, MvccRawStoreFormat.CURRENT_END_SEQUENCE);
        row[MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID] =
                MvccRawStoreFormat.longValue(
                        transaction, current.previousVersionId());
        row[MvccRawStoreFormat.VERSION_FLAGS] =
                MvccRawStoreFormat.intValue(transaction, current.flags());
        StoreDataValue[] clone = StoreValueCopySupport.cloneRow(current.values(), true);
        System.arraycopy(
                clone,
                0,
                row,
                MvccRawStoreFormat.VERSION_PAYLOAD_START,
                clone.length);
        row[MvccRawStoreFormat.versionHintPageField(table.columnCount())] =
                MvccRawStoreFormat.longValue(
                        transaction, current.previousHint().pageNumber());
        row[MvccRawStoreFormat.versionHintRecordField(table.columnCount())] =
                MvccRawStoreFormat.intValue(
                        transaction, current.previousHint().recordId());
        return row;
    }

    private static FormatableBitSet gen2C1UpdateColumns(
            int columnCount,
            FormatableBitSet validColumns) {
        FormatableBitSet fields = new FormatableBitSet(
                MvccRawStoreFormat.gen2C1CurrentFieldCount(columnCount));
        // Retain the immutable record kind, format and logical row identity.
        // Only version/visibility metadata and the predecessor link change.
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS);
        fields.set(MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID);

        // SQL payload positions start after the C1 header in the RawStore row.
        // A null mask means full replacement; an empty mask means header only.
        // Build a new mask rather than changing the caller's SQL-column mask.
        int payloadStart = MvccRawStoreFormat.GEN2_C1_CURRENT_PAYLOAD_START;
        if (validColumns == null) {
            for (int column = 0; column < columnCount; column++) {
                fields.set(payloadStart + column);
            }
        } else {
            for (int column = validColumns.anySetBit();
                 column >= 0 && column < columnCount;
                 column = validColumns.anySetBit(column)) {
                fields.set(payloadStart + column);
            }
        }
        return fields;
    }

    private static void updateGen2Current(
            Transaction transaction,
            Descriptor table,
            Gen2A1CurrentRecord expected,
            Object[] replacement,
            FormatableBitSet updatedFields,
            RecordHandle archiveHandle) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC Gen2 current container is absent: "
                            + table.metadataContainer());
        }
        ContainerHandle archiveContainer = null;
        Page page = null;
        try {
            if (Boolean.getBoolean("delosdb.experimental.mvccGen2C2.archivedUndo.enabled")) {
                archiveContainer = transaction.openContainer(table.versionContainer(),
                        MvccRawStorePhysicalLocking.rowLevel(transaction),
                        ContainerHandle.MODE_FORUPDATE);
            }
            page = container.getPage(expected.handle().getPageNumber());
            if (page == null) {
                throw new IllegalStateException(
                        "RawStore MVCC Gen2 current page disappeared for logical row "
                                + expected.rowId());
            }
            int slot = page.getSlotNumber(expected.handle());
            Gen2A1CurrentRecord actual = decodeGen2A1Current(
                    transaction, table, page, slot);
            if (actual == null
                    || actual.rowId() != expected.rowId()
                    || actual.versionId() != expected.versionId()) {
                throw StandardException.newException(
                        SQLState.DEADLOCK,
                        "RawStore MVCC write conflict for logical row " + expected.rowId());
            }
            if (archiveContainer != null
                    && page instanceof org.apache.derby.iapi.store.raw.ArchivedUndoPage archived) {
                // Do not wait for a second latch while CURRENT is latched.
                // A miss uses ordinary logged UPDATE, never a retry loop.
                Page source = archiveContainer.getUserPageNoWait(archiveHandle.getPageNumber());
                if (source != null) {
                    try {
                        if (archived.tryUpdateWithArchive(slot, replacement, updatedFields,
                                source, source.getSlotNumber(archiveHandle))) {
                            return;
                        }
                    } finally {
                        source.unlatch();
                    }
                }
            }
            page.updateAtSlot(slot, replacement, updatedFields);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (archiveContainer != null) {
                archiveContainer.close();
            }
            container.close();
        }
    }

    static boolean delete(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            MvccRawStoreTransactionContext context) throws StandardException {
        return delete(
                rawTransaction,
                table,
                new MvccRowLocation(rowId),
                context);
    }

    static boolean delete(
            Transaction rawTransaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreTransactionContext context) throws StandardException {
        if (table.gen2A1()) {
            if (!table.gen2History()) {
                rejectGen2A1HistoryMutation(table);
            }
            if (table.gen2PkHistory()) {
                throw StandardException.newException(
                        SQLState.NOT_IMPLEMENTED,
                        "MVCC Gen2-C3 indexed DELETE requires historical SQL-index reachability");
            }
            return deleteGen2C1(rawTransaction, table, rowLocation, context);
        }
        long rowId = rowLocation.rowId();
        context.beforeRowWrite(table, rowId);
        MutationTarget target = mutationTarget(rawTransaction, table, rowLocation, context);
        if (target == null) {
            return false;
        }
        MvccRawStoreOrderedIndex.lockUniqueKeysForDelete(
                rawTransaction,
                table,
                target.visible().values(),
                context);
        PendingVersion tombstone = appendVersion(
                rawTransaction,
                table,
                rowId,
                target.directory().head(),
                target.directoryLocation(),
                target.visible(),
                MvccRawStoreFormat.TOMBSTONE_FLAGS,
                null,
                context);
        context.rememberDeletedKeyProof(
                table,
                target.visible().values(),
                tombstone);
        rowLocation.setWriteVersion(0L);
        return true;
    }

    private static boolean deleteGen2C1(
            Transaction transaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreTransactionContext context) throws StandardException {
        long rowId = rowLocation.rowId();
        context.beforeRowWrite(table, rowId);
        if (context.hasPendingVersion(table, rowId)) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2-C1 supports one mutation per logical row per transaction");
        }

        Gen2A1CurrentRecord current = findGen2CurrentAt(
                transaction, table, rowLocation, null);
        if (current == null
                || (current.flags() & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0) {
            return false;
        }
        validateWriteVersion(rowLocation, current.versionId());
        if (current.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2-C1 cannot archive an uncommitted current image");
        }

        long newVersionId = context.reserveVersionIdentifier(table);
        Object[] historyRow = gen2HistoryRow(transaction, table, current);
        RecordHandle historyHandle = insertRow(
                transaction, table.versionContainer(), historyRow);
        RecordHint historyHint = RecordHint.of(historyHandle);
        Object[] tombstoneRow = gen2CurrentRow(
                transaction,
                table,
                rowId,
                newVersionId,
                context.transactionId(),
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                MvccRawStoreFormat.TOMBSTONE_FLAGS,
                current.versionId(),
                historyHint,
                current.values());
        updateGen2Current(
                transaction,
                table,
                current,
                tombstoneRow,
                gen2C1UpdateColumns(table.columnCount(), new FormatableBitSet(0)),
                historyHandle);

        MvccRowLocation currentLocation = MvccRawStoreRowDirectory.location(
                rowId, current.handle());
        PendingVersion tombstone = new PendingVersion(
                table,
                rowId,
                newVersionId,
                context.transactionId(),
                current.versionId(),
                historyHint,
                MvccRawStoreFormat.TOMBSTONE_FLAGS,
                current.handle(),
                currentLocation,
                true);
        context.addPending(tombstone);
        context.rememberDeletedKeyProof(table, current.values(), tombstone);
        rowLocation.setWriteVersion(0L);
        return true;
    }

    static List<VisibleRow> scanVisibleAt(
            Transaction rawTransaction,
            Descriptor table,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        List<VisibleRow> rows = new ArrayList<>();
        ContainerHandle container = rawTransaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                ContainerHandle.MODE_READONLY
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            return rows;
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    DirectoryRecord directory = decodeDirectory(rawTransaction, page, slot);
                    if (directory == null) {
                        continue;
                    }
                    if (table.gen2A1()) {
                        Gen2A1CurrentRecord current = decodeGen2A1Current(
                                rawTransaction, table, page, slot, projection);
                        VisibleRow visible = visibleGen2CurrentOrHistory(
                                rawTransaction,
                                table,
                                current,
                                projection,
                                snapshotSequence,
                                context.transactionId());
                        if (visible != null) {
                            rows.add(visible);
                        }
                        continue;
                    }
                    VersionRecord version = MvccRawStoreVersionReader.findVisible(
                            rawTransaction,
                            table,
                            directory.rowId(),
                            directory.head(),
                            context.transactionId(),
                            snapshotSequence,
                            projection);
                    if (version != null && !version.tombstone()) {
                        rows.add(new VisibleRow(
                                directory.rowId(),
                                version.versionId(),
                                version.values(),
                                version.handle(),
                                MvccRawStoreRowDirectory.location(
                                        directory.rowId(), directory.handle())));
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        return rows;
    }

    static boolean pendingVersionExists(
            Transaction rawTransaction,
            PendingVersion pending,
            long creatorTransactionId) throws StandardException {
        if (pending.inlineCurrent()) {
            Gen2A1CurrentRecord current = readGen2A1CurrentByHandle(
                    rawTransaction, pending.table(), pending.handle());
            return current != null
                    && current.rowId() == pending.rowId()
                    && current.versionId() == pending.versionId()
                    && current.creatorTransactionId() == creatorTransactionId
                    && current.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE;
        }
        VersionRecord version = MvccRawStoreVersionReader.find(
                rawTransaction,
                pending.table(),
                pending.rowId(),
                pending.versionId(),
                RecordHint.of(pending.handle()),
                null);
        return version != null
                && version.creatorTransactionId() == creatorTransactionId
                && version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE;
    }

    static void stampPendingVersions(
            Transaction rawTransaction,
            List<PendingVersion> pending,
            long commitSequence) throws StandardException {
        List<PendingVersion> ordered = new ArrayList<>(pending);
        ordered.sort(Comparator
                .comparingLong((PendingVersion version) ->
                        version.table().metadataContainer().getSegmentId())
                .thenComparingLong(version ->
                        version.table().metadataContainer().getContainerId())
                .thenComparingLong(PendingVersion::versionId));
        for (PendingVersion version : ordered) {
            if (!version.inlineCurrent()) {
                updateVersionBegin(
                        rawTransaction,
                        version.table(),
                        version,
                        commitSequence);
            }
            MvccRawStoreRowDirectory.stampCommittedHead(
                    rawTransaction,
                    version,
                    commitSequence);
            if (version.previousVersionId() != MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
                updateVersionEnd(
                        rawTransaction,
                        version.table(),
                        version.rowId(),
                        version.previousVersionId(),
                        version.previousHint(),
                        commitSequence);
            }
        }
    }

    static void rebuildOrderedIndexForTransaction(
            Transaction transaction,
            Descriptor table,
            ContainerKey target,
            MvccRawStoreTransactionContext context) throws StandardException {
        MvccRawStoreOrderedIndex.rebuild(
                context.transactionManager(),
                table,
                target,
                collectIndexVersions(transaction, table, context));
    }

    static void rebuildOrderedIndexForPublication(
            Transaction transaction,
            Descriptor table,
            ContainerKey target,
            long transactionId,
            MvccRawStoreTransactionContext context) throws StandardException {
        MvccRawStoreOrderedIndex.rebuild(
                context.transactionManager(),
                table,
                target,
                collectIndexVersions(transaction, table, context));
    }

    static void rebuildOrderedIndexForMaintenance(
            TransactionManager transactionManager,
            Descriptor table,
            ContainerKey target) throws StandardException {
        Transaction transaction = transactionManager.getRawStoreXact();
        MvccRawStoreOrderedIndex.rebuild(
                transactionManager,
                table,
                target,
                collectIndexVersions(transaction, table, null));
    }

    private static List<MvccRawStoreOrderedIndex.VersionInput> collectIndexVersions(
            Transaction transaction,
            Descriptor table,
            MvccRawStoreTransactionContext context) throws StandardException {
        Map<Long, MvccRowLocation> directoryLocations = MvccRawStoreRowDirectory.locations(
                transaction, table);
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC version container is absent: " + table.versionContainer());
        }
        List<MvccRawStoreOrderedIndex.VersionInput> versions = new ArrayList<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    VersionRecord version = MvccRawStoreVersionRows.decodeAtSlot(transaction, table, page, slot);
                    if (version == null || version.tombstone()) {
                        continue;
                    }
                    if (context == null) {
                        if (version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
                            throw new IllegalStateException(
                                    "RawStore MVCC encountered an uncommitted version while rebuilding a maintenance index under the exclusive table-schema lock: "
                                            + version.versionId());
                        }
                    } else {
                        if (version.creatorTransactionId() != context.transactionId()
                                && context.isTransactionActive(version.creatorTransactionId())) {
                            continue;
                        }
                        if (version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE
                                && version.creatorTransactionId() != context.transactionId()) {
                            continue;
                        }
                    }
                    versions.add(new MvccRawStoreOrderedIndex.VersionInput(
                            version.rowId(),
                            version.versionId(),
                            MvccRawStoreRowDirectory.requireLocation(
                                    directoryLocations, version.rowId()),
                            version.values()));
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        return versions;
    }

    // The metadata container's RawStore row-count estimate is the persistent
    // logical-row estimate for the MVCC base conglomerate. The container is
    // initialized to zero after its control rows are created, so later
    // directory-row deltas track logical inserts rather than metadata rows.
    static long estimatedRowCount(
            Transaction rawTransaction, Descriptor table) throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                ContainerHandle.MODE_READONLY
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            return 0L;
        }
        try {
            return Math.max(0L, container.getEstimatedRowCount(0));
        } finally {
            container.close();
        }
    }

    // Persist scan/statistics row-count feedback so subsequently opened cost
    // controllers see it, matching the Derby heap RowCountable contract.
    static void setEstimatedRowCount(
            Transaction rawTransaction, Descriptor table, long count) throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                ContainerHandle.MODE_READONLY
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            return;
        }
        try {
            container.setEstimatedRowCount(Math.max(0L, count), 0);
        } finally {
            container.close();
        }
    }

    static void drop(
            TransactionManager transactionManager, Descriptor table) throws StandardException {
        Transaction rawTransaction = transactionManager.getRawStoreXact();
        ContainerKey orderedIndex = MvccRawStoreTableMetadata.discoverOrderedIndexContainer(
                rawTransaction, table, true);
        // Derby B-tree drop opens the base conglomerate while releasing its
        // physical index containers. Retire the ordered-index generation while
        // the MVCC metadata container is still present, then drop the version
        // and metadata containers in the established table lock order.
        if (orderedIndex != null) {
            MvccRawStoreOrderedIndexGeneration.dropGeneration(
                    transactionManager, table, orderedIndex);
        }
        rawTransaction.dropContainer(table.versionContainer());
        rawTransaction.dropContainer(table.metadataContainer());
    }

    private static void initializeMetadataContainer(Transaction rawTransaction, Descriptor descriptor)
            throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                descriptor.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                ContainerHandle.MODE_FORUPDATE
                        | (descriptor.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        Page page = null;
        try {
            page = container.getFirstPage();
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    MvccRawStoreTableMetadata.controlRow(rawTransaction, descriptor),
                    null,
                    null,
                    insertFlags(page),
                    OVERFLOW_THRESHOLD);
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER + 1,
                    allocatorRow(rawTransaction),
                    null,
                    null,
                    insertFlags(page),
                    OVERFLOW_THRESHOLD);
            container.setEstimatedRowCount(0L, 0);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static void initializeVersionContainer(Transaction rawTransaction, Descriptor descriptor)
            throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                descriptor.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                ContainerHandle.MODE_FORUPDATE
                        | (descriptor.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        Page page = null;
        try {
            page = container.getFirstPage();
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    new Object[] {
                            MvccRawStoreFormat.intValue(rawTransaction, MvccRawStoreFormat.VERSION_CONTAINER_KIND),
                            MvccRawStoreFormat.intValue(rawTransaction, MvccRawStoreFormat.FORMAT_VERSION)
                    },
                    null,
                    null,
                    insertFlags(page),
                    OVERFLOW_THRESHOLD);
            container.setEstimatedRowCount(0L, 0);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static Object[] allocatorRow(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.ALLOCATOR_KIND),
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.FORMAT_VERSION),
                MvccRawStoreFormat.longValue(transaction, 1L),
                MvccRawStoreFormat.longValue(transaction, 1L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    private static Object[] versionRow(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long previousVersionId,
            RecordHint previousHint,
            int flags,
            StoreDataValue[] values) throws StandardException {
        Object[] row = MvccRawStoreVersionRows.template(transaction, table, true);
        row[MvccRawStoreFormat.VERSION_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.VERSION_KIND);
        row[MvccRawStoreFormat.VERSION_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.VERSION_ROW_ID] = MvccRawStoreFormat.longValue(transaction, rowId);
        row[MvccRawStoreFormat.VERSION_ID] = MvccRawStoreFormat.longValue(transaction, versionId);
        row[MvccRawStoreFormat.VERSION_CREATOR_TRANSACTION_ID] = MvccRawStoreFormat.longValue(
                transaction,
                creatorTransactionId);
        row[MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE] = MvccRawStoreFormat.longValue(
                transaction,
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE);
        row[MvccRawStoreFormat.VERSION_END_SEQUENCE] = MvccRawStoreFormat.longValue(
                transaction,
                MvccRawStoreFormat.CURRENT_END_SEQUENCE);
        row[MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID] = MvccRawStoreFormat.longValue(
                transaction,
                previousVersionId);
        row[MvccRawStoreFormat.VERSION_FLAGS] = MvccRawStoreFormat.intValue(
                transaction,
                flags);
        if (values != null) {
            StoreDataValue[] clone = StoreValueCopySupport.cloneRow(values, true);
            System.arraycopy(clone, 0, row, MvccRawStoreFormat.VERSION_PAYLOAD_START, clone.length);
        }
        row[MvccRawStoreFormat.versionHintPageField(table.columnCount())] =
                MvccRawStoreFormat.longValue(transaction, previousHint.pageNumber());
        row[MvccRawStoreFormat.versionHintRecordField(table.columnCount())] =
                MvccRawStoreFormat.intValue(transaction, previousHint.recordId());
        return row;
    }

    static void validateWriteVersion(
            MvccRowLocation rowLocation,
            long currentVersion) throws StandardException {
        long writeVersion = rowLocation.getWriteVersion();
        if (writeVersion != 0L && writeVersion != currentVersion) {
            throw StandardException.newException(
                    SQLState.DEADLOCK,
                    "RawStore MVCC write conflict for logical row " + rowLocation.rowId());
        }
    }

    private static MutationTarget mutationTarget(
            Transaction transaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreTransactionContext context) throws StandardException {
        return mutationTarget(transaction, table, rowLocation, context, false);
    }

    private static MutationTarget mutationTarget(
            Transaction transaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreTransactionContext context,
            boolean useLockedCurrentHead) throws StandardException {
        long rowId = rowLocation.rowId();
        DirectoryRecord directory = MvccRawStoreRowDirectory.find(
                transaction, table, rowLocation);
        validateWriteVersion(rowLocation, directory.head().versionId());
        VersionRecord visible = useLockedCurrentHead
                ? MvccRawStoreVersionReader.find(
                        transaction,
                        table,
                        rowId,
                        directory.head().versionId(),
                        directory.head().hint(),
                        null)
                : MvccRawStoreVersionReader.findVisible(
                        transaction,
                        table,
                        rowId,
                        directory.head(),
                        null,
                        context);
        if (visible == null || visible.tombstone()) {
            return null;
        }
        if (visible.versionId() != directory.head().versionId()) {
            throw StandardException.newException(
                    SQLState.DEADLOCK,
                    "RawStore MVCC write conflict for logical row " + rowId);
        }
        return new MutationTarget(
                directory,
                MvccRawStoreRowDirectory.location(rowId, directory.handle()),
                visible);
    }

    private static PendingVersion appendVersion(
            Transaction transaction,
            Descriptor table,
            long rowId,
            DirectoryHead expectedHead,
            MvccRowLocation directoryLocation,
            VersionRecord previousVersion,
            int flags,
            StoreDataValue[] values,
            MvccRawStoreTransactionContext context) throws StandardException {
        long versionId = context.reserveVersionIdentifier(table);
        Object[] versionRow = versionRow(
                transaction,
                table,
                rowId,
                versionId,
                context.transactionId(),
                previousVersion.versionId(),
                RecordHint.of(previousVersion.handle()),
                flags,
                values);
        RecordHandle versionHandle = insertRow(transaction, table.versionContainer(), versionRow);
        MvccRawStoreRowDirectory.updateHead(
                transaction,
                table,
                rowId,
                expectedHead,
                directoryLocation,
                versionId,
                RecordHint.of(versionHandle),
                context.transactionId(),
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                flags);
        MvccRawStoreOrderedIndex.insertVersion(
                context.transactionManager(),
                table,
                rowId,
                versionId,
                directoryLocation,
                previousVersion.values(),
                values,
                context);
        PendingVersion pending = new PendingVersion(
                table,
                rowId,
                versionId,
                context.transactionId(),
                previousVersion.versionId(),
                RecordHint.of(previousVersion.handle()),
                flags,
                versionHandle,
                directoryLocation,
                false);
        context.addPending(pending);
        return pending;
    }

    static AllocatorHighWater readAllocatorHighWater(
            Transaction transaction,
            Descriptor table) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            Object[] allocator = allocatorTemplate(transaction);
            page.fetchFromSlot(
                    null,
                    Page.FIRST_SLOT_NUMBER + 1,
                    allocator,
                    null,
                    false);
            long nextRowId = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID);
            long nextVersionId = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID);
            validateAllocator(nextRowId, nextVersionId);
            return new AllocatorHighWater(nextRowId, nextVersionId);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static AllocatorHighWater reconstructGen2A1AllocatorHighWater(
            Transaction transaction,
            Descriptor table) throws StandardException {
        if (!table.gen2A1()) {
            throw new IllegalArgumentException(
                    "allocator reconstruction is Gen2-A1 only");
        }

        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: "
                            + table.metadataContainer());
        }

        long nextRowId = 1L;
        long nextVersionId = 1L;
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    Gen2A1CurrentRecord current = decodeGen2A1Current(
                            transaction, table, page, slot);
                    if (current == null) {
                        continue;
                    }
                    if (current.rowId() == Long.MAX_VALUE
                            || current.versionId() == Long.MAX_VALUE) {
                        throw new IllegalStateException(
                                "RawStore MVCC Gen2-A1 row/version identity is exhausted");
                    }
                    nextRowId = Math.max(nextRowId, current.rowId() + 1L);
                    nextVersionId = Math.max(nextVersionId, current.versionId() + 1L);
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return new AllocatorHighWater(nextRowId, nextVersionId);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static void stageAllocatorHighWater(
            Transaction transaction,
            Descriptor table,
            long nextRowId,
            long nextVersionId) throws StandardException {
        validateAllocator(nextRowId, nextVersionId);
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            int slot = Page.FIRST_SLOT_NUMBER + 1;
            Object[] allocator = allocatorTemplate(transaction);
            page.fetchFromSlot(null, slot, allocator, null, false);
            long persistedRow = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID);
            long persistedVersion = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID);
            if (nextRowId > persistedRow) {
                page.updateFieldAtSlot(
                        slot,
                        MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID,
                        MvccRawStoreFormat.longValue(transaction, nextRowId),
                        null);
            }
            if (nextVersionId > persistedVersion) {
                page.updateFieldAtSlot(
                        slot,
                        MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID,
                        MvccRawStoreFormat.longValue(transaction, nextVersionId),
                        null);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void validateAllocator(long nextRowId, long nextVersionId) {
        if (nextRowId <= 0L || nextVersionId <= 0L
                || nextRowId == Long.MAX_VALUE || nextVersionId == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "RawStore MVCC logical identity allocator is invalid or exhausted: row="
                            + nextRowId + ", version=" + nextVersionId);
        }
    }

    private static RecordHandle insertRow(
            Transaction transaction,
            ContainerKey key,
            Object[] row) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC insert container is absent: " + key);
        }
        Page page = null;
        try {
            // RawStore tracks the last inserted and relatively unfilled pages
            // for every container. Use that canonical insertion path instead
            // of walking from the first page for every version or directory
            // append. The old scan made mutation latency grow with the total
            // number of pages in the table.
            page = container.getPageForInsert(0);
            RecordHandle handle = insertOnCandidatePage(page, row);
            if (handle != null) {
                return handle;
            }
            if (page != null) {
                page.unlatch();
                page = null;
            }

            page = container.getPageForInsert(ContainerHandle.GET_PAGE_UNFILLED);
            handle = insertOnCandidatePage(page, row);
            if (handle != null) {
                return handle;
            }
            if (page != null) {
                page.unlatch();
                page = null;
            }

            page = container.addPage();
            handle = page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    row,
                    null,
                    null,
                    insertFlags(page),
                    OVERFLOW_THRESHOLD);
            if (handle == null) {
                throw new IllegalStateException("RawStore MVCC row did not fit on an empty page");
            }
            return handle;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static RecordHandle insertOnCandidatePage(
            Page page,
            Object[] row) throws StandardException {
        if (page == null) {
            return null;
        }
        return page.insertAtSlot(
                page.recordCount(),
                row,
                (FormatableBitSet) null,
                null,
                insertFlags(page),
                OVERFLOW_THRESHOLD);
    }

    private static void updateVersionBegin(
            Transaction transaction,
            Descriptor table,
            PendingVersion pending,
            long commitSequence) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            RecordHandle handle = pending.handle();
            page = container.getPage(handle.getPageNumber());
            if (page != null) {
                int slot = page.getSlotNumber(handle);
                if (slot >= Page.FIRST_SLOT_NUMBER && !page.isDeletedAtSlot(slot)) {
                    StoreDataValue storedKind = MvccRawStoreFormat.intValue(transaction, 0);
                    StoreDataValue storedRowId = MvccRawStoreFormat.longValue(transaction, 0L);
                    StoreDataValue storedVersionId = MvccRawStoreFormat.longValue(transaction, 0L);
                    page.fetchFieldFromSlot(
                            slot,
                            MvccRawStoreFormat.VERSION_KIND_FIELD,
                            storedKind);
                    page.fetchFieldFromSlot(
                            slot,
                            MvccRawStoreFormat.VERSION_ROW_ID,
                            storedRowId);
                    page.fetchFieldFromSlot(
                            slot,
                            MvccRawStoreFormat.VERSION_ID,
                            storedVersionId);
                    if (StoreTypeUtil.getLong(storedKind) == MvccRawStoreFormat.VERSION_KIND
                            && StoreTypeUtil.getLong(storedRowId) == pending.rowId()
                            && StoreTypeUtil.getLong(storedVersionId) == pending.versionId()) {
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE,
                                MvccRawStoreFormat.longValue(transaction, commitSequence),
                                null);
                        return;
                    }
                }
                page.unlatch();
                page = null;
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        updateVersionBeginByLogicalId(
                transaction,
                table,
                pending.rowId(),
                pending.versionId(),
                commitSequence);
    }

    private static void updateVersionBeginByLogicalId(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            long commitSequence) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    StoreDataValue candidateRow = MvccRawStoreFormat.longValue(transaction, 0L);
                    StoreDataValue candidateVersion = MvccRawStoreFormat.longValue(transaction, 0L);
                    page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ROW_ID, candidateRow);
                    page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ID, candidateVersion);
                    if (StoreTypeUtil.getLong(candidateRow) == rowId
                            && StoreTypeUtil.getLong(candidateVersion) == versionId) {
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE,
                                MvccRawStoreFormat.longValue(transaction, commitSequence),
                                null);
                        return;
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        throw new IllegalStateException("RawStore MVCC version disappeared before commit: " + versionId);
    }

    private static void updateVersionEnd(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            RecordHint hint,
            long commitSequence) throws StandardException {
        if (updateVersionEndByHint(
                transaction,
                table,
                rowId,
                versionId,
                hint,
                commitSequence)) {
            return;
        }

        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    StoreDataValue candidateRow = MvccRawStoreFormat.longValue(transaction, 0L);
                    StoreDataValue candidateVersion = MvccRawStoreFormat.longValue(transaction, 0L);
                    page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ROW_ID, candidateRow);
                    page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ID, candidateVersion);
                    if (StoreTypeUtil.getLong(candidateRow) == rowId
                            && StoreTypeUtil.getLong(candidateVersion) == versionId) {
                        stampVersionEndAtSlot(transaction, page, slot, versionId, commitSequence);
                        return;
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        throw new IllegalStateException(
                "RawStore MVCC predecessor version disappeared before commit: " + versionId);
    }

    private static boolean updateVersionEndByHint(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            RecordHint hint,
            long commitSequence) throws StandardException {
        if (!hint.valid()) {
            return false;
        }
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            return false;
        }
        Page page = null;
        try {
            page = container.getPage(hint.pageNumber());
            if (page == null) {
                return false;
            }
            RecordHandle handle = page.getRecordHandle(hint.recordId());
            if (handle == null) {
                return false;
            }
            int slot = page.getSlotNumber(handle);
            if (page.isDeletedAtSlot(slot)) {
                return false;
            }
            StoreDataValue candidateKind = MvccRawStoreFormat.intValue(transaction, 0);
            StoreDataValue candidateRow = MvccRawStoreFormat.longValue(transaction, 0L);
            StoreDataValue candidateVersion = MvccRawStoreFormat.longValue(transaction, 0L);
            page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_KIND_FIELD, candidateKind);
            page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ROW_ID, candidateRow);
            page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ID, candidateVersion);
            if (StoreTypeUtil.getLong(candidateKind) != MvccRawStoreFormat.VERSION_KIND
                    || StoreTypeUtil.getLong(candidateRow) != rowId
                    || StoreTypeUtil.getLong(candidateVersion) != versionId) {
                return false;
            }
            stampVersionEndAtSlot(transaction, page, slot, versionId, commitSequence);
            return true;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void stampVersionEndAtSlot(
            Transaction transaction,
            Page page,
            int slot,
            long versionId,
            long commitSequence) throws StandardException {
        StoreDataValue currentEnd = MvccRawStoreFormat.longValue(transaction, 0L);
        page.fetchFieldFromSlot(
                slot,
                MvccRawStoreFormat.VERSION_END_SEQUENCE,
                currentEnd);
        if (StoreTypeUtil.getLong(currentEnd) != MvccRawStoreFormat.CURRENT_END_SEQUENCE) {
            throw new IllegalStateException(
                    "RawStore MVCC predecessor already has an end sequence: " + versionId);
        }
        page.updateFieldAtSlot(
                slot,
                MvccRawStoreFormat.VERSION_END_SEQUENCE,
                MvccRawStoreFormat.longValue(transaction, commitSequence),
                null);
    }

    static DirectoryRecord decodeDirectory(
            Transaction transaction,
            Page page,
            int slot) throws StandardException {
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        boolean gen2A1Current = fieldCount > MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT;
        if (fieldCount != MvccRawStoreFormat.DIRECTORY_BASE_FIELD_COUNT
                && fieldCount != MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT
                && fieldCount != MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT
                && !gen2A1Current) {
            throw new IllegalStateException(
                    "RawStore MVCC directory row has unsupported field count: " + fieldCount);
        }
        boolean hasHint = fieldCount >= MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT;
        boolean hasSummary = fieldCount >= MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT;
        int directoryFieldCount = gen2A1Current
                ? MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT
                : fieldCount;
        Object[] row = directoryTemplate(transaction, directoryFieldCount);
        RecordHandle handle = page.fetchFromSlot(null, slot, row, null, false);
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.DIRECTORY_KIND_FIELD)
                != MvccRawStoreFormat.DIRECTORY_KIND) {
            return null;
        }
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION)
                != MvccRawStoreFormat.FORMAT_VERSION) {
            throw new IllegalStateException("RawStore MVCC directory row format is unsupported");
        }
        RecordHint hint = hasHint
                ? new RecordHint(
                        MvccRawStoreFormat.longAt(
                                row,
                                MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE),
                        MvccRawStoreFormat.intAt(
                                row,
                                MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD))
                : RecordHint.NONE;
        DirectoryHeadSummary summary = hasSummary
                ? new DirectoryHeadSummary(
                        true,
                        MvccRawStoreFormat.longAt(
                                row,
                                MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID),
                        MvccRawStoreFormat.longAt(
                                row,
                                MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE),
                        MvccRawStoreFormat.intAt(
                                row,
                                MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS))
                : DirectoryHeadSummary.NONE;
        return new DirectoryRecord(
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.DIRECTORY_ROW_ID),
                new DirectoryHead(
                        MvccRawStoreFormat.longAt(
                                row,
                                MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID),
                        hint,
                        summary),
                handle);
    }

    private static Object[] gen2A1CurrentRow(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            StoreDataValue[] values) throws StandardException {
        return gen2CurrentRow(
                transaction,
                table,
                rowId,
                versionId,
                creatorTransactionId,
                beginSequence,
                flags,
                MvccRawStoreFormat.NO_PREVIOUS_VERSION,
                RecordHint.NONE,
                values);
    }

    private static Object[] gen2CurrentRow(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            long previousVersionId,
            RecordHint previousHint,
            StoreDataValue[] values) throws StandardException {
        int fieldCount = table.gen2History()
                ? MvccRawStoreFormat.gen2C1CurrentFieldCount(table.columnCount())
                : MvccRawStoreFormat.gen2A1CurrentFieldCount(table.columnCount());
        int payloadStart = table.gen2History()
                ? MvccRawStoreFormat.GEN2_C1_CURRENT_PAYLOAD_START
                : MvccRawStoreFormat.GEN2_A1_CURRENT_PAYLOAD_START;
        Object[] row = new Object[fieldCount];
        Object[] head = directoryRow(
                transaction,
                rowId,
                versionId,
                table.gen2History() ? previousHint : RecordHint.NONE,
                creatorTransactionId,
                beginSequence,
                flags);
        System.arraycopy(head, 0, row, 0, head.length);
        if (table.gen2History()) {
            row[MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID] =
                    MvccRawStoreFormat.longValue(transaction, previousVersionId);
        }
        StoreDataValue[] copy = StoreValueCopySupport.cloneRow(values);
        System.arraycopy(copy, 0, row, payloadStart, copy.length);
        return row;
    }

    private static Object[] gen2A1CurrentTemplate(
            Transaction transaction, Descriptor table) throws StandardException {
        return gen2A1CurrentTemplate(transaction, table, null);
    }

    private static Object[] gen2A1CurrentTemplate(
            Transaction transaction,
            Descriptor table,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        int fieldCount = table.gen2History()
                ? MvccRawStoreFormat.gen2C1CurrentFieldCount(table.columnCount())
                : MvccRawStoreFormat.gen2A1CurrentFieldCount(table.columnCount());
        int payloadStart = table.gen2History()
                ? MvccRawStoreFormat.GEN2_C1_CURRENT_PAYLOAD_START
                : MvccRawStoreFormat.GEN2_A1_CURRENT_PAYLOAD_START;
        Object[] row = new Object[fieldCount];
        Object[] head = directoryTemplate(
                transaction, MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT);
        System.arraycopy(head, 0, row, 0, head.length);
        if (table.gen2History()) {
            row[MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID] =
                    MvccRawStoreFormat.longValue(transaction, 0L);
        }
        for (int index = 0; index < table.columnCount(); index++) {
            if (projection == null || projection.includes(index)) {
                row[payloadStart + index] =
                        MvccRawStoreFormat.nullValue(
                                transaction, table.formatId(index), table.collationId(index));
            }
        }
        return row;
    }

    private static Gen2A1CurrentRecord decodeGen2A1Current(
            Transaction transaction,
            Descriptor table,
            Page page,
            int slot) throws StandardException {
        return decodeGen2A1Current(transaction, table, page, slot, null);
    }

    private static Gen2A1CurrentRecord decodeGen2A1Current(
            Transaction transaction,
            Descriptor table,
            Page page,
            int slot,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        int expected = table.gen2History()
                ? MvccRawStoreFormat.gen2C1CurrentFieldCount(table.columnCount())
                : MvccRawStoreFormat.gen2A1CurrentFieldCount(table.columnCount());
        if (page.fetchNumFieldsAtSlot(slot) != expected) {
            return null;
        }
        MvccRawStoreVersionRows.FetchProjection currentProjection =
                table.projectedCurrentRead() ? projection : null;
        Object[] row = gen2A1CurrentTemplate(transaction, table, currentProjection);
        FetchDescriptor descriptor = currentProjection == null
                ? null
                : currentProjection.currentDescriptor(table);
        RecordHandle handle = page.fetchFromSlot(null, slot, row, descriptor, false);
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.DIRECTORY_KIND_FIELD)
                != MvccRawStoreFormat.DIRECTORY_KIND
                || MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION)
                != MvccRawStoreFormat.FORMAT_VERSION) {
            return null;
        }
        int payloadStart = table.gen2History()
                ? MvccRawStoreFormat.GEN2_C1_CURRENT_PAYLOAD_START
                : MvccRawStoreFormat.GEN2_A1_CURRENT_PAYLOAD_START;
        StoreDataValue[] values = currentProjection != null && !currentProjection.includesPayload()
                ? null
                : new StoreDataValue[table.columnCount()];
        if (values != null) {
            for (int index = 0; index < values.length; index++) {
                if ((currentProjection == null || currentProjection.includes(index))
                        && row[payloadStart + index] != null) {
                    values[index] = StoreValueCopySupport.cloneValue(
                            (StoreDataValue) row[payloadStart + index], true);
                }
            }
        }
        long previousVersionId = table.gen2History()
                ? MvccRawStoreFormat.longAt(
                        row, MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID)
                : MvccRawStoreFormat.NO_PREVIOUS_VERSION;
        RecordHint previousHint = table.gen2History()
                ? new RecordHint(
                        MvccRawStoreFormat.longAt(
                                row, MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE),
                        MvccRawStoreFormat.intAt(
                                row, MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD))
                : RecordHint.NONE;
        return new Gen2A1CurrentRecord(
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.DIRECTORY_ROW_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID),
                MvccRawStoreFormat.longAt(
                        row, MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID),
                MvccRawStoreFormat.longAt(
                        row, MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE),
                MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS),
                previousVersionId,
                previousHint,
                values,
                handle);
    }

    private static Gen2A1CurrentRecord readGen2A1CurrentByHandle(
            Transaction transaction, Descriptor table, RecordHandle handle)
            throws StandardException {
        if (handle == null) {
            return null;
        }
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        Page page = null;
        try {
            page = container == null ? null : container.getPage(handle.getPageNumber());
            if (page == null) {
                return null;
            }
            int slot;
            try {
                slot = page.getSlotNumber(handle);
            } catch (StandardException failure) {
                if (SQLState.RAWSTORE_RECORD_VANISHED.equals(failure.getMessageId())) {
                    return null;
                }
                throw failure;
            }
            return decodeGen2A1Current(transaction, table, page, slot);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static Gen2A1CurrentRecord findGen2CurrentAt(
            Transaction transaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            ContainerHandle suppliedContainer) throws StandardException {
        return findGen2CurrentAt(transaction, table, rowLocation, suppliedContainer, null);
    }

    private static Gen2A1CurrentRecord findGen2CurrentAt(
            Transaction transaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            ContainerHandle suppliedContainer,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        ContainerHandle container = suppliedContainer;
        boolean closeContainer = false;
        if (container == null) {
            container = transaction.openContainer(
                    table.metadataContainer(),
                    MvccRawStorePhysicalLocking.rowLevel(transaction),
                    ContainerHandle.MODE_READONLY);
            closeContainer = true;
        }
        Page page = null;
        try {
            if (container == null || rowLocation == null) {
                return null;
            }
            if (rowLocation.hasLocatorHint()) {
                page = container.getPage(rowLocation.locatorPageId());
                if (page != null) {
                    Gen2A1CurrentRecord hinted = decodeGen2A1Current(
                            transaction, table, page, rowLocation.locatorSlotId(), projection);
                    if (hinted != null && hinted.rowId() == rowLocation.rowId()) {
                        return hinted;
                    }
                    page.unlatch();
                    page = null;
                }
            }
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    Gen2A1CurrentRecord current = decodeGen2A1Current(
                            transaction, table, page, slot, projection);
                    if (current != null && current.rowId() == rowLocation.rowId()) {
                        return current;
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return null;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (closeContainer && container != null) {
                container.close();
            }
        }
    }

    private static VisibleRow readGen2A1CurrentAt(
            Transaction transaction,
            Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreVersionRows.FetchProjection projection,
            long snapshotSequence,
            long transactionId,
            ContainerHandle suppliedContainer) throws StandardException {
        Gen2A1CurrentRecord current = findGen2CurrentAt(
                transaction, table, rowLocation, suppliedContainer, projection);
        return visibleGen2CurrentOrHistory(
                transaction, table, current, projection, snapshotSequence, transactionId);
    }

    private static VisibleRow readGen2A1CurrentFromDirectory(
            Transaction transaction,
            Descriptor table,
            DirectoryRecord directory,
            MvccRawStoreVersionRows.FetchProjection projection,
            long snapshotSequence,
            long transactionId,
            ContainerHandle suppliedContainer) throws StandardException {
        if (directory == null || directory.handle() == null) {
            return null;
        }
        MvccRowLocation location = MvccRawStoreRowDirectory.location(
                directory.rowId(), directory.handle());
        return readGen2A1CurrentAt(
                transaction,
                table,
                location,
                projection,
                snapshotSequence,
                transactionId,
                suppliedContainer);
    }

    private static VisibleRow visibleGen2CurrentOrHistory(
            Transaction transaction,
            Descriptor table,
            Gen2A1CurrentRecord current,
            MvccRawStoreVersionRows.FetchProjection projection,
            long snapshotSequence,
            long transactionId) throws StandardException {
        if (current == null) {
            return null;
        }
        if (currentVisibleTo(
                current.creatorTransactionId(),
                current.beginSequence(),
                transactionId,
                snapshotSequence)) {
            if ((current.flags() & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0) {
                return null;
            }
            return new VisibleRow(
                    current.rowId(),
                    current.versionId(),
                    current.values(),
                    current.handle(),
                    MvccRawStoreRowDirectory.location(current.rowId(), current.handle()));
        }
        if (!table.gen2History()
                || current.previousVersionId() == MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
            return null;
        }
        VersionRecord history = MvccRawStoreVersionReader.findVisible(
                transaction,
                table,
                current.rowId(),
                new DirectoryHead(current.previousVersionId(), current.previousHint()),
                transactionId,
                snapshotSequence,
                projection);
        if (history == null || history.tombstone()) {
            return null;
        }
        return new VisibleRow(
                current.rowId(),
                history.versionId(),
                history.values(),
                history.handle(),
                MvccRawStoreRowDirectory.location(current.rowId(), current.handle()));
    }

    private static boolean currentVisibleTo(
            long creatorTransactionId,
            long beginSequence,
            long transactionId,
            long snapshotSequence) {
        if (beginSequence == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            return creatorTransactionId != 0L && creatorTransactionId == transactionId;
        }
        return beginSequence > 0L && beginSequence <= snapshotSequence;
    }

    private static void rejectGen2A1HistoryMutation(Descriptor table) throws StandardException {
        if (table.gen2A1()) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "MVCC Gen2-A1 UPDATE/DELETE/history is deferred to the next slice");
        }
    }

    private record Gen2A1CurrentRecord(
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            long previousVersionId,
            RecordHint previousHint,
            StoreDataValue[] values,
            RecordHandle handle) {
    }

    private static Object[] allocatorTemplate(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    static Object[] directoryRow(
            Transaction transaction,
            long rowId,
            long headVersionId,
            RecordHint headHint,
            long creatorTransactionId,
            long beginSequence,
            int flags) throws StandardException {
        Object[] row = directoryTemplate(
                transaction,
                MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT);
        row[MvccRawStoreFormat.DIRECTORY_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.DIRECTORY_KIND);
        row[MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.DIRECTORY_ROW_ID] = MvccRawStoreFormat.longValue(transaction, rowId);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID] =
                MvccRawStoreFormat.longValue(transaction, headVersionId);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE] =
                MvccRawStoreFormat.longValue(transaction, headHint.pageNumber());
        row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD] =
                MvccRawStoreFormat.intValue(transaction, headHint.recordId());
        row[MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID] =
                MvccRawStoreFormat.longValue(transaction, creatorTransactionId);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE] =
                MvccRawStoreFormat.longValue(transaction, beginSequence);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS] =
                MvccRawStoreFormat.intValue(transaction, flags);
        return row;
    }

    private static Object[] directoryTemplate(
            Transaction transaction,
            int fieldCount) throws StandardException {
        Object[] row = new Object[fieldCount];
        row[MvccRawStoreFormat.DIRECTORY_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.DIRECTORY_ROW_ID] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID] =
                MvccRawStoreFormat.longValue(transaction, 0L);
        if (fieldCount >= MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT) {
            row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE] =
                    MvccRawStoreFormat.longValue(transaction, 0L);
            row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD] =
                    MvccRawStoreFormat.intValue(transaction, 0);
        }
        if (fieldCount == MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT) {
            row[MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID] =
                    MvccRawStoreFormat.longValue(transaction, 0L);
            row[MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE] =
                    MvccRawStoreFormat.longValue(transaction, 0L);
            row[MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS] =
                    MvccRawStoreFormat.intValue(transaction, 0);
        }
        return row;
    }

    record Allocation(long rowId, long versionId) {
    }

    record AllocatorHighWater(long nextRowId, long nextVersionId) {
    }

    record RecordHint(long pageNumber, int recordId) {
        static final RecordHint NONE = new RecordHint(0L, 0);

        static RecordHint of(RecordHandle handle) {
            return handle == null
                    ? NONE
                    : new RecordHint(handle.getPageNumber(), handle.getId());
        }

        boolean valid() {
            return pageNumber >= ContainerHandle.FIRST_PAGE_NUMBER
                    && recordId >= RecordHandle.FIRST_RECORD_ID;
        }
    }

    record DirectoryHeadSummary(
            boolean available,
            long creatorTransactionId,
            long beginSequence,
            int flags) {
        static final DirectoryHeadSummary NONE =
                new DirectoryHeadSummary(false, 0L, 0L, MvccRawStoreFormat.LIVE_FLAGS);

        boolean visibleTo(long transactionId, long snapshotSequence) {
            if (!available) {
                return false;
            }
            if (beginSequence == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
                return creatorTransactionId == transactionId;
            }
            return beginSequence <= snapshotSequence;
        }

        boolean tombstone() {
            return (flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0;
        }
    }

    record DirectoryHead(
            long versionId,
            RecordHint hint,
            DirectoryHeadSummary summary) {
        static final DirectoryHead NONE = new DirectoryHead(
                MvccRawStoreFormat.NO_PREVIOUS_VERSION,
                RecordHint.NONE,
                DirectoryHeadSummary.NONE);

        DirectoryHead(long versionId, RecordHint hint) {
            this(versionId, hint, DirectoryHeadSummary.NONE);
        }
    }

    record DirectoryRecord(long rowId, DirectoryHead head, RecordHandle handle) {
    }

    private record MutationTarget(
            DirectoryRecord directory,
            MvccRowLocation directoryLocation,
            VersionRecord visible) {
    }

    record VersionRecord(
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            long endSequence,
            long previousVersionId,
            RecordHint previousHint,
            int flags,
            StoreDataValue[] values,
            RecordHandle handle) {
        boolean tombstone() {
            return (flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0;
        }
    }
}
