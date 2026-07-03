/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCostReport

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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Read-only aggregate cost checkpoint for heap and MVCC storage targets. */
public record DelosStorageCostReport(boolean storageStatisticsEnabled,
                                     boolean consumedByDerbyOptimizer,
                                     List<DelosStorageCostEstimate> estimates) {
    public DelosStorageCostReport {
        estimates = List.copyOf(Objects.requireNonNull(estimates, "estimates"));
        if (estimates.isEmpty()) {
            throw new IllegalArgumentException("storage cost report must contain at least one estimate");
        }
        if (consumedByDerbyOptimizer) {
            for (DelosStorageCostEstimate estimate : estimates) {
                if (!estimate.consumedByDerbyOptimizer()) {
                    throw new IllegalArgumentException("consumed cost report contains proof-only estimate");
                }
            }
        }
    }

    public int targetCount() {
        return estimates.size();
    }

    public boolean readOnly() {
        for (DelosStorageCostEstimate estimate : estimates) {
            if (!estimate.readOnly()) {
                return false;
            }
        }
        return true;
    }

    public boolean proofOnly() {
        if (consumedByDerbyOptimizer) {
            return false;
        }
        for (DelosStorageCostEstimate estimate : estimates) {
            if (!estimate.proofOnly()) {
                return false;
            }
        }
        return true;
    }

    public Set<String> providerIds() {
        Set<String> providers = new LinkedHashSet<>();
        for (DelosStorageCostEstimate estimate : estimates) {
            providers.add(estimate.providerId());
        }
        return providers;
    }

    public long totalEstimatedFullScanCost() {
        long total = 0L;
        for (DelosStorageCostEstimate estimate : estimates) {
            total += estimate.estimatedFullScanCost();
        }
        return total;
    }

    public DelosStorageCostEstimate estimate(String providerId, int segment, long containerId) {
        String normalized = DelosStorageProviderIds.normalize(providerId);
        for (DelosStorageCostEstimate estimate : estimates) {
            if (estimate.providerId().equals(normalized)
                    && estimate.segment() == segment
                    && estimate.containerId() == containerId) {
                return estimate;
            }
        }
        throw new IllegalArgumentException("No storage cost estimate for provider=" + providerId
                + " segment=" + segment + " container=" + containerId);
    }

    public List<String> summaries() {
        return estimates.stream()
                .map(DelosStorageCostEstimate::summary)
                .toList();
    }
}
