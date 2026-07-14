/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccCommitMetrics

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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccCommitDurabilityMetrics;

/**
 * Per-table and process-wide concurrency counters used only while the MVCC
 * commit JFR event is enabled.
 */
final class MvccCommitMetrics {
    private static final AtomicInteger PROCESS_REQUESTS = new AtomicInteger();
    private static final AtomicInteger PROCESS_PREPARATIONS = new AtomicInteger();
    private static final AtomicInteger PROCESS_DURABILITY_QUEUE_ENTRIES = new AtomicInteger();
    private static final AtomicInteger PROCESS_DURABILITY_EXECUTIONS = new AtomicInteger();

    private final AtomicInteger tableRequests = new AtomicInteger();
    private final AtomicInteger tablePreparations = new AtomicInteger();
    private final AtomicInteger tableDurabilityQueueEntries = new AtomicInteger();
    private final AtomicInteger tableDurabilityExecutions = new AtomicInteger();

    Concurrency enterRequest() {
        return new Concurrency(
                tableRequests.incrementAndGet(),
                PROCESS_REQUESTS.incrementAndGet());
    }

    void exitRequest() {
        tableRequests.decrementAndGet();
        PROCESS_REQUESTS.decrementAndGet();
    }

    int activeTableRequests() {
        return tableRequests.get();
    }

    Concurrency enterPreparation() {
        return new Concurrency(
                tablePreparations.incrementAndGet(),
                PROCESS_PREPARATIONS.incrementAndGet());
    }

    void exitPreparation() {
        tablePreparations.decrementAndGet();
        PROCESS_PREPARATIONS.decrementAndGet();
    }

    Concurrency enterDurabilityQueue() {
        return new Concurrency(
                tableDurabilityQueueEntries.incrementAndGet(),
                PROCESS_DURABILITY_QUEUE_ENTRIES.incrementAndGet());
    }

    void exitDurabilityQueue() {
        tableDurabilityQueueEntries.decrementAndGet();
        PROCESS_DURABILITY_QUEUE_ENTRIES.decrementAndGet();
    }

    Concurrency enterDurabilityExecution() {
        return new Concurrency(
                tableDurabilityExecutions.incrementAndGet(),
                PROCESS_DURABILITY_EXECUTIONS.incrementAndGet());
    }

    void exitDurabilityExecution() {
        tableDurabilityExecutions.decrementAndGet();
        PROCESS_DURABILITY_EXECUTIONS.decrementAndGet();
    }

    record Concurrency(int table, int process) {
        static final Concurrency NONE = new Concurrency(0, 0);
    }

    record Sample(
            String storageId,
            long transactionId,
            int changedRows,
            long totalCommitNanos,
            long preparationNanos,
            long backupWaitNanos,
            long durabilityCoordinatorWaitNanos,
            long durabilityCoordinatorHoldNanos,
            long tableLockWaitNanos,
            long tableLockHoldNanos,
            long validationNanos,
            long transactionStatusCommitNanos,
            long pageStatePersistenceNanos,
            long orderedIndexRebuildNanos,
            long transactionStatePublicationNanos,
            long maintenanceNanos,
            Concurrency requestConcurrency,
            Concurrency preparationConcurrency,
            Concurrency durabilityQueueConcurrency,
            Concurrency durabilityExecutionConcurrency,
            MvccCommitDurabilityMetrics.Snapshot durability,
            boolean durabilityMeasurementComplete,
            boolean success,
            String failure) {
        Sample {
            storageId = Objects.requireNonNull(storageId, "storageId");
            requestConcurrency = Objects.requireNonNull(requestConcurrency, "requestConcurrency");
            preparationConcurrency = Objects.requireNonNull(preparationConcurrency, "preparationConcurrency");
            durabilityQueueConcurrency = Objects.requireNonNull(
                    durabilityQueueConcurrency,
                    "durabilityQueueConcurrency");
            durabilityExecutionConcurrency = Objects.requireNonNull(
                    durabilityExecutionConcurrency,
                    "durabilityExecutionConcurrency");
            durability = Objects.requireNonNull(durability, "durability");
            failure = failure == null ? "" : failure;
        }
    }
}
