/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageLifecycleJfr

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

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** JFR event for the live DelosDB MVCC analyze/statistics lifecycle. */
public final class DelosStorageLifecycleJfr {
    private static final String EMPTY = "";

    private DelosStorageLifecycleJfr() {
    }

    /**
     * Records one Derby-triggered MVCC analyze/statistics lifecycle outcome.
     * Recording is observability only and is inert unless this event is enabled.
     */
    public static void recordMvccAnalyzeStatistics(
            String providerId,
            String qualifiedTableName,
            long containerId,
            long logicalRowCount,
            long physicalVersionCount,
            long orderedIndexEntryCount,
            long estimatedFullScanCost,
            long estimatedIndexLookupCost,
            String runContext,
            boolean success,
            String failure) {
        MvccAnalyzeStatisticsEvent event = new MvccAnalyzeStatisticsEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerId = normalize(providerId);
        event.qualifiedTableName = normalize(qualifiedTableName);
        event.containerId = containerId;
        event.logicalRowCount = logicalRowCount;
        event.physicalVersionCount = physicalVersionCount;
        event.orderedIndexEntryCount = orderedIndexEntryCount;
        event.estimatedFullScanCost = estimatedFullScanCost;
        event.estimatedIndexLookupCost = estimatedIndexLookupCost;
        event.runContext = normalize(runContext);
        event.success = success;
        event.failure = normalize(failure);
        event.commit();
    }

    private static String normalize(String value) {
        return value == null ? EMPTY : value;
    }

    @Name("org.apache.derby.delosdb.mvcc.AnalyzeStatistics")
    @Label("DelosDB MVCC Analyze Statistics")
    @Category({"DelosDB", "Storage", "MVCC"})
    @Description("Records Derby-triggered MVCC analyze/update-statistics lifecycle checkpoints.")
    public static final class MvccAnalyzeStatisticsEvent extends Event {
        @Label("Provider")
        public String providerId;
        @Label("Table")
        public String qualifiedTableName;
        @Label("Container ID")
        public long containerId;
        @Label("Logical Rows")
        public long logicalRowCount;
        @Label("Physical Versions")
        public long physicalVersionCount;
        @Label("Ordered Index Entries")
        public long orderedIndexEntryCount;
        @Label("Estimated Full Scan Cost")
        public long estimatedFullScanCost;
        @Label("Estimated Index Lookup Cost")
        public long estimatedIndexLookupCost;
        @Label("Run Context")
        public String runContext;
        @Label("Success")
        public boolean success;
        @Label("Failure")
        public String failure;
    }
}
