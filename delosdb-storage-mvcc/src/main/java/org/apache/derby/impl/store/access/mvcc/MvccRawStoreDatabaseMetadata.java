/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreDatabaseMetadata

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.log.LogInstant;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Durable database-wide MVCC identity and publication metadata in one RawStore container. */
final class MvccRawStoreDatabaseMetadata {
    static final String CONTAINER_PROPERTY =
            "delosdb.mvcc.rawStore.databaseMetadataContainerId";

    static final long MAGIC = 0x44454c4f534d4554L; // "DELOSMET"
    static final int FORMAT_VERSION = 1;
    static final int METADATA_KIND = 1;

    static final int MAGIC_FIELD = 0;
    static final int KIND_FIELD = 1;
    static final int FORMAT_VERSION_FIELD = 2;
    static final int NEXT_TRANSACTION_ID_FIELD = 3;
    static final int NEXT_COMMIT_SEQUENCE_FIELD = 4;
    static final int RECOVERY_PUBLICATION_CEILING_FIELD = 5;
    static final int FIELD_COUNT = 6;

    private static final long TRANSACTION_STATUS_MAGIC = 0x44454c4f5354584eL; // "DELOSTXN"
    private static final int TRANSACTION_STATUS_MAGIC_FIELD = 0;
    private static final int TRANSACTION_STATUS_TRANSACTION_ID_FIELD = 1;
    private static final int TRANSACTION_STATUS_COMMIT_SEQUENCE_FIELD = 2;
    private static final int TRANSACTION_STATUS_DEPENDENCY_SEGMENT_FIELD = 3;
    private static final int TRANSACTION_STATUS_DEPENDENCY_CONTAINER_FIELD = 4;
    private static final int LEGACY_TRANSACTION_STATUS_FIELD_COUNT = 3;
    private static final int TRANSACTION_STATUS_FIELD_COUNT = 5;

    private static final int INSERT_FLAGS = Page.INSERT_UNDO_WITH_PURGE;
    private static final int SEGMENT_ID = 0;

    private volatile ContainerKey containerKey;

    long ensureInitialized(
            TransactionManager parent, boolean useReservedRecoveryCeiling) throws StandardException {
        ContainerKey existing = containerKey;
        if (existing != null) {
            return -1L;
        }

        synchronized (this) {
            existing = containerKey;
            if (existing != null) {
                return -1L;
            }

            TransactionController child = null;
            boolean committed = false;
            try {
                child = parent.startNestedUserTransaction(false, true);
                if (!(child instanceof TransactionManager childManager)) {
                    throw StandardException.newException(
                            SQLState.NOT_IMPLEMENTED,
                            "RawStore MVCC database metadata requires a Derby transaction manager");
                }

                Serializable persisted = child.getProperty(CONTAINER_PROPERTY);
                ContainerKey key;
                if (persisted == null) {
                    long containerId = childManager.getRawStoreXact().addContainer(
                            SEGMENT_ID,
                            ContainerHandle.DEFAULT_ASSIGN_ID,
                            ContainerHandle.MODE_DEFAULT,
                            new Properties(),
                            TransactionController.IS_DEFAULT);
                    if (containerId <= 0L) {
                        throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
                    }
                    key = new ContainerKey(SEGMENT_ID, containerId);
                    initialize(childManager.getRawStoreXact(), key);
                    child.setProperty(CONTAINER_PROPERTY, Long.toString(containerId), true);
                } else {
                    key = new ContainerKey(SEGMENT_ID, parseContainerId(persisted));
                }

                long recoveryPublicationCeiling = validateAndReadRecoveryPublicationCeiling(
                        childManager.getRawStoreXact(),
                        key,
                        useReservedRecoveryCeiling);
                child.commit();
                committed = true;
                containerKey = key;
                return recoveryPublicationCeiling;
            } catch (StandardException | RuntimeException | Error failure) {
                abortChild(child, failure);
                throw failure;
            } finally {
                destroyChild(child);
            }
        }
    }

    long reserveTransactionId(Transaction parent) throws StandardException {
        return reserve(parent, NEXT_TRANSACTION_ID_FIELD, "transaction ID");
    }

