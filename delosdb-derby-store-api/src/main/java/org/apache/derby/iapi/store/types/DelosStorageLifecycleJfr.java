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

/**
 * JFR event surface for DelosDB storage lifecycle algorithms.
 *
 * <p>The events in this class are observability-only.  Creating and committing
 * one of these events must not change Derby optimizer authority, heap
 * compatibility behavior, MVCC visibility, storage format, recovery ordering, or
 * backup/restore semantics.  Event recording is a no-op unless JFR is enabled
 * for the corresponding event type.</p>
 */
public final class DelosStorageLifecycleJfr {
    private static final String EMPTY = "";

    private DelosStorageLifecycleJfr() {
    }

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

    public static void recordMvccPurge(
            String storageId,
            long visibleDebt,
            long prunedVersionCount,
            long purgeQueueDepth,
            boolean success,
            String failure) {
        MvccPurgeEvent event = new MvccPurgeEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.storageId = normalize(storageId);
        event.visibleDebt = visibleDebt;
        event.prunedVersionCount = prunedVersionCount;
        event.purgeQueueDepth = purgeQueueDepth;
        event.success = success;
        event.failure = normalize(failure);
        event.commit();
    }

    public static void recordMvccRecoveryReplay(
            String storageId,
            long replayedRecordCount,
            long transactionOutcomeCount,
            boolean success,
            String failure) {
        MvccRecoveryReplayEvent event = new MvccRecoveryReplayEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.storageId = normalize(storageId);
        event.replayedRecordCount = replayedRecordCount;
        event.transactionOutcomeCount = transactionOutcomeCount;
        event.success = success;
        event.failure = normalize(failure);
        event.commit();
    }

    public static void recordMvccBackupSidecar(
            String action,
            String databaseName,
            long copiedFileCount,
            long copiedByteCount,
            boolean manifestVerified,
            boolean success,
            String failure) {
        MvccBackupSidecarEvent event = new MvccBackupSidecarEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.action = normalize(action);
        event.databaseName = normalize(databaseName);
        event.copiedFileCount = copiedFileCount;
        event.copiedByteCount = copiedByteCount;
        event.manifestVerified = manifestVerified;
        event.success = success;
        event.failure = normalize(failure);
        event.commit();
    }

    public static void recordMvccBufferEviction(
            String storageId,
            String pageClass,
            long pageId,
            long candidateCount,
            boolean dirty,
            boolean pinned,
            boolean success,
            String reason) {
        MvccBufferEvictionEvent event = new MvccBufferEvictionEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.storageId = normalize(storageId);
        event.pageClass = normalize(pageClass);
        event.pageId = pageId;
        event.candidateCount = candidateCount;
        event.dirty = dirty;
        event.pinned = pinned;
        event.success = success;
        event.reason = normalize(reason);
        event.commit();
    }

    public static void recordHeapSanityCheck(
            String qualifiedTableName,
            long pageCount,
            long overflowPageCount,
            long findingCount,
            boolean success,
            String failure) {
        HeapSanityCheckEvent event = new HeapSanityCheckEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.qualifiedTableName = normalize(qualifiedTableName);
        event.pageCount = pageCount;
        event.overflowPageCount = overflowPageCount;
        event.findingCount = findingCount;
        event.success = success;
        event.failure = normalize(failure);
        event.commit();
    }

