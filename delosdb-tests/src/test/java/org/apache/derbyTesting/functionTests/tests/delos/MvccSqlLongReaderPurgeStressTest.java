/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlLongReaderPurgeStressTest

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

/** Stress proof that purge/vacuum preserves history required by a long SQL reader. */
public final class MvccSqlLongReaderPurgeStressTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY = "delosdb.mvcc.purgeDaemon.enabled";
    private static final String CHANGED_ROWS_THRESHOLD_PROPERTY = "delosdb.mvcc.purgeDaemon.changedRowsThreshold";
    private static final String VISIBILITY_DEBT_THRESHOLD_PROPERTY =
            "delosdb.mvcc.purgeDaemon.visibilityDebtThreshold";

    public void testLongRepeatableReadReaderSurvivesAggressivePurgeStress() throws Exception {
        String databaseName = databaseName("mvcc-long-reader-purge-stress-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table long_reader_purge_t "
                    + "(id int primary key, payload varchar(32)) using delos_mvcc");
            executeUpdate(setup, "insert into long_reader_purge_t values (1, 'v00')");
            setup.commit();
            containerId = mvccContainerId(setup, "LONG_READER_PURGE_T");
            setup.rollback();
        }

        try (SystemPropertyScope enabled = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope changedRowsThreshold = setSystemProperty(CHANGED_ROWS_THRESHOLD_PROPERTY, "1");
             SystemPropertyScope debtThreshold = setSystemProperty(VISIBILITY_DEBT_THRESHOLD_PROPERTY, "1");
             Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v00");

            long runCountBeforeStress = diagnostics.purgeDaemonRunCountForTesting(0, containerId);
            int iterations = 12;
            for (int i = 1; i <= iterations; i++) {
                String value = String.format("v%02d", i);
                assertEquals(1, executeUpdate(writer,
                        "update long_reader_purge_t set payload = '" + value + "' where id = 1"));
                writer.commit();

                if (i % 3 == 0) {
                    inPlaceCompressTable(writer, "LONG_READER_PURGE_T");
                    writer.commit();
                }

                assertRows(reader,
                        "select id, payload from long_reader_purge_t where id = 1",
                        "1|v00");
                assertRows(writer,
                        "select id, payload from long_reader_purge_t where id = 1",
                        "1|" + value);
                assertEquals("purge stress must preserve one logical row",
                        1, diagnostics.logicalRowCountForTesting(0, containerId));
            }

            assertTrue("aggressive purge daemon should run during the write burst",
                    diagnostics.purgeDaemonRunCountForTesting(0, containerId) > runCountBeforeStress);
            assertTrue("aggressive purge daemon should schedule work during the write burst",
                    diagnostics.purgeDaemonScheduleCountForTesting(0, containerId) > 0L);
            assertEquals("automatic purge queue should not leave pending entries after drain",
                    0L, diagnostics.purgeQueuePendingCountForTesting(0, containerId));

            int versionsWhileReaderActive = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertTrue("active long reader must retain at least old-reader and latest versions, got "
                    + versionsWhileReaderActive,
                    versionsWhileReaderActive >= 2);
            assertRows(reader,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v00");
            assertRows(writer,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v12");

            reader.rollback();

            inPlaceCompressTable(writer, "LONG_READER_PURGE_T");
            writer.commit();

            assertFalse("final vacuum should run after the long reader releases its snapshot",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("final vacuum should prune history previously protected by the long reader",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) >= 1);
            assertTrue("final vacuum should reduce physical versions after reader release",
                    diagnostics.physicalVersionCountForTesting(0, containerId) < versionsWhileReaderActive);
            assertEquals("final vacuum must preserve one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(writer,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v12");
            writer.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v12");
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }
}
