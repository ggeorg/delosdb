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

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/** Shared RawStore read boundary for one ordered-index candidate batch. */
final class MvccRawStoreIndexedReader implements AutoCloseable {
    record Result(MvccRawStoreTable.VisibleRow row, boolean covered) {
    }

    private final Transaction transaction;
    private final MvccRawStoreTable.Descriptor table;
    private final long snapshotSequence;
    private final MvccRawStoreVersionRows.FetchProjection projection;
    private MvccRawStoreVersionRows.FetchProjection metadataProjection;
    private final MvccRawStoreTransactionContext context;
    private final ContainerHandle directoryContainer;
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
        directoryContainer = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
    }

    Result read(
            MvccRawStoreOrderedIndex.Candidate candidate,
            boolean coveringEligible) throws StandardException {
        metrics.candidateVisited(coveringEligible);
        MvccRawStoreTable.DirectoryRecord directory = MvccRawStoreRowDirectory.find(
                transaction,
                candidate.rowLocation(),
                directoryContainer,
                metrics);
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
                                    null),
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
                        metadataProjection(),
                        context);
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
                                    head.handle()),
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
                projection,
                context);
        if (visible == null || visible.tombstone()) {
            return new Result(null, false);
        }
        return new Result(
                new MvccRawStoreTable.VisibleRow(
                        candidate.rowId(),
                        visible.versionId(),
                        visible.values(),
                        visible.handle()),
                false);
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
