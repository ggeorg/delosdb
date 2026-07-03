/*

   Derby - Class org.apache.derby.impl.store.access.provider.DerbyHeapStorageDiagnostics

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

package org.apache.derby.impl.store.access.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.derby.iapi.store.types.DelosHeapSanityDiagnostics;
import org.apache.derby.iapi.store.types.DelosHeapStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.StoreRowLocation;

/**
 * Read-only diagnostics adapter for Derby-compatible heap containers.
 *
 * <p>This class deliberately observes only the stable raw container file shape
 * used by Derby heap compatibility mode. It does not parse or mutate heap pages,
 * and it does not introduce a dependency from heap/raw-store code to MVCC.</p>
 */
public final class DerbyHeapStorageDiagnostics implements DelosStorageDiagnostics {
    private static final long DEFAULT_HEAP_PAGE_SIZE = 4096L;

    private volatile Path databaseDirectory;

    @Override
    public String providerId() {
        return DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID;
    }

    @Override
    public void setDatabaseDirectoryForTesting(Path databaseDirectory) {
        this.databaseDirectory = Objects.requireNonNull(databaseDirectory, "databaseDirectory");
    }

    @Override
    public void clearDatabaseDirectoryForTesting() {
        this.databaseDirectory = null;
    }

    @Override
    public void clearRuntimeStateForTesting() {
        clearDatabaseDirectoryForTesting();
    }

    @Override
    public int runtimeStateCountForTesting() {
        return databaseDirectory == null ? 0 : 1;
    }

    @Override
    public Path pageVolumeStateFileForTesting(int segment, long containerId) {
        return heapContainerPath(segment, containerId);
    }

    @Override
    public Path rowDirectoryStateFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path reusablePageIndexFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path pageMutationLogFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path writeAheadLogFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path checkpointFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path legacySnapshotFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public String checkpointStatusForTesting(int segment, long containerId) {
        return Files.isRegularFile(heapContainerPath(segment, containerId)) ? "HEAP_FILE_OBSERVED" : "HEAP_FILE_MISSING";
    }

