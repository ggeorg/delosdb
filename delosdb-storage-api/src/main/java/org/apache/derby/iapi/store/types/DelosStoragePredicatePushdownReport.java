/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStoragePredicatePushdownReport

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

/** Read-only predicate pushdown/remainder report for one or more targets. */
public record DelosStoragePredicatePushdownReport(List<DelosStoragePredicatePushdown> plans) {
    public DelosStoragePredicatePushdownReport {
        plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
    }

    public int targetCount() {
        return plans.size();
    }

    public boolean readOnly() {
        return plans.stream().allMatch(DelosStoragePredicatePushdown::readOnly);
    }

    public boolean consumedByDerbyOptimizer() {
        return plans.stream().anyMatch(DelosStoragePredicatePushdown::consumedByDerbyOptimizer);
    }

    public long pushedTargetCount() {
        return plans.stream().filter(DelosStoragePredicatePushdown::pushedToStorage).count();
    }

    public long remainderTargetCount() {
        return plans.stream().filter(DelosStoragePredicatePushdown::requiresDerbyRemainder).count();
    }

    public DelosStoragePredicatePushdown plan(String providerId, int segment, long containerId) {
        String normalizedProviderId = DelosStorageProviderIds.normalize(providerId);
        return plans.stream()
                .filter(plan -> DelosStorageProviderIds.matches(plan.providerId(), normalizedProviderId)
                        && plan.segment() == segment
                        && plan.containerId() == containerId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No predicate pushdown plan found for " + normalizedProviderId
                                + " segment=" + segment
                                + " container=" + containerId));
    }

    public List<String> summaries() {
        return plans.stream().map(DelosStoragePredicatePushdown::summary).toList();
    }
}
