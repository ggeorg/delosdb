/*

   Derby - Class org.apache.derby.iapi.store.types.DelosHeapStorageStatistics

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Heap-specific, read-only storage-statistics snapshot for inherited Derby
 * heap compatibility containers.
 *
 * <p>This is a statistics/reporting boundary only. It observes existing heap
 * diagnostics and container files; it does not parse heap pages, rewrite heap
 * pages, change Derby raw-log format, or affect SQL planning.</p>
 */
public record DelosHeapStorageStatistics(String providerId,
                                         int segment,
                                         long containerId,
                                         boolean readOnly,
                                         boolean tableContainerFileExists,
                                         Path tableContainerFile,
                                         long tableContainerBytes,
                                         List<Long> indexContainerIds,
                                         List<Path> indexContainerFiles,
                                         long indexContainerBytes,
                                         long totalStorageBytes,
                                         long estimatedHeapPageCount,
                                         long estimatedIndexPageCount,
                                         long estimatedTotalPageCount,
                                         long allocatedPageCount,
                                         long freePageCount,
                                         long overflowPageCount,
                                         long reusablePageCount,
                                         long estimatedCompressBeforeBytes,
                                         long estimatedCompressAfterBytes,
                                         String rawStoreSanitySummary,
                                         boolean clean,
                                         long observedStorageBytes,
                                         List<String> observations) {
    public DelosHeapStorageStatistics {
        providerId = DelosStorageProviderIds.normalize(providerId);
        if (!DelosStorageProviderIds.isHeap(providerId)) {
            throw new IllegalArgumentException("heap storage statistics require provider "
                    + DelosStorageProviderIds.HEAP_PROVIDER_ID + ", got " + providerId);
        }
        tableContainerFile = Objects.requireNonNull(tableContainerFile, "tableContainerFile");
        indexContainerIds = List.copyOf(Objects.requireNonNull(indexContainerIds, "indexContainerIds"));
        indexContainerFiles = List.copyOf(Objects.requireNonNull(indexContainerFiles, "indexContainerFiles"));
        rawStoreSanitySummary = Objects.requireNonNull(rawStoreSanitySummary, "rawStoreSanitySummary");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (indexContainerIds.size() != indexContainerFiles.size()) {
            throw new IllegalArgumentException("index container ids/files must have the same size");
        }
        if (tableContainerBytes < 0L
                || indexContainerBytes < 0L
                || totalStorageBytes < 0L
                || estimatedHeapPageCount < 0L
                || estimatedIndexPageCount < 0L
                || estimatedTotalPageCount < 0L
                || allocatedPageCount < 0L
                || freePageCount < 0L
                || overflowPageCount < 0L
                || reusablePageCount < 0L
                || estimatedCompressBeforeBytes < 0L
                || estimatedCompressAfterBytes < 0L
                || observedStorageBytes < 0L) {
            throw new IllegalArgumentException("heap storage statistics counters must not be negative");
        }
        if (totalStorageBytes != tableContainerBytes + indexContainerBytes) {
            throw new IllegalArgumentException("total storage bytes must equal table plus index bytes");
        }
        if (observedStorageBytes != totalStorageBytes) {
            throw new IllegalArgumentException("observed storage bytes must equal total heap storage bytes");
        }
        if (estimatedTotalPageCount != estimatedHeapPageCount + estimatedIndexPageCount) {
            throw new IllegalArgumentException("estimated total pages must equal heap plus index pages");
        }
        if (estimatedCompressAfterBytes > estimatedCompressBeforeBytes) {
            throw new IllegalArgumentException("estimated after-compress bytes must not exceed before-compress bytes");
        }
    }

    public static DelosHeapStorageStatistics fromDiagnostics(
            DelosStorageDiagnostics diagnostics,
            int segment,
            long containerId,
            long... indexContainerIds) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        DelosHeapStorageDiagnostics heapDiagnostics = diagnostics.heapStorageDiagnosticsForTesting(
                segment, containerId, indexContainerIds);
        List<String> observations = new ArrayList<>(heapDiagnostics.observations());
        observations.add("heap storage statistics are derived from read-only heap diagnostics");
        observations.add("heap page format remains Derby-compatible and unchanged");
        observations.add("raw log format remains Derby-compatible and unchanged");
        observations.add("optimizer/cost integration is not enabled by this report");

        long estimatedIndexPages = estimatePages(heapDiagnostics.indexContainerBytes(),
                heapDiagnostics.indexContainerFiles());
        long estimatedTotalPages = heapDiagnostics.estimatedPageCount() + estimatedIndexPages;

        return new DelosHeapStorageStatistics(
                diagnostics.providerId(),
                segment,
                containerId,
                heapDiagnostics.readOnly(),
                heapDiagnostics.tableContainerFileExists(),
                heapDiagnostics.tableContainerFile(),
                heapDiagnostics.tableContainerBytes(),
                heapDiagnostics.indexContainerIds(),
                heapDiagnostics.indexContainerFiles(),
                heapDiagnostics.indexContainerBytes(),
                heapDiagnostics.totalStorageBytes(),
                heapDiagnostics.estimatedPageCount(),
                estimatedIndexPages,
                estimatedTotalPages,
                heapDiagnostics.allocatedPageCount(),
                heapDiagnostics.freePageCount(),
                heapDiagnostics.overflowPageCount(),
                heapDiagnostics.reusablePageCount(),
                heapDiagnostics.estimatedCompressBeforeBytes(),
                heapDiagnostics.estimatedCompressAfterBytes(),
                heapDiagnostics.rawStoreSanitySummary(),
                heapDiagnostics.clean(),
                heapDiagnostics.totalStorageBytes(),
                observations);
    }

    public long indexContainerCount() {
        return indexContainerFiles.size();
    }

    public boolean hasIndexStorageStatistics() {
        return indexContainerCount() > 0L && indexContainerBytes > 0L;
    }

    public boolean hasHeapPageStatistics() {
        return estimatedHeapPageCount > 0L;
    }

    public boolean hasRawStoreSanitySummary() {
        return !rawStoreSanitySummary.isBlank();
    }

    public boolean compressEstimateValid() {
        return estimatedCompressAfterBytes <= estimatedCompressBeforeBytes;
    }

    private static long estimatePages(long bytes, List<Path> files) {
        if (bytes <= 0L || files.isEmpty()) {
            return 0L;
        }
        long pages = 0L;
        for (Path file : files) {
            long fileBytes = regularFileBytes(file);
            if (fileBytes > 0L) {
                pages += Math.max(1L, (fileBytes + 4095L) / 4096L);
            }
        }
        if (pages == 0L) {
            pages = Math.max(1L, (bytes + 4095L) / 4096L);
        }
        return pages;
    }

    private static long regularFileBytes(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (java.io.IOException ignored) {
            return 0L;
        }
    }
}
