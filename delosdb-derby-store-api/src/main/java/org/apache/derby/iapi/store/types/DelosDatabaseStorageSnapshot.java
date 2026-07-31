/*

   Derby - Class org.apache.derby.iapi.store.types.DelosDatabaseStorageSnapshot

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

/**
 * Versioned, immutable, database-scoped storage observation.
 *
 * <p>The snapshot exposes values only. It carries no engine references and no
 * operation capable of changing database state. Counter fields are collected
 * with weakly consistent semantics: every field is an atomic observation, but
 * concurrent storage activity may occur between field reads. Path history is
 * not included. Nested table provider fields use existing
 * read-lock-guarded diagnostics, but the combined table observation remains
 * weakly consistent across fields. Table and transaction collections are
 * bounded with explicit dropped-entry accounting.</p>
 */
public record DelosDatabaseStorageSnapshot(
        int schemaVersion,
        String providerId,
        String databaseIdentity,
        String collectionSemantics,
        long captureSequence,
        long capturedAtEpochMillis,
        boolean runtimeActive,
        int tableStateCount,
        long insertCount,
        long updateCount,
        long deleteCount,
        long scanOpenCount,
        long qualifierRejectCount,
        long candidateIndexLookupCount,
        long candidateIndexFallbackLookupCount,
        long candidateIndexRowIdCount,
        long candidateIndexVisibilityRejectCount,
        long candidateIndexQualifierRejectCount,
        long pageBackedCommittedScanCount,
        long pageBackedCommittedReadCount,
        long rowIdFastPathReadCount,
        long rowIdFastPathHitCount,
        int tableSnapshotCapacity,
        long tableSnapshotDroppedCount,
        List<DelosTableStorageSnapshot> tableSnapshots,
        int transactionSnapshotCapacity,
        long transactionSnapshotDroppedCount,
        List<DelosTransactionSnapshot> transactionSnapshots,
        DelosDatabaseCommitTimingSnapshot commitTiming) {

    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final String WEAKLY_CONSISTENT_COLLECTION =
            "weakly-consistent-database-counters-with-bounded-table-and-transaction-observations";

    public DelosDatabaseStorageSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        providerId = DelosStorageProviderIds.normalize(providerId);
        databaseIdentity = DelosStorageText.requireNonBlank(
                databaseIdentity, "databaseIdentity");
        collectionSemantics = DelosStorageText.requireNonBlank(
                collectionSemantics, "collectionSemantics");
        if (captureSequence < 0L || capturedAtEpochMillis < 0L || tableStateCount < 0) {
            throw new IllegalArgumentException("snapshot identity and state counts must be non-negative");
        }
        validateNonNegative(
                insertCount,
                updateCount,
                deleteCount,
                scanOpenCount,
                qualifierRejectCount,
                candidateIndexLookupCount,
                candidateIndexFallbackLookupCount,
                candidateIndexRowIdCount,
                candidateIndexVisibilityRejectCount,
                candidateIndexQualifierRejectCount,
                pageBackedCommittedScanCount,
                pageBackedCommittedReadCount,
                rowIdFastPathReadCount,
                rowIdFastPathHitCount,
                tableSnapshotDroppedCount,
                transactionSnapshotDroppedCount);
        if (tableSnapshotCapacity < 0
                || transactionSnapshotCapacity < 0) {
            throw new IllegalArgumentException("snapshot capacities must be non-negative");
        }
        tableSnapshots = List.copyOf(
                Objects.requireNonNull(tableSnapshots, "tableSnapshots"));
        transactionSnapshots = List.copyOf(
                Objects.requireNonNull(transactionSnapshots, "transactionSnapshots"));
        if (tableSnapshots.size() > tableSnapshotCapacity) {
            throw new IllegalArgumentException("table snapshots exceed declared capacity");
        }
        if (transactionSnapshots.size() > transactionSnapshotCapacity) {
            throw new IllegalArgumentException("transaction snapshots exceed declared capacity");
        }
        for (DelosTableStorageSnapshot tableSnapshot : tableSnapshots) {
            requireMatchingIdentity(
                    providerId, databaseIdentity,
                    tableSnapshot.providerId(), tableSnapshot.databaseIdentity());
        }
        for (DelosTransactionSnapshot transactionSnapshot : transactionSnapshots) {
            requireMatchingIdentity(
                    providerId, databaseIdentity,
                    transactionSnapshot.providerId(), transactionSnapshot.databaseIdentity());
        }
        commitTiming = Objects.requireNonNull(commitTiming, "commitTiming");
    }

    public static DelosDatabaseStorageSnapshot unavailable(String providerId) {
        return new DelosDatabaseStorageSnapshot(
                CURRENT_SCHEMA_VERSION,
                providerId,
                "<unbound>",
                WEAKLY_CONSISTENT_COLLECTION,
                0L,
                System.currentTimeMillis(),
                false,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0,
                0L,
                List.of(),
                0,
                0L,
                List.of(),
                DelosDatabaseCommitTimingSnapshot.EMPTY);
    }

    private static void requireMatchingIdentity(
            String providerId,
            String databaseIdentity,
            String nestedProviderId,
            String nestedDatabaseIdentity) {
        if (!providerId.equals(nestedProviderId)
                || !databaseIdentity.equals(nestedDatabaseIdentity)) {
            throw new IllegalArgumentException(
                    "nested storage snapshot identity does not match database snapshot");
        }
    }


    private static void validateNonNegative(long... values) {
        for (long value : values) {
            if (value < 0L) {
                throw new IllegalArgumentException("snapshot counters must be non-negative");
            }
        }
    }
}
