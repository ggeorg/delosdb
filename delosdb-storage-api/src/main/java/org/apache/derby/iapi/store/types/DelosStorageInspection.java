/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageInspection

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

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral snapshot of storage state for one table/container.
 *
 * <p>This is intentionally a read-only inspection object. It gives DelosDB one
 * stable vocabulary for heap and MVCC diagnostics without putting either
 * engine's implementation classes on the caller's compile path.</p>
 */
public record DelosStorageInspection(String providerId,
                                     int segment,
                                     long containerId,
                                     String checkpointStatus,
                                     DelosStoragePageDiagnostics pageDiagnostics,
                                     DelosStoragePageCacheDiagnostics pageCacheDiagnostics,
                                     DelosStorageConsistencyDiagnostics consistencyDiagnostics,
                                     DelosVacuumOutcome lastVacuumOutcome,
                                     Map<String, Path> files) {
    public static final String PAGE_VOLUME_FILE = "pageVolume";
    public static final String ROW_DIRECTORY_FILE = "rowDirectory";
    public static final String REUSABLE_PAGE_INDEX_FILE = "reusablePageIndex";
    public static final String PAGE_MUTATION_LOG_FILE = "pageMutationLog";
    public static final String PURGE_QUEUE_FILE = "purgeQueue";
    public static final String WRITE_AHEAD_LOG_FILE = "writeAheadLog";
    public static final String CHECKPOINT_FILE = "checkpoint";
    public static final String LEGACY_SNAPSHOT_FILE = "legacySnapshot";

    public DelosStorageInspection {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
        checkpointStatus = checkpointStatus == null ? "" : checkpointStatus;
        pageDiagnostics = Objects.requireNonNull(pageDiagnostics, "pageDiagnostics");
        pageCacheDiagnostics = Objects.requireNonNull(pageCacheDiagnostics, "pageCacheDiagnostics");
        consistencyDiagnostics = Objects.requireNonNull(consistencyDiagnostics, "consistencyDiagnostics");
        lastVacuumOutcome = Objects.requireNonNull(lastVacuumOutcome, "lastVacuumOutcome");
        files = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(files, "files")));
    }

    public static DelosStorageInspection fromDiagnostics(DelosStorageDiagnostics diagnostics,
                                                         int segment,
                                                         long containerId) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        Map<String, Path> files = new LinkedHashMap<>();
        putIfPresent(files, PAGE_VOLUME_FILE, diagnostics.pageVolumeStateFileForTesting(segment, containerId));
        putIfPresent(files, ROW_DIRECTORY_FILE, diagnostics.rowDirectoryStateFileForTesting(segment, containerId));
        putIfPresent(files, REUSABLE_PAGE_INDEX_FILE,
                diagnostics.reusablePageIndexFileForTesting(segment, containerId));
        putIfPresent(files, PAGE_MUTATION_LOG_FILE,
                diagnostics.pageMutationLogFileForTesting(segment, containerId));
        putIfPresent(files, PURGE_QUEUE_FILE, diagnostics.purgeQueueFileForTesting(segment, containerId));
        putIfPresent(files, WRITE_AHEAD_LOG_FILE, diagnostics.writeAheadLogFileForTesting(segment, containerId));
        putIfPresent(files, CHECKPOINT_FILE, diagnostics.checkpointFileForTesting(segment, containerId));
        putIfPresent(files, LEGACY_SNAPSHOT_FILE, diagnostics.legacySnapshotFileForTesting(segment, containerId));

        return new DelosStorageInspection(
                diagnostics.providerId(),
                segment,
                containerId,
                diagnostics.checkpointStatusForTesting(segment, containerId),
                diagnostics.pageDiagnosticsForTesting(segment, containerId),
                diagnostics.pageCacheDiagnosticsForTesting(segment, containerId),
                diagnostics.consistencyDiagnosticsForTesting(segment, containerId),
                diagnostics.lastVacuumOutcomeForTesting(segment, containerId),
                files);
    }

    public Path file(String name) {
        return files.get(name);
    }

    private static void putIfPresent(Map<String, Path> files, String name, Path path) {
        if (path != null) {
            files.put(name, path);
        }
    }
}
