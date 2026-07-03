/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageMetadataQuery

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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only metadata-query facade over a deterministic storage provider chain.
 */
public final class DelosStorageMetadataQuery {
    private final List<DelosStorageMetadataProvider> providers;

    public DelosStorageMetadataQuery(List<DelosStorageMetadataProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        List<DelosStorageMetadataProvider> copy = new ArrayList<>();
        Set<String> providerIds = new LinkedHashSet<>();
        for (DelosStorageMetadataProvider provider : providers) {
            DelosStorageMetadataProvider nonNullProvider = Objects.requireNonNull(provider, "provider");
            String providerId = DelosStorageProviderIds.normalize(nonNullProvider.providerId());
            if (!providerIds.add(providerId)) {
                throw new IllegalArgumentException("duplicate storage metadata provider: " + providerId);
            }
            copy.add(nonNullProvider);
        }
        copy.sort(Comparator.comparingInt((DelosStorageMetadataProvider provider) -> providerRank(provider.providerId()))
                .thenComparing(provider -> DelosStorageProviderIds.normalize(provider.providerId())));
        this.providers = List.copyOf(copy);
    }

    public static DelosStorageMetadataQuery fromDiagnostics(List<DelosStorageDiagnostics> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        List<DelosStorageMetadataProvider> providers = new ArrayList<>();
        for (DelosStorageDiagnostics diagnosticProvider : diagnostics) {
            providers.add(DelosStorageMetadataProvider.fromDiagnostics(diagnosticProvider));
        }
        return new DelosStorageMetadataQuery(providers);
    }

    public List<String> providerIds() {
        List<String> ids = new ArrayList<>();
        for (DelosStorageMetadataProvider provider : providers) {
            ids.add(DelosStorageProviderIds.normalize(provider.providerId()));
        }
        return List.copyOf(ids);
    }

    public DelosStorageMetadataSnapshot snapshot(DelosStorageConsistencyTarget target) {
        Objects.requireNonNull(target, "target");
        for (DelosStorageMetadataProvider provider : providers) {
            if (provider.supports(target)) {
                return provider.snapshot(target);
            }
        }
        throw new IllegalStateException("No Delos storage metadata provider found for " + target.providerId());
    }

    public List<DelosStorageMetadataSnapshot> snapshots(DelosStorageConsistencyTarget... targets) {
        Objects.requireNonNull(targets, "targets");
        return snapshots(List.of(targets));
    }

    public List<DelosStorageMetadataSnapshot> snapshots(List<DelosStorageConsistencyTarget> targets) {
        Objects.requireNonNull(targets, "targets");
        List<DelosStorageMetadataSnapshot> snapshots = new ArrayList<>();
        for (DelosStorageConsistencyTarget target : targets) {
            snapshots.add(snapshot(Objects.requireNonNull(target, "target")));
        }
        return List.copyOf(snapshots);
    }

    public DelosStorageInspectionReport inspectionReport(List<DelosStorageConsistencyTarget> targets) {
        List<DelosStorageInspection> inspections = new ArrayList<>();
        for (DelosStorageMetadataSnapshot snapshot : snapshots(targets)) {
            inspections.add(snapshot.inspection());
        }
        return new DelosStorageInspectionReport(inspections);
    }

    public DelosCrossEngineConsistencyReport consistencyReport(List<DelosStorageConsistencyTarget> targets) {
        List<DelosStorageConsistencyFinding> findings = new ArrayList<>();
        for (DelosStorageMetadataSnapshot snapshot : snapshots(targets)) {
            findings.add(snapshot.consistencyFinding());
        }
        return new DelosCrossEngineConsistencyReport(findings);
    }

    public DelosStorageStatisticsReport statisticsReport(List<DelosStorageConsistencyTarget> targets) {
        List<DelosStorageStatistics> statistics = new ArrayList<>();
        for (DelosStorageMetadataSnapshot snapshot : snapshots(targets)) {
            statistics.add(snapshot.statistics());
        }
        return new DelosStorageStatisticsReport(statistics);
    }

    public DelosStorageCostReport costReport(List<DelosStorageConsistencyTarget> targets) {
        List<DelosStorageCostEstimate> estimates = new ArrayList<>();
        for (DelosStorageMetadataSnapshot snapshot : snapshots(targets)) {
            estimates.add(snapshot.costEstimate());
        }
        return new DelosStorageCostReport(DelosStorageCostIntegration.enabled(), false, estimates);
    }

    public DelosStorageCapabilitiesReport capabilitiesReport(List<DelosStorageConsistencyTarget> targets) {
        List<DelosStorageCapabilities> capabilities = new ArrayList<>();
        for (DelosStorageMetadataSnapshot snapshot : snapshots(targets)) {
            capabilities.add(snapshot.capabilities());
        }
        return new DelosStorageCapabilitiesReport(capabilities);
    }

    public DelosStoragePredicatePushdown predicatePushdown(
            DelosStoragePredicatePushdownRequest request) {
        Objects.requireNonNull(request, "request");
        DelosStorageMetadataSnapshot snapshot = snapshot(request.target());
        return DelosStoragePredicatePushdown.plan(snapshot.capabilities(), request);
    }

    public DelosStoragePredicatePushdownReport predicatePushdownReport(
            List<DelosStoragePredicatePushdownRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        List<DelosStoragePredicatePushdown> plans = new ArrayList<>();
        for (DelosStoragePredicatePushdownRequest request : requests) {
            plans.add(predicatePushdown(Objects.requireNonNull(request, "request")));
        }
        return new DelosStoragePredicatePushdownReport(plans);
    }

    public DelosStorageOptimizerReviewReport optimizerReviewReport(
            List<DelosStorageConsistencyTarget> targets,
            List<DelosStoragePredicatePushdownRequest> requests) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(requests, "requests");
        return DelosStorageOptimizerReviewReport.from(
                snapshots(targets),
                predicatePushdownReport(requests));
    }

    private static int providerRank(String providerId) {
        if (DelosStorageProviderIds.isHeap(providerId)) {
            return 0;
        }
        if (DelosStorageProviderIds.isMvcc(providerId)) {
            return 1;
        }
        return 100;
    }
}