    public static void recordStoragePathDecision(DelosStoragePathDiagnostic diagnostic) {
        if (diagnostic == null) {
            return;
        }
        StoragePathDecisionEvent event = new StoragePathDecisionEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.decisionKind = diagnostic.decisionKind().name();
        event.state = diagnostic.state().name();
        event.providerId = normalize(diagnostic.providerId());
        event.segment = diagnostic.segment();
        event.containerId = diagnostic.containerId();
        event.reason = normalize(diagnostic.reason());
        event.readMode = normalize(diagnostic.readMode());
        event.shortcutSafe = diagnostic.shortcutSafe();
        event.rowIdCount = diagnostic.rowIdCount();
        event.detailCount = diagnostic.details().size();
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

    @Name("org.apache.derby.delosdb.mvcc.Purge")
    @Label("DelosDB MVCC Purge")
    @Category({"DelosDB", "Storage", "MVCC"})
    @Description("Records MVCC purge lifecycle points.")
    public static final class MvccPurgeEvent extends Event {
        @Label("Storage ID")
        public String storageId;
        @Label("Visibility Debt")
        public long visibleDebt;
        @Label("Pruned Versions")
        public long prunedVersionCount;
        @Label("Purge Queue Depth")
        public long purgeQueueDepth;
        @Label("Success")
        public boolean success;
        @Label("Failure")
        public String failure;
    }

    @Name("org.apache.derby.delosdb.mvcc.RecoveryReplay")
    @Label("DelosDB MVCC Recovery Replay")
    @Category({"DelosDB", "Storage", "MVCC"})
    @Description("Records MVCC recovery replay lifecycle points.")
    public static final class MvccRecoveryReplayEvent extends Event {
        @Label("Storage ID")
        public String storageId;
        @Label("Replayed Records")
        public long replayedRecordCount;
        @Label("Transaction Outcomes")
        public long transactionOutcomeCount;
        @Label("Success")
        public boolean success;
        @Label("Failure")
        public String failure;
    }

    @Name("org.apache.derby.delosdb.mvcc.BackupSidecar")
    @Label("DelosDB MVCC Backup Sidecar")
    @Category({"DelosDB", "Storage", "MVCC", "Backup"})
    @Description("Records MVCC sidecar backup/restore lifecycle points.")
    public static final class MvccBackupSidecarEvent extends Event {
        @Label("Action")
        public String action;
        @Label("Database")
        public String databaseName;
        @Label("Copied Files")
        public long copiedFileCount;
        @Label("Copied Bytes")
        public long copiedByteCount;
        @Label("Manifest Verified")
        public boolean manifestVerified;
        @Label("Success")
        public boolean success;
        @Label("Failure")
        public String failure;
    }

    @Name("org.apache.derby.delosdb.mvcc.BufferEviction")
    @Label("DelosDB MVCC Buffer Eviction")
    @Category({"DelosDB", "Storage", "MVCC", "Buffer"})
    @Description("Records MVCC buffer replacement and eviction decisions.")
    public static final class MvccBufferEvictionEvent extends Event {
        @Label("Storage ID")
        public String storageId;
        @Label("Page Class")
        public String pageClass;
        @Label("Page ID")
        public long pageId;
        @Label("Candidates")
        public long candidateCount;
        @Label("Dirty")
        public boolean dirty;
        @Label("Pinned")
        public boolean pinned;
        @Label("Success")
        public boolean success;
        @Label("Reason")
        public String reason;
    }

    @Name("org.apache.derby.delosdb.heap.SanityCheck")
    @Label("DelosDB Heap Sanity Check")
    @Category({"DelosDB", "Storage", "Heap"})
    @Description("Records Derby heap sanity-check diagnostic lifecycle points.")
    public static final class HeapSanityCheckEvent extends Event {
        @Label("Table")
        public String qualifiedTableName;
        @Label("Pages")
        public long pageCount;
        @Label("Overflow Pages")
        public long overflowPageCount;
        @Label("Findings")
        public long findingCount;
        @Label("Success")
        public boolean success;
        @Label("Failure")
        public String failure;
    }

    @Name("org.apache.derby.delosdb.storage.PathDecision")
    @Label("DelosDB Storage Path Decision")
    @Category({"DelosDB", "Storage", "Path"})
    @Description("Records diagnostic storage path decisions without changing path selection.")
    public static final class StoragePathDecisionEvent extends Event {
        @Label("Decision Kind")
        public String decisionKind;
        @Label("State")
        public String state;
        @Label("Provider")
        public String providerId;
        @Label("Segment")
        public int segment;
        @Label("Container ID")
        public long containerId;
        @Label("Reason")
        public String reason;
        @Label("Read Mode")
        public String readMode;
        @Label("Shortcut Safe")
        public boolean shortcutSafe;
        @Label("Row IDs")
        public long rowIdCount;
        @Label("Details")
        public int detailCount;
    }
}
