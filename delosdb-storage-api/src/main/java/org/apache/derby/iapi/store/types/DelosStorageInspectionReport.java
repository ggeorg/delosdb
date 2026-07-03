/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageInspectionReport

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral read-only inspection report for heap and MVCC storage
 * targets.
 *
 * <p>The report deliberately aggregates existing provider diagnostics. It does
 * not define a new storage format, mutate containers, or require heap and MVCC
 * to share implementation classes.</p>
 */
public record DelosStorageInspectionReport(List<DelosStorageInspection> inspections) {
    public DelosStorageInspectionReport {
        inspections = List.copyOf(Objects.requireNonNull(inspections, "inspections"));
    }

    public int targetCount() {
        return inspections.size();
    }

    public boolean clean() {
        return inspections.stream()
                .allMatch(inspection -> inspection.consistencyDiagnostics().errorCount() == 0);
    }

    public int errorCount() {
        return inspections.stream()
                .mapToInt(inspection -> inspection.consistencyDiagnostics().errorCount())
                .sum();
    }

    public Set<String> providerIds() {
        Set<String> providers = new LinkedHashSet<>();
        for (DelosStorageInspection inspection : inspections) {
            providers.add(inspection.providerId());
        }
        return providers;
    }

    public List<DelosStorageInspection> failedInspections() {
        List<DelosStorageInspection> failures = new ArrayList<>();
        for (DelosStorageInspection inspection : inspections) {
            if (inspection.consistencyDiagnostics().errorCount() != 0) {
                failures.add(inspection);
            }
        }
        return List.copyOf(failures);
    }

    public DelosStorageInspection inspection(String providerId, int segment, long containerId) {
        String normalizedProviderId = normalize(providerId);
        for (DelosStorageInspection inspection : inspections) {
            if (normalize(inspection.providerId()).equals(normalizedProviderId)
                    && inspection.segment() == segment
                    && inspection.containerId() == containerId) {
                return inspection;
            }
        }
        throw new IllegalArgumentException("No storage inspection for "
                + providerId + " segment=" + segment + " container=" + containerId);
    }

    public List<String> summaries() {
        List<String> summaries = new ArrayList<>();
        for (DelosStorageInspection inspection : inspections) {
            summaries.add(inspection.providerId()
                    + " segment=" + inspection.segment()
                    + " container=" + inspection.containerId()
                    + " pages=" + inspection.pageDiagnostics().pageCount()
                    + " rows=" + inspection.pageDiagnostics().logicalRowCount()
                    + " errors=" + inspection.consistencyDiagnostics().errorCount()
                    + " status=" + inspection.checkpointStatus());
        }
        return List.copyOf(summaries);
    }

    private static String normalize(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}
