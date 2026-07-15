/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccOpenContext

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

package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccOrderedIndexPageStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;

/**
 * Open-time sidecar context for inherited MVCC page-volume storage.
 *
 * <p>This keeps sidecar discovery, replay-plan construction, ordered-index
 * sidecar fallback classification, and checkpoint validation out of
 * {@link PageVolumeMvccStateStore}'s steady-state read/write API. It does not
 * own runtime behavior; it only assembles already-existing sidecars for the
 * state store constructor.</p>
 */
final class PageVolumeMvccOpenContext {
    final String storageId;
    final Path pageFile;
    final Path pageMutationLogFile;
    final Path transactionOutcomeLogFile;
    final PageVolumeMvccWriteAheadLog writeAheadLog;
    final PageVolumeMvccCheckpointStore checkpointStore;
    final MvccSubsystemRecoveryRecordStore recoveryRecordStore;
    final PageBackedMvccTable table;
    final MvccOrderedIndexPageStore orderedIndexPageStore;
    final PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason orderedIndexOpenFallbackReason;

    private PageVolumeMvccOpenContext(
            String storageId,
            Path pageFile,
            Path pageMutationLogFile,
            Path transactionOutcomeLogFile,
            PageVolumeMvccWriteAheadLog writeAheadLog,
            PageVolumeMvccCheckpointStore checkpointStore,
            MvccSubsystemRecoveryRecordStore recoveryRecordStore,
            PageBackedMvccTable table,
            MvccOrderedIndexPageStore orderedIndexPageStore,
            PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason orderedIndexOpenFallbackReason) {
        this.storageId = Objects.requireNonNull(storageId, "storageId");
        this.pageFile = Objects.requireNonNull(pageFile, "pageFile");
        this.pageMutationLogFile = Objects.requireNonNull(pageMutationLogFile, "pageMutationLogFile");
        this.transactionOutcomeLogFile = Objects.requireNonNull(transactionOutcomeLogFile, "transactionOutcomeLogFile");
        this.writeAheadLog = Objects.requireNonNull(writeAheadLog, "writeAheadLog");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.recoveryRecordStore = Objects.requireNonNull(recoveryRecordStore, "recoveryRecordStore");
        this.table = Objects.requireNonNull(table, "table");
        this.orderedIndexPageStore = orderedIndexPageStore;
        this.orderedIndexOpenFallbackReason = orderedIndexOpenFallbackReason;
    }

    static PageVolumeMvccOpenContext open(Path databaseDirectory, String storageId) throws IOException {
        Path pageFile = PageVolumeMvccPaths.pageFile(databaseDirectory, storageId);
        Path pageMutationLog = PageVolumeMvccPaths.pageMutationLogFileFor(pageFile);
        Path transactionOutcomeLog = PageVolumeMvccPaths.transactionOutcomeLogFileFor(pageFile);
        PageVolumeMvccWriteAheadLog writeAheadLog = PageVolumeMvccWriteAheadLog.open(
                databaseDirectory, storageId);
        PageVolumeMvccCheckpointStore checkpointStore = PageVolumeMvccCheckpointStore.open(
                databaseDirectory, storageId);
        MvccSubsystemRecoveryRecordStore recoveryRecordStore = MvccSubsystemRecoveryRecordStore.open(
                databaseDirectory, storageId);
        Path transactionStatusFile = PageVolumeMvccPaths.transactionStatusFile(databaseDirectory, storageId);
        var transactionStatuses = MvccTransactionStatusStore.open(transactionStatusFile).recoverStatuses();
        PageBackedMvccTable table = PageBackedMvccTable.open(
                pageFile,
                pageMutationLog,
                transactionOutcomeLog,
                recoveryRecordStore.replayPlan(),
                transactionStatuses);
        Path orderedIndexPagesPath = PageBackedMvccTable.orderedIndexPagesPath(pageFile);
        boolean orderedIndexPagesExisted = Files.exists(orderedIndexPagesPath);
        OrderedIndexOpenResult orderedIndexOpenResult = openOrderedIndexPagesSafely(
                orderedIndexPagesPath,
                orderedIndexPagesExisted,
                table.logicalRowCount());
        checkpointStore.validate(
                pageFile,
                PageBackedMvccTable.rowDirectoryPath(pageFile),
                pageMutationLog,
                writeAheadLog.path(),
                table.durableRowDirectoryHeads(),
                table.physicalVersionCount(),
                table.logicalRowCount(),
                table.durableRowDirectoryHeads().keySet().stream()
                        .mapToLong(io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId::value)
                        .max()
                        .orElse(0L) + 1L);
        return new PageVolumeMvccOpenContext(
                storageId,
                pageFile,
                pageMutationLog,
                transactionOutcomeLog,
                writeAheadLog,
                checkpointStore,
                recoveryRecordStore,
                table,
                orderedIndexOpenResult.store(),
                orderedIndexOpenResult.fallbackReason());
    }

    private static OrderedIndexOpenResult openOrderedIndexPagesSafely(
            Path orderedIndexPagesPath,
            boolean orderedIndexPagesExisted,
            int logicalRowCount) {
        try {
            MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(orderedIndexPagesPath);
            PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason fallbackReason = null;
            if (!orderedIndexPagesExisted && logicalRowCount > 0) {
                fallbackReason = PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason
                        .STALE_OR_MISSING_ORDERED_INDEX_SIDECAR;
            }
            return new OrderedIndexOpenResult(store, fallbackReason);
        } catch (IOException | RuntimeException e) {
            PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason fallbackReason = Files.exists(orderedIndexPagesPath)
                    ? PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason.MALFORMED_ORDERED_INDEX_SIDECAR
                    : PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason.STALE_OR_MISSING_ORDERED_INDEX_SIDECAR;
            return new OrderedIndexOpenResult(null, fallbackReason);
        }
    }

    private record OrderedIndexOpenResult(
            MvccOrderedIndexPageStore store,
            PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason fallbackReason) {
    }
}
