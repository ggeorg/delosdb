/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageLifecycleConsistencyReport

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

/** Read-only aggregate lifecycle report for heap and MVCC storage targets. */
public record DelosStorageLifecycleConsistencyReport(
        List<DelosStorageLifecycleConsistencySnapshot> snapshots) {
    public DelosStorageLifecycleConsistencyReport {
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("storage lifecycle report must contain at least one target");
        }
    }

    public int targetCount() {
        return snapshots.size();
    }

    public Set<String> providerIds() {
        Set<String> providers = new LinkedHashSet<>();
        for (DelosStorageLifecycleConsistencySnapshot snapshot : snapshots) {
            providers.add(snapshot.providerId());
        }
        return providers;
    }

    public boolean clean() {
        return snapshots.stream().allMatch(DelosStorageLifecycleConsistencySnapshot::clean);
    }

    public long analyzedTargetCount() {
        return snapshots.stream()
                .filter(DelosStorageLifecycleConsistencySnapshot::analyzeObserved)
                .count();
    }

    public long purgeObservedTargetCount() {
        return snapshots.stream()
                .filter(DelosStorageLifecycleConsistencySnapshot::purgeObserved)
                .count();
    }

    public long recoveryCompleteTargetCount() {
        return snapshots.stream()
                .filter(DelosStorageLifecycleConsistencySnapshot::recoveryComplete)
                .count();
    }

    public List<String> summaries() {
        return snapshots.stream()
                .map(DelosStorageLifecycleConsistencySnapshot::summary)
                .toList();
    }

    public DelosStorageLifecycleConsistencySnapshot snapshot(String providerId, int segment, long containerId) {
        String normalizedProvider = DelosStorageProviderIds.normalize(providerId);
        return snapshots.stream()
                .filter(snapshot -> snapshot.providerId().equals(normalizedProvider)
                        && snapshot.segment() == segment
                        && snapshot.containerId() == containerId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No storage lifecycle snapshot for " + providerId
                                + " segment=" + segment
                                + " container=" + containerId));
    }
}
