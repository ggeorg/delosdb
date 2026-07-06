/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlIsolationLevelCheckpointTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL checkpoint for the explicit delos_mvcc isolation-level read-view policy. */
public final class MvccSqlIsolationLevelCheckpointTest extends MvccSqlTestSupport {
    public void testReadCommittedRefreshesButRepeatableReadKeepsTransactionSnapshot() throws Exception {
        String databaseName = databaseName("mvcc-isolation-level-checkpoint-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table isolation_checkpoint_t "
                    + "(id int, payload varchar(32)) using delos_mvcc");
            executeUpdate(setup, "insert into isolation_checkpoint_t values (1, 'before')");
            setup.commit();
            containerId = mvccContainerId(setup, "ISOLATION_CHECKPOINT_T");
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, payload from isolation_checkpoint_t",
                    "1|before");

            executeUpdate(writer, "update isolation_checkpoint_t set payload = 'after-rc' where id = 1");
            writer.commit();

            diagnostics.resetScanCountersForTesting();
            assertRows(reader,
                    "select id, payload from isolation_checkpoint_t",
                    "1|after-rc");
            assertTrue("READ COMMITTED should refresh to the current committed image between statements",
                    diagnostics.pageBackedCommittedScanCountForTesting() > 0);
            assertEquals("READ COMMITTED must not use legacy snapshot fallback",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            reader.rollback();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, payload from isolation_checkpoint_t",
                    "1|after-rc");

            executeUpdate(writer, "update isolation_checkpoint_t set payload = 'after-rr' where id = 1");
            writer.commit();

            int beforeHistoricalScan = diagnostics.pageBackedHistoricalSnapshotScanCountForTesting(0, containerId);
            diagnostics.resetScanCountersForTesting();
            assertRows(reader,
                    "select id, payload from isolation_checkpoint_t",
                    "1|after-rc");
            assertEquals("REPEATABLE READ must not use the current committed image while the transaction is active",
                    0, diagnostics.pageBackedCommittedScanCountForTesting());
            assertTrue("REPEATABLE READ should be served by the page-backed historical snapshot path",
                    diagnostics.pageBackedHistoricalSnapshotScanCountForTesting(0, containerId)
                            > beforeHistoricalScan);
            assertEquals("REPEATABLE READ must not use legacy snapshot fallback",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));

            reader.commit();
            assertRows(reader,
                    "select id, payload from isolation_checkpoint_t",
                    "1|after-rr");
            reader.rollback();
        }

        shutdownDatabase(databaseName);
    }

    public void testRepeatableReadSeesOwnWritesWithoutAdvancingToOtherCommittedWriters() throws Exception {
        String databaseName = databaseName("mvcc-isolation-read-your-writes-db");

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table isolation_read_your_writes_t "
                    + "(id int, payload varchar(32)) using delos_mvcc");
            executeUpdate(setup, "insert into isolation_read_your_writes_t values (1, 'base')");
            setup.commit();
        }

        try (Connection readerWriter = openDatabase(databaseName, false);
             Connection otherWriter = openDatabase(databaseName, false)) {
            readerWriter.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            readerWriter.setAutoCommit(false);
            otherWriter.setAutoCommit(false);

            assertRows(readerWriter,
                    "select id, payload from isolation_read_your_writes_t order by id",
                    "1|base");

            executeUpdate(readerWriter,
                    "update isolation_read_your_writes_t set payload = 'own-write' where id = 1");
            assertRows(readerWriter,
                    "select id, payload from isolation_read_your_writes_t order by id",
                    "1|own-write");

            executeUpdate(otherWriter,
                    "insert into isolation_read_your_writes_t values (2, 'other-committed')");
            otherWriter.commit();

            assertRows(readerWriter,
                    "select id, payload from isolation_read_your_writes_t order by id",
                    "1|own-write");
            readerWriter.commit();

            assertRows(readerWriter,
                    "select id, payload from isolation_read_your_writes_t order by id",
                    "1|own-write",
                    "2|other-committed");
            readerWriter.rollback();
        }

        shutdownDatabase(databaseName);
    }
}