    @Override
    public int physicalVersionCountForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public int logicalRowCountForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public long pageCountForTesting(int segment, long containerId) {
        Path file = heapContainerPath(segment, containerId);
        if (!Files.isRegularFile(file)) {
            return 0L;
        }
        try {
            long bytes = Files.size(file);
            return bytes == 0L ? 0L : Math.max(1L, (bytes + DEFAULT_HEAP_PAGE_SIZE - 1L) / DEFAULT_HEAP_PAGE_SIZE);
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public long overflowPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long reusablePageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheMaxPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheSizeForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheHitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheMissCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheWriteCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheEvictionCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheInvalidationCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public int consistencyErrorCountForTesting(int segment, long containerId) {
        return Files.isRegularFile(heapContainerPath(segment, containerId)) ? 0 : 1;
    }

    @Override
    public String consistencySummaryForTesting(int segment, long containerId) {
        Path file = heapContainerPath(segment, containerId);
        return Files.isRegularFile(file)
                ? "heap container file exists: " + file
                : "heap container file is missing: " + file;
    }

    @Override
    public void assertConsistentForTesting(int segment, long containerId) {
        Path file = heapContainerPath(segment, containerId);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Heap container file is missing: " + file);
        }
    }

    @Override
    public DelosHeapSanityDiagnostics heapSanityDiagnosticsForTesting(int segment, long containerId) {
        Path segmentDirectory = heapSegmentDirectory(segment);
        Path file = heapContainerPath(segment, containerId);
        boolean segmentExists = Files.isDirectory(segmentDirectory);
        boolean fileExists = Files.isRegularFile(file);
        long bytes = safeSize(file);
        long pageCount = fileExists && bytes > 0L
                ? Math.max(1L, (bytes + DEFAULT_HEAP_PAGE_SIZE - 1L) / DEFAULT_HEAP_PAGE_SIZE)
                : 0L;
        long overflowPages = overflowPageCountForTesting(segment, containerId);
        long reusablePages = reusablePageCountForTesting(segment, containerId);

        List<String> observations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        observations.add("heap sanity checker is read-only");
        observations.add("segment directory: " + segmentDirectory);
        observations.add("container file: " + file);
        observations.add("container bytes: " + bytes);
        observations.add("estimated pages: " + pageCount);
        observations.add("overflow diagnostics: " + overflowPages);
        observations.add("reusable-page diagnostics: " + reusablePages);

        if (!segmentExists) {
            errors.add("heap segment directory is missing: " + segmentDirectory);
        }
        if (!fileExists) {
            errors.add("heap container file is missing: " + file);
        }
        if (fileExists && bytes <= 0L) {
            errors.add("heap container file is empty: " + file);
        }
        if (fileExists && pageCount <= 0L) {
            errors.add("heap page count is zero for non-empty container: " + file);
        }

        return new DelosHeapSanityDiagnostics(
                providerId(),
                segment,
                containerId,
                segmentDirectory,
                file,
                true,
                segmentExists,
                fileExists,
                bytes,
                pageCount,
                overflowPages,
                reusablePages,
                errors.size(),
                observations,
                errors);
    }

    @Override
    public DelosHeapStorageDiagnostics heapStorageDiagnosticsForTesting(
            int segment,
            long containerId,
            long... indexContainerIds) {
        Path segmentDirectory = heapSegmentDirectory(segment);
        Path tableFile = heapContainerPath(segment, containerId);
        boolean tableExists = Files.isRegularFile(tableFile);
        long tableBytes = safeSize(tableFile);
        long estimatedTablePages = tableExists && tableBytes > 0L
                ? Math.max(1L, (tableBytes + DEFAULT_HEAP_PAGE_SIZE - 1L) / DEFAULT_HEAP_PAGE_SIZE)
                : 0L;
        long overflowPages = overflowPageCountForTesting(segment, containerId);
        long reusablePages = reusablePageCountForTesting(segment, containerId);
        long freePages = Math.min(estimatedTablePages, reusablePages);

        List<Long> indexIds = new ArrayList<>();
        List<Path> indexFiles = new ArrayList<>();
        long indexBytes = 0L;
        if (indexContainerIds != null) {
            for (long indexContainerId : indexContainerIds) {
                Path indexFile = heapContainerPath(segment, indexContainerId);
                indexIds.add(indexContainerId);
                indexFiles.add(indexFile);
                indexBytes += safeSize(indexFile);
            }
        }

        long totalBytes = tableBytes + indexBytes;
        long estimatedBeforeCompressBytes = totalBytes;
        long estimatedAfterCompressBytes = Math.max(0L,
                estimatedBeforeCompressBytes - (freePages * DEFAULT_HEAP_PAGE_SIZE));

        List<String> observations = new ArrayList<>();
        observations.add("heap diagnostics are read-only");
        observations.add("table container file: " + tableFile);
        observations.add("table storage bytes: " + tableBytes);
        observations.add("index storage bytes: " + indexBytes);
        observations.add("estimated allocated pages: " + estimatedTablePages);
        observations.add("estimated free pages: " + freePages);
        observations.add("overflow diagnostics: " + overflowPages);
        observations.add("reusable-page diagnostics: " + reusablePages);
        observations.add("compress estimate before bytes: " + estimatedBeforeCompressBytes);
        observations.add("compress estimate after bytes: " + estimatedAfterCompressBytes);

        return new DelosHeapStorageDiagnostics(
                providerId(),
                segment,
                containerId,
                segmentDirectory,
                tableFile,
                indexIds,
                indexFiles,
                true,
                tableExists,
                tableBytes,
                indexBytes,
                totalBytes,
                estimatedTablePages,
                estimatedTablePages,
                freePages,
                overflowPages,
                reusablePages,
                estimatedBeforeCompressBytes,
                estimatedAfterCompressBytes,
                consistencySummaryForTesting(segment, containerId),
                observations);
    }

    @Override
    public boolean lastVacuumSkippedForTesting(int segment, long containerId) {
        return true;
    }

    @Override
    public String lastVacuumReasonForTesting(int segment, long containerId) {
        return "heap diagnostics are read-only";
    }

    @Override
    public int lastVacuumRemovedVersionsForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public int lastVacuumRemainingVersionsForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public void resetMutationCountersForTesting() {
    }

    @Override
    public int insertCountForTesting() {
        return 0;
    }

    @Override
    public int updateCountForTesting() {
        return 0;
    }

    @Override
    public int deleteCountForTesting() {
        return 0;
    }

    @Override
    public void resetScanCountersForTesting() {
    }

    @Override
    public int scanOpenCountForTesting() {
        return 0;
    }

    @Override
    public void resetQualifierRejectCountForTesting() {
    }

    @Override
    public int qualifierRejectCountForTesting() {
        return 0;
    }

    @Override
    public void resetCandidateIndexCountersForTesting() {
    }

    @Override
    public int candidateIndexLookupCountForTesting() {
        return 0;
    }

    @Override
    public int candidateIndexRowIdCountForTesting() {
        return 0;
    }

    @Override
    public int candidateIndexVisibilityRejectCountForTesting() {
        return 0;
    }

    @Override
    public int candidateIndexQualifierRejectCountForTesting() {
        return 0;
    }

    @Override
    public void clearTransactionsForTesting() {
    }

    @Override
    public boolean isProviderScan(Object scanController) {
        return false;
    }

    @Override
    public boolean hasLocatorHint(StoreRowLocation location) {
        return false;
    }

    private Path heapSegmentDirectory(int segment) {
        Path database = databaseDirectory;
        if (database == null) {
            throw new IllegalStateException("Heap diagnostics require a database directory");
        }
        return database.resolve("seg" + segment);
    }

    private Path heapContainerPath(int segment, long containerId) {
        Path segmentDirectory = heapSegmentDirectory(segment);
        Path lowerCase = segmentDirectory.resolve("c" + Long.toHexString(containerId) + ".dat");
        if (Files.exists(lowerCase)) {
            return lowerCase;
        }
        return segmentDirectory.resolve("C" + Long.toHexString(containerId) + ".DAT");
    }

    private static long safeSize(Path file) {
        if (!Files.isRegularFile(file)) {
            return 0L;
        }
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }
}
