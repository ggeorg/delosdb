/*

   Derby - Class org.apache.derby.iapi.store.types.DelosTableStorageSnapshot

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

import java.util.Objects;

/**
 * Versioned immutable observation of one provider-owned table.
 *
 * <p>Provider fields are read through existing table diagnostics while active
 * participant membership is copied from the provider-neutral transaction
 * registry. The combined value is deliberately weakly consistent.</p>
 */
public record DelosTableStorageSnapshot(
        int schemaVersion,
        String providerId,
        String databaseIdentity,
        long segmentId,
        long containerId,
        String collectionSemantics,
        long capturedAtEpochMillis,
        boolean tableActive,
        int registeredTransactionCount,
        int registeredReadOnlyTransactionCount,
        int registeredWriteTransactionCount,
        int registeredWriteIntentCount,
        int logicalRowCount,
        int physicalVersionCount,
        long pageCount,
        long overflowPageCount,
        long reusablePageCount,
        long orderedIndexEntryCount,
        int orderedIndexDistinctKeyCount,
        long purgeQueuePendingCount,
        String checkpointStatus,
        int consistencyErrorCount,
        String consistencySummary) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String WEAKLY_CONSISTENT_COLLECTION =
            "weakly-consistent-table-diagnostics-with-registry-participants";

    public DelosTableStorageSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        providerId = DelosStorageProviderIds.normalize(providerId);
        databaseIdentity = requireNonBlank(databaseIdentity, "databaseIdentity");
        collectionSemantics = requireNonBlank(collectionSemantics, "collectionSemantics");
        checkpointStatus = Objects.requireNonNull(checkpointStatus, "checkpointStatus");
        consistencySummary = Objects.requireNonNull(consistencySummary, "consistencySummary");
        if (segmentId < 0 || containerId < 0L || capturedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("table identity and capture time must be non-negative");
        }
        validateNonNegative(
                registeredTransactionCount,
                registeredReadOnlyTransactionCount,
                registeredWriteTransactionCount,
                registeredWriteIntentCount,
                logicalRowCount,
                physicalVersionCount,
                pageCount,
                overflowPageCount,
                reusablePageCount,
                orderedIndexEntryCount,
                orderedIndexDistinctKeyCount,
                purgeQueuePendingCount,
                consistencyErrorCount);
        if (registeredReadOnlyTransactionCount + registeredWriteTransactionCount
                != registeredTransactionCount) {
            throw new IllegalArgumentException(
                    "registered transaction mode counts must equal registeredTransactionCount");
        }
    }

    public String tableIdentity() {
        return "segment-" + segmentId + "/container-" + containerId;
    }

    private static String requireNonBlank(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static void validateNonNegative(long... values) {
        for (long value : values) {
            if (value < 0L) {
                throw new IllegalArgumentException("table snapshot counters must be non-negative");
            }
        }
    }
}
