/*

   Derby - Class org.apache.derby.iapi.store.types.DelosHeapRawStoreBoundaryDiagnostics

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
 * Read-only diagnostic proof that DelosDB observes the inherited Derby heap
 * raw-store boundary without taking ownership of the heap page or log format.
 */
public record DelosHeapRawStoreBoundaryDiagnostics(String providerId,
                                                   int segment,
                                                   long containerId,
                                                   Path segmentDirectory,
                                                   Path containerFile,
                                                   boolean readOnly,
                                                   boolean containerFileExists,
                                                   long pageSizeBytes,
                                                   long containerBytes,
                                                   long estimatedPageCount,
                                                   boolean heapPageFormatMutationAllowed,
                                                   boolean rawLogFormatMutationAllowed,
                                                   boolean catalogMutationAllowed,
                                                   List<String> observations) {
    public DelosHeapRawStoreBoundaryDiagnostics {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
        segmentDirectory = Objects.requireNonNull(segmentDirectory, "segmentDirectory");
        containerFile = Objects.requireNonNull(containerFile, "containerFile");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (pageSizeBytes < 0L || containerBytes < 0L || estimatedPageCount < 0L) {
            throw new IllegalArgumentException("heap raw-store boundary counters must not be negative");
        }
        if (!readOnly) {
            throw new IllegalArgumentException("heap raw-store boundary diagnostics must be read-only");
        }
        if (heapPageFormatMutationAllowed || rawLogFormatMutationAllowed || catalogMutationAllowed) {
            throw new IllegalArgumentException("heap boundary diagnostics must not permit compatibility mutations");
        }
    }

    public boolean clean() {
        return readOnly
                && containerFileExists
                && pageSizeBytes > 0L
                && containerBytes > 0L
                && estimatedPageCount > 0L
                && !heapPageFormatMutationAllowed
                && !rawLogFormatMutationAllowed
                && !catalogMutationAllowed;
    }
}
