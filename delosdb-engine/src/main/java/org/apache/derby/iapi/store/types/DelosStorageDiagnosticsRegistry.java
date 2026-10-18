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

    public static DelosStorageDiagnostics mvcc(Path databaseDirectory) {
        return mvcc().withContext(
                DelosStorageDiagnosticsContext.databaseDirectory(databaseDirectory));
    }

    public static DelosStorageDiagnostics mvccMemory(String databaseName) {
        return mvcc().withContext(
                DelosStorageDiagnosticsContext.memoryDatabase(databaseName));
    }

    public static DelosStorageMaintenanceSnapshot mvccDatabaseMaintenanceSnapshot(
            Path databaseDirectory) {
        return mvcc(databaseDirectory).databaseMaintenanceSnapshot();
    }

    public static DelosStorageMaintenanceSnapshot mvccMemoryDatabaseMaintenanceSnapshot(
            String databaseName) {
        return mvccMemory(databaseName).databaseMaintenanceSnapshot();
    }

    public static DelosDatabaseMemorySnapshot mvccDatabaseMemorySnapshot(
            Path databaseDirectory) {
        return mvcc(databaseDirectory).databaseMemorySnapshot();
    }

    public static DelosDatabaseMemorySnapshot mvccMemoryDatabaseMemorySnapshot(
            String databaseName) {
        return mvccMemory(databaseName).databaseMemorySnapshot();
    }

    public static DelosRawStoreIoSnapshot mvccDatabaseRawStoreIoSnapshot(
            Path databaseDirectory) {
        return mvcc(databaseDirectory).databaseRawStoreIoSnapshot();
    }

    public static DelosRawStoreIoSnapshot mvccMemoryDatabaseRawStoreIoSnapshot(
            String databaseName) {
        return mvccMemory(databaseName).databaseRawStoreIoSnapshot();
    }

    public static DelosStorageDiagnostics heap() {
        return forProvider(HEAP_PROVIDER_ID);
    }

    public static DelosRawStoreIoSnapshot heapDatabaseRawStoreIoSnapshot(
            Path databaseDirectory) {
        return heapDiagnostics(databaseDirectory).databaseRawStoreIoSnapshot();
    }

    public static DelosRawStoreIoSnapshot heapMemoryDatabaseRawStoreIoSnapshot(
            String databaseName) {
        return heap().withContext(
                DelosStorageDiagnosticsContext.memoryDatabase(databaseName))
                .databaseRawStoreIoSnapshot();
    }

    public static DelosHeapSanityDiagnostics inspectHeapSanity(Path databaseDirectory, int segment, long containerId) {
        DelosStorageDiagnostics diagnostics = heapDiagnostics(databaseDirectory);
        return diagnostics.heapSanityDiagnosticsForTesting(segment, containerId);
    }

    public static DelosHeapRawStoreBoundaryDiagnostics inspectHeapRawStoreBoundary(
            Path databaseDirectory,
            int segment,
            long containerId) {
        DelosStorageDiagnostics diagnostics = heapDiagnostics(databaseDirectory);
        return diagnostics.heapRawStoreBoundaryDiagnosticsForTesting(segment, containerId);
    }

    public static DelosHeapStorageDiagnostics inspectHeapStorage(
            Path databaseDirectory,
            int segment,
            long containerId,
            long... indexContainerIds) {
        DelosStorageDiagnostics diagnostics = heapDiagnostics(databaseDirectory);
        return diagnostics.heapStorageDiagnosticsForTesting(segment, containerId, indexContainerIds);
    }

    public static DelosHeapStorageStatistics heapStorageStatistics(
            Path databaseDirectory,
            int segment,
            long containerId,
            long... indexContainerIds) {
        DelosStorageDiagnostics diagnostics = heapDiagnostics(databaseDirectory);
        return diagnostics.heapStorageStatisticsForTesting(segment, containerId, indexContainerIds);
    }

    public static DelosHeapDiagnosticsPerformanceReport inspectHeapStoragePerformance(
            Path databaseDirectory,
            int segment,
            long containerId,
            int iterations,
            long... indexContainerIds) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        DelosHeapStorageDiagnostics first = null;
        DelosHeapStorageDiagnostics last = null;
        long totalNanos = 0L;
        long minNanos = Long.MAX_VALUE;
        long maxNanos = 0L;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            DelosHeapStorageDiagnostics snapshot = inspectHeapStorage(
                    databaseDirectory, segment, containerId, indexContainerIds);
            long elapsed = Math.max(0L, System.nanoTime() - start);
            if (first == null) {
                first = snapshot;
            }
            last = snapshot;
            totalNanos += elapsed;
            minNanos = Math.min(minNanos, elapsed);
            maxNanos = Math.max(maxNanos, elapsed);
        }

        return new DelosHeapDiagnosticsPerformanceReport(
                first, last, iterations, totalNanos, minNanos, maxNanos);
    }

    public static DelosStoragePredicatePushdown predicatePushdown(
            DelosStoragePredicatePushdownRequest request) {
        return DelosStoragePredicatePushdown.plan(request);
    }

    private static DelosStorageDiagnostics heapDiagnostics(Path databaseDirectory) {
        return heap().withContext(DelosStorageDiagnosticsContext.databaseDirectory(databaseDirectory));
    }

    public static DelosStorageDiagnostics forProvider(String providerId) {
        String normalizedProviderId = DelosStorageProviderIds.normalize(providerId);
        for (DelosStorageDiagnostics diagnostics : diagnosticsProviders()) {
            if (DelosStorageProviderIds.normalize(diagnostics.providerId()).equals(normalizedProviderId)) {
                return diagnostics;
            }
        }
        throw new IllegalStateException("No Delos storage diagnostics provider found for " + providerId);
    }

    private static List<DelosStorageDiagnostics> diagnosticsProviders() {
        List<DelosStorageDiagnostics> diagnostics = new ArrayList<>();
        for (DelosStorageDiagnostics provider : ServiceLoader.load(DelosStorageDiagnostics.class)) {
            diagnostics.add(provider);
        }
        return List.copyOf(diagnostics);
    }

}
