/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccScanInfo

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

import java.util.Properties;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.ScanInfo;
import org.apache.derby.shared.common.error.StandardException;

/** Immutable performance snapshot for an MVCC scan. */
final class MvccScanInfo implements ScanInfo {
    private static final String SCAN_TYPE = "scanType";
    private static final String ROWS_VISITED = "numRowsVisited";
    private static final String ROWS_QUALIFIED = "numRowsQualified";
    private static final String COLUMNS_FETCHED = "numColumnsFetched";
    private static final String COLUMNS_FETCHED_BIT_SET = "columnsFetchedBitSet";
    private static final String ORDERED_CANDIDATES = "mvccOrderedCandidates";
    private static final String COVERING_CANDIDATES = "mvccCoveringCandidates";
    private static final String COVERED_CANDIDATES = "mvccCoveredCandidates";
    private static final String FALLBACK_CANDIDATES = "mvccFallbackCandidates";
    private static final String DIRECTORY_PAGE_ACQUISITIONS = "mvccDirectoryPageAcquisitions";
    private static final String DIRECTORY_PAGE_BATCH_CANDIDATES =
            "mvccDirectoryPageBatchCandidates";
    private static final String DIRECTORY_PAGE_REUSE_HITS = "mvccDirectoryPageReuseHits";
    private static final String DIRECTORY_LOGICAL_FALLBACKS = "mvccDirectoryLogicalFallbacks";
    private static final String DIRECTORY_HEAD_SUMMARY_CHECKS =
            "mvccDirectoryHeadSummaryChecks";
    private static final String DIRECTORY_HEAD_SUMMARY_HITS =
            "mvccDirectoryHeadSummaryHits";
    private static final String DIRECTORY_HEAD_SUMMARY_FALLBACKS =
            "mvccDirectoryHeadSummaryFallbacks";
    private static final String VERSION_PAGE_ACQUISITIONS = "mvccVersionPageAcquisitions";
    private static final String VERSION_SLOT_FETCHES = "mvccVersionSlotFetches";
    private static final String VISIBILITY_CHECKS = "mvccVisibilityChecks";
    private static final String VERSION_CHAIN_STEPS = "mvccVersionChainSteps";
    private static final String VERSION_LOGICAL_FALLBACKS = "mvccVersionLogicalFallbacks";

    private final String scanType;
    private final long rowsVisited;
    private final long rowsQualified;
    private final FormatableBitSet columnsFetched;
    private final MvccRawStoreIndexedReadMetrics.Snapshot indexedReadMetrics;

    MvccScanInfo(long rowsVisited, long rowsQualified, FormatableBitSet columnsFetched) {
        this(
                "delos_mvcc",
                rowsVisited,
                rowsQualified,
                columnsFetched,
                MvccRawStoreIndexedReadMetrics.EMPTY);
    }

    MvccScanInfo(
            String scanType,
            long rowsVisited,
            long rowsQualified,
            FormatableBitSet columnsFetched,
            MvccRawStoreIndexedReadMetrics.Snapshot indexedReadMetrics) {
        this.scanType = java.util.Objects.requireNonNull(scanType, "scanType");
        this.rowsVisited = rowsVisited;
        this.rowsQualified = rowsQualified;
        this.columnsFetched = columnsFetched == null
                ? null
                : (FormatableBitSet) columnsFetched.clone();
        this.indexedReadMetrics = java.util.Objects.requireNonNull(
                indexedReadMetrics,
                "indexedReadMetrics");
    }

    @Override
    public Properties getAllScanInfo(Properties properties) throws StandardException {
        Properties result = properties == null ? new Properties() : properties;
        result.setProperty(SCAN_TYPE, scanType);
        result.setProperty(ROWS_VISITED, Long.toString(rowsVisited));
        result.setProperty(ROWS_QUALIFIED, Long.toString(rowsQualified));
        if (columnsFetched == null) {
            result.setProperty(COLUMNS_FETCHED_BIT_SET, "all");
        } else {
            result.setProperty(COLUMNS_FETCHED, Integer.toString(countSetBits(columnsFetched)));
            result.setProperty(COLUMNS_FETCHED_BIT_SET, columnsFetched.toString());
        }
        result.setProperty(
                ORDERED_CANDIDATES,
                Long.toString(indexedReadMetrics.candidatesVisited()));
        result.setProperty(
                COVERING_CANDIDATES,
                Long.toString(indexedReadMetrics.coveringCandidates()));
        result.setProperty(
                COVERED_CANDIDATES,
                Long.toString(indexedReadMetrics.coveredCandidates()));
        result.setProperty(
                FALLBACK_CANDIDATES,
                Long.toString(indexedReadMetrics.fallbackCandidates()));
        result.setProperty(
                DIRECTORY_PAGE_ACQUISITIONS,
                Long.toString(indexedReadMetrics.directoryPageAcquisitions()));
        result.setProperty(
                DIRECTORY_PAGE_BATCH_CANDIDATES,
                Long.toString(indexedReadMetrics.directoryPageBatchCandidates()));
        result.setProperty(
                DIRECTORY_PAGE_REUSE_HITS,
                Long.toString(indexedReadMetrics.directoryPageReuseHits()));
        result.setProperty(
                DIRECTORY_LOGICAL_FALLBACKS,
                Long.toString(indexedReadMetrics.directoryLogicalFallbacks()));
        result.setProperty(
                DIRECTORY_HEAD_SUMMARY_CHECKS,
                Long.toString(indexedReadMetrics.directoryHeadSummaryChecks()));
        result.setProperty(
                DIRECTORY_HEAD_SUMMARY_HITS,
                Long.toString(indexedReadMetrics.directoryHeadSummaryHits()));
        result.setProperty(
                DIRECTORY_HEAD_SUMMARY_FALLBACKS,
                Long.toString(indexedReadMetrics.directoryHeadSummaryFallbacks()));
        result.setProperty(
                VERSION_PAGE_ACQUISITIONS,
                Long.toString(indexedReadMetrics.versionPageAcquisitions()));
        result.setProperty(
                VERSION_SLOT_FETCHES,
                Long.toString(indexedReadMetrics.versionSlotFetches()));
        result.setProperty(
                VISIBILITY_CHECKS,
                Long.toString(indexedReadMetrics.visibilityChecks()));
        result.setProperty(
                VERSION_CHAIN_STEPS,
                Long.toString(indexedReadMetrics.versionChainSteps()));
        result.setProperty(
                VERSION_LOGICAL_FALLBACKS,
                Long.toString(indexedReadMetrics.versionLogicalFallbacks()));
        return result;
    }

    private static int countSetBits(FormatableBitSet bitSet) {
        int count = 0;
        for (int index = 0; index < bitSet.size(); index++) {
            if (bitSet.get(index)) {
                count++;
            }
        }
        return count;
    }
}
