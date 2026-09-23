/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreDatabaseMetadata

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

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
    private static final int TRANSACTION_STATUS_FIELD_COUNT = 3;

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
            long commitSequence) throws StandardException {
        if (transactionId <= 0L || commitSequence <= 0L) {
            throw new IllegalArgumentException(
                    "RawStore MVCC committed transaction status requires positive IDs: tx="
                            + transactionId + ", commit=" + commitSequence);
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
            Object[] row = transactionStatusRow(parent, transactionId, commitSequence);
            page = container.getPageForInsert(0);
            if (insertTransactionStatus(page, row)) {
                return;
            }
            if (page != null) {
                page.unlatch();
                page = null;
            }
            page = container.getPageForInsert(ContainerHandle.GET_PAGE_UNFILLED);
            if (insertTransactionStatus(page, row)) {
                return;
            }
            if (page != null) {
                page.unlatch();
                page = null;
            }
            page = container.addPage();
            if (!insertTransactionStatus(page, row)) {
                throw new IllegalStateException(
                        "RawStore MVCC transaction-status row did not fit on an empty page");
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    Map<Long, Long> readCommittedTransactionStatuses(TransactionManager parent)
            throws StandardException {
        try (ChildTransactionScope child = ChildTransactionScope.open(parent)) {
            Map<Long, Long> statuses = readCommittedTransactionStatuses(
                    child.manager().getRawStoreXact());
            child.commit();
            return statuses;
        }
    }

    Map<Long, Long> readCommittedTransactionStatuses(Transaction transaction)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                requireContainerKey(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw missingContainer();
        }
        Map<Long, Long> statuses = new LinkedHashMap<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControlRow(transaction, page);
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)
                            || page.fetchNumFieldsAtSlot(slot) != TRANSACTION_STATUS_FIELD_COUNT) {
                        continue;
                    }
                    Object[] row = transactionStatusTemplate(transaction);
                    page.fetchFromSlot(null, slot, row, null, false);
                    if (MvccRawStoreFormat.longAt(row, TRANSACTION_STATUS_MAGIC_FIELD)
                            != TRANSACTION_STATUS_MAGIC) {
                        continue;
                    }
                    long transactionId = MvccRawStoreFormat.longAt(
                            row, TRANSACTION_STATUS_TRANSACTION_ID_FIELD);
                    long commitSequence = MvccRawStoreFormat.longAt(
                            row, TRANSACTION_STATUS_COMMIT_SEQUENCE_FIELD);
                    if (transactionId <= 0L || commitSequence <= 0L) {
                        throw new IllegalStateException(
                                "RawStore MVCC transaction-status row is invalid: tx="
                                        + transactionId + ", commit=" + commitSequence);
                    }
                    Long previous = statuses.put(transactionId, commitSequence);
                    if (previous != null && previous.longValue() != commitSequence) {
                        throw new IllegalStateException(
                                "RawStore MVCC transaction status is duplicated with different commit sequences: tx="
                                        + transactionId + ", first=" + previous + ", second=" + commitSequence);
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return Map.copyOf(statuses);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static boolean insertTransactionStatus(Page page, Object[] row)
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
            long commitSequence) throws StandardException {
        Object[] row = transactionStatusTemplate(transaction);
        row[TRANSACTION_STATUS_MAGIC_FIELD] =
                MvccRawStoreFormat.longValue(transaction, TRANSACTION_STATUS_MAGIC);
        row[TRANSACTION_STATUS_TRANSACTION_ID_FIELD] =
                MvccRawStoreFormat.longValue(transaction, transactionId);
        row[TRANSACTION_STATUS_COMMIT_SEQUENCE_FIELD] =
                MvccRawStoreFormat.longValue(transaction, commitSequence);
        return row;
    }

    private static Object[] transactionStatusTemplate(Transaction transaction)
            throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
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

    private static final class ChildTransactionScope implements AutoCloseable {
        private final TransactionController child;
        private boolean committed;

        private ChildTransactionScope(TransactionController child) {
            this.child = child;
        }

        static ChildTransactionScope open(TransactionManager parent)
                throws StandardException {
            return new ChildTransactionScope(
                    parent.startNestedUserTransaction(false, true));
        }

        TransactionManager manager() throws StandardException {
            if (child instanceof TransactionManager childManager) {
                return childManager;
            }
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "RawStore MVCC transaction-status reload requires a Derby transaction manager");
        }

        void commit() throws StandardException {
            child.commit();
            committed = true;
        }

        @Override
        public void close() throws StandardException {
            try {
                if (!committed) {
                    child.abort();
                }
            } finally {
                child.destroy();
            }
        }
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
