/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCostIntegration

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

/** Opt-in, read-only storage-statistics cost checkpoint. */
public final class DelosStorageCostIntegration {
    public static final String ENABLED_PROPERTY = "delosdb.storage.costIntegration.enabled";

    private DelosStorageCostIntegration() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    public static DelosStorageCostEstimate estimate(DelosStorageStatistics statistics) {
        return DelosStorageCostEstimate.fromStatistics(statistics, enabled());
    }

    public static DelosStorageCostReport report(DelosStorageStatisticsReport statisticsReport) {
        Objects.requireNonNull(statisticsReport, "statisticsReport");
        boolean enabled = enabled();
        List<DelosStorageCostEstimate> estimates = new ArrayList<>();
        for (DelosStorageStatistics statistics : statisticsReport.statistics()) {
            estimates.add(DelosStorageCostEstimate.fromStatistics(statistics, enabled));
        }
        return new DelosStorageCostReport(enabled, false, estimates);
    }
}
