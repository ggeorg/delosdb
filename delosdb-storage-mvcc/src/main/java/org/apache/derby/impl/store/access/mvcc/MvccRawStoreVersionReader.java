/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreVersionReader

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

import java.util.HashSet;
import java.util.Set;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;

/** Physical lookup and visibility traversal for one MVCC version container. */
final class MvccRawStoreVersionReader implements AutoCloseable {
    private final Transaction transaction;
    private final MvccRawStoreTable.Descriptor table;
    private final ContainerHandle container;

    MvccRawStoreVersionReader(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        this.transaction = transaction;
        this.table = table;
        this.container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
    }

    static MvccRawStoreTable.VersionRecord findVisible(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreTable.DirectoryHead head,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        return findVisible(
                transaction,
                table,
                rowId,
                head,
                context.transactionId(),
                context.snapshotSequence(),
                projection,
                context);
    }

    static MvccRawStoreTable.VersionRecord findVisible(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreTable.DirectoryHead head,
            long transactionId,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        try (MvccRawStoreVersionReader reader =
                     new MvccRawStoreVersionReader(transaction, table)) {
            return reader.findVisible(
                    rowId,
                    head,
                    transactionId,
                    snapshotSequence,
                    projection,
                    context);
        }
    }

    static MvccRawStoreTable.VersionRecord find(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            MvccRawStoreTable.RecordHint hint,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        try (MvccRawStoreVersionReader reader =
                     new MvccRawStoreVersionReader(transaction, table)) {
            return reader.find(rowId, versionId, hint, projection);
        }
    }

    MvccRawStoreTable.VersionRecord findVisibleHead(
            long rowId,
            MvccRawStoreTable.DirectoryHead head,
            long expectedVersionId,
            long transactionId,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        if (head.versionId() != expectedVersionId) {
            return null;
        }
        MvccRawStoreTable.VersionRecord version = find(
                rowId,
                expectedVersionId,
                head.hint(),
                projection);
        return version != null && visible(version, transactionId, snapshotSequence, context)
                ? version
                : null;
    }

    MvccRawStoreTable.VersionRecord findVisible(
            long rowId,
            MvccRawStoreTable.DirectoryHead head,
            long transactionId,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        long versionId = head.versionId();
        MvccRawStoreTable.RecordHint hint = head.hint();
        Set<Long> visited = new HashSet<>();
        while (versionId != MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
            if (!visited.add(versionId)) {
                throw new IllegalStateException(
                        "RawStore MVCC version-chain cycle for logical row " + rowId
                                + " at version " + versionId);
            }
            MvccRawStoreTable.VersionRecord version = find(
                    rowId,
                    versionId,
                    hint,
                    projection);
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

    MvccRawStoreTable.VersionRecord find(
            long rowId,
            long versionId,
            MvccRawStoreTable.RecordHint hint,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        MvccRawStoreTable.VersionRecord hinted = findByHint(
                rowId,
                versionId,
                hint,
                projection);
        if (hinted != null) {
            return hinted;
        }
        return findByLogicalId(rowId, versionId, projection);
    }

    private MvccRawStoreTable.VersionRecord findByHint(
            long rowId,
            long versionId,
            MvccRawStoreTable.RecordHint hint,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        if (container == null || !hint.valid()) {
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
            return MvccRawStoreVersionRows.decodeAtSlot(
                    transaction,
                    table,
                    page,
                    slot,
                    projection);
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private MvccRawStoreTable.VersionRecord findByLogicalId(
            long rowId,
            long versionId,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
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
                    MvccRawStoreTable.VersionRecord version =
                            MvccRawStoreVersionRows.decodeAtSlot(
                                    transaction,
                                    table,
                                    page,
                                    slot,
                                    projection);
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
        }
    }

    private static boolean visible(
            MvccRawStoreTable.VersionRecord version,
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

    @Override
    public void close() {
        if (container != null) {
            container.close();
        }
    }
}
