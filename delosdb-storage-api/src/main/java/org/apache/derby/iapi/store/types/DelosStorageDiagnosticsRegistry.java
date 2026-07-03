/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * ServiceLoader lookup for storage diagnostics implementations.
 */
public final class DelosStorageDiagnosticsRegistry {
    public static final String MVCC_PROVIDER_ID = DelosStorageProviderIds.MVCC_PROVIDER_ID;
    public static final String HEAP_PROVIDER_ID = DelosStorageProviderIds.HEAP_PROVIDER_ID;

    private DelosStorageDiagnosticsRegistry() {
    }

    public static DelosStorageDiagnostics mvcc() {
        return forProvider(MVCC_PROVIDER_ID);
    }

    public static DelosStorageDiagnostics heap() {
        return forProvider(HEAP_PROVIDER_ID);
    }

    public static DelosStorageInspector mvccInspector() {
        return inspectorForProvider(MVCC_PROVIDER_ID);
    }

    public static DelosStorageInspection inspectMvcc(int segment, long containerId) {
        return inspect(MVCC_PROVIDER_ID, segment, containerId);
    }

    public static DelosStorageInspection inspectHeap(Path databaseDirectory, int segment, long containerId) {
        DelosStorageDiagnostics diagnostics = heap();
        diagnostics.setDatabaseDirectoryForTesting(databaseDirectory);
        return DelosStorageInspection.fromDiagnostics(diagnostics, segment, containerId);
    }

    public static DelosStorageStatistics statisticsForMvcc(int segment, long containerId) {
        return storageStatistics(MVCC_PROVIDER_ID, segment, containerId);
    }

    public static DelosMvccStorageStatistics mvccStorageStatistics(int segment, long containerId) {
        return mvcc().mvccStorageStatisticsForTesting(segment, containerId);
    }

    public static DelosStorageStatistics statisticsForHeap(Path databaseDirectory, int segment, long containerId) {
        DelosStorageDiagnostics diagnostics = heap();
        diagnostics.setDatabaseDirectoryForTesting(databaseDirectory);
        return diagnostics.storageStatisticsForTesting(segment, containerId);
    }

    public static DelosHeapSanityDiagnostics inspectHeapSanity(Path databaseDirectory, int segment, long containerId) {
        DelosStorageDiagnostics diagnostics = heap();
        diagnostics.setDatabaseDirectoryForTesting(databaseDirectory);
        return diagnostics.heapSanityDiagnosticsForTesting(segment, containerId);
    }


    public static DelosHeapRawStoreBoundaryDiagnostics inspectHeapRawStoreBoundary(
            Path databaseDirectory,
            int segment,
            long containerId) {
        DelosStorageDiagnostics diagnostics = heap();
        diagnostics.setDatabaseDirectoryForTesting(databaseDirectory);
        return diagnostics.heapRawStoreBoundaryDiagnosticsForTesting(segment, containerId);
    }

    public static DelosHeapStorageDiagnostics inspectHeapStorage(
            Path databaseDirectory,
            int segment,
            long containerId,
            long... indexContainerIds) {
        DelosStorageDiagnostics diagnostics = heap();
        diagnostics.setDatabaseDirectoryForTesting(databaseDirectory);
        return diagnostics.heapStorageDiagnosticsForTesting(segment, containerId, indexContainerIds);
    }

    public static DelosHeapStorageStatistics heapStorageStatistics(
            Path databaseDirectory,
            int segment,
            long containerId,
            long... indexContainerIds) {
        DelosStorageDiagnostics diagnostics = heap();
        diagnostics.setDatabaseDirectoryForTesting(databaseDirectory);
        return diagnostics.heapStorageStatisticsForTesting(segment, containerId, indexContainerIds);
    }

    public static DelosStorageInspection inspect(String providerId, int segment, long containerId) {
        return inspectorForProvider(providerId).inspect(segment, containerId);
    }

    public static DelosStorageInspectionReport inspectionReport(DelosStorageConsistencyTarget... targets) {
        Objects.requireNonNull(targets, "targets");
        return inspectionReport(List.of(targets));
    }

    public static DelosStorageInspectionReport inspectionReport(List<DelosStorageConsistencyTarget> targets) {
        Objects.requireNonNull(targets, "targets");
        List<DelosStorageInspection> inspections = new ArrayList<>();
        for (DelosStorageConsistencyTarget target : targets) {
            BoundDiagnostics bound = bind(Objects.requireNonNull(target, "target"));
            inspections.add(DelosStorageInspection.fromDiagnostics(
                    bound.diagnostics(),
                    bound.target().segment(),
                    bound.target().containerId()));
        }
        return new DelosStorageInspectionReport(inspections);
    }