    long reserveTransactionIds(Transaction parent, int count) throws StandardException {
        if (count <= 0) {
            throw new IllegalArgumentException("transaction ID reservation count must be positive");
        }
        return reserve(parent, NEXT_TRANSACTION_ID_FIELD, count, "transaction ID", false);
    }

    long reserveCommitSequences(
            Transaction parent, int count, boolean advanceRecoveryPublicationCeiling)
            throws StandardException {
        if (count <= 0) {
            throw new IllegalArgumentException("commit sequence reservation count must be positive");
        }
        return reserve(
                parent,
                NEXT_COMMIT_SEQUENCE_FIELD,
                count,
                "commit sequence",
                advanceRecoveryPublicationCeiling);
    }

    void stageCommittedHighWater(Transaction parent, long commitSequence) throws StandardException {
        if (commitSequence <= 0L) {
            throw new IllegalArgumentException("commitSequence must be positive");
        }
        ContainerHandle container = parent.openContainer(
                requireContainerKey(),
                lockingPolicy(parent),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw missingContainer();
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControlRow(parent, page);
            StoreDataValue current = MvccRawStoreFormat.longValue(parent, 0L);
            page.fetchFieldFromSlot(
                    Page.FIRST_SLOT_NUMBER,
                    RECOVERY_PUBLICATION_CEILING_FIELD,
                    current);
            long currentHighWater = StoreTypeUtil.getLong(current);
            if (commitSequence <= currentHighWater) {
                throw new IllegalStateException(
                        "RawStore MVCC committed high-water must advance: current="
                                + currentHighWater + ", requested=" + commitSequence);
            }
            page.updateFieldAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    RECOVERY_PUBLICATION_CEILING_FIELD,
                    MvccRawStoreFormat.longValue(parent, commitSequence),
                    null);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private long reserve(Transaction parent, int field, String label) throws StandardException {
        return reserve(parent, field, 1, label, false);
    }

    private long reserve(
            Transaction parent,
            int field,
            int count,
            String label,
            boolean advanceRecoveryPublicationCeiling) throws StandardException {
        if (!(parent instanceof RawTransaction rawParent)) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "RawStore MVCC " + label + " allocation requires a raw transaction");
        }

