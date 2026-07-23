/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageSharedServiceReadinessReport

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
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative report for deciding which heap/MVCC seams are ready to become
 * shared services.
 *
 * <p>The report is deliberately diagnostic-only.  It may bless read-only shared
 * reporting helpers, but it must not imply that heap raw-store behavior, MVCC
 * visibility, buffer replacement, purge, page-codec, or ordered-index authority
 * can be extracted without a separate executable proof.</p>
 */
public record DelosStorageSharedServiceReadinessReport(
        List<DelosStorageSharedServiceReadinessItem> items) {
    public static final String DIAGNOSTICS_READ_MODEL = "storage-diagnostics-read-model";
    public static final String LIFECYCLE_READ_MODEL = "storage-lifecycle-read-model";
    public static final String STATISTICS_COST_READ_MODEL = "storage-statistics-cost-read-model";
    public static final String BACKUP_RESTORE_ORCHESTRATION = "backup-restore-orchestration";
    public static final String BUFFER_MANAGEMENT = "buffer-management";
    public static final String PAGE_CODEC = "page-codec";
    public static final String ORDERED_INDEX_AUTHORITY = "ordered-index-authority";
    public static final String PURGE_VACUUM = "purge-vacuum";

    public DelosStorageSharedServiceReadinessReport {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("shared-service readiness report must contain at least one item");
        }
    }

    public static DelosStorageSharedServiceReadinessReport from(
            DelosStorageLifecycleConsistencyReport lifecycleReport,
            DelosStorageCapabilitiesReport capabilitiesReport) {
        Objects.requireNonNull(lifecycleReport, "lifecycleReport");
        Objects.requireNonNull(capabilitiesReport, "capabilitiesReport");
        if (lifecycleReport.targetCount() != capabilitiesReport.targetCount()) {
            throw new IllegalArgumentException("lifecycle and capability reports must cover the same target count");
        }

        boolean mixedProviders = mixedHeapAndMvcc(lifecycleReport.providerIds());
        boolean readOnlyCapabilities = capabilitiesReport.readOnly();
        boolean cleanLifecycle = lifecycleReport.clean();
        boolean optimizerNotConsumingCapabilities = !capabilitiesReport.consumedByDerbyOptimizer();
        boolean mvccOrderedProof = capabilitiesReport.capabilities().stream()
                .anyMatch(capability -> DelosStorageProviderIds.isMvcc(capability.providerId())
                        && capability.supportsOrderedLookup()
                        && capability.supportsStableKeyOrder());

        List<DelosStorageSharedServiceReadinessItem> decisions = new ArrayList<>();
        decisions.add(new DelosStorageSharedServiceReadinessItem(
                DIAGNOSTICS_READ_MODEL,
                readyReadOnly(mixedProviders && readOnlyCapabilities),
                mixedProviders && readOnlyCapabilities,
                true,
                List.of(
                        "heap and MVCC diagnostics are reachable through DelosStorageDiagnosticsRegistry",
                        "capability snapshots are read-only",
                        "provider ids are normalized through DelosStorageProviderIds"),
                mixedProviders ? List.of() : List.of("report does not cover both heap and delos_mvcc"),
                "keep this service read-only; do not make it storage authority"));
        decisions.add(new DelosStorageSharedServiceReadinessItem(
                LIFECYCLE_READ_MODEL,
                readyReadOnly(mixedProviders && cleanLifecycle),
                mixedProviders && cleanLifecycle,
                true,
                List.of(
                        "lifecycle consistency report covers checkpoint/recovery/purge/analyze/backup signals",
                        "all observed lifecycle snapshots are clean"),
                cleanLifecycle ? List.of() : List.of("one or more lifecycle snapshots are not clean"),
                "allow shared reporting only; leave lifecycle execution provider-local"));
        decisions.add(new DelosStorageSharedServiceReadinessItem(
                STATISTICS_COST_READ_MODEL,
                optimizerNotConsumingCapabilities
                        ? DelosStorageSharedServiceReadinessLevel.READY_FOR_REPORT_ONLY
                        : DelosStorageSharedServiceReadinessLevel.NOT_READY,
                false,
                true,
                List.of(
                        "statistics and cost reports are exposed through provider-neutral snapshots",
                        "Derby optimizer remains the execution authority"),
                optimizerNotConsumingCapabilities
                        ? List.of("report is not optimizer-consumed and must stay diagnostic-only")
                        : List.of("capability report says optimizer is consuming storage capabilities"),
                "keep cost/statistics shared as a report until a separate optimizer gate consumes it"));
        decisions.add(new DelosStorageSharedServiceReadinessItem(
                BACKUP_RESTORE_ORCHESTRATION,
                DelosStorageSharedServiceReadinessLevel.READY_FOR_REPORT_ONLY,
                false,
                true,
                List.of(
                        "mixed-engine backup/restore matrix proves heap and delos_mvcc can be verified together",
                        "backup sidecar state is observable as lifecycle/report metadata"),
                List.of("backup execution is still owned by Derby raw-store plus MVCC sidecar integration"),
                "keep orchestration/reporting shared; do not extract execution yet"));
        decisions.add(new DelosStorageSharedServiceReadinessItem(
                BUFFER_MANAGEMENT,
                DelosStorageSharedServiceReadinessLevel.MVCC_ONLY_PROOF,
                false,
                false,
                List.of("MVCC page-cache replacement policy has a test seam"),
                List.of("Derby heap buffer/raw cache remains an inherited compatibility boundary"),
                "collect comparable heap/raw-store cache proof before considering a shared buffer service"));
        decisions.add(new DelosStorageSharedServiceReadinessItem(
                PAGE_CODEC,
                DelosStorageSharedServiceReadinessLevel.HEAP_COMPATIBILITY_BOUNDARY,
                false,
                false,
                List.of("MVCC durable page codec is DelosDB-owned"),
                List.of("Derby heap page and raw log formats are compatibility boundaries"),
                "do not extract a shared page codec; continue typed-codec proofs inside MVCC only"));
        decisions.add(new DelosStorageSharedServiceReadinessItem(
                ORDERED_INDEX_AUTHORITY,
                mvccOrderedProof
                        ? DelosStorageSharedServiceReadinessLevel.MVCC_ONLY_PROOF
                        : DelosStorageSharedServiceReadinessLevel.NOT_READY,
                false,
                false,
                mvccOrderedProof
                        ? List.of("MVCC ordered equality/range capabilities are proven by ordered-index pages")
                        : List.of("no MVCC ordered-index proof was observed in capabilities"),
                List.of("heap ordered/index authority remains Derby BTree/access-path behavior"),
                "keep ordered-index execution provider-local; share only diagnostics"));
        decisions.add(new DelosStorageSharedServiceReadinessItem(
                PURGE_VACUUM,
                DelosStorageSharedServiceReadinessLevel.MVCC_ONLY_PROOF,
                false,
                false,
                List.of("MVCC purge/vacuum is observable through lifecycle diagnostics"),
                List.of("heap compress/purge behavior is inherited Derby behavior with different semantics"),
                "stress MVCC purge separately; do not extract a heap/MVCC purge service"));
        return new DelosStorageSharedServiceReadinessReport(decisions);
    }

    public int itemCount() {
        return items.size();
    }

    public long extractionAllowedCount() {
        return items.stream().filter(DelosStorageSharedServiceReadinessItem::extractionAllowed).count();
    }

    public long reportOnlyCount() {
        return items.stream()
                .filter(item -> item.readinessLevel() == DelosStorageSharedServiceReadinessLevel.READY_FOR_REPORT_ONLY)
                .count();
    }

    public long providerOwnedCount() {
        return items.stream()
                .filter(item -> item.readinessLevel() == DelosStorageSharedServiceReadinessLevel.MVCC_ONLY_PROOF
                        || item.readinessLevel() == DelosStorageSharedServiceReadinessLevel.HEAP_COMPATIBILITY_BOUNDARY)
                .count();
    }

    public boolean extractionLimitedToReadOnlyServices() {
        return items.stream()
                .filter(DelosStorageSharedServiceReadinessItem::extractionAllowed)
                .allMatch(DelosStorageSharedServiceReadinessItem::readOnlyOnly);
    }

    public DelosStorageSharedServiceReadinessItem item(String serviceName) {
        String normalized = Objects.requireNonNull(serviceName, "serviceName").trim();
        return items.stream()
                .filter(item -> item.serviceName().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No shared-service readiness item named " + serviceName));
    }

    public List<String> summaries() {
        return items.stream().map(DelosStorageSharedServiceReadinessItem::summary).toList();
    }

    private static DelosStorageSharedServiceReadinessLevel readyReadOnly(boolean ready) {
        return ready
                ? DelosStorageSharedServiceReadinessLevel.READY_FOR_READ_ONLY_SHARED_SERVICE
                : DelosStorageSharedServiceReadinessLevel.NOT_READY;
    }

    private static boolean mixedHeapAndMvcc(Set<String> providerIds) {
        return providerIds.stream().anyMatch(DelosStorageProviderIds::isHeap)
                && providerIds.stream().anyMatch(DelosStorageProviderIds::isMvcc);
    }
}
