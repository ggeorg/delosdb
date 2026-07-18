/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccTableTransactionSnapshotTest

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
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.store.types.DelosDatabaseStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosTableStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosTransactionSnapshot;

/** Phase 9 proof for immutable table and active provider-transaction observations. */
public final class MvccTableTransactionSnapshotTest extends MvccSqlTestSupport {
    public void testTableAndTransactionSnapshotsRemainBoundedImmutableAndScoped()
            throws Exception {
        String databaseA = databaseName("mvcc-table-transaction-snapshot-a");
        String databaseB = databaseName("mvcc-table-transaction-snapshot-b");
        DelosStorageDiagnostics diagnosticsA = mvccDiagnostics(databaseA);
        DelosStorageDiagnostics diagnosticsB = mvccDiagnostics(databaseB);

        try (Connection connectionA = openDatabase(databaseA, true);
             Connection connectionB = openDatabase(databaseB, true)) {
            connectionA.setAutoCommit(false);
            connectionB.setAutoCommit(false);
            connectionB.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            executeUpdate(connectionA,
                    "create table snapshot_a1 (id int, code varchar(32)) using delos_mvcc");
            executeUpdate(connectionA,
                    "create table snapshot_a2 (id int, code varchar(32)) using delos_mvcc");
            executeUpdate(connectionB,
                    "create table snapshot_b1 (id int, code varchar(32)) using delos_mvcc");
            connectionA.commit();
            connectionB.commit();

            executeUpdate(connectionA, "insert into snapshot_a1 values (1, 'alpha')");
            executeUpdate(connectionA, "insert into snapshot_a2 values (2, 'beta')");
            assertRows(connectionB, "select count(*) from snapshot_b1", "0");

            DelosDatabaseStorageSnapshot snapshotA = diagnosticsA.databaseStorageSnapshot();
            DelosDatabaseStorageSnapshot snapshotB = diagnosticsB.databaseStorageSnapshot();

            assertEquals(2, snapshotA.schemaVersion());
            assertEquals(256, snapshotA.tableSnapshotCapacity());
            assertEquals(512, snapshotA.transactionSnapshotCapacity());
            assertEquals(0L, snapshotA.tableSnapshotDroppedCount());
            assertEquals(0L, snapshotA.transactionSnapshotDroppedCount());
            assertEquals(2, snapshotA.tableSnapshots().size());
            assertEquals(1, snapshotB.tableSnapshots().size());
            assertTrue("database A must expose its active provider transactions",
                    snapshotA.transactionSnapshots().size() >= 2);
            assertTrue("database B read must remain scoped to database B",
                    snapshotB.transactionSnapshots().stream().anyMatch(
                            transaction -> DelosTransactionSnapshot.READ_ONLY.equals(
                                    transaction.accessMode())));

            for (DelosTableStorageSnapshot table : snapshotA.tableSnapshots()) {
                assertEquals(snapshotA.databaseIdentity(), table.databaseIdentity());
                assertEquals(DelosTableStorageSnapshot.CURRENT_SCHEMA_VERSION,
                        table.schemaVersion());
                assertEquals(DelosTableStorageSnapshot.WEAKLY_CONSISTENT_COLLECTION,
                        table.collectionSemantics());
                assertEquals(snapshotA.capturedAtEpochMillis(),
                        table.capturedAtEpochMillis());
                assertTrue(table.tableActive());
                assertTrue(table.registeredWriteTransactionCount() > 0);
                assertTrue(table.registeredWriteIntentCount() > 0);
            }
            for (DelosTransactionSnapshot transaction : snapshotA.transactionSnapshots()) {
                assertEquals(snapshotA.databaseIdentity(), transaction.databaseIdentity());
                assertEquals(DelosTransactionSnapshot.CURRENT_SCHEMA_VERSION,
                        transaction.schemaVersion());
                assertEquals(DelosTransactionSnapshot.ACTIVE, transaction.state());
                assertEquals(snapshotA.capturedAtEpochMillis(),
                        transaction.capturedAtEpochMillis());
                assertEquals(DelosTransactionSnapshot.READ_WRITE, transaction.accessMode());
                assertTrue(transaction.providerTransactionId() > 0L);
                assertTrue(transaction.writeIntentCount() > 0);
            }
            assertTrue(snapshotB.tableSnapshots().stream().allMatch(
                    table -> snapshotB.databaseIdentity().equals(table.databaseIdentity())));
            assertTrue(snapshotB.transactionSnapshots().stream().allMatch(
                    transaction -> snapshotB.databaseIdentity().equals(
                            transaction.databaseIdentity())));

            try {
                snapshotA.tableSnapshots().clear();
                fail("table snapshots must be immutable");
            } catch (UnsupportedOperationException expected) {
                // Expected immutable nested observation.
            }
            try {
                snapshotA.transactionSnapshots().clear();
                fail("transaction snapshots must be immutable");
            } catch (UnsupportedOperationException expected) {
                // Expected immutable nested observation.
            }

            connectionA.rollback();
            connectionB.rollback();
            DelosDatabaseStorageSnapshot afterRollbackA = diagnosticsA.databaseStorageSnapshot();
            DelosDatabaseStorageSnapshot afterRollbackB = diagnosticsB.databaseStorageSnapshot();
            assertTrue("rollback must retire database A provider transactions",
                    afterRollbackA.transactionSnapshots().isEmpty());
            assertTrue("rollback must retire database B provider transactions",
                    afterRollbackB.transactionSnapshots().isEmpty());
            assertTrue(afterRollbackA.tableSnapshots().stream().allMatch(
                    table -> table.registeredTransactionCount() == 0
                            && table.registeredWriteIntentCount() == 0));
        } finally {
            shutdownDatabase(databaseA);
            shutdownDatabase(databaseB);
        }
    }

