/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageMaintenanceDiagnostics

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

/** Page-mutation, purge, and database-maintenance diagnostics. */
interface DelosStorageMaintenanceDiagnostics {
    default long pageMutationContextBeginCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextCommitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextAbortCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextPageReservationCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextReservedBytesForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextPageWriteCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextFreeSpaceMapUpdateCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextReusableIndexUpdateCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default String lastPageMutationContextOperationForTesting(int segment, long containerId) {
        return "none";
    }

    default long purgeQueuePendingCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeQueueEnqueueCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeQueueDrainCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeQueueLastDrainCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> purgeQueueEntrySummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long purgeDaemonScheduleCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeDaemonRunCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeDaemonSkipCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeDaemonLastTriggerChangedRowsForTesting(int segment, long containerId) {
        return 0L;
    }

    default String purgeDaemonLastDecisionForTesting(int segment, long containerId) {
        return "disabled";
    }

    default long purgeDaemonLastVisibilityDebtScoreForTesting(int segment, long containerId) {
        return 0L;
    }

    default String purgeDaemonLastVisibilityDebtSummaryForTesting(int segment, long containerId) {
        return "none";
    }

    default int databaseMaintenanceWorkerCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int databaseMaintenanceRegisteredTableCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int databaseMaintenanceQueuedTaskCountForTesting(int segment, long containerId) {
        return 0;
    }

    default long databaseMaintenanceCommitWakeupCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long databaseMaintenancePeriodicScanCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long databaseMaintenanceRunCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long databaseMaintenanceFailureCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default int databaseMaintenanceMaximumActiveWorkerCountForTesting(int segment, long containerId) {
        return 0;
    }

    default boolean databaseMaintenanceAcceptingForTesting(int segment, long containerId) {
        return false;
    }
}