        RawTransaction nested = rawParent.startNestedTopTransaction();
        boolean committed = false;
        try {
            long reserved;
            ContainerHandle container = nested.openContainer(
                    requireContainerKey(),
                    lockingPolicy(nested),
                    ContainerHandle.MODE_FORUPDATE);
            if (container == null) {
                throw missingContainer();
            }
            Page page = null;
            try {
                page = container.getFirstPage();
                validateControlRow(nested, page);
                StoreDataValue value = MvccRawStoreFormat.longValue(nested, 0L);
                page.fetchFieldFromSlot(Page.FIRST_SLOT_NUMBER, field, value);
                reserved = StoreTypeUtil.getLong(value);
                if (reserved <= 0L || reserved > Long.MAX_VALUE - count) {
                    throw new IllegalStateException(
                            "RawStore MVCC " + label + " allocator is invalid or exhausted: " + reserved);
                }
                page.updateFieldAtSlot(
                        Page.FIRST_SLOT_NUMBER,
                        field,
                        MvccRawStoreFormat.longValue(nested, reserved + count),
                        null);
                if (advanceRecoveryPublicationCeiling) {
                    long ceiling = reserved + count - 1L;
                    StoreDataValue highWater = MvccRawStoreFormat.longValue(nested, 0L);
                    page.fetchFieldFromSlot(
                            Page.FIRST_SLOT_NUMBER,
                            RECOVERY_PUBLICATION_CEILING_FIELD,
                            highWater);
                    if (ceiling > StoreTypeUtil.getLong(highWater)) {
                        page.updateFieldAtSlot(
                                Page.FIRST_SLOT_NUMBER,
                                RECOVERY_PUBLICATION_CEILING_FIELD,
                                MvccRawStoreFormat.longValue(nested, ceiling),
                                null);
                    }
                }
            } finally {
                if (page != null) {
                    page.unlatch();
                }
                container.close();
            }
            LogInstant commitInstant = nested.commit();
            committed = true;
            if (commitInstant == null) {
                throw new IllegalStateException(
                        "RawStore MVCC " + label + " reservation produced no commit record");
            }
            // Nested top transactions commit independently but do not force by default.
            nested.getLogFactory().flush(commitInstant);
            return reserved;
        } catch (StandardException | RuntimeException | Error failure) {
            if (!committed) {
                abortRaw(nested, failure);
            }
            throw failure;
        } finally {
            destroyRaw(nested, committed);
        }
    }

    void stageCommittedTransactionStatus(
            Transaction parent,
            long transactionId,
            long commitSequence,
            List<ContainerKey> dependencyTables) throws StandardException {
        if (transactionId <= 0L || commitSequence <= 0L) {
            throw new IllegalArgumentException(
                    "RawStore MVCC committed transaction status requires positive IDs: tx="
                            + transactionId + ", commit=" + commitSequence);
        }
        if (dependencyTables == null || dependencyTables.isEmpty()) {
            throw new IllegalArgumentException(
                    "RawStore MVCC committed transaction status requires dependent tables");
        }
        LinkedHashSet<ContainerKey> dependencies = new LinkedHashSet<>(dependencyTables);

        // Transaction-status dependency rows are append-only until maintenance has
        // durably removed the corresponding table's status-backed CURRENT state.
        // Keep their physical mutation in the parent RawStore transaction, but do
        // not serialize every committing writer behind the database-metadata
        // container's transaction-duration exclusive lock. Record-level RawStore
        // locking is sufficient for independent status rows; page latches continue
        // to protect the physical insert itself.
        ContainerHandle container = parent.openContainer(
                requireContainerKey(),
                MvccRawStorePhysicalLocking.rowLevel(parent),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw missingContainer();
        }
        try {
            for (ContainerKey dependency : dependencies) {
                insertTransactionStatus(
                        container,
                        transactionStatusRow(
                                parent,
                                transactionId,
                                commitSequence,
                                dependency));
            }
        } finally {
            container.close();
        }
    }

    long readCommittedTransactionStatus(
            Transaction transaction,
            long transactionId) throws StandardException {
        if (transactionId <= 0L) {
            return 0L;
        }
        ContainerHandle container = transaction.openContainer(
                requireContainerKey(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw missingContainer();
        }
        long commitSequence = 0L;
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControlRow(transaction, page);
            while (page != null) {
                int startSlot = firstStatusSlot(page);
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    int fieldCount = transactionStatusFieldCount(page, slot);
                    if (fieldCount == 0) {
                        continue;
                    }
                    Object[] row = transactionStatusTemplate(transaction, fieldCount);
                    page.fetchFromSlot(null, slot, row, null, false);
                    if (MvccRawStoreFormat.longAt(row, TRANSACTION_STATUS_MAGIC_FIELD)
                            != TRANSACTION_STATUS_MAGIC
                            || MvccRawStoreFormat.longAt(
                                    row, TRANSACTION_STATUS_TRANSACTION_ID_FIELD)
                                    != transactionId) {
                        continue;
                    }
                    long candidate = MvccRawStoreFormat.longAt(
                            row, TRANSACTION_STATUS_COMMIT_SEQUENCE_FIELD);
                    if (candidate <= 0L) {
                        throw new IllegalStateException(
                                "RawStore MVCC transaction-status row has invalid commit: tx="
                                        + transactionId + ", commit=" + candidate);
                    }
                    if (commitSequence != 0L && commitSequence != candidate) {
                        throw new IllegalStateException(
                                "RawStore MVCC transaction status is duplicated with different "
                                        + "commit sequences: tx=" + transactionId
                                        + ", first=" + commitSequence
                                        + ", second=" + candidate);
                    }
                    commitSequence = candidate;
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return commitSequence;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    TransactionStatusReclamation reclaimCommittedTransactionStatusDependencies(
            Transaction transaction,
            ContainerKey table) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                requireContainerKey(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw missingContainer();
        }

        try {
            PurgedDependencies purged = purgeTransactionStatusDependencies(
                    transaction, container, table);
            if (purged.transactionIds().isEmpty()) {
                return TransactionStatusReclamation.EMPTY;
            }
            Set<Long> stillDurable = remainingTransactionStatuses(
                    transaction, container, purged.transactionIds());
            Set<Long> fullyReclaimed = new LinkedHashSet<>(purged.transactionIds());
            fullyReclaimed.removeAll(stillDurable);
            return new TransactionStatusReclamation(
                    purged.rowCount(), Set.copyOf(fullyReclaimed));
        } finally {
            container.close();
        }
    }

    private static PurgedDependencies purgeTransactionStatusDependencies(
            Transaction transaction,
            ContainerHandle container,
            ContainerKey table) throws StandardException {
        Set<Long> transactionIds = new LinkedHashSet<>();
        int rowCount = 0;
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControlRow(transaction, page);
            while (page != null) {
                int startSlot = firstStatusSlot(page);
                for (int slot = page.recordCount() - 1; slot >= startSlot; slot--) {
                    if (!dependencyRowMatches(transaction, page, slot, table)) {
                        continue;
                    }
                    long transactionId = transactionStatusTransactionId(
                            transaction, page, slot, TRANSACTION_STATUS_FIELD_COUNT);
                    transactionIds.add(transactionId);
                    page.purgeAtSlot(slot, 1, true);
                    rowCount++;
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return new PurgedDependencies(rowCount, Set.copyOf(transactionIds));
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private static Set<Long> remainingTransactionStatuses(
            Transaction transaction,
            ContainerHandle container,
            Set<Long> candidates) throws StandardException {
        Set<Long> remaining = new LinkedHashSet<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControlRow(transaction, page);
            while (page != null) {
                int startSlot = firstStatusSlot(page);
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    int fieldCount = transactionStatusFieldCount(page, slot);
                    if (fieldCount == 0) {
                        continue;
                    }
                    long transactionId = transactionStatusTransactionId(
                            transaction, page, slot, fieldCount);
                    if (candidates.contains(transactionId)) {
                        remaining.add(transactionId);
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return Set.copyOf(remaining);
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private static boolean dependencyRowMatches(
            Transaction transaction,
            Page page,
            int slot,
            ContainerKey table) throws StandardException {
        if (page.isDeletedAtSlot(slot)
                || page.fetchNumFieldsAtSlot(slot) != TRANSACTION_STATUS_FIELD_COUNT) {
            return false;
        }
        Object[] row = transactionStatusTemplate(transaction, TRANSACTION_STATUS_FIELD_COUNT);
        page.fetchFromSlot(null, slot, row, null, false);
        if (MvccRawStoreFormat.longAt(row, TRANSACTION_STATUS_MAGIC_FIELD)
                != TRANSACTION_STATUS_MAGIC) {
            return false;
        }
        ContainerKey dependency = new ContainerKey(
                MvccRawStoreFormat.longAt(row, TRANSACTION_STATUS_DEPENDENCY_SEGMENT_FIELD),
                MvccRawStoreFormat.longAt(row, TRANSACTION_STATUS_DEPENDENCY_CONTAINER_FIELD));
        return table.equals(dependency);
    }

    private static long transactionStatusTransactionId(
            Transaction transaction,
            Page page,
            int slot,
            int fieldCount) throws StandardException {
        Object[] row = transactionStatusTemplate(transaction, fieldCount);
        page.fetchFromSlot(null, slot, row, null, false);
        if (MvccRawStoreFormat.longAt(row, TRANSACTION_STATUS_MAGIC_FIELD)
                != TRANSACTION_STATUS_MAGIC) {
            return 0L;
        }
        long transactionId = MvccRawStoreFormat.longAt(
                row, TRANSACTION_STATUS_TRANSACTION_ID_FIELD);
        if (transactionId <= 0L) {
            throw new IllegalStateException(
                    "RawStore MVCC transaction-status row has invalid tx=" + transactionId);
        }
        return transactionId;
    }

    private static int transactionStatusFieldCount(Page page, int slot)
            throws StandardException {
        if (page.isDeletedAtSlot(slot)) {
            return 0;
        }
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        return fieldCount == LEGACY_TRANSACTION_STATUS_FIELD_COUNT
                        || fieldCount == TRANSACTION_STATUS_FIELD_COUNT
                ? fieldCount
                : 0;
    }

    private static int firstStatusSlot(Page page) {
        return page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                ? Page.FIRST_SLOT_NUMBER + 1
                : Page.FIRST_SLOT_NUMBER;
    }

    private static void insertTransactionStatus(ContainerHandle container, Object[] row)
            throws StandardException {
        Page page = null;
        try {
            page = container.getPageForInsert(0);
            if (insertTransactionStatusOnPage(page, row)) {
                return;
            }
            if (page != null) {
                page.unlatch();
                page = null;
            }
            page = container.getPageForInsert(ContainerHandle.GET_PAGE_UNFILLED);
            if (insertTransactionStatusOnPage(page, row)) {
                return;
            }
            if (page != null) {
                page.unlatch();
                page = null;
            }
            page = container.addPage();
            if (!insertTransactionStatusOnPage(page, row)) {
                throw new IllegalStateException(
                        "RawStore MVCC transaction-status row did not fit on an empty page");
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private static boolean insertTransactionStatusOnPage(Page page, Object[] row)
            throws StandardException {
        if (page == null) {
            return false;
        }
        return page.insertAtSlot(
                        page.recordCount(),
                        row,
                        null,
                        null,
                        (byte) INSERT_FLAGS,
                        100)
                != null;
    }

    private static Object[] transactionStatusRow(
            Transaction transaction,
            long transactionId,
            long commitSequence,
            ContainerKey dependency) throws StandardException {
        Object[] row = transactionStatusTemplate(transaction, TRANSACTION_STATUS_FIELD_COUNT);
        row[TRANSACTION_STATUS_MAGIC_FIELD] =
                MvccRawStoreFormat.longValue(transaction, TRANSACTION_STATUS_MAGIC);
        row[TRANSACTION_STATUS_TRANSACTION_ID_FIELD] =
                MvccRawStoreFormat.longValue(transaction, transactionId);
        row[TRANSACTION_STATUS_COMMIT_SEQUENCE_FIELD] =
                MvccRawStoreFormat.longValue(transaction, commitSequence);
        row[TRANSACTION_STATUS_DEPENDENCY_SEGMENT_FIELD] =
                MvccRawStoreFormat.longValue(transaction, dependency.getSegmentId());
        row[TRANSACTION_STATUS_DEPENDENCY_CONTAINER_FIELD] =
                MvccRawStoreFormat.longValue(transaction, dependency.getContainerId());
        return row;
    }

    private static Object[] transactionStatusTemplate(Transaction transaction, int fieldCount)
            throws StandardException {
        List<Object> fields = new ArrayList<>(fieldCount);
        for (int index = 0; index < fieldCount; index++) {
            fields.add(MvccRawStoreFormat.longValue(transaction, 0L));
        }
        return fields.toArray();
    }

    private record PurgedDependencies(int rowCount, Set<Long> transactionIds) {
        PurgedDependencies {
            transactionIds = Set.copyOf(transactionIds);
        }
    }

    record TransactionStatusReclamation(
            int purgedDependencyRows,
            Set<Long> fullyReclaimedTransactionIds) {
        static final TransactionStatusReclamation EMPTY =
                new TransactionStatusReclamation(0, Set.of());

        TransactionStatusReclamation {
            if (purgedDependencyRows < 0) {
                throw new IllegalArgumentException("purgedDependencyRows must be non-negative");
            }
            fullyReclaimedTransactionIds = Set.copyOf(fullyReclaimedTransactionIds);
        }
    }

    private static void initialize(Transaction transaction, ContainerKey key)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw missingContainer();
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            Object[] row = template(transaction);
            row[MAGIC_FIELD] = MvccRawStoreFormat.longValue(transaction, MAGIC);
            row[KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, METADATA_KIND);
            row[FORMAT_VERSION_FIELD] = MvccRawStoreFormat.intValue(transaction, FORMAT_VERSION);
            row[NEXT_TRANSACTION_ID_FIELD] = MvccRawStoreFormat.longValue(transaction, 1L);
            row[NEXT_COMMIT_SEQUENCE_FIELD] = MvccRawStoreFormat.longValue(transaction, 1L);
            row[RECOVERY_PUBLICATION_CEILING_FIELD] = MvccRawStoreFormat.longValue(transaction, 0L);
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    row,
                    (FormatableBitSet) null,
                    null,
                    (byte) INSERT_FLAGS,
                    100);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static long validateAndReadRecoveryPublicationCeiling(
            Transaction transaction,
            ContainerKey key,
            boolean useReservedRecoveryCeiling) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                lockingPolicy(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw missingContainer();
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControlRow(transaction, page);
            StoreDataValue highWater = MvccRawStoreFormat.longValue(transaction, 0L);
            page.fetchFieldFromSlot(
                    Page.FIRST_SLOT_NUMBER,
                    RECOVERY_PUBLICATION_CEILING_FIELD,
                    highWater);
            long value = StoreTypeUtil.getLong(highWater);
            if (value < 0L) {
                throw new IllegalStateException(
                        "RawStore MVCC committed high-water is invalid: " + value);
            }
            if (!useReservedRecoveryCeiling) {
                return value;
            }
            StoreDataValue nextCommit = MvccRawStoreFormat.longValue(transaction, 0L);
            page.fetchFieldFromSlot(
                    Page.FIRST_SLOT_NUMBER,
                    NEXT_COMMIT_SEQUENCE_FIELD,
                    nextCommit);
            long next = StoreTypeUtil.getLong(nextCommit);
            if (next <= 0L) {
                throw new IllegalStateException(
                        "RawStore MVCC next commit sequence is invalid: " + next);
            }
            return Math.max(value, next - 1L);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void validateControlRow(Transaction transaction, Page page)
            throws StandardException {
        if (page == null || page.recordCount() <= Page.FIRST_SLOT_NUMBER) {
            throw new IllegalStateException("RawStore MVCC database metadata row is missing");
        }
        Object[] row = template(transaction);
        page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, row, null, false);
        if (MvccRawStoreFormat.longAt(row, MAGIC_FIELD) != MAGIC
                || MvccRawStoreFormat.intAt(row, KIND_FIELD) != METADATA_KIND
                || MvccRawStoreFormat.intAt(row, FORMAT_VERSION_FIELD) != FORMAT_VERSION) {
            throw new IllegalStateException("RawStore MVCC database metadata format is invalid");
        }
    }

    private static Object[] template(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    private ContainerKey requireContainerKey() {
        ContainerKey key = containerKey;
        if (key == null) {
            throw new IllegalStateException("RawStore MVCC database metadata is not initialized");
        }
        return key;
    }

    private static long parseContainerId(Serializable persisted) {
        try {
            long containerId = Long.parseLong(persisted.toString());
            if (containerId <= 0L) {
                throw new NumberFormatException("non-positive container id");
            }
            return containerId;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(
                    "RawStore MVCC database metadata container property is invalid: " + persisted,
                    invalid);
        }
    }

    private static LockingPolicy lockingPolicy(Transaction transaction) {
        return transaction.newLockingPolicy(
                LockingPolicy.MODE_CONTAINER,
                TransactionController.ISOLATION_SERIALIZABLE,
                true);
    }

    private static StandardException missingContainer() {
        return StandardException.newException(
                SQLState.DATA_CONTAINER_VANISHED,
                "RawStore MVCC database metadata container");
    }

    private static void abortChild(TransactionController child, Throwable failure) {
        if (child == null) {
            return;
        }
        try {
            child.abort();
        } catch (Throwable abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    private static void destroyChild(TransactionController child) {
        if (child == null) {
            return;
        }
        child.destroy();
    }

    private static void abortRaw(RawTransaction transaction, Throwable failure) {
        try {
            transaction.abort();
        } catch (Throwable abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    private static void destroyRaw(RawTransaction transaction, boolean committed) {
        try {
            transaction.destroy();
        } catch (StandardException destroyFailure) {
            if (committed) {
                throw new IllegalStateException(
                        "Unable to destroy committed RawStore MVCC allocator transaction",
                        destroyFailure);
            }
        }
    }
}
