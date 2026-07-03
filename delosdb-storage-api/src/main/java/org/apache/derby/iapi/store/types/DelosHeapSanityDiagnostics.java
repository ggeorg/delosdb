/*

   Derby - Class org.apache.derby.iapi.store.types.DelosHeapSanityDiagnostics

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
 * Read-only sanity-check result for an inherited Derby heap container.
 *
 * <p>This is deliberately observational.  It describes the compatibility heap
 * container shape DelosDB can safely inspect without parsing or rewriting the
 * Derby heap page format, raw log format, or catalog state.</p>
 */
public record DelosHeapSanityDiagnostics(String providerId,
                                         int segment,
                                         long containerId,
                                         Path segmentDirectory,
                                         Path containerFile,
                                         boolean readOnly,
                                         boolean segmentDirectoryExists,
                                         boolean containerFileExists,
                                         long containerFileBytes,
                                         long estimatedPageCount,
                                         long overflowPageCount,
                                         long reusablePageCount,
                                         int errorCount,
                                         List<String> observations,
                                         List<String> errors) {
    public DelosHeapSanityDiagnostics {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
        segmentDirectory = Objects.requireNonNull(segmentDirectory, "segmentDirectory");
        containerFile = Objects.requireNonNull(containerFile, "containerFile");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (errorCount < 0) {
            throw new IllegalArgumentException("error count must not be negative");
        }
        if (containerFileBytes < 0L) {
            throw new IllegalArgumentException("container file bytes must not be negative");
        }
        if (estimatedPageCount < 0L) {
            throw new IllegalArgumentException("estimated page count must not be negative");
        }
        if (overflowPageCount < 0L) {
            throw new IllegalArgumentException("overflow page count must not be negative");
        }
        if (reusablePageCount < 0L) {
            throw new IllegalArgumentException("reusable page count must not be negative");
        }
        if (errorCount != errors.size()) {
            throw new IllegalArgumentException("error count must match error list size");
        }
    }

    public boolean clean() {
        return errorCount == 0;
    }
}
