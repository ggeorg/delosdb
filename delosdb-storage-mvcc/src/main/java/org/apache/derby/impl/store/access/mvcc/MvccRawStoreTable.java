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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodConglomerateProperties;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
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
        private volatile long accessConglomerateId;
        private volatile List<UniqueConstraint> uniqueConstraints;
        private volatile ContainerKey orderedIndexContainer;

        Descriptor(
                ContainerKey metadataContainer,
                ContainerKey versionContainer,
                ContainerKey orderedIndexContainer,
                int[] formatIds,
                int[] collationIds,
                boolean temporary,
                List<UniqueConstraint> uniqueConstraints) {
            this.metadataContainer = java.util.Objects.requireNonNull(
                    metadataContainer, "metadataContainer");
            this.versionContainer = java.util.Objects.requireNonNull(
                    versionContainer, "versionContainer");
            this.orderedIndexContainer = orderedIndexContainer;
            this.formatIds = formatIds.clone();
            this.collationIds = collationIds.clone();
            this.temporary = temporary;
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
            orderedIndexContainer = container;
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

    record VisibleRow(long rowId, long versionId, StoreDataValue[] values, RecordHandle versionHandle) {
    }

    record PendingVersion(
            Descriptor table,
            long rowId,
            long versionId,
            long previousVersionId,
            RecordHint previousHint,
            int flags,
            RecordHandle handle) {
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
        long orderedIndexId = rawTransaction.addContainer(
                segment,
                0L,
                ContainerHandle.MODE_DEFAULT,
                properties,
                temporaryFlag);
        if (orderedIndexId < 0L) {
            throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
        }

        Descriptor descriptor = new Descriptor(
                new ContainerKey(segment, metadataId),
                new ContainerKey(segment, versionId),
                new ContainerKey(segment, orderedIndexId),
                formatIds,
                collationIds,
                (temporaryFlag & TransactionController.IS_TEMPORARY) == TransactionController.IS_TEMPORARY,
                uniqueConstraints);
        initializeMetadataContainer(rawTransaction, descriptor);
        initializeVersionContainer(rawTransaction, descriptor);
        MvccRawStoreOrderedIndexGeneration.initializeDirectory(
                rawTransaction, descriptor, descriptor.orderedIndexContainer());
        return descriptor;
    }

    static java.util.Optional<List<Long>> orderedIndexRowIdsForAt(
            Descriptor table,
            org.apache.derby.iapi.store.access.Qualifier[][] qualifiers,
            MvccRawStoreTransactionContext context) throws StandardException {
        ContainerKey orderedIndex = context.orderedIndexForRead(table);
        return MvccRawStoreOrderedIndex.rowIdsForAt(
                context.transactionManager(),
                table,
                orderedIndex,
                qualifiers);
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
                    VersionRecord version = decodeVersionAtSlot(transaction, table, page, slot);
                    if (version == null || version.tombstone()) {
                        continue;
                    }
                    versions.add(new MvccRawStoreOrderedIndex.VersionInput(
                            version.rowId(),
                            version.versionId(),
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
        MvccRawStoreOrderedIndex.assertUnique(
                rawTransaction,
                table,
                null,
                values,
                0L,
                context);
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
                RecordHint.of(versionHandle));
        insertRow(rawTransaction, table.metadataContainer(), directoryRow);
        ContainerKey orderedIndex = context.orderedIndexForWrite(table);
        MvccRawStoreOrderedIndex.insertVersion(
                context.transactionManager(),
                table,
                orderedIndex,
                allocation.rowId(),
                allocation.versionId(),
                values);
        PendingVersion pending = new PendingVersion(
                table,
                allocation.rowId(),
                allocation.versionId(),
                MvccRawStoreFormat.NO_PREVIOUS_VERSION,
                RecordHint.NONE,
                MvccRawStoreFormat.LIVE_FLAGS,
                versionHandle);
        context.addPending(pending);
        if (destination != null) {
            destination.set(
                    allocation.rowId(),
                    versionHandle.getPageNumber(),
                    versionHandle.getSlotNumberHint());
        }
        return pending;
    }

    static VisibleRow readVisible(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            MvccRawStoreTransactionContext context) throws StandardException {
        DirectoryHead head = findHead(rawTransaction, table, rowId);
        VersionRecord version = findVisibleVersion(
                rawTransaction,
                table,
                rowId,
                head,
                context);
        if (version == null || version.tombstone()) {
            return null;
        }
        return new VisibleRow(rowId, version.versionId(), version.values(), version.handle());
    }

    static VisibleRow readVisibleAt(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            long snapshotSequence,
            MvccRawStoreTransactionContext context) throws StandardException {
        DirectoryHead head = findHead(rawTransaction, table, rowId);
        VersionRecord version = findVisibleVersion(
                rawTransaction,
                table,
                rowId,
                head,
                context.transactionId(),
                snapshotSequence,
                context);
        if (version == null || version.tombstone()) {
            return null;
        }
        return new VisibleRow(rowId, version.versionId(), version.values(), version.handle());
    }

    static boolean replace(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            StoreDataValue[] replacement,
            FormatableBitSet validColumns,
            MvccRawStoreTransactionContext context) throws StandardException {
        // Reserve the database-wide transaction identity before acquiring table
        // container locks. INSERT follows the same database-metadata -> table
        // ordering, which prevents an UPDATE/DELETE lock-order inversion.
        context.beforeRowWrite(table, rowId);
        MutationTarget target = mutationTarget(rawTransaction, table, rowId, context);
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
                target.head(),
                target.visible(),
                MvccRawStoreFormat.LIVE_FLAGS,
                values,
                context);
        return true;
    }

    static boolean delete(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            MvccRawStoreTransactionContext context) throws StandardException {
        context.beforeRowWrite(table, rowId);
        MutationTarget target = mutationTarget(rawTransaction, table, rowId, context);
        if (target == null) {
            return false;
        }
        MvccRawStoreOrderedIndex.lockUniqueKeysForDelete(
                rawTransaction,
                table,
                target.visible().values(),
                context);
        appendVersion(
                rawTransaction,
                table,
                rowId,
                target.head(),
                target.visible(),
                MvccRawStoreFormat.TOMBSTONE_FLAGS,
                null,
                context);
        return true;
    }

    static List<VisibleRow> scanVisibleAt(
            Transaction rawTransaction,
            Descriptor table,
            long snapshotSequence,
            MvccRawStoreTransactionContext context) throws StandardException {
        List<VisibleRow> rows = new ArrayList<>();
        ContainerHandle container = rawTransaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                ContainerHandle.MODE_READONLY);
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
                    VersionRecord version = findVisibleVersion(
                            rawTransaction,
                            table,
                            directory.rowId(),
                            directory.head(),
                            context.transactionId(),
                            snapshotSequence,
                            context);
                    if (version != null && !version.tombstone()) {
                        rows.add(new VisibleRow(
                                directory.rowId(),
                                version.versionId(),
                                version.values(),
                                version.handle()));
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
        VersionRecord version = findVersion(
                rawTransaction,
                pending.table(),
                pending.rowId(),
                pending.versionId(),
                RecordHint.of(pending.handle()));
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
            updateVersionBegin(
                    rawTransaction,
                    version.table(),
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
                    VersionRecord version = decodeVersionAtSlot(transaction, table, page, slot);
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
        Object[] row = versionTemplate(transaction, table, true);
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

    private static MutationTarget mutationTarget(
            Transaction transaction,
            Descriptor table,
            long rowId,
            MvccRawStoreTransactionContext context) throws StandardException {
        DirectoryHead head = findHead(transaction, table, rowId);
        VersionRecord visible = findVisibleVersion(
                transaction,
                table,
                rowId,
                head,
                context);
        if (visible == null || visible.tombstone()) {
            return null;
        }
        if (visible.versionId() != head.versionId()) {
            throw StandardException.newException(
                    SQLState.DEADLOCK,
                    "RawStore MVCC write conflict for logical row " + rowId);
        }
        return new MutationTarget(head, visible);
    }

    private static PendingVersion appendVersion(
            Transaction transaction,
            Descriptor table,
            long rowId,
            DirectoryHead expectedHead,
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
        updateDirectoryHead(
                transaction,
                table,
                rowId,
                expectedHead,
                versionId,
                RecordHint.of(versionHandle));
        ContainerKey orderedIndex = context.orderedIndexForWrite(table);
        MvccRawStoreOrderedIndex.insertVersion(
                context.transactionManager(),
                table,
                orderedIndex,
                rowId,
                versionId,
                values);
        PendingVersion pending = new PendingVersion(
                table,
                rowId,
                versionId,
                previousVersion.versionId(),
                RecordHint.of(previousVersion.handle()),
                flags,
                versionHandle);
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
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                RecordHandle handle = page.insertAtSlot(
                        page.recordCount(),
                        row,
                        (FormatableBitSet) null,
                        null,
                        insertFlags(page),
                        OVERFLOW_THRESHOLD);
                if (handle != null) {
                    return handle;
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            page = container.addPage();
            RecordHandle handle = page.insertAtSlot(
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
            if (container != null) {
                container.close();
            }
        }
    }

    private static void updateDirectoryHead(
            Transaction transaction,
            Descriptor table,
            long rowId,
            DirectoryHead expectedHead,
            long newHeadVersionId,
            RecordHint newHeadHint) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
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
                    DirectoryRecord directory = decodeDirectory(transaction, page, slot);
                    if (directory == null || directory.rowId() != rowId) {
                        continue;
                    }
                    if (!directory.head().equals(expectedHead)) {
                        throw StandardException.newException(
                                SQLState.DEADLOCK,
                                "RawStore MVCC directory head changed for logical row " + rowId);
                    }
                    page.updateAtSlot(
                            slot,
                            directoryRow(
                                    transaction,
                                    rowId,
                                    newHeadVersionId,
                                    newHeadHint),
                            null);
                    return;
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
        throw new IllegalStateException(
                "RawStore MVCC directory entry disappeared for logical row " + rowId);
    }

    private static VersionRecord findVisibleVersion(
            Transaction transaction,
            Descriptor table,
            long rowId,
            DirectoryHead head,
            MvccRawStoreTransactionContext context) throws StandardException {
        return findVisibleVersion(
                transaction,
                table,
                rowId,
                head,
                context.transactionId(),
                context.snapshotSequence(),
                context);
    }

    private static VersionRecord findVisibleVersion(
            Transaction transaction,
            Descriptor table,
            long rowId,
            DirectoryHead head,
            long transactionId,
            long snapshotSequence,
            MvccRawStoreTransactionContext context) throws StandardException {
        long versionId = head.versionId();
        RecordHint hint = head.hint();
        Set<Long> visited = new HashSet<>();
        while (versionId != MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
            if (!visited.add(versionId)) {
                throw new IllegalStateException(
                        "RawStore MVCC version-chain cycle for logical row " + rowId
                                + " at version " + versionId);
            }
            VersionRecord version = findVersion(
                    transaction,
                    table,
                    rowId,
                    versionId,
                    hint);
            if (version == null) {
                throw new IllegalStateException(
                        "RawStore MVCC version-chain entry is missing for logical row " + rowId
                                + ": version " + versionId);
            }
            if (visible(version, transactionId, snapshotSequence, context)) {
                return version;
            }
            versionId = version.previousVersionId();
            hint = version.previousHint();
        }
        return null;
    }

    private static DirectoryHead findHead(
            Transaction transaction,
            Descriptor table,
            long rowId) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return DirectoryHead.NONE;
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
                    DirectoryRecord directory = decodeDirectory(transaction, page, slot);
                    if (directory != null && directory.rowId() == rowId) {
                        return directory.head();
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return DirectoryHead.NONE;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static VersionRecord findVersion(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            RecordHint hint) throws StandardException {
        VersionRecord hinted = findVersionByHint(
                transaction,
                table,
                rowId,
                versionId,
                hint);
        if (hinted != null) {
            return hinted;
        }
        return findVersionByLogicalId(transaction, table, rowId, versionId);
    }

    private static VersionRecord findVersionByHint(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            RecordHint hint) throws StandardException {
        if (!hint.valid()) {
            return null;
        }
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return null;
        }
        Page page = null;
        try {
            page = container.getPage(hint.pageNumber());
            if (page == null) {
                return null;
            }
            RecordHandle handle = page.getRecordHandle(hint.recordId());
            if (handle == null) {
                return null;
            }
            int slot = page.getSlotNumber(handle);
            if (page.isDeletedAtSlot(slot)) {
                return null;
            }
            int fieldCount = page.fetchNumFieldsAtSlot(slot);
            int baseFieldCount = MvccRawStoreFormat.versionBaseFieldCount(table.columnCount());
            int hintFieldCount = MvccRawStoreFormat.versionHintFieldCount(table.columnCount());
            if (fieldCount != baseFieldCount && fieldCount != hintFieldCount) {
                return null;
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
                return null;
            }
            return decodeVersionAtSlot(transaction, table, page, slot);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static VersionRecord findVersionByLogicalId(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return null;
        }
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
                    VersionRecord version = decodeVersionAtSlot(
                            transaction,
                            table,
                            page,
                            slot);
                    if (version != null && version.versionId() == versionId) {
                        if (version.rowId() != rowId) {
                            throw new IllegalStateException(
                                    "RawStore MVCC version identity " + versionId
                                            + " belongs to logical row " + version.rowId()
                                            + " instead of " + rowId);
                        }
                        return version;
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
            container.close();
        }
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

    private static boolean visible(
            VersionRecord version,
            long transactionId,
            long snapshotSequence,
            MvccRawStoreTransactionContext context) {
        if (version.creatorTransactionId() != transactionId
                && context.isTransactionActive(version.creatorTransactionId())) {
            return false;
        }
        if (version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            return version.creatorTransactionId() == transactionId;
        }
        return version.beginSequence() <= snapshotSequence
                && snapshotSequence < version.endSequence();
    }

    private static DirectoryRecord decodeDirectory(
            Transaction transaction,
            Page page,
            int slot) throws StandardException {
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        if (fieldCount != MvccRawStoreFormat.DIRECTORY_BASE_FIELD_COUNT
                && fieldCount != MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT) {
            throw new IllegalStateException(
                    "RawStore MVCC directory row has unsupported field count: " + fieldCount);
        }
        boolean hasHint = fieldCount == MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT;
        Object[] row = directoryTemplate(transaction, hasHint);
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
        return new DirectoryRecord(
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.DIRECTORY_ROW_ID),
                new DirectoryHead(
                        MvccRawStoreFormat.longAt(
                                row,
                                MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID),
                        hint),
                handle);
    }

    private static VersionRecord decodeVersionAtSlot(
            Transaction transaction,
            Descriptor table,
            Page page,
            int slot) throws StandardException {
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        int baseFieldCount = MvccRawStoreFormat.versionBaseFieldCount(table.columnCount());
        int hintFieldCount = MvccRawStoreFormat.versionHintFieldCount(table.columnCount());
        if (fieldCount != baseFieldCount && fieldCount != hintFieldCount) {
            throw new IllegalStateException(
                    "RawStore MVCC version row has unsupported field count: " + fieldCount);
        }
        Object[] row = versionTemplate(transaction, table, fieldCount == hintFieldCount);
        RecordHandle handle = page.fetchFromSlot(null, slot, row, null, false);
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_KIND_FIELD)
                != MvccRawStoreFormat.VERSION_KIND) {
            return null;
        }
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_FORMAT_VERSION)
                != MvccRawStoreFormat.FORMAT_VERSION) {
            throw new IllegalStateException("RawStore MVCC version row format is unsupported");
        }
        return decodeVersion(row, table, handle);
    }

    private static VersionRecord decodeVersion(
            Object[] row,
            Descriptor table,
            RecordHandle handle) throws StandardException {
        StoreDataValue[] values = new StoreDataValue[table.columnCount()];
        for (int index = 0; index < values.length; index++) {
            values[index] = StoreValueCopySupport.cloneValue(
                    (StoreDataValue) row[MvccRawStoreFormat.VERSION_PAYLOAD_START + index],
                    true);
        }
        boolean hasHint = row.length == MvccRawStoreFormat.versionHintFieldCount(table.columnCount());
        RecordHint previousHint = hasHint
                ? new RecordHint(
                        MvccRawStoreFormat.longAt(
                                row,
                                MvccRawStoreFormat.versionHintPageField(table.columnCount())),
                        MvccRawStoreFormat.intAt(
                                row,
                                MvccRawStoreFormat.versionHintRecordField(table.columnCount())))
                : RecordHint.NONE;
        return new VersionRecord(
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_ROW_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_CREATOR_TRANSACTION_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_END_SEQUENCE),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID),
                previousHint,
                MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_FLAGS),
                values,
                handle);
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

    private static Object[] directoryRow(
            Transaction transaction,
            long rowId,
            long headVersionId,
            RecordHint headHint) throws StandardException {
        Object[] row = directoryTemplate(transaction, true);
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
        return row;
    }

    private static Object[] directoryTemplate(
            Transaction transaction,
            boolean includeHint) throws StandardException {
        Object[] row = new Object[includeHint
                ? MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT
                : MvccRawStoreFormat.DIRECTORY_BASE_FIELD_COUNT];
        row[MvccRawStoreFormat.DIRECTORY_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.DIRECTORY_ROW_ID] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID] =
                MvccRawStoreFormat.longValue(transaction, 0L);
        if (includeHint) {
            row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE] =
                    MvccRawStoreFormat.longValue(transaction, 0L);
            row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD] =
                    MvccRawStoreFormat.intValue(transaction, 0);
        }
        return row;
    }

    private static Object[] versionTemplate(
            Transaction transaction,
            Descriptor table,
            boolean includeHint) throws StandardException {
        Object[] row = new Object[includeHint
                ? MvccRawStoreFormat.versionHintFieldCount(table.columnCount())
                : MvccRawStoreFormat.versionBaseFieldCount(table.columnCount())];
        row[MvccRawStoreFormat.VERSION_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.VERSION_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int field = MvccRawStoreFormat.VERSION_ROW_ID;
                field <= MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID;
                field++) {
            row[field] = MvccRawStoreFormat.longValue(transaction, 0L);
        }
        row[MvccRawStoreFormat.VERSION_FLAGS] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int index = 0; index < table.columnCount(); index++) {
            row[MvccRawStoreFormat.VERSION_PAYLOAD_START + index] = MvccRawStoreFormat.nullValue(
                    transaction,
                    table.formatIds()[index],
                    table.collationIds()[index]);
        }
        if (includeHint) {
            row[MvccRawStoreFormat.versionHintPageField(table.columnCount())] =
                    MvccRawStoreFormat.longValue(transaction, 0L);
            row[MvccRawStoreFormat.versionHintRecordField(table.columnCount())] =
                    MvccRawStoreFormat.intValue(transaction, 0);
        }
        return row;
    }

    record Allocation(long rowId, long versionId) {
    }

    record AllocatorHighWater(long nextRowId, long nextVersionId) {
    }

    private record RecordHint(long pageNumber, int recordId) {
        private static final RecordHint NONE = new RecordHint(0L, 0);

        private static RecordHint of(RecordHandle handle) {
            return handle == null
                    ? NONE
                    : new RecordHint(handle.getPageNumber(), handle.getId());
        }

        private boolean valid() {
            return pageNumber >= ContainerHandle.FIRST_PAGE_NUMBER
                    && recordId >= RecordHandle.FIRST_RECORD_ID;
        }
    }

    private record DirectoryHead(long versionId, RecordHint hint) {
        private static final DirectoryHead NONE =
                new DirectoryHead(MvccRawStoreFormat.NO_PREVIOUS_VERSION, RecordHint.NONE);
    }

    private record DirectoryRecord(long rowId, DirectoryHead head, RecordHandle handle) {
    }

    private record MutationTarget(DirectoryHead head, VersionRecord visible) {
    }

    private record VersionRecord(
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
