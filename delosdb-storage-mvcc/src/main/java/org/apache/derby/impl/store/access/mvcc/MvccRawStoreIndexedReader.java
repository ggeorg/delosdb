/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreIndexedReader

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

import java.util.List;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/** Shared RawStore read boundary for one ordered-index candidate batch. */
final class MvccRawStoreIndexedReader implements AutoCloseable {
    private static final int DIRECTORY_PAGE_BATCH_SIZE = 64;

    record Result(MvccRawStoreTable.VisibleRow row, boolean covered) {
    }

    private final Transaction transaction;
    private final MvccRawStoreTable.Descriptor table;
    private final long snapshotSequence;
    private final MvccRawStoreVersionRows.FetchProjection projection;
    private MvccRawStoreVersionRows.FetchProjection metadataProjection;
    private final MvccRawStoreTransactionContext context;
    private ContainerHandle directoryContainer;
    private final MvccRawStoreIndexedReadMetrics metrics;
    private MvccRawStoreVersionReader versionReader;

    MvccRawStoreIndexedReader(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        this.transaction = transaction;
        this.table = table;
        this.snapshotSequence = snapshotSequence;
        this.projection = projection;
        this.context = context;
        this.metrics = new MvccRawStoreIndexedReadMetrics();
    }

    List<Result> read(
            List<MvccRawStoreOrderedIndex.Candidate> candidates,
            boolean coveringEligible) throws StandardException {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Result[] results = new Result[candidates.size()];
        int batchStart = 0;
        while (batchStart < candidates.size()) {
            int batchEnd = Math.min(
                    candidates.size(),
                    batchStart + DIRECTORY_PAGE_BATCH_SIZE);
            readDirectoryBatch(candidates, coveringEligible, batchStart, batchEnd, results);
            batchStart = batchEnd;
        }
        return List.copyOf(java.util.Arrays.asList(results));
    }

    private void readDirectoryBatch(
            List<MvccRawStoreOrderedIndex.Candidate> candidates,
            boolean coveringEligible,
            int start,
            int end,
            Result[] results) throws StandardException {
        int index = start;
        while (index < end) {
            MvccRawStoreOrderedIndex.Candidate first = candidates.get(index);
            Result anchored = readAnchoredCurrent(first);
            if (anchored != null) {
                metrics.candidateVisited(coveringEligible);
                results[index] = anchored;
                index++;
                continue;
            }
            MvccRowLocation firstLocation = first.rowLocation();
            if (!firstLocation.hasLocatorHint()) {
                metrics.candidateVisited(coveringEligible);
                results[index] = readWithDirectoryLookup(first, coveringEligible);
                index++;
                continue;
            }

            long pageNumber = firstLocation.locatorPageId();
            int groupEnd = index + 1;
            while (groupEnd < end
                    && candidates.get(groupEnd).rowLocation().hasLocatorHint()
                    && candidates.get(groupEnd).rowLocation().locatorPageId() == pageNumber) {
                groupEnd++;
            }
            readLatchedDirectoryPage(
                    candidates,
                    coveringEligible,
                    index,
                    groupEnd,
                    pageNumber,
                    results);
            index = groupEnd;
        }
    }