    public static DelosCrossEngineConsistencyReport consistencyReport(DelosStorageConsistencyTarget... targets) {
        Objects.requireNonNull(targets, "targets");
        return consistencyReport(List.of(targets));
    }

    public static DelosCrossEngineConsistencyReport consistencyReport(List<DelosStorageConsistencyTarget> targets) {
        Objects.requireNonNull(targets, "targets");
        List<DelosStorageConsistencyFinding> findings = new ArrayList<>();
        for (DelosStorageConsistencyTarget target : targets) {
            BoundDiagnostics bound = bind(Objects.requireNonNull(target, "target"));
            findings.add(DelosStorageConsistencyFinding.from(
                    bound.diagnostics().providerId(),
                    bound.target().segment(),
                    bound.target().containerId(),
                    bound.diagnostics().consistencyDiagnosticsForTesting(
                            bound.target().segment(),
                            bound.target().containerId())));
        }
        return new DelosCrossEngineConsistencyReport(findings);
    }

    public static DelosStorageStatisticsReport statisticsReport(DelosStorageConsistencyTarget... targets) {
        Objects.requireNonNull(targets, "targets");
        return statisticsReport(List.of(targets));
    }

    public static DelosStorageStatisticsReport statisticsReport(List<DelosStorageConsistencyTarget> targets) {
        Objects.requireNonNull(targets, "targets");
        List<DelosStorageStatistics> statistics = new ArrayList<>();
        for (DelosStorageConsistencyTarget target : targets) {
            BoundDiagnostics bound = bind(Objects.requireNonNull(target, "target"));
            statistics.add(bound.diagnostics().storageStatisticsForTesting(
                    bound.target().segment(),
                    bound.target().containerId()));
        }
        return new DelosStorageStatisticsReport(statistics);
    }

    public static DelosStorageStatistics storageStatistics(String providerId, int segment, long containerId) {
        return forProvider(providerId).storageStatisticsForTesting(segment, containerId);
    }

    public static DelosStorageCostEstimate storageCostEstimate(String providerId, int segment, long containerId) {
        return DelosStorageCostIntegration.estimate(storageStatistics(providerId, segment, containerId));
    }

    public static DelosStorageCostReport costReport(DelosStorageConsistencyTarget... targets) {
        Objects.requireNonNull(targets, "targets");
        return costReport(List.of(targets));
    }

    public static DelosStorageCostReport costReport(List<DelosStorageConsistencyTarget> targets) {
        return DelosStorageCostIntegration.report(statisticsReport(targets));
    }

    public static DelosMvccStorageStatistics mvccStorageStatistics(String providerId, int segment, long containerId) {
        String normalizedProviderId = DelosStorageProviderIds.normalize(providerId);
        if (!DelosStorageProviderIds.isMvcc(normalizedProviderId)) {
            throw new IllegalArgumentException("MVCC storage statistics require provider "
                    + MVCC_PROVIDER_ID + ", got " + providerId);
        }
        return forProvider(normalizedProviderId).mvccStorageStatisticsForTesting(segment, containerId);
    }

    public static DelosStorageInspector inspectorForProvider(String providerId) {
        return DelosStorageInspector.fromDiagnostics(forProvider(providerId));
    }

    public static DelosStorageDiagnostics forProvider(String providerId) {
        String normalizedProviderId = DelosStorageProviderIds.normalize(providerId);
        for (DelosStorageDiagnostics diagnostics : ServiceLoader.load(DelosStorageDiagnostics.class)) {
            if (DelosStorageProviderIds.normalize(diagnostics.providerId()).equals(normalizedProviderId)) {
                return diagnostics;
            }
        }
        throw new IllegalStateException("No Delos storage diagnostics provider found for " + providerId);
    }

    private static BoundDiagnostics bind(DelosStorageConsistencyTarget target) {
        DelosStorageDiagnostics diagnostics = forProvider(target.providerId());
        if (target.hasDatabaseDirectory()) {
            diagnostics.setDatabaseDirectoryForTesting(target.databaseDirectory());
        } else {
            diagnostics.clearDatabaseDirectoryForTesting();
        }
        return new BoundDiagnostics(target, diagnostics);
    }

    private record BoundDiagnostics(DelosStorageConsistencyTarget target,
                                    DelosStorageDiagnostics diagnostics) {
    }
}
