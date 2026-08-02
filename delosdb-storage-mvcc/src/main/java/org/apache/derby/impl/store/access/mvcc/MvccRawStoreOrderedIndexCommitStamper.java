/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreOrderedIndexCommitStamper

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;

/** Commits all pending version transitions with one candidate-index scan per table. */
final class MvccRawStoreOrderedIndexCommitStamper {
    private MvccRawStoreOrderedIndexCommitStamper() {
    }

    static void stampPendingVersions(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            List<MvccRawStoreTable.PendingVersion> pending,
            long commitSequence) throws StandardException {
        int expectedEntryCount = MvccRawStoreOrderedIndex.indexedColumnCount(table);
        Map<VersionIdentity, Integer> expectedBegins = new LinkedHashMap<>();
        Map<VersionIdentity, Integer> expectedEnds = new LinkedHashMap<>();
        for (MvccRawStoreTable.PendingVersion version : pending) {
            if (!version.tombstone()) {
                expectedBegins.put(
                        new VersionIdentity(version.rowId(), version.versionId()),
                        expectedEntryCount);
            }
            if (version.previousVersionId() != MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
                expectedEnds.put(
                        new VersionIdentity(version.rowId(), version.previousVersionId()),
                        expectedEntryCount);
            }
        }
        if (expectedBegins.isEmpty() && expectedEnds.isEmpty()) {
            return;
        }

        ContainerKey key = MvccRawStoreOrderedIndex.requireContainer(table);
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC ordered-index container is absent: " + key);
        }

        Map<VersionIdentity, Integer> actualBegins = new LinkedHashMap<>();
        Map<VersionIdentity, Integer> actualEnds = new LinkedHashMap<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            MvccRawStoreOrderedIndex.validateControl(transaction, table, page);
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    VersionIdentity version = versionIdentity(transaction, page, slot);
                    if (expectedBegins.containsKey(version)) {
                        stampSequence(
                                transaction,
                                page,
                                slot,
                                version,
                                MvccRawStoreFormat.ORDERED_INDEX_ENTRY_BEGIN_SEQUENCE,
                                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                                commitSequence);
                        actualBegins.merge(version, 1, Integer::sum);
                    }
                    if (expectedEnds.containsKey(version)) {
                        stampSequence(
                                transaction,
                                page,
                                slot,
                                version,
                                MvccRawStoreFormat.ORDERED_INDEX_ENTRY_END_SEQUENCE,
                                MvccRawStoreFormat.CURRENT_END_SEQUENCE,
                                commitSequence);
                        actualEnds.merge(version, 1, Integer::sum);
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

        verifyCounts("begin", expectedBegins, actualBegins);
        verifyCounts("end", expectedEnds, actualEnds);
    }

    private static VersionIdentity versionIdentity(
            Transaction transaction,
            Page page,
            int slot) throws StandardException {
        return new VersionIdentity(
                longField(transaction, page, slot, MvccRawStoreFormat.ORDERED_INDEX_ENTRY_ROW_ID),
                longField(
                        transaction,
                        page,
                        slot,
                        MvccRawStoreFormat.ORDERED_INDEX_ENTRY_VERSION_ID));
    }

    private static void stampSequence(
            Transaction transaction,
            Page page,
            int slot,
            VersionIdentity version,
            int field,
            long expectedCurrentValue,
            long newValue) throws StandardException {
        long current = longField(transaction, page, slot, field);
        if (current != expectedCurrentValue) {
            throw new IllegalStateException(
                    "RawStore MVCC ordered-index entry has unexpected sequence for row "
                            + version.rowId() + ", version " + version.versionId()
                            + ": " + current);
        }
        page.updateFieldAtSlot(
                slot,
                field,
                MvccRawStoreFormat.longValue(transaction, newValue),
                null);
    }

    private static long longField(
            Transaction transaction,
            Page page,
            int slot,
            int field) throws StandardException {
        StoreDataValue value = MvccRawStoreFormat.longValue(transaction, 0L);
        page.fetchFieldFromSlot(slot, field, value);
        return StoreTypeUtil.getLong(value);
    }

    private static void verifyCounts(
            String sequence,
            Map<VersionIdentity, Integer> expected,
            Map<VersionIdentity, Integer> actual) {
        for (Map.Entry<VersionIdentity, Integer> entry : expected.entrySet()) {
            int actualCount = actual.getOrDefault(entry.getKey(), 0);
            if (actualCount != entry.getValue()) {
                throw new IllegalStateException(
                        "RawStore MVCC ordered-index " + sequence
                                + " stamping mismatch for row " + entry.getKey().rowId()
                                + ", version " + entry.getKey().versionId()
                                + ": expected " + entry.getValue()
                                + " entries, found " + actualCount);
            }
        }
    }

    private record VersionIdentity(long rowId, long versionId) {
    }
}
