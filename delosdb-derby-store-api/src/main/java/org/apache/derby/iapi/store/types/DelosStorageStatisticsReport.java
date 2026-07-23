/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageStatisticsReport

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

/** Read-only aggregate storage-statistics report for heap and MVCC targets. */
public record DelosStorageStatisticsReport(List<DelosStorageStatistics> statistics) {
    public DelosStorageStatisticsReport {
        statistics = List.copyOf(Objects.requireNonNull(statistics, "statistics"));
        if (statistics.isEmpty()) {
            throw new IllegalArgumentException("storage statistics report must contain at least one target");
        }
    }

    public int targetCount() {
        return statistics.size();
    }

    public Set<String> providerIds() {
        Set<String> providers = new LinkedHashSet<>();
        for (DelosStorageStatistics snapshot : statistics) {
            providers.add(snapshot.providerId());
        }
        return providers;
    }

    public long totalLogicalRowCount() {
        return statistics.stream().mapToLong(DelosStorageStatistics::logicalRowCount).sum();
    }

    public long totalPageCount() {
        return statistics.stream().mapToLong(DelosStorageStatistics::pageCount).sum();
    }

    public long totalObservedStorageBytes() {
        return statistics.stream().mapToLong(DelosStorageStatistics::observedStorageBytes).sum();
    }

    public boolean readOnly() {
        return statistics.stream().allMatch(DelosStorageStatistics::readOnly);
    }

    public DelosStorageStatistics statistics(String providerId, int segment, long containerId) {
        String normalizedProviderId = DelosStorageProviderIds.normalize(providerId);
        return statistics.stream()
                .filter(snapshot -> DelosStorageProviderIds.matches(snapshot.providerId(), normalizedProviderId)
                        && snapshot.segment() == segment
                        && snapshot.containerId() == containerId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No storage statistics for " + providerId
                                + " segment=" + segment + " container=" + containerId));
    }

    public List<String> summaries() {
        return statistics.stream().map(DelosStorageStatistics::summary).toList();
    }
}
