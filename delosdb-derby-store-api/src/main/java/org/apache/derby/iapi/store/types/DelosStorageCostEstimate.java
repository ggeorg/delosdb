/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCostEstimate

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
package org.apache.derby.iapi.store.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only storage-statistics-derived cost checkpoint for one storage target.
 *
 * <p>This is an optimizer-facing <em>candidate</em> estimate, not an optimizer
 * decision. It deliberately records whether DelosDB storage-cost integration was
 * explicitly enabled and whether Derby consumed the estimate. For this checkpoint
 * Derby consumption remains disabled so existing plans stay stable by default.</p>
 */
public record DelosStorageCostEstimate(String providerId,
                                       int segment,
                                       long containerId,
                                       boolean readOnly,
                                       boolean storageStatisticsEnabled,
                                       boolean consumedByDerbyOptimizer,
                                       boolean proofOnly,
                                       long logicalRowCount,
                                       long physicalVersionCount,
                                       long estimatedPageCount,
                                       long estimatedStorageBytes,
                                       long estimatedFullScanCost,
                                       long estimatedRowFetchCost,
                                       long estimatedIndexLookupCost,
                                       List<String> observations) {
    private static final long DEFAULT_PAGE_SIZE = 4096L;

    public DelosStorageCostEstimate {
        providerId = DelosStorageProviderIds.normalize(providerId);
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (logicalRowCount < 0L
                || physicalVersionCount < 0L
                || estimatedPageCount < 0L
                || estimatedStorageBytes < 0L
                || estimatedFullScanCost < 0L
                || estimatedRowFetchCost < 0L
                || estimatedIndexLookupCost < 0L) {
            throw new IllegalArgumentException("storage cost counters must not be negative");
        }
        if (!readOnly) {
            throw new IllegalArgumentException("storage cost estimates must be read-only");
        }
        if (consumedByDerbyOptimizer && proofOnly) {
            throw new IllegalArgumentException("a consumed estimate cannot be proof-only");
        }
    }

    public static DelosStorageCostEstimate fromStatistics(
            DelosStorageStatistics statistics,
            boolean storageStatisticsEnabled) {
        return fromStatistics(statistics, storageStatisticsEnabled, false, true,
                "Derby optimizer consumption remains disabled for this checkpoint",
                "optimizer consumption eligibility: fail-closed proof-only checkpoint");
    }

    public static DelosStorageCostEstimate fromStatisticsForOptimizerCosting(
            DelosStorageStatistics statistics) {
        return fromStatistics(statistics, true, true, false,
                "Derby optimizer consumption occurs through the StoreCostController seam",
                "optimizer consumption eligibility: explicit MVCC StoreCostController opt-in");
    }

    private static DelosStorageCostEstimate fromStatistics(
            DelosStorageStatistics statistics,
            boolean storageStatisticsEnabled,
            boolean consumedByDerbyOptimizer,
            boolean proofOnly,
            String optimizerObservation,
            String eligibilityObservation) {
        Objects.requireNonNull(statistics, "statistics");

        long observedStoragePages = pagesForBytes(statistics.observedStorageBytes());
        long estimatedPages = Math.max(statistics.pageCount(), observedStoragePages);
        long auxiliaryPages = saturatedAdd(
                saturatedAdd(statistics.overflowPageCount(), statistics.freeSpaceMapPageCount()),
                saturatedAdd(statistics.visibilityMapPageCount(), statistics.orderedIndexPageCount()));
        long rows = Math.max(statistics.logicalRowCount(), statistics.physicalVersionCount());
        long versionBloat = Math.max(0L, statistics.physicalVersionCount() - statistics.logicalRowCount());
        long scanRows = saturatedAdd(rows, versionBloat);
        long fullScanCost = Math.max(1L, saturatedAdd(saturatedAdd(estimatedPages, auxiliaryPages), scanRows));
        long rowFetchCost = Math.max(1L, estimatedPages == 0L ? 1L : saturatedAdd(1L, Math.min(estimatedPages, 4L)));
        long indexLookupCost = statistics.orderedIndexEntryCount() > 0L
                ? Math.max(1L, saturatedAdd(
                        logarithmicCost(statistics.orderedIndexEntryCount()),
                        saturatedAdd(rowFetchCost, statistics.orderedIndexPageCount())))
                : 0L;

        List<String> observations = new ArrayList<>();
        observations.add("storage cost estimate is derived from read-only storage statistics");
        observations.add("storage statistics integration enabled: " + storageStatisticsEnabled);
        observations.add(optimizerObservation);
        observations.add("provider: " + statistics.providerId());
        observations.add("estimated pages: " + estimatedPages);
        observations.add("estimated storage bytes: " + statistics.observedStorageBytes());
        observations.add("ordered index pages: " + statistics.orderedIndexPageCount());
        observations.add("ordered index entries: " + statistics.orderedIndexEntryCount());
        observations.add("overflow pages: " + statistics.overflowPageCount());
        observations.add("free-space map pages: " + statistics.freeSpaceMapPageCount());
        observations.add("visibility map pages: " + statistics.visibilityMapPageCount());
        observations.add("physical/logical version bloat: " + versionBloat);
        observations.add(eligibilityObservation);

        return new DelosStorageCostEstimate(
                statistics.providerId(),
                statistics.segment(),
                statistics.containerId(),
                true,
                storageStatisticsEnabled,
                consumedByDerbyOptimizer,
                proofOnly,
                statistics.logicalRowCount(),
                statistics.physicalVersionCount(),
                estimatedPages,
                statistics.observedStorageBytes(),
                fullScanCost,
                rowFetchCost,
                indexLookupCost,
                observations);
    }

    public boolean hasIndexLookupCost() {
        return estimatedIndexLookupCost > 0L;
    }

    public boolean optimizerConsumptionEligible() {
        return storageStatisticsEnabled
                && readOnly
                && !proofOnly
                && estimatedFullScanCost > 0L
                && estimatedRowFetchCost > 0L
                && estimatedStorageBytes >= 0L;
    }

    public boolean failClosedForOptimizer() {
        return !optimizerConsumptionEligible();
    }

    public String decision() {
        return consumedByDerbyOptimizer ? "consumed" : "proof-only";
    }

    public String summary() {
        return providerId
                + " segment=" + segment
                + " container=" + containerId
                + " fullScanCost=" + estimatedFullScanCost
                + " rowFetchCost=" + estimatedRowFetchCost
                + " indexLookupCost=" + estimatedIndexLookupCost
                + " decision=" + decision();
    }

    private static long pagesForBytes(long bytes) {
        if (bytes <= 0L) {
            return 0L;
        }
        return Math.max(1L, ((bytes - 1L) / DEFAULT_PAGE_SIZE) + 1L);
    }

    private static long saturatedAdd(long left, long right) {
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long logarithmicCost(long entries) {
        long value = Math.max(1L, entries);
        long cost = 1L;
        while (value > 1L) {
            value = (value + 1L) / 2L;
            cost++;
        }
        return cost;
    }
}
