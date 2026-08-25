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
import org.apache.derby.shared.common.error.StandardException;

/** Physical lookup and visibility traversal for one MVCC version container. */
final class MvccRawStoreVersionReader implements AutoCloseable {
    private final Transaction transaction;
    private final MvccRawStoreTable.Descriptor table;
    private final ContainerHandle container;
    private final MvccRawStoreIndexedReadMetrics metrics;
    private MvccRawStoreVersionRows.FetchProjection primaryProjection;
    private MvccRawStoreVersionRows.Decoder primaryDecoder;
    private MvccRawStoreVersionRows.FetchProjection secondaryProjection;
    private MvccRawStoreVersionRows.Decoder secondaryDecoder;

    MvccRawStoreVersionReader(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        this(transaction, table, null);
    }

    MvccRawStoreVersionReader(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreIndexedReadMetrics metrics) throws StandardException {
        this.transaction = transaction;
        this.table = table;
        this.metrics = metrics;
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
                projection);
    }

    static MvccRawStoreTable.VersionRecord findVisible(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreTable.DirectoryHead head,
            long transactionId,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        try (MvccRawStoreVersionReader reader =
                     new MvccRawStoreVersionReader(transaction, table)) {
            return reader.findVisible(
                    rowId,
                    head,
                    transactionId,
                    snapshotSequence,
                    projection);
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

    MvccRawStoreTable.VersionRecord findAnchoredCurrent(
            MvccRawStoreRuntime.CurrentRowAnchor anchor,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        if (container == null || anchor == null || !anchor.hint().valid()) {
            return null;
        }
        Page page = null;
        try {
            page = container.getPage(anchor.hint().pageNumber());
            if (page != null && metrics != null) {
                metrics.versionPageAcquired();
            }
            if (page == null) {
                return null;
            }
            RecordHandle handle = page.getRecordHandle(anchor.hint().recordId());
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
            MvccRawStoreTable.VersionRecord decoded = decoder(projection).decodeAtSlot(page, slot);
            if (metrics != null) {
                metrics.versionSlotFetched();
            }
            if (decoded == null
                    || decoded.rowId() != anchor.rowId()
                    || decoded.versionId() != anchor.versionId()
                    || decoded.beginSequence() != anchor.beginSequence()
                    || decoded.flags() != anchor.flags()) {
                return null;
            }
            return decoded;
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    MvccRawStoreTable.VersionRecord findVisibleHead(
            long rowId,
            MvccRawStoreTable.DirectoryHead head,
            long expectedVersionId,
            long transactionId,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        if (head.versionId() != expectedVersionId) {
            return null;
        }
        MvccRawStoreTable.VersionRecord version = find(
                rowId,
                expectedVersionId,
                head.hint(),
                projection);
        if (version == null) {
            return null;
        }
        if (metrics != null) {
            metrics.visibilityChecked();
        }
        return visible(version, transactionId, snapshotSequence) ? version : null;
    }

    MvccRawStoreTable.VersionRecord findVisible(
            long rowId,
            MvccRawStoreTable.DirectoryHead head,
            long transactionId,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        long versionId = head.versionId();
        long firstVersionId = versionId;
        MvccRawStoreTable.RecordHint hint = head.hint();
        Set<Long> visited = null;
        boolean first = true;
        while (versionId != MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
            if (metrics != null) {
                metrics.versionChainStep();
            }
            if (first) {
                first = false;
            } else {
                if (visited == null) {
                    visited = new HashSet<>();
                    visited.add(firstVersionId);
                }
                if (!visited.add(versionId)) {
                    throw new IllegalStateException(
                            "RawStore MVCC version-chain cycle for logical row " + rowId
                                    + " at version " + versionId);
                }
            }
            MvccRawStoreTable.VersionRecord version = find(
                    rowId,
                    versionId,
                    hint,
                    projection);
            if (version == null) {
                throw new MissingVersionException(rowId, versionId);
            }
            if (metrics != null) {
                metrics.visibilityChecked();
            }
            if (visible(version, transactionId, snapshotSequence)) {
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
        if (metrics != null) {
            metrics.versionLogicalFallback();
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
            if (page != null && metrics != null) {
                metrics.versionPageAcquired();
            }
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
            // The projected decode already fetches VERSION_KIND, VERSION_ROW_ID, and
            // VERSION_ID. Decode the hinted record once, then validate its identity.
            // A stale hint still returns null and falls back to logical lookup exactly
            // as before, but the common valid-hint path avoids a redundant RawStore
            // slot fetch of the same physical version record.
            MvccRawStoreTable.VersionRecord decoded = decoder(projection).decodeAtSlot(page, slot);
            if (metrics != null) {
                metrics.versionSlotFetched();
            }
            return decoded != null
                            && decoded.rowId() == rowId
                            && decoded.versionId() == versionId
                    ? decoded
                    : null;
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
            if (page != null && metrics != null) {
                metrics.versionPageAcquired();
            }
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    MvccRawStoreTable.VersionRecord version =
                            decoder(projection).decodeAtSlot(page, slot);
                    if (metrics != null) {
                        metrics.versionSlotFetched();
                    }
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
                if (page != null && metrics != null) {
                    metrics.versionPageAcquired();
                }
            }
            return null;
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private MvccRawStoreVersionRows.Decoder decoder(
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        if (primaryDecoder == null) {
            primaryProjection = projection;
            primaryDecoder = new MvccRawStoreVersionRows.Decoder(transaction, table, projection);
            return primaryDecoder;
        }
        if (primaryProjection == projection) {
            return primaryDecoder;
        }
        if (secondaryDecoder == null) {
            secondaryProjection = projection;
            secondaryDecoder = new MvccRawStoreVersionRows.Decoder(transaction, table, projection);
            return secondaryDecoder;
        }
        if (secondaryProjection == projection) {
            return secondaryDecoder;
        }
        return new MvccRawStoreVersionRows.Decoder(transaction, table, projection);
    }

    // Snapshots are contiguous published commit-sequence frontiers. A foreign
    // uncommitted version is rejected below, and a precommit-stamped version
    // cannot enter a snapshot until its sequence is published.
    static boolean visible(
            MvccRawStoreTable.VersionRecord version,
            long transactionId,
            long snapshotSequence) {
        if (version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            return version.creatorTransactionId() == transactionId;
        }
        return version.beginSequence() <= snapshotSequence
                && snapshotSequence < version.endSequence();
    }

    static final class MissingVersionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private MissingVersionException(long rowId, long versionId) {
            super("RawStore MVCC version-chain entry is missing for logical row " + rowId
                    + ": version " + versionId);
        }
    }

    @Override
    public void close() {
        if (container != null) {
            container.close();
        }
    }
}
