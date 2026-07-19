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
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodConglomerateProperties;
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
    private static final int INSERT_FLAGS = Page.INSERT_UNDO_WITH_PURGE | Page.INSERT_OVERFLOW;
    private static final int OVERFLOW_THRESHOLD = 100;

    static final class Descriptor {
        private final ContainerKey metadataContainer;
        private final ContainerKey versionContainer;
        private final int[] formatIds;
        private final int[] collationIds;
        private final boolean temporary;
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

    private MvccRawStoreTable() {
    }

    static Descriptor create(
            Transaction rawTransaction,
            int segment,
            long requestedContainerId,
            StoreDataValue[] template,
            int[] suppliedCollationIds,
            Properties properties,
            int temporaryFlag) throws StandardException {
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

        List<UniqueConstraint> uniqueConstraints = parseUniqueConstraints(
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
        MvccRawStoreOrderedIndex.initialize(rawTransaction, descriptor);
        return descriptor;
    }

    static Descriptor read(Transaction rawTransaction, ContainerKey metadataKey) throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                metadataKey,
                lockingPolicy(rawTransaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return null;
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            if (page == null || page.recordCount() < 2) {
                return null;
            }
            Object[] prefix = controlPrefixTemplate(rawTransaction);
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, prefix, null, false);
            if (MvccRawStoreFormat.longAt(prefix, MvccRawStoreFormat.CONTROL_MAGIC)
                    != MvccRawStoreFormat.MAGIC
                    || MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_KIND_FIELD)
                    != MvccRawStoreFormat.CONTROL_KIND
                    || MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_FORMAT_VERSION)
                    != MvccRawStoreFormat.FORMAT_VERSION) {
                return null;
            }
            int columnCount = MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_COLUMN_COUNT);
            int controlFieldCount = page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER);
            int orderedIndexField = MvccRawStoreFormat.controlOrderedIndexContainerField(columnCount);
            boolean hasOrderedIndexField = controlFieldCount > orderedIndexField;
            int uniqueCountField = MvccRawStoreFormat.controlUniqueConstraintCountField(columnCount);
            boolean hasUniqueMetadata = controlFieldCount > uniqueCountField;
            Object[] row = controlTemplate(
                    rawTransaction,
                    columnCount,
                    hasOrderedIndexField,
                    hasUniqueMetadata ? controlFieldCount - uniqueCountField - 1 : -1);
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, row, null, false);
            int[] formatIds = new int[columnCount];
            int[] collationIds = new int[columnCount];
            for (int index = 0; index < columnCount; index++) {
                formatIds[index] = MvccRawStoreFormat.intAt(
                        row,
                        MvccRawStoreFormat.CONTROL_FIXED_FIELDS + index);
                collationIds[index] = MvccRawStoreFormat.intAt(
                        row,
                        MvccRawStoreFormat.CONTROL_FIXED_FIELDS + columnCount + index);
            }
            ContainerKey orderedIndexContainer = null;
            if (hasOrderedIndexField) {
                long orderedIndexId = MvccRawStoreFormat.longAt(row, orderedIndexField);
                if (orderedIndexId > 0L) {
                    orderedIndexContainer = new ContainerKey(metadataKey.getSegmentId(), orderedIndexId);
                }
            }
            List<UniqueConstraint> uniqueConstraints = hasUniqueMetadata
                    ? decodeUniqueConstraints(row, columnCount)
                    : List.of();
            return new Descriptor(
                    new ContainerKey(
                            metadataKey.getSegmentId(),
                            MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CONTROL_METADATA_CONTAINER)),
                    new ContainerKey(
                            metadataKey.getSegmentId(),
                            MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CONTROL_VERSION_CONTAINER)),
                    orderedIndexContainer,
                    formatIds,
                    collationIds,
                    MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.CONTROL_TEMPORARY) != 0,
                    uniqueConstraints);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static void validateUniqueConstraintDefinition(
            Descriptor table,
            int[] baseColumnPositions,
            boolean deferrable) throws StandardException {
        if (deferrable) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "deferrable unique constraints for RawStore-backed delos_mvcc");
        }
        validateUniqueColumns(table, baseColumnPositions);
    }

    static void addUniqueConstraint(
            Transaction transaction,
            Descriptor table,
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed,
            boolean deferrable,
            MvccRawStoreTransactionContext context) throws StandardException {
        validateUniqueConstraintDefinition(table, baseColumnPositions, deferrable);
        context.beforeWrite();
        ensureOrderedIndex(transaction, table);

        List<UniqueConstraint> existing = refreshUniqueConstraints(transaction, table, true);
        UniqueConstraint candidate = new UniqueConstraint(
                existing.size() + 1,
                baseColumnPositions,
                duplicateNullsAllowed);
        MvccRawStoreOrderedIndex.assertConstraintCanBeAdded(
                transaction,
                table,
                candidate,
                context);

        List<UniqueConstraint> updated = new ArrayList<>(existing);
        updated.add(candidate);
        rewriteControlRow(transaction, table, updated);
        table.observeUniqueConstraints(updated);
    }

    static void dropUniqueConstraint(
            Transaction transaction,
            Descriptor table,
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed,
            MvccRawStoreTransactionContext context) throws StandardException {
        validateUniqueColumns(table, baseColumnPositions);
        context.beforeWrite();

        List<UniqueConstraint> existing = refreshUniqueConstraints(transaction, table, true);
        // Compatibility: a table created before native unique metadata existed may
        // still have an inherited Derby unique index/constraint. Its SQL DROP must
        // not be blocked merely because there is no access-method definition to remove.
        if (existing.isEmpty()) {
            return;
        }
        List<UniqueConstraint> updated = new ArrayList<>(existing.size());
        boolean removed = false;
        for (UniqueConstraint constraint : existing) {
            if (!removed && constraint.matches(baseColumnPositions, duplicateNullsAllowed)) {
                removed = true;
                continue;
            }
            updated.add(new UniqueConstraint(
                    updated.size() + 1,
                    constraint.columns(),
                    constraint.duplicateNullsAllowed()));
        }
        if (!removed) {
            throw new IllegalStateException(
                    "RawStore MVCC unique metadata is absent for requested key");
        }
        rewriteControlRow(transaction, table, updated);
        table.observeUniqueConstraints(updated);
    }

    static List<UniqueConstraint> refreshUniqueConstraints(
            Transaction transaction,
            Descriptor table,
            boolean forUpdate) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                forUpdate ? ContainerHandle.MODE_FORUPDATE : ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            int countField = MvccRawStoreFormat.controlUniqueConstraintCountField(
                    table.columnCount());
            if (page == null || page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER) <= countField) {
                table.observeUniqueConstraints(List.of());
                return List.of();
            }
            int fieldCount = page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER);
            Object[] row = controlTemplate(
                    transaction,
                    table.columnCount(),
                    true,
                    fieldCount - countField - 1);
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, row, null, false);
            List<UniqueConstraint> constraints = decodeUniqueConstraints(row, table.columnCount());
            table.observeUniqueConstraints(constraints);
            return constraints;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void validateUniqueColumns(
            Descriptor table,
            int[] baseColumnPositions) {
        if (baseColumnPositions == null || baseColumnPositions.length == 0) {
            throw new IllegalArgumentException("RawStore MVCC unique key must contain a column");
        }
        Set<Integer> seen = new HashSet<>();
        for (int column : baseColumnPositions) {
            if (column < 0 || column >= table.columnCount()) {
                throw new IllegalArgumentException(
                        "RawStore MVCC unique column outside table row: " + column);
            }
            if (!seen.add(column)) {
                throw new IllegalArgumentException(
                        "RawStore MVCC unique key repeats column: " + column);
            }
        }
    }

    static java.util.Optional<List<Long>> orderedIndexRowIdsFor(
            Transaction transaction,
            Descriptor table,
            org.apache.derby.iapi.store.access.Qualifier[][] qualifiers,
            MvccRawStoreTransactionContext context) throws StandardException {
        ContainerKey orderedIndex = discoverOrderedIndexContainer(transaction, table, false);
        if (orderedIndex == null) {
            return java.util.Optional.empty();
        }
        return MvccRawStoreOrderedIndex.rowIdsFor(
                transaction,
                table,
                qualifiers,
                context.transactionId(),
                context.snapshotSequence());
    }

    private static void ensureOrderedIndex(Transaction transaction, Descriptor table)
            throws StandardException {
        ContainerKey existing = discoverOrderedIndexContainer(transaction, table, false);
        if (existing != null) {
            return;
        }
        // Upgrade only for the compatibility rebuild. Existing indexed tables
        // must not take a metadata update lock before UPDATE/DELETE has checked
        // the visible head and can fail a stale writer without waiting behind a
        // long-running historical reader.
        existing = discoverOrderedIndexContainer(transaction, table, true);
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
            MvccRawStoreOrderedIndex.initialize(transaction, table);
            rebuildOrderedIndex(transaction, table);
            rewriteControlRow(transaction, table);
        } catch (StandardException | RuntimeException | Error failure) {
            table.observeOrderedIndexContainer(null);
            throw failure;
        }
    }

    private static ContainerKey discoverOrderedIndexContainer(
            Transaction transaction,
            Descriptor table,
            boolean forUpdate) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                forUpdate ? ContainerHandle.MODE_FORUPDATE : ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            int field = MvccRawStoreFormat.controlOrderedIndexContainerField(table.columnCount());
            if (page == null || page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER) <= field) {
                table.observeOrderedIndexContainer(null);
                return null;
            }
            StoreDataValue value = MvccRawStoreFormat.longValue(transaction, 0L);
            page.fetchFieldFromSlot(Page.FIRST_SLOT_NUMBER, field, value);
            long containerId = StoreTypeUtil.getLong(value);
            ContainerKey key = containerId <= 0L
                    ? null
                    : new ContainerKey(table.metadataContainer().getSegmentId(), containerId);
            table.observeOrderedIndexContainer(key);
            return key;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void rewriteControlRow(Transaction transaction, Descriptor table)
            throws StandardException {
        rewriteControlRow(transaction, table, table.uniqueConstraints());
    }

    private static void rewriteControlRow(
            Transaction transaction,
            Descriptor table,
            List<UniqueConstraint> uniqueConstraints) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            page.updateAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    controlRow(transaction, table, uniqueConstraints),
                    null);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void rebuildOrderedIndex(Transaction transaction, Descriptor table)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                lockingPolicy(transaction),
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
                            version.creatorTransactionId(),
                            version.beginSequence(),
                            version.endSequence(),
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
        MvccRawStoreOrderedIndex.rebuild(transaction, table, versions);
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
        context.beforeWrite();
        ensureOrderedIndex(rawTransaction, table);
        long creatorTransactionId = context.transactionId();
        MvccRawStoreOrderedIndex.assertUnique(
                rawTransaction,
                table,
                values,
                0L,
                context);
        Allocation allocation = allocateIdentifiers(rawTransaction, table);
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
        MvccRawStoreOrderedIndex.insertVersion(
                rawTransaction,
                table,
                allocation.rowId(),
                allocation.versionId(),
                creatorTransactionId,
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                MvccRawStoreFormat.CURRENT_END_SEQUENCE,
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
                context.transactionId(),
                context.snapshotSequence());
        if (version == null || version.tombstone()) {
            return null;
        }
        return new VisibleRow(rowId, version.versionId(), version.values(), version.handle());
    }

    static VisibleRow readVisibleAt(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            long transactionId,
            long snapshotSequence) throws StandardException {
        DirectoryHead head = findHead(rawTransaction, table, rowId);
        VersionRecord version = findVisibleVersion(
                rawTransaction,
                table,
                rowId,
                head,
                transactionId,
                snapshotSequence);
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
        context.beforeWrite();
        ensureOrderedIndex(rawTransaction, table);
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
        context.beforeWrite();
        ensureOrderedIndex(rawTransaction, table);
        MutationTarget target = mutationTarget(rawTransaction, table, rowId, context);
        if (target == null) {
            return false;
        }
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

    static List<VisibleRow> scanVisible(
            Transaction rawTransaction,
            Descriptor table,
            MvccRawStoreTransactionContext context) throws StandardException {
        long snapshot = context.snapshotSequence();
        List<VisibleRow> rows = new ArrayList<>();
        ContainerHandle container = rawTransaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(rawTransaction),
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
                    DirectoryRecord directory = decodeDirectory(
                            rawTransaction,
                            page,
                            slot);
                    if (directory == null) {
                        continue;
                    }
                    long rowId = directory.rowId();
                    VersionRecord version = findVisibleVersion(
                            rawTransaction,
                            table,
                            rowId,
                            directory.head(),
                            context.transactionId(),
                            snapshot);
                    if (version != null && !version.tombstone()) {
                        rows.add(new VisibleRow(
                                rowId,
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

    static List<VisibleRow> scanVisibleAt(
            Transaction rawTransaction,
            Descriptor table,
            long transactionId,
            long snapshotSequence) throws StandardException {
        List<VisibleRow> rows = new ArrayList<>();
        ContainerHandle container = rawTransaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(rawTransaction),
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
                            transactionId,
                            snapshotSequence);
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
            MvccRawStoreOrderedIndex.stampVersionBegin(
                    rawTransaction,
                    version.table(),
                    version.rowId(),
                    version.versionId(),
                    commitSequence,
                    version.tombstone() ? 0 : version.table().columnCount());
            if (version.previousVersionId() != MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
                updateVersionEnd(
                        rawTransaction,
                        version.table(),
                        version.rowId(),
                        version.previousVersionId(),
                        version.previousHint(),
                        commitSequence);
                MvccRawStoreOrderedIndex.stampVersionEnd(
                        rawTransaction,
                        version.table(),
                        version.rowId(),
                        version.previousVersionId(),
                        commitSequence);
            }
        }
    }

    static void drop(Transaction rawTransaction, Descriptor table) throws StandardException {
        ContainerKey orderedIndex = discoverOrderedIndexContainer(rawTransaction, table, true);
        // Readers acquire metadata, versions, then the ordered index. Drop
        // follows the same order so no participant waits while holding a later
        // container in the table lock order.
        rawTransaction.dropContainer(table.metadataContainer());
        rawTransaction.dropContainer(table.versionContainer());
        if (orderedIndex != null) {
            rawTransaction.dropContainer(orderedIndex);
        }
    }

    private static void initializeMetadataContainer(Transaction rawTransaction, Descriptor descriptor)
            throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                descriptor.metadataContainer(),
                lockingPolicy(rawTransaction),
                ContainerHandle.MODE_FORUPDATE
                        | (descriptor.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        Page page = null;
        try {
            page = container.getFirstPage();
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    controlRow(rawTransaction, descriptor),
                    null,
                    null,
                    (byte) INSERT_FLAGS,
                    OVERFLOW_THRESHOLD);
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER + 1,
                    allocatorRow(rawTransaction),
                    null,
                    null,
                    (byte) INSERT_FLAGS,
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
                lockingPolicy(rawTransaction),
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
                    (byte) INSERT_FLAGS,
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

    private static Object[] controlRow(Transaction transaction, Descriptor descriptor) throws StandardException {
        return controlRow(transaction, descriptor, descriptor.uniqueConstraints());
    }

    private static Object[] controlRow(
            Transaction transaction,
            Descriptor descriptor,
            List<UniqueConstraint> uniqueConstraints) throws StandardException {
        int uniqueMetadataFields = uniqueMetadataFieldCount(uniqueConstraints);
        Object[] row = controlTemplate(
                transaction,
                descriptor.columnCount(),
                true,
                uniqueMetadataFields);
        row[MvccRawStoreFormat.CONTROL_MAGIC] = MvccRawStoreFormat.longValue(transaction, MvccRawStoreFormat.MAGIC);
        row[MvccRawStoreFormat.CONTROL_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.CONTROL_KIND);
        row[MvccRawStoreFormat.CONTROL_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.CONTROL_METADATA_CONTAINER] = MvccRawStoreFormat.longValue(
                transaction,
                descriptor.metadataContainer().getContainerId());
        row[MvccRawStoreFormat.CONTROL_VERSION_CONTAINER] = MvccRawStoreFormat.longValue(
                transaction,
                descriptor.versionContainer().getContainerId());
        row[MvccRawStoreFormat.CONTROL_COLUMN_COUNT] = MvccRawStoreFormat.intValue(
                transaction,
                descriptor.columnCount());
        row[MvccRawStoreFormat.CONTROL_TEMPORARY] = MvccRawStoreFormat.intValue(
                transaction,
                descriptor.temporary() ? 1 : 0);
        for (int index = 0; index < descriptor.columnCount(); index++) {
            row[MvccRawStoreFormat.CONTROL_FIXED_FIELDS + index] = MvccRawStoreFormat.intValue(
                    transaction,
                    descriptor.formatIds()[index]);
            row[MvccRawStoreFormat.CONTROL_FIXED_FIELDS + descriptor.columnCount() + index] =
                    MvccRawStoreFormat.intValue(transaction, descriptor.collationIds()[index]);
        }
        ContainerKey orderedIndexContainer = descriptor.orderedIndexContainer();
        row[MvccRawStoreFormat.controlOrderedIndexContainerField(descriptor.columnCount())] =
                MvccRawStoreFormat.longValue(
                        transaction,
                        orderedIndexContainer == null ? 0L : orderedIndexContainer.getContainerId());
        int field = MvccRawStoreFormat.controlUniqueConstraintCountField(descriptor.columnCount());
        row[field++] = MvccRawStoreFormat.intValue(
                transaction,
                uniqueConstraints.size());
        for (UniqueConstraint constraint : uniqueConstraints) {
            row[field++] = MvccRawStoreFormat.intValue(
                    transaction,
                    constraint.duplicateNullsAllowed() ? 1 : 0);
            int[] columns = constraint.columns();
            row[field++] = MvccRawStoreFormat.intValue(transaction, columns.length);
            for (int column : columns) {
                row[field++] = MvccRawStoreFormat.intValue(transaction, column);
            }
        }
        return row;
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
            StoreDataValue[] clone = StoreValueCopySupport.cloneRow(values);
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
                context.transactionId(),
                context.snapshotSequence());
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
        long versionId = allocateVersionIdentifier(transaction, table);
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
        MvccRawStoreOrderedIndex.insertVersion(
                transaction,
                table,
                rowId,
                versionId,
                context.transactionId(),
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                MvccRawStoreFormat.CURRENT_END_SEQUENCE,
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

    private static long allocateVersionIdentifier(Transaction transaction, Descriptor table)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            Object[] allocator = allocatorTemplate(transaction);
            int slot = Page.FIRST_SLOT_NUMBER + 1;
            page.fetchFromSlot(null, slot, allocator, null, false);
            long versionId = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID);
            if (versionId <= 0L || versionId == Long.MAX_VALUE) {
                throw new IllegalStateException(
                        "RawStore MVCC version identity allocator is invalid or exhausted: "
                                + versionId);
            }
            page.updateFieldAtSlot(
                    slot,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID,
                    MvccRawStoreFormat.longValue(transaction, versionId + 1L),
                    null);
            return versionId;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static Allocation allocateIdentifiers(Transaction transaction, Descriptor table)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            Object[] allocator = allocatorTemplate(transaction);
            int slot = Page.FIRST_SLOT_NUMBER + 1;
            page.fetchFromSlot(null, slot, allocator, null, false);
            long rowId = MvccRawStoreFormat.longAt(allocator, MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID);
            long versionId = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID);
            if (rowId <= 0L || versionId <= 0L
                    || rowId == Long.MAX_VALUE || versionId == Long.MAX_VALUE) {
                throw new IllegalStateException(
                        "RawStore MVCC logical identity allocator is invalid or exhausted: row="
                                + rowId + ", version=" + versionId);
            }
            page.updateFieldAtSlot(
                    slot,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID,
                    MvccRawStoreFormat.longValue(transaction, rowId + 1L),
                    null);
            page.updateFieldAtSlot(
                    slot,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID,
                    MvccRawStoreFormat.longValue(transaction, versionId + 1L),
                    null);
            return new Allocation(rowId, versionId);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static RecordHandle insertRow(
            Transaction transaction,
            ContainerKey key,
            Object[] row) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                lockingPolicy(transaction),
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
                        (byte) INSERT_FLAGS,
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
                    (byte) INSERT_FLAGS,
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
                lockingPolicy(transaction),
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
            long transactionId,
            long snapshotSequence) throws StandardException {
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
            if (visible(version, transactionId, snapshotSequence)) {
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
                lockingPolicy(transaction),
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
                lockingPolicy(transaction),
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
                lockingPolicy(transaction),
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
                lockingPolicy(transaction),
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
                lockingPolicy(transaction),
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
                lockingPolicy(transaction),
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
                lockingPolicy(transaction),
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

    private static LockingPolicy lockingPolicy(Transaction transaction) {
        return transaction.newLockingPolicy(
                LockingPolicy.MODE_CONTAINER,
                TransactionController.ISOLATION_SERIALIZABLE,
                true);
    }

    private static boolean visible(VersionRecord version, long transactionId, long snapshotSequence) {
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
                    (StoreDataValue) row[MvccRawStoreFormat.VERSION_PAYLOAD_START + index]);
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

    private static Object[] controlPrefixTemplate(Transaction transaction) throws StandardException {
        return controlTemplate(transaction, 0, false, -1);
    }

    private static Object[] controlTemplate(
            Transaction transaction,
            int columnCount,
            boolean includeOrderedIndex,
            int uniqueMetadataFields) throws StandardException {
        Object[] row = new Object[includeOrderedIndex
                ? (uniqueMetadataFields >= 0
                        ? MvccRawStoreFormat.controlFieldCount(columnCount, uniqueMetadataFields)
                        : MvccRawStoreFormat.controlUniqueConstraintCountField(columnCount))
                : MvccRawStoreFormat.controlOrderedIndexContainerField(columnCount)];
        row[MvccRawStoreFormat.CONTROL_MAGIC] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_METADATA_CONTAINER] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_VERSION_CONTAINER] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_COLUMN_COUNT] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_TEMPORARY] = MvccRawStoreFormat.intValue(transaction, 0);
        int orderedIndexField = MvccRawStoreFormat.controlOrderedIndexContainerField(columnCount);
        for (int index = MvccRawStoreFormat.CONTROL_FIXED_FIELDS;
                index < Math.min(row.length, orderedIndexField);
                index++) {
            row[index] = MvccRawStoreFormat.intValue(transaction, 0);
        }
        if (includeOrderedIndex) {
            row[orderedIndexField] = MvccRawStoreFormat.longValue(transaction, 0L);
            if (uniqueMetadataFields >= 0) {
                int uniqueCountField = MvccRawStoreFormat.controlUniqueConstraintCountField(columnCount);
                for (int index = uniqueCountField; index < row.length; index++) {
                    row[index] = MvccRawStoreFormat.intValue(transaction, 0);
                }
            }
        }
        return row;
    }

    private static List<UniqueConstraint> parseUniqueConstraints(
            String encoded,
            int columnCount) throws StandardException {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<UniqueConstraint> result = new ArrayList<>();
        String[] definitions = encoded.split(";", -1);
        for (int ordinal = 0; ordinal < definitions.length; ordinal++) {
            String[] parts = definitions[ordinal].split(":", -1);
            if (parts.length != 3 || parts[0].length() != 1) {
                throw new IllegalArgumentException(
                        "Invalid access-method unique metadata: " + definitions[ordinal]);
            }
            boolean duplicateNullsAllowed = switch (parts[0].charAt(0)) {
                case 'S' -> false;
                case 'N' -> true;
                default -> throw new IllegalArgumentException(
                        "Invalid access-method unique mode: " + parts[0]);
            };
            if ("1".equals(parts[1])) {
                throw StandardException.newException(
                        SQLState.NOT_IMPLEMENTED,
                        "deferrable unique constraints for RawStore-backed delos_mvcc");
            }
            if (!"0".equals(parts[1])) {
                throw new IllegalArgumentException(
                        "Invalid access-method deferred flag: " + parts[1]);
            }
            String[] encodedColumns = parts[2].split(",", -1);
            int[] columns = new int[encodedColumns.length];
            for (int index = 0; index < encodedColumns.length; index++) {
                int column = Integer.parseInt(encodedColumns[index]);
                if (column < 0 || column >= columnCount) {
                    throw new IllegalArgumentException(
                            "Unique column outside table row: " + column);
                }
                columns[index] = column;
            }
            result.add(new UniqueConstraint(
                    ordinal + 1,
                    columns,
                    duplicateNullsAllowed));
        }
        return List.copyOf(result);
    }

    private static int uniqueMetadataFieldCount(List<UniqueConstraint> constraints) {
        int count = 0;
        for (UniqueConstraint constraint : constraints) {
            count = Math.addExact(count, 2 + constraint.columns().length);
        }
        return count;
    }

    private static List<UniqueConstraint> decodeUniqueConstraints(
            Object[] row,
            int columnCount) throws StandardException {
        int field = MvccRawStoreFormat.controlUniqueConstraintCountField(columnCount);
        int count = MvccRawStoreFormat.intAt(row, field++);
        if (count < 0) {
            throw new IllegalStateException("Negative RawStore MVCC unique-constraint count");
        }
        List<UniqueConstraint> result = new ArrayList<>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            if (field + 2 > row.length) {
                throw new IllegalStateException("Truncated RawStore MVCC unique metadata");
            }
            boolean duplicateNullsAllowed = MvccRawStoreFormat.intAt(row, field++) != 0;
            int keyColumns = MvccRawStoreFormat.intAt(row, field++);
            if (keyColumns <= 0 || field + keyColumns > row.length) {
                throw new IllegalStateException("Invalid RawStore MVCC unique key width");
            }
            int[] columns = new int[keyColumns];
            for (int index = 0; index < keyColumns; index++) {
                int column = MvccRawStoreFormat.intAt(row, field++);
                if (column < 0 || column >= columnCount) {
                    throw new IllegalStateException(
                            "RawStore MVCC unique column outside table row: " + column);
                }
                columns[index] = column;
            }
            result.add(new UniqueConstraint(
                    ordinal + 1,
                    columns,
                    duplicateNullsAllowed));
        }
        if (field != row.length) {
            throw new IllegalStateException("Unexpected trailing RawStore MVCC unique metadata");
        }
        return List.copyOf(result);
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

    private record Allocation(long rowId, long versionId) {
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
