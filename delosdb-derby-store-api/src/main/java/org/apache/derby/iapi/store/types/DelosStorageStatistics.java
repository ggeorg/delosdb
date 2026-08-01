/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageStatistics

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral, read-only storage-statistics snapshot for one heap or MVCC
 * storage target.
 *
 * <p>This is a reporting boundary only. It aggregates existing diagnostics and
 * file observations; it does not mutate storage, define a new storage format, or
 * make heap and MVCC share implementation classes.</p>
 */
public record DelosStorageStatistics(String providerId,
                                     int segment,
                                     long containerId,
                                     boolean readOnly,
                                     long logicalRowCount,
                                     long physicalVersionCount,
                                     long pageCount,
                                     long overflowPageCount,
                                     long reusablePageCount,
                                     long freeSpaceMapPageCount,
                                     long visibilityMapPageCount,
                                     long orderedIndexPageCount,
                                     long orderedIndexEntryCount,
                                     long pageCacheSize,
                                     long pageCacheDirtyPageCount,
                                     long attributeOverflowValueBytes,
                                     long observedStorageBytes,
                                     List<String> observations) {
    public DelosStorageStatistics {
        providerId = DelosStorageProviderIds.normalize(providerId);
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (logicalRowCount < 0L
                || physicalVersionCount < 0L
                || pageCount < 0L
                || overflowPageCount < 0L
                || reusablePageCount < 0L
                || freeSpaceMapPageCount < 0L
                || visibilityMapPageCount < 0L
                || orderedIndexPageCount < 0L
                || orderedIndexEntryCount < 0L
                || pageCacheSize < 0L
                || pageCacheDirtyPageCount < 0L
                || attributeOverflowValueBytes < 0L
                || observedStorageBytes < 0L) {
            throw new IllegalArgumentException("storage statistics counters must not be negative");
        }
    }

    public static DelosStorageStatistics fromDiagnostics(
            DelosStorageDiagnostics diagnostics,
            int segment,
            long containerId) {
        Objects.requireNonNull(diagnostics, "diagnostics");

        List<String> observations = new ArrayList<>();
        observations.add("storage statistics are read-only");
        observations.add("provider: " + diagnostics.providerId());
        observations.add("segment: " + segment);
        observations.add("container: " + containerId);

        long observedBytes = observedStorageBytes(diagnostics, segment, containerId, observations);
        return new DelosStorageStatistics(
                diagnostics.providerId(),
                segment,
                containerId,
                true,
                diagnostics.logicalRowCountForTesting(segment, containerId),
                diagnostics.physicalVersionCountForTesting(segment, containerId),
                diagnostics.pageCountForTesting(segment, containerId),
                diagnostics.overflowPageCountForTesting(segment, containerId),
                diagnostics.reusablePageCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapPageCountForTesting(segment, containerId),
                diagnostics.visibilityMapPageCountForTesting(segment, containerId),
                diagnostics.orderedIndexPageCountForTesting(segment, containerId),
                diagnostics.orderedIndexEntryCountForTesting(segment, containerId),
                diagnostics.pageCacheSizeForTesting(segment, containerId),
                diagnostics.pageCacheDirtyPageCountForTesting(segment, containerId),
                diagnostics.attributeOverflowValueBytesForTesting(segment, containerId),
                observedBytes,
                observations);
    }

    /**
     * Builds the same provider-neutral snapshot from an already-open table
     * diagnostics surface.  This keeps MVCC optimizer costing on Derby's
     * StoreCostController path without forcing the bridge through a separate
     * ServiceLoader diagnostics lookup.
     */
    public static DelosStorageStatistics fromTableDiagnostics(
            String providerId,
            int segment,
            long containerId,
            DelosStorageTableDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");

        List<String> observations = new ArrayList<>();
        observations.add("storage statistics are read-only");
        observations.add("provider: " + providerId);
        observations.add("segment: " + segment);
        observations.add("container: " + containerId);
        observations.add("statistics source: open table diagnostics");

        long observedBytes = observedStorageBytes(diagnostics, observations);
        return new DelosStorageStatistics(
                providerId,
                segment,
                containerId,
                true,
                diagnostics.logicalRowCountForTesting(),
                diagnostics.physicalVersionCountForTesting(),
                diagnostics.pageCountForTesting(),
                diagnostics.overflowPageCountForTesting(),
                diagnostics.reusablePageCountForTesting(),
                diagnostics.freeSpaceMapPageCountForTesting(),
                diagnostics.visibilityMapPageCountForTesting(),
                diagnostics.orderedIndexPageCountForTesting(),
                diagnostics.orderedIndexEntryCountForTesting(),
                diagnostics.pageCacheSizeForTesting(),
                diagnostics.pageCacheDirtyPageCountForTesting(),
                diagnostics.attributeOverflowValueBytesForTesting(),
                observedBytes,
                observations);
    }

    public boolean hasRows() {
        return logicalRowCount > 0L;
    }

    public boolean hasPages() {
        return pageCount > 0L;
    }

    public boolean hasOrderedIndexStatistics() {
        return orderedIndexPageCount > 0L || orderedIndexEntryCount > 0L;
    }

    public String summary() {
        return providerId
                + " segment=" + segment
                + " container=" + containerId
                + " rows=" + logicalRowCount
                + " pages=" + pageCount
                + " overflowPages=" + overflowPageCount
                + " orderedIndexPages=" + orderedIndexPageCount
                + " bytes=" + observedStorageBytes;
    }

    private static long observedStorageBytes(
            DelosStorageDiagnostics diagnostics,
            int segment,
            long containerId,
            List<String> observations) {
        Set<Path> paths = new LinkedHashSet<>();
        add(paths, diagnostics.pageVolumeStateFileForTesting(segment, containerId));
        add(paths, diagnostics.rowDirectoryStateFileForTesting(segment, containerId));
        add(paths, diagnostics.reusablePageIndexFileForTesting(segment, containerId));
        add(paths, diagnostics.freeSpaceMapFileForTesting(segment, containerId));
        add(paths, diagnostics.visibilityMapFileForTesting(segment, containerId));
        add(paths, diagnostics.purgeQueueFileForTesting(segment, containerId));
        add(paths, diagnostics.orderedIndexPagesFileForTesting(segment, containerId));
        add(paths, diagnostics.pageMutationLogFileForTesting(segment, containerId));
        add(paths, diagnostics.writeAheadLogFileForTesting(segment, containerId));
        add(paths, diagnostics.checkpointFileForTesting(segment, containerId));
        add(paths, diagnostics.legacySnapshotFileForTesting(segment, containerId));

        long bytes = 0L;
        for (Path path : paths) {
            long fileBytes = regularFileBytes(path);
            if (fileBytes > 0L) {
                observations.add("observed storage file: " + path + " bytes=" + fileBytes);
            }
            bytes += fileBytes;
        }
        observations.add("observed storage bytes: " + bytes);
        return bytes;
    }

    private static long observedStorageBytes(
            DelosStorageTableDiagnostics diagnostics,
            List<String> observations) {
        Set<Path> paths = new LinkedHashSet<>();
        add(paths, diagnostics.pageVolumeStateFileForTesting());
        add(paths, diagnostics.rowDirectoryStateFileForTesting());
        add(paths, diagnostics.reusablePageIndexFileForTesting());
        add(paths, diagnostics.freeSpaceMapFileForTesting());
        add(paths, diagnostics.visibilityMapFileForTesting());
        add(paths, diagnostics.purgeQueueFileForTesting());
        add(paths, diagnostics.orderedIndexPagesFileForTesting());
        add(paths, diagnostics.pageMutationLogFileForTesting());
        add(paths, diagnostics.writeAheadLogFileForTesting());
        add(paths, diagnostics.checkpointFileForTesting());
        add(paths, diagnostics.legacySnapshotFileForTesting());

        long bytes = 0L;
        for (Path path : paths) {
            long fileBytes = regularFileBytes(path);
            if (fileBytes > 0L) {
                observations.add("observed storage file: " + path + " bytes=" + fileBytes);
            }
            bytes += fileBytes;
        }
        observations.add("observed storage bytes: " + bytes);
        return bytes;
    }

    private static void add(Set<Path> paths, Path path) {
        if (path != null) {
            paths.add(path);
        }
    }

    private static long regularFileBytes(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
