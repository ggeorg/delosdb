/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerateState

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.ggeorg.delosdb.storage.mvcc.MvccRow;
import io.github.ggeorg.delosdb.storage.mvcc.MvccScan;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;

import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Shared state behind the inherited MVCC conglomerate provider.
 *
 * <p>MODULE9A turned the static map into a cache instead of the restart
 * authority. MODULE11A/11B moved committed-row and row-directory reload
 * authority to the existing Delos page-volume backed MVCC table, while still
 * keeping all reads and writes behind the inherited Derby store/access
 * provider. MODULE11C removes the old MODULE9A snapshot fallback as an
 * authority.</p>
 */
final class MvccConglomerateState {
    private final ContainerKey key;
    private final Path retiredSnapshotFile;
    private final Path transactionStatusFile;
    private final InheritedMvccPageVolumeStateStore pageVolumeStateStore;
    private final MvccTransactionStatusStore transactionStatusStore;
    private final MvccTable<Long, StoreDataValue[]> table = new MvccTable<>();
    private final MvccTransactionManager transactions;
    private long nextRowId = 1L;

    MvccConglomerateState(ContainerKey key, Path databaseDirectory) {
        this.key = key;
        this.retiredSnapshotFile = retiredSnapshotFile(databaseDirectory, key);
        this.transactionStatusFile = transactionStatusFile(databaseDirectory, key);
        this.pageVolumeStateStore = InheritedMvccPageVolumeStateStore.open(databaseDirectory, key);
        this.transactionStatusStore = transactionStatusFile == null || key.getContainerId() == 0L
                ? MvccTransactionStatusStore.disabled()
                : MvccTransactionStatusStore.open(transactionStatusFile);
        this.transactions = new MvccTransactionManager(transactionStatusStore);
        loadCommittedState();
    }

    ContainerKey key() {
        return key;
    }

    MvccTable<Long, StoreDataValue[]> table() {
        return table;
    }

    MvccTransactionManager transactions() {
        return transactions;
    }

    synchronized long nextRowId() {
        return nextRowId++;
    }

    /**
     * Persists the current committed visible state through the MODULE11 page-volume
     * state store. The old MODULE9A snapshot file is no longer a reload authority.
     */
    synchronized void persistCommittedState() {
        pageVolumeStateStore.persistVisibleRows(visibleRows());
    }

    synchronized void dropDurableState() {
        try {
            pageVolumeStateStore.drop();
            if (retiredSnapshotFile != null) {
                Files.deleteIfExists(retiredSnapshotFile);
            }
            if (transactionStatusFile != null) {
                Files.deleteIfExists(transactionStatusFile);
            }
            Path pageMutationLogFile = pageVolumeStateStore.pageMutationLogFile();
            if (pageMutationLogFile != null) {
                Files.deleteIfExists(pageMutationLogFile);
            }
            Path writeAheadLogFile = pageVolumeStateStore.writeAheadLogFile();
            if (writeAheadLogFile != null) {
                Files.deleteIfExists(writeAheadLogFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete inherited MVCC state for " + key, e);
        }
    }

    Path pageVolumeStateFileForTesting() {
        return pageVolumeStateStore.pageFile();
    }

    Path rowDirectoryStateFileForTesting() {
        return pageVolumeStateStore.rowDirectoryFile();
    }

    Path pageMutationLogFileForTesting() {
        return pageVolumeStateStore.pageMutationLogFile();
    }

    Path writeAheadLogFileForTesting() {
        return pageVolumeStateStore.writeAheadLogFile();
    }

    /**
     * Returns the old MODULE9A snapshot location so smokes can assert that it is
     * absent or inert. Production reload no longer reads this file.
     */
    Path legacySnapshotFileForTesting() {
        return retiredSnapshotFile;
    }

    synchronized MvccRowLocation rowLocationFor(long rowId) {
        return pageVolumeStateStore.rowHeadForInheritedRowId(rowId)
                .map(head -> new MvccRowLocation(
                        rowId,
                        head.headLocator().pageId().value(),
                        head.headLocator().slotId()))
                .orElseGet(() -> new MvccRowLocation(rowId));
    }

    synchronized void close() {
        pageVolumeStateStore.close();
    }

    private void loadCommittedState() {
        if (pageVolumeStateStore.hasDurableState()) {
            hydrateCommittedRows(
                    pageVolumeStateStore.loadVisibleRows(),
                    pageVolumeStateStore.nextInheritedRowId());
        }
    }

    private void hydrateCommittedRows(
            List<InheritedMvccPageVolumeStateStore.PersistedRow> rows,
            long storedNextRowId) {
        if (rows.isEmpty()) {
            nextRowId = Math.max(nextRowId, storedNextRowId);
            return;
        }
        MvccTransaction hydrator = transactions.begin();
        try {
            long maxRowId = 0L;
            for (InheritedMvccPageVolumeStateStore.PersistedRow row : rows) {
                table.insert(row.rowId(), row.values(), hydrator);
                maxRowId = Math.max(maxRowId, row.rowId());
            }
            transactions.commit(hydrator);
            nextRowId = Math.max(storedNextRowId, maxRowId + 1L);
        } catch (RuntimeException failure) {
            transactions.abort(hydrator);
            throw failure;
        }
    }

    private List<InheritedMvccPageVolumeStateStore.PersistedRow> visibleRows() {
        MvccTransaction reader = transactions.begin();
        try {
            MvccSnapshot snapshot = transactions.snapshot(reader);
            List<InheritedMvccPageVolumeStateStore.PersistedRow> rows = new ArrayList<>();
            try (MvccScan<Long, StoreDataValue[]> scan = table.openScan(snapshot, transactions)) {
                while (scan.next()) {
                    MvccRow<Long, StoreDataValue[]> row = scan.row();
                    rows.add(new InheritedMvccPageVolumeStateStore.PersistedRow(
                            row.key(),
                            MvccConglomerateController.cloneRow(row.value())));
                }
            } catch (StandardException e) {
                throw new IllegalStateException("Could not clone inherited MVCC row for persistence", e);
            }
            return List.copyOf(rows);
        } finally {
            transactions.abort(reader);
        }
    }

    private static Path retiredSnapshotFile(Path databaseDirectory, ContainerKey key) {
        Path directory = InheritedMvccPageVolumeStateStore.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + key.getSegmentId() + "-" + key.getContainerId() + ".snapshot");
    }

    private static Path transactionStatusFile(Path databaseDirectory, ContainerKey key) {
        Path directory = InheritedMvccPageVolumeStateStore.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + key.getSegmentId() + "-" + key.getContainerId() + ".txstatus");
    }


}
