/*

   Derby - Class org.apache.derby.iapi.store.types.DelosHeapStorageDiagnostics

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
import java.util.List;
import java.util.Objects;

/**
 * Read-only storage-size and raw-store summary for inherited Derby heap
 * compatibility containers.
 *
 * <p>The values are deliberately conservative observations of stable container
 * files. They are not a heap page parser, they do not rewrite heap pages, and
 * they do not imply a DelosDB heap format change.</p>
 */
public record DelosHeapStorageDiagnostics(String providerId,
                                          int segment,
                                          long containerId,
                                          Path segmentDirectory,
                                          Path tableContainerFile,
                                          List<Long> indexContainerIds,
                                          List<Path> indexContainerFiles,
                                          boolean readOnly,
                                          boolean tableContainerFileExists,
                                          long tableContainerBytes,
                                          long indexContainerBytes,
                                          long totalStorageBytes,
                                          long estimatedPageCount,
                                          long allocatedPageCount,
                                          long freePageCount,
                                          long overflowPageCount,
                                          long reusablePageCount,
                                          long estimatedCompressBeforeBytes,
                                          long estimatedCompressAfterBytes,
                                          String rawStoreSanitySummary,
                                          List<String> observations) {
    public DelosHeapStorageDiagnostics {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
        segmentDirectory = Objects.requireNonNull(segmentDirectory, "segmentDirectory");
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
                || estimatedPageCount < 0L
                || allocatedPageCount < 0L
                || freePageCount < 0L
                || overflowPageCount < 0L
                || reusablePageCount < 0L
                || estimatedCompressBeforeBytes < 0L
                || estimatedCompressAfterBytes < 0L) {
            throw new IllegalArgumentException("heap diagnostic counters must not be negative");
        }
        if (estimatedCompressAfterBytes > estimatedCompressBeforeBytes) {
            throw new IllegalArgumentException("estimated after-compress bytes must not exceed before-compress bytes");
        }
        if (totalStorageBytes != tableContainerBytes + indexContainerBytes) {
            throw new IllegalArgumentException("total storage bytes must equal table plus index bytes");
        }
    }

    public long indexContainerCount() {
        return indexContainerFiles.size();
    }

    public boolean clean() {
        return tableContainerFileExists && rawStoreSanitySummary.toLowerCase(java.util.Locale.ROOT).contains("exists");
    }
}
