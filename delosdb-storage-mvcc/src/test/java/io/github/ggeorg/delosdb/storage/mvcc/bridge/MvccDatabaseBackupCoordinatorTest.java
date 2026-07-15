/*

   DelosDB - Database-scoped MVCC backup coordinator proofs.

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
final class MvccDatabaseBackupCoordinatorTest {
    @TempDir
    Path databaseRoot;

    @Test
    void sameDatabaseTablesShareOneBoundaryButDifferentDatabasesDoNotBlock() throws Exception {
        try (MvccInheritedStore firstStore = new MvccInheritedStore(databaseRoot.resolve("database-a"));
             MvccInheritedStore secondStore = new MvccInheritedStore(databaseRoot.resolve("database-b"))) {
            MvccInheritedTable firstTable = table(firstStore, 1001L);
            MvccInheritedTable siblingTable = table(firstStore, 1002L);
            MvccInheritedTable secondTable = table(secondStore, 2001L);

            assertSame(firstTable.backupCoordinatorForTesting(), siblingTable.backupCoordinatorForTesting());
            assertNotSame(firstTable.backupCoordinatorForTesting(), secondTable.backupCoordinatorForTesting());
            try (DelosStorageBackupCoordinator.DatabaseLease rawStoreIdentityLease =
                         DelosStorageBackupCoordinator.openDatabase(
                                 databaseRoot.resolve("database-a").toFile().getCanonicalPath())) {
                assertSame(
                        firstTable.backupCoordinatorForTesting(),
                        rawStoreIdentityLease.coordinator(),
                        "RawStore canonical identity must resolve the database-owned coordinator");
            }

            DelosStorageTransaction first = firstTable.beginTransaction();
            firstTable.insert(1L, emptyRow(), first);
            DelosStorageTransaction second = secondTable.beginTransaction();
            secondTable.insert(1L, emptyRow(), second);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<?> blockedCommit;
            Future<?> independentCommit;
            DelosStorageBackupCoordinator firstCoordinator = firstStore.backupCoordinatorForTesting();
            try (DelosStorageBackupCoordinator.Guard ignored = firstCoordinator.enterBackupSnapshot()) {
                blockedCommit = executor.submit(() -> firstTable.commit(first));
                independentCommit = executor.submit(() -> secondTable.commit(second));

                awaitWaitingWriter(firstCoordinator, Duration.ofSeconds(10));
                independentCommit.get(10L, TimeUnit.SECONDS);
                assertThrows(TimeoutException.class, () -> blockedCommit.get(200L, TimeUnit.MILLISECONDS));

                DelosStorageBackupCoordinator.Snapshot held = firstCoordinator.snapshot();
                assertEquals(1L, held.backupSnapshotStartCount());
                assertEquals(0L, held.backupSnapshotCompletionCount());
                assertEquals(
                        held.lastBackupStartCommittedTransactionCount(),
                        held.lastBackupEndCommittedTransactionCount());
                assertEquals(0, firstTable.logicalRowCountForTesting());
                assertEquals(1, secondTable.logicalRowCountForTesting());
            } finally {
                executor.shutdown();
            }

            blockedCommit.get(10L, TimeUnit.SECONDS);
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));

            DelosStorageBackupCoordinator.Snapshot firstSnapshot = firstCoordinator.snapshot();
            assertEquals(1L, firstSnapshot.backupSnapshotCompletionCount());
            assertEquals(1L, firstSnapshot.committedTransactionCount());
            assertEquals(
                    firstSnapshot.lastBackupStartCommittedTransactionCount(),
                    firstSnapshot.lastBackupEndCommittedTransactionCount());
            assertTrue(firstSnapshot.maximumDurableMutationWaitNanos()
                    >= TimeUnit.MILLISECONDS.toNanos(100L));
            assertEquals(1L, firstSnapshot.mutationEntryCount(
                    DelosStorageBackupCoordinator.Mutation.COMMIT_PUBLICATION));

            DelosStorageBackupCoordinator.Snapshot secondSnapshot =
                    secondStore.backupCoordinatorForTesting().snapshot();
            assertEquals(0L, secondSnapshot.backupSnapshotStartCount());
            assertEquals(1L, secondSnapshot.committedTransactionCount());
            assertEquals(1, firstTable.logicalRowCountForTesting());
            firstTable.assertConsistentForTesting();
            secondTable.assertConsistentForTesting();
        }
    }

    @Test
    void activeAndAbortStatusWritesWaitAtTheDatabaseBoundary() throws Exception {
        try (MvccInheritedStore store = new MvccInheritedStore(databaseRoot.resolve("status-safety"))) {
            MvccInheritedTable table = table(store, 3001L);
            DelosStorageBackupCoordinator coordinator = store.backupCoordinatorForTesting();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<DelosStorageTransaction> begin;
            try (DelosStorageBackupCoordinator.Guard ignored = coordinator.enterBackupSnapshot()) {
                begin = executor.submit(table::beginTransaction);
                awaitWaitingWriter(coordinator, Duration.ofSeconds(10));
                assertThrows(TimeoutException.class, () -> begin.get(200L, TimeUnit.MILLISECONDS));
            }
            DelosStorageTransaction transaction = begin.get(10L, TimeUnit.SECONDS);

            Future<?> abort;
            try (DelosStorageBackupCoordinator.Guard ignored = coordinator.enterBackupSnapshot()) {
                abort = executor.submit(() -> table.abort(transaction));
                awaitWaitingWriter(coordinator, Duration.ofSeconds(10));
                assertThrows(TimeoutException.class, () -> abort.get(200L, TimeUnit.MILLISECONDS));
            }
            abort.get(10L, TimeUnit.SECONDS);
            executor.shutdown();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));

            DelosStorageBackupCoordinator.Snapshot snapshot = coordinator.snapshot();
            assertEquals(2L, snapshot.backupSnapshotStartCount());
            assertEquals(2L, snapshot.backupSnapshotCompletionCount());
            assertEquals(1L, snapshot.mutationEntryCount(
                    DelosStorageBackupCoordinator.Mutation.TRANSACTION_BEGIN));
            assertEquals(1L, snapshot.mutationEntryCount(
                    DelosStorageBackupCoordinator.Mutation.TRANSACTION_ABORT));
            assertEquals(0L, snapshot.committedTransactionCount());
            assertEquals(
                    snapshot.lastBackupStartCommittedTransactionCount(),
                    snapshot.lastBackupEndCommittedTransactionCount());
            assertFalse(snapshot.databaseIdentity().isBlank());
            table.assertConsistentForTesting();
        }
    }

    private static MvccInheritedTable table(MvccInheritedStore store, long containerId) {
        return (MvccInheritedTable) store.openTable(new DelosStorageTableKey(0L, containerId));
    }

    private static void awaitWaitingWriter(
            DelosStorageBackupCoordinator coordinator,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (coordinator.snapshot().waitingDurableMutationCount() > 0) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("writer did not reach the database backup boundary");
    }

    private static StoreDataValue[] emptyRow() {
        return new StoreDataValue[0];
    }
}
