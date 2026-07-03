/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageMetadataSnapshot

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

import java.util.List;
import java.util.Objects;

/**
 * One provider-chain metadata snapshot for a heap or MVCC storage target.
 */
public record DelosStorageMetadataSnapshot(DelosStorageConsistencyTarget target,
                                           DelosStorageInspection inspection,
                                           DelosStorageConsistencyFinding consistencyFinding,
                                           DelosStorageStatistics statistics,
                                           DelosStorageCostEstimate costEstimate,
                                           DelosStorageCapabilities capabilities,
                                           List<String> observations) {
    public DelosStorageMetadataSnapshot {
        target = Objects.requireNonNull(target, "target");
        inspection = Objects.requireNonNull(inspection, "inspection");
        consistencyFinding = Objects.requireNonNull(consistencyFinding, "consistencyFinding");
        statistics = Objects.requireNonNull(statistics, "statistics");
        costEstimate = Objects.requireNonNull(costEstimate, "costEstimate");
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        String providerId = target.providerId();
        if (!DelosStorageProviderIds.matches(providerId, inspection.providerId())
                || !DelosStorageProviderIds.matches(providerId, consistencyFinding.providerId())
                || !DelosStorageProviderIds.matches(providerId, statistics.providerId())
                || !DelosStorageProviderIds.matches(providerId, costEstimate.providerId())
                || !DelosStorageProviderIds.matches(providerId, capabilities.providerId())) {
            throw new IllegalArgumentException("metadata snapshot provider ids do not match");
        }
        if (target.segment() != inspection.segment()
                || target.segment() != consistencyFinding.segment()
                || target.segment() != statistics.segment()
                || target.segment() != costEstimate.segment()
                || target.segment() != capabilities.segment()
                || target.containerId() != inspection.containerId()
                || target.containerId() != consistencyFinding.containerId()
                || target.containerId() != statistics.containerId()
                || target.containerId() != costEstimate.containerId()
                || target.containerId() != capabilities.containerId()) {
            throw new IllegalArgumentException("metadata snapshot target coordinates do not match");
        }
    }

    public String providerId() {
        return target.providerId();
    }

    public int segment() {
        return target.segment();
    }

    public long containerId() {
        return target.containerId();
    }

    public boolean clean() {
        return consistencyFinding.clean();
    }

    public boolean readOnly() {
        return statistics.readOnly() && costEstimate.readOnly() && capabilities.readOnly();
    }

    public String summary() {
        return providerId()
                + " segment=" + segment()
                + " container=" + containerId()
                + " rows=" + statistics.logicalRowCount()
                + " pages=" + statistics.pageCount()
                + " cost=" + costEstimate.estimatedFullScanCost()
                + " orderedRange=" + capabilities.supportsOrderedRangeScan()
                + " errors=" + consistencyFinding.errorCount();
    }
}
