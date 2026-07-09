/*

   Derby - Class org.apache.derby.iapi.store.types.DelosHeapDiagnosticsPerformanceReport

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

import java.util.Objects;

/**
 * Timing summary for repeated read-only heap diagnostics inspection.
 *
 * <p>This is deliberately a diagnostic/reporting object. It does not define a
 * performance threshold, it does not rewrite heap containers, and it does not
 * imply a Derby heap-format change.</p>
 */
public record DelosHeapDiagnosticsPerformanceReport(DelosHeapStorageDiagnostics firstSnapshot,
                                                    DelosHeapStorageDiagnostics lastSnapshot,
                                                    int iterations,
                                                    long totalNanos,
                                                    long minNanos,
                                                    long maxNanos) {
    public DelosHeapDiagnosticsPerformanceReport {
        firstSnapshot = Objects.requireNonNull(firstSnapshot, "firstSnapshot");
        lastSnapshot = Objects.requireNonNull(lastSnapshot, "lastSnapshot");
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        if (totalNanos < 0L || minNanos < 0L || maxNanos < 0L) {
            throw new IllegalArgumentException("timing counters must not be negative");
        }
        if (maxNanos < minNanos) {
            throw new IllegalArgumentException("max nanos must not be less than min nanos");
        }
    }

    public String providerId() {
        return firstSnapshot.providerId();
    }

    public int segment() {
        return firstSnapshot.segment();
    }

    public long containerId() {
        return firstSnapshot.containerId();
    }

    public long averageNanos() {
        return totalNanos / iterations;
    }

    public boolean readOnlyObserved() {
        return firstSnapshot.readOnly()
                && lastSnapshot.readOnly()
                && firstSnapshot.tableContainerBytes() == lastSnapshot.tableContainerBytes()
                && firstSnapshot.indexContainerBytes() == lastSnapshot.indexContainerBytes()
                && firstSnapshot.totalStorageBytes() == lastSnapshot.totalStorageBytes();
    }

    public String summaryLine() {
        return "provider=" + providerId()
                + " segment=" + segment()
                + " container=" + containerId()
                + " iterations=" + iterations
                + " averageNanos=" + averageNanos()
                + " minNanos=" + minNanos
                + " maxNanos=" + maxNanos
                + " readOnlyObserved=" + readOnlyObserved();
    }
}
