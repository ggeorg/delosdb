/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccCommitJfr

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
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/** JFR event boundary for measured MVCC transaction commits. */
final class MvccCommitJfr {
    static final String EVENT_NAME = "org.apache.derby.delosdb.mvcc.Commit";
    private static final EventType EVENT_TYPE = EventType.getEventType(MvccCommitEvent.class);

    private MvccCommitJfr() {
    }

    static boolean enabled() {
        return EVENT_TYPE.isEnabled();
    }

    static void record(MvccCommitMetrics.Sample sample) {
        MvccCommitEvent event = new MvccCommitEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.storageId = sample.storageId();
        event.transactionId = sample.transactionId();
        event.changedRows = sample.changedRows();
        event.totalCommitNanos = sample.totalCommitNanos();
        event.backupWaitNanos = sample.backupWaitNanos();
        event.tableLockWaitNanos = sample.tableLockWaitNanos();
        event.tableLockHoldNanos = sample.tableLockHoldNanos();
        event.validationNanos = sample.validationNanos();
        event.transactionStatusCommitNanos = sample.transactionStatusCommitNanos();
        event.pageStatePersistenceNanos = sample.pageStatePersistenceNanos();
        event.orderedIndexRebuildNanos = sample.orderedIndexRebuildNanos();
        event.transactionStatePublicationNanos = sample.transactionStatePublicationNanos();
        event.maintenanceNanos = sample.maintenanceNanos();
        event.tableRequestConcurrency = sample.requestConcurrency().table();
        event.processRequestConcurrency = sample.requestConcurrency().process();
        event.tableDurabilityQueueConcurrency = sample.durabilityQueueConcurrency().table();
        event.processDurabilityQueueConcurrency = sample.durabilityQueueConcurrency().process();
        event.tableDurabilityExecutionConcurrency = sample.durabilityExecutionConcurrency().table();
        event.processDurabilityExecutionConcurrency = sample.durabilityExecutionConcurrency().process();
        event.transactionStatusForceCount = sample.durability().transactionStatusForceCount();
        event.transactionOutcomeForceCount = sample.durability().transactionOutcomeForceCount();
        event.writeAheadLogForceCount = sample.durability().writeAheadLogForceCount();
        event.otherSidecarForceCount = sample.durability().otherSidecarForceCount();
        event.directoryForceCount = sample.durability().directoryForceCount();
        event.pageVolumeForceCount = sample.durability().pageVolumeForceCount();
        event.pageVolumePagesCovered = sample.durability().pageVolumePagesCovered();
        event.sidecarBytesCovered = sample.durability().sidecarBytesCovered();
        event.pageVolumeBytesCovered = sample.durability().pageVolumeBytesCovered();
        event.durabilityMeasurementComplete = sample.durabilityMeasurementComplete();
        event.success = sample.success();
        event.failure = sample.failure();
        event.commit();
    }

    @Name(EVENT_NAME)
    @Label("DelosDB MVCC Commit")
    @Category({"DelosDB", "Storage", "MVCC"})
    @Description("Records MVCC commit lock waits and durability force activity without changing commit semantics.")
    public static final class MvccCommitEvent extends Event {
        @Label("Storage ID")
        public String storageId;
        @Label("Transaction ID")
        public long transactionId;
        @Label("Changed Rows")
        public int changedRows;
        @Label("Total Commit Time")
        @Timespan(Timespan.NANOSECONDS)
        public long totalCommitNanos;
        @Label("Backup Coordinator Wait")
        @Timespan(Timespan.NANOSECONDS)
        public long backupWaitNanos;
        @Label("Table Write Lock Wait")
        @Timespan(Timespan.NANOSECONDS)
        public long tableLockWaitNanos;
        @Label("Table Write Lock Hold")
        @Timespan(Timespan.NANOSECONDS)
        public long tableLockHoldNanos;
        @Label("Changed-Row Validation")
        @Timespan(Timespan.NANOSECONDS)
        public long validationNanos;
        @Label("Transaction Status Commit")
        @Timespan(Timespan.NANOSECONDS)
        public long transactionStatusCommitNanos;
        @Label("Page State Persistence")
        @Timespan(Timespan.NANOSECONDS)
        public long pageStatePersistenceNanos;
        @Label("Ordered Index Rebuild")
        @Timespan(Timespan.NANOSECONDS)
        public long orderedIndexRebuildNanos;
        @Label("Transaction State Publication")
        @Timespan(Timespan.NANOSECONDS)
        public long transactionStatePublicationNanos;
        @Label("Post-Commit Maintenance")
        @Timespan(Timespan.NANOSECONDS)
        public long maintenanceNanos;
        @Label("Table Concurrent Commit Requests")
        public int tableRequestConcurrency;
        @Label("Process Concurrent Commit Requests")
        public int processRequestConcurrency;
        @Label("Table Concurrent Durability Queue Entries")
        public int tableDurabilityQueueConcurrency;
        @Label("Process Concurrent Durability Queue Entries")
        public int processDurabilityQueueConcurrency;
        @Label("Table Concurrent Durability Executions")
        public int tableDurabilityExecutionConcurrency;
        @Label("Process Concurrent Durability Executions")
        public int processDurabilityExecutionConcurrency;
        @Label("Transaction Status Force Calls")
        public long transactionStatusForceCount;
        @Label("Transaction Outcome Force Calls")
        public long transactionOutcomeForceCount;
        @Label("WAL Force Calls")
        public long writeAheadLogForceCount;
        @Label("Other Sidecar Force Calls")
        public long otherSidecarForceCount;
        @Label("Directory Force Calls")
        public long directoryForceCount;
        @Label("Page Volume Force Calls")
        public long pageVolumeForceCount;
        @Label("Page Volume Pages Covered")
        public long pageVolumePagesCovered;
        @Label("Sidecar Bytes Covered")
        public long sidecarBytesCovered;
        @Label("Page Volume Bytes Covered")
        public long pageVolumeBytesCovered;
        @Label("Durability Measurement Complete")
        public boolean durabilityMeasurementComplete;
        @Label("Commit Call Succeeded")
        public boolean success;
        @Label("Failure")
        public String failure;
    }
}
