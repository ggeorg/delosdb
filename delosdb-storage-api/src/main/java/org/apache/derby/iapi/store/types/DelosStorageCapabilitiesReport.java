/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCapabilitiesReport

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

/** Read-only capability report for one or more storage targets. */
public record DelosStorageCapabilitiesReport(List<DelosStorageCapabilities> capabilities) {
    public DelosStorageCapabilitiesReport {
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    public int targetCount() {
        return capabilities.size();
    }

    public boolean readOnly() {
        return capabilities.stream().allMatch(DelosStorageCapabilities::readOnly);
    }

    public boolean consumedByDerbyOptimizer() {
        return capabilities.stream().anyMatch(DelosStorageCapabilities::consumedByDerbyOptimizer);
    }

    public DelosStorageCapabilities capability(String providerId, int segment, long containerId) {
        String normalizedProviderId = DelosStorageProviderIds.normalize(providerId);
        return capabilities.stream()
                .filter(capability -> DelosStorageProviderIds.matches(capability.providerId(), normalizedProviderId)
                        && capability.segment() == segment
                        && capability.containerId() == containerId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No storage capability found for " + normalizedProviderId
                                + " segment=" + segment
                                + " container=" + containerId));
    }

    public List<String> summaries() {
        return capabilities.stream().map(DelosStorageCapabilities::summary).toList();
    }
}
