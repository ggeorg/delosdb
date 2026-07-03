/*

   Derby - Class org.apache.derby.iapi.store.types.DelosDiagnosticsStorageMetadataProvider

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

/** Diagnostics-backed metadata provider used by the default provider chain. */
final class DelosDiagnosticsStorageMetadataProvider implements DelosStorageMetadataProvider {
    private final DelosStorageDiagnostics diagnostics;

    DelosDiagnosticsStorageMetadataProvider(DelosStorageDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public String providerId() {
        return DelosStorageProviderIds.normalize(diagnostics.providerId());
    }

    @Override
    public DelosStorageMetadataSnapshot snapshot(DelosStorageConsistencyTarget target) {
        Objects.requireNonNull(target, "target");
        if (!supports(target)) {
            throw new IllegalArgumentException("Metadata provider " + providerId()
                    + " cannot serve target provider " + target.providerId());
        }
        if (target.hasDatabaseDirectory()) {
            diagnostics.setDatabaseDirectoryForTesting(target.databaseDirectory());
        } else {
            diagnostics.clearDatabaseDirectoryForTesting();
        }

        DelosStorageConsistencyTarget normalizedTarget = new DelosStorageConsistencyTarget(
                providerId(),
                target.databaseDirectory(),
                target.segment(),
                target.containerId());
        DelosStorageInspection inspection = DelosStorageInspection.fromDiagnostics(
                diagnostics,
                normalizedTarget.segment(),
                normalizedTarget.containerId());
        DelosStorageConsistencyFinding finding = DelosStorageConsistencyFinding.from(
                providerId(),
                normalizedTarget.segment(),
                normalizedTarget.containerId(),
                diagnostics.consistencyDiagnosticsForTesting(
                        normalizedTarget.segment(),
                        normalizedTarget.containerId()));
        DelosStorageStatistics statistics = diagnostics.storageStatisticsForTesting(
                normalizedTarget.segment(),
                normalizedTarget.containerId());
        DelosStorageCostEstimate estimate = DelosStorageCostIntegration.estimate(statistics);
        return new DelosStorageMetadataSnapshot(
                normalizedTarget,
                inspection,
                finding,
                statistics,
                estimate,
                List.of("metadata provider: " + providerId(),
                        "metadata source: diagnostics"));
    }
}