    private void readLatchedDirectoryPage(
            List<MvccRawStoreOrderedIndex.Candidate> candidates,
            boolean coveringEligible,
            int start,
            int end,
            long pageNumber,
            Result[] results) throws StandardException {
        MvccRawStoreTable.DirectoryRecord[] directories =
                new MvccRawStoreTable.DirectoryRecord[end - start];
        Page page = null;
        try {
            page = directoryContainer().getPage(pageNumber);
            if (page != null) {
                metrics.directoryPageAcquired();
            }
            for (int index = start; index < end; index++) {
                metrics.candidateVisited(coveringEligible);
                if (page == null) {
                    continue;
                }
                metrics.directoryPageBatchCandidate();
                if (index > start) {
                    metrics.directoryPageReuseHit();
                }
                MvccRawStoreOrderedIndex.Candidate candidate = candidates.get(index);
                directories[index - start] = coveringEligible
                        ? MvccRawStoreRowDirectory.findByHint(
                                transaction, candidate.rowLocation(), page)
                        : MvccRawStoreRowDirectory.findCurrentByHint(
                                transaction,
                                table,
                                candidate.rowLocation(),
                                projection,
                                page);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }

        for (int index = start; index < end; index++) {
            MvccRawStoreOrderedIndex.Candidate candidate = candidates.get(index);
            MvccRawStoreTable.DirectoryRecord directory = directories[index - start];
            results[index] = directory == null
                    ? readWithDirectoryLookup(candidate, coveringEligible)
                    : readResolved(candidate, coveringEligible, directory);
        }
    }

    private Result readWithDirectoryLookup(
            MvccRawStoreOrderedIndex.Candidate candidate,
            boolean coveringEligible) throws StandardException {
        MvccRawStoreTable.DirectoryRecord directory = coveringEligible
                ? MvccRawStoreRowDirectory.find(
                        transaction,
                        candidate.rowLocation(),
                        directoryContainer(),
                        metrics)
                : MvccRawStoreRowDirectory.findCurrent(
                        transaction,
                        table,
                        candidate.rowLocation(),
                        projection,
                        directoryContainer(),
                        metrics);
        return readResolved(candidate, coveringEligible, directory);
    }

    private Result readResolved(
            MvccRawStoreOrderedIndex.Candidate candidate,
            boolean coveringEligible,
            MvccRawStoreTable.DirectoryRecord directory) throws StandardException {
        MvccRawStoreTable.DirectoryRecord current = directory;
        while (true) {
            try {
                return readResolvedAt(candidate, coveringEligible, current);
            } catch (MvccRawStoreVersionReader.MissingVersionException missing) {
                // RawStore rollback changes the directory before it removes the
                // rolled-back version. The directory was read without retaining
                // its page latch, so re-resolve only when the current head moved.
                MvccRawStoreTable.DirectoryRecord refreshed = coveringEligible
                        ? MvccRawStoreRowDirectory.find(
                                transaction,
                                candidate.rowLocation(),
                                directoryContainer(),
                                metrics)
                        : MvccRawStoreRowDirectory.findCurrent(
                                transaction,
                                table,
                                candidate.rowLocation(),
                                projection,
                                directoryContainer(),
                                metrics);
                if (refreshed.head().versionId() == current.head().versionId()) {
                    throw missing;
                }
                current = refreshed;
            }
        }
    }

    private Result readResolvedAt(
            MvccRawStoreOrderedIndex.Candidate candidate,
            boolean coveringEligible,
            MvccRawStoreTable.DirectoryRecord directory) throws StandardException {
        context.observeCurrentRowAnchor(table, directory);
        if (!coveringEligible && directory.rowBearing()) {
            MvccRawStoreTable.DirectoryHeadSummary summary = directory.head().summary();
            if (summary.available()) {
                metrics.directoryHeadSummaryChecked();
                metrics.visibilityChecked();
                if (summary.visibleTo(context.transactionId(), snapshotSequence)) {
                    metrics.directoryHeadSummaryHit();
                    if (summary.tombstone()) {
                        return new Result(null, false);
                    }
                    return new Result(
                            new MvccRawStoreTable.VisibleRow(
                                    candidate.rowId(),
                                    directory.head().versionId(),
                                    directory.currentValues(),
                                    null,
                                    MvccRawStoreRowDirectory.location(
                                            candidate.rowId(), directory.handle())),
                            false);
                }
                metrics.directoryHeadSummaryFallback();
            }
        }
        if (coveringEligible && directory.head().versionId() == candidate.versionId()) {
            MvccRawStoreTable.DirectoryHeadSummary summary = directory.head().summary();
            if (summary.available()) {
                metrics.directoryHeadSummaryChecked();
                metrics.visibilityChecked();
                if (summary.visibleTo(context.transactionId(), snapshotSequence)) {
                    metrics.directoryHeadSummaryHit();
                    metrics.coveredCandidate();
                    if (summary.tombstone()) {
                        return new Result(null, true);
                    }
                    StoreDataValue[] values = new StoreDataValue[table.columnCount()];
                    values[candidate.columnId()] = StoreValueCopySupport.cloneValue(
                            candidate.key(),
                            true);
                    return new Result(
                            new MvccRawStoreTable.VisibleRow(
                                    candidate.rowId(),
                                    candidate.versionId(),
                                    values,
                                    null,
                                    MvccRawStoreRowDirectory.location(
                                            candidate.rowId(), directory.handle())),
                            true);
                }
                metrics.directoryHeadSummaryFallback();
            } else {
                MvccRawStoreTable.VersionRecord head = versionReader().findVisibleHead(
                        candidate.rowId(),
                        directory.head(),
                        candidate.versionId(),
                        context.transactionId(),
                        snapshotSequence,
                        metadataProjection());
                if (head != null) {
                    metrics.coveredCandidate();
                    if (head.tombstone()) {
                        return new Result(null, true);
                    }
                    StoreDataValue[] values = new StoreDataValue[table.columnCount()];
                    values[candidate.columnId()] = StoreValueCopySupport.cloneValue(
                            candidate.key(),
                            true);
                    return new Result(
                            new MvccRawStoreTable.VisibleRow(
                                    candidate.rowId(),
                                    candidate.versionId(),
                                    values,
                                    head.handle(),
                                    MvccRawStoreRowDirectory.location(
                                            candidate.rowId(), directory.handle())),
                            true);
                }
            }
        }

        metrics.fallbackCandidate();
        MvccRawStoreTable.VersionRecord visible = versionReader().findVisible(
                candidate.rowId(),
                directory.head(),
                context.transactionId(),
                snapshotSequence,
                projection);
        if (visible == null || visible.tombstone()) {
            return new Result(null, false);
        }
        return new Result(
                new MvccRawStoreTable.VisibleRow(
                        candidate.rowId(),
                        visible.versionId(),
                        visible.values(),
                        visible.handle(),
                        MvccRawStoreRowDirectory.location(
                                candidate.rowId(), directory.handle())),
                false);
    }

    private Result readAnchoredCurrent(
            MvccRawStoreOrderedIndex.Candidate candidate) throws StandardException {
        if (context.hasPendingVersion(table, candidate.rowId())) {
            return null;
        }
        MvccRawStoreRuntime.CurrentRowAnchor anchor =
                context.currentRowAnchor(table, candidate.rowId());
        if (anchor == null) {
            return null;
        }
        metrics.currentRowAnchorChecked();
        if (!anchor.visibleTo(snapshotSequence)) {
            metrics.currentRowAnchorFallback();
            return null;
        }
        metrics.visibilityChecked();
        if (anchor.tombstone()) {
            metrics.currentRowAnchorHit();
            return new Result(null, false);
        }
        MvccRawStoreTable.VersionRecord current = readAnchoredVersion(anchor);
        if (current == null) {
            context.invalidateCurrentRowAnchor(table, candidate.rowId(), anchor);
            metrics.currentRowAnchorFallback();
            return null;
        }
        metrics.currentRowAnchorHit();
        return new Result(
                new MvccRawStoreTable.VisibleRow(
                        candidate.rowId(),
                        current.versionId(),
                        current.values(),
                        current.handle(),
                        anchor.directoryLocation()),
                false);
    }

    private MvccRawStoreTable.VersionRecord readAnchoredVersion(
            MvccRawStoreRuntime.CurrentRowAnchor anchor) throws StandardException {
        metrics.currentVersionReadImageChecked();
        MvccRawStoreTable.VersionRecord image = context.currentVersionReadImage(table, anchor);
        if (image != null) {
            metrics.currentVersionReadImageHit();
            return MvccRawStoreVersionRows.project(image, projection);
        }
        metrics.currentVersionReadImageFallback();
        MvccRawStoreTable.VersionRecord decoded = versionReader().findAnchoredCurrent(anchor, null);
        if (decoded == null) {
            return null;
        }
        MvccRawStoreTable.VersionRecord projected =
                MvccRawStoreVersionRows.project(decoded, projection);
        context.publishCurrentVersionReadImage(table, anchor, decoded);
        return projected;
    }

    private ContainerHandle directoryContainer() throws StandardException {
        if (directoryContainer == null) {
            directoryContainer = transaction.openContainer(
                    table.metadataContainer(),
                    MvccRawStorePhysicalLocking.rowLevel(transaction),
                    ContainerHandle.MODE_READONLY);
        }
        return directoryContainer;
    }

    private MvccRawStoreVersionRows.FetchProjection metadataProjection() {
        if (metadataProjection == null) {
            metadataProjection = MvccRawStoreVersionRows.metadataProjection(table);
        }
        return metadataProjection;
    }

    private MvccRawStoreVersionReader versionReader() throws StandardException {
        if (versionReader == null) {
            versionReader = new MvccRawStoreVersionReader(transaction, table, metrics);
        }
        return versionReader;
    }

    MvccRawStoreIndexedReadMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    @Override
    public void close() {
        if (versionReader != null) {
            versionReader.close();
        }
        if (directoryContainer != null) {
            directoryContainer.close();
        }
    }
}
