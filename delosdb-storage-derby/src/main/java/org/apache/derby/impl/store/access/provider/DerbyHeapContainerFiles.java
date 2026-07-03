/*

   Derby - Class org.apache.derby.impl.store.access.provider.DerbyHeapContainerFiles

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
import java.util.Objects;

/**
 * Read-only path and sizing helper for inherited Derby heap containers.
 *
 * <p>This helper is deliberately below the DelosDB diagnostics boundary. It
 * centralizes raw-store container naming and conservative page-count estimates
 * without parsing pages, changing page format, changing log format, or touching
 * the Derby catalog.</p>
 */
final class DerbyHeapContainerFiles {
    static final long DEFAULT_HEAP_PAGE_SIZE = 4096L;

    private DerbyHeapContainerFiles() {
    }

    static Path segmentDirectory(Path databaseDirectory, int segment) {
        return requireDatabaseDirectory(databaseDirectory).resolve("seg" + segment);
    }

    static Path containerPath(Path databaseDirectory, int segment, long containerId) {
        Path segmentDirectory = segmentDirectory(databaseDirectory, segment);
        Path lowerCase = segmentDirectory.resolve("c" + Long.toHexString(containerId) + ".dat");
        if (Files.exists(lowerCase)) {
            return lowerCase;
        }
        return segmentDirectory.resolve("C" + Long.toHexString(containerId) + ".DAT");
    }

    static boolean containerExists(Path containerFile) {
        return Files.isRegularFile(containerFile);
    }

    static long safeSize(Path containerFile) {
        if (!Files.isRegularFile(containerFile)) {
            return 0L;
        }
        try {
            return Files.size(containerFile);
        } catch (IOException e) {
            return 0L;
        }
    }

    static long estimatedPageCount(long bytes) {
        return bytes == 0L ? 0L : Math.max(1L, (bytes + DEFAULT_HEAP_PAGE_SIZE - 1L) / DEFAULT_HEAP_PAGE_SIZE);
    }

    static Snapshot snapshot(Path databaseDirectory, int segment, long containerId) {
        Path segmentDirectory = segmentDirectory(databaseDirectory, segment);
        Path containerFile = containerPath(databaseDirectory, segment, containerId);
        boolean segmentExists = Files.isDirectory(segmentDirectory);
        boolean containerExists = containerExists(containerFile);
        long bytes = safeSize(containerFile);
        long pageCount = containerExists && bytes > 0L ? estimatedPageCount(bytes) : 0L;
        return new Snapshot(segmentDirectory, containerFile, segmentExists, containerExists, bytes, pageCount);
    }

    private static Path requireDatabaseDirectory(Path databaseDirectory) {
        if (databaseDirectory == null) {
            throw new IllegalStateException("Heap diagnostics require a database directory");
        }
        return Objects.requireNonNull(databaseDirectory, "databaseDirectory");
    }

    record Snapshot(Path segmentDirectory,
                    Path containerFile,
                    boolean segmentExists,
                    boolean containerExists,
                    long bytes,
                    long estimatedPageCount) {
        Snapshot {
            segmentDirectory = Objects.requireNonNull(segmentDirectory, "segmentDirectory");
            containerFile = Objects.requireNonNull(containerFile, "containerFile");
            if (bytes < 0L || estimatedPageCount < 0L) {
                throw new IllegalArgumentException("heap container snapshot counters must not be negative");
            }
        }
    }
}