    public void testConcurrentTransactionSnapshotCaptureRemainsCoherent()
            throws Exception {
        String database = databaseName("mvcc-transaction-snapshot-concurrent");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(database);
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch writesComplete = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread writer = null;

        try {
            try (Connection setup = openDatabase(database, true)) {
                executeUpdate(setup,
                        "create table snapshot_concurrent (id int, code varchar(32)) "
                                + "using delos_mvcc");
            }

            writer = new Thread(() -> {
                try (Connection connection = openDatabase(database, false)) {
                    connection.setAutoCommit(false);
                    for (int i = 0; i < 200; i++) {
                        executeUpdate(connection,
                                "insert into snapshot_concurrent values ("
                                        + i + ", 'value-" + i + "')");
                        if (i == 0) {
                            firstWrite.countDown();
                        }
                        if ((i & 7) == 0) {
                            Thread.yield();
                        }
                    }
                    writesComplete.countDown();
                    releaseWriter.await();
                    connection.rollback();
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                    firstWrite.countDown();
                    writesComplete.countDown();
                }
            }, "mvcc-transaction-snapshot-writer");
            writer.start();

            assertTrue("concurrent writer did not publish its first write",
                    firstWrite.await(30L, TimeUnit.SECONDS));
            long captureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
            int captureCount = 0;
            while (writesComplete.getCount() != 0L || captureCount < 32) {
                if (System.nanoTime() > captureDeadline) {
                    fail("concurrent writer did not finish within the snapshot proof window");
                }
                DelosDatabaseStorageSnapshot snapshot = diagnostics.databaseStorageSnapshot();
                for (DelosTransactionSnapshot transaction : snapshot.transactionSnapshots()) {
                    assertTrue(transaction.appendedWriteIntentCount()
                            >= transaction.writeIntentCount());
                    if (DelosTransactionSnapshot.READ_ONLY.equals(transaction.accessMode())) {
                        assertEquals(0, transaction.writeIntentCount());
                        assertEquals(0, transaction.appendedWriteIntentCount());
                    }
                }
                captureCount++;
            }

            DelosDatabaseStorageSnapshot active = diagnostics.databaseStorageSnapshot();
            assertTrue("concurrent writer must remain observable before rollback",
                    active.transactionSnapshots().stream().anyMatch(
                            transaction -> DelosTransactionSnapshot.READ_WRITE.equals(
                                    transaction.accessMode())
                                    && transaction.appendedWriteIntentCount() >= 200));

            releaseWriter.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(30L));
            assertFalse("concurrent snapshot writer did not stop", writer.isAlive());
            if (workerFailure.get() != null) {
                throw new AssertionError("concurrent snapshot worker failed", workerFailure.get());
            }
            assertTrue("rollback must retire the concurrent writer",
                    diagnostics.databaseStorageSnapshot().transactionSnapshots().isEmpty());
        } finally {
            releaseWriter.countDown();
            if (writer != null && writer.isAlive()) {
                writer.interrupt();
                writer.join(TimeUnit.SECONDS.toMillis(5L));
            }
            shutdownDatabase(database);
        }
    }
}
