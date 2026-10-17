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

/**
 * Stress proof that vacuum preserves the history a long repeatable-read
 * reader still needs. Background maintenance runs after every committed
 * change, and an explicit in-place compress adds pressure every third update;
 * neither may remove the reader's version, and once the reader ends,
 * background maintenance alone must prune it.
 */
public final class MvccSqlLongReaderPurgeStressTest extends MvccSqlTestSupport {
    private static final String MAINTENANCE_ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.enabled";
    private static final String PERIOD_MILLIS_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.periodMillis";
    private static final String CHANGED_ROWS_THRESHOLD_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.changedRowsThreshold";
    private static final String TABLE = "LONG_READER_PURGE_T";

    public void testLongRepeatableReadReaderSurvivesAggressivePurgeStress() throws Exception {
        String databaseName = databaseName("mvcc-long-reader-purge-stress-db");
        // Maintenance settings are read when the database boots.
        try (SystemPropertyScope maintenance =
                     setSystemProperty(MAINTENANCE_ENABLED_PROPERTY, "true");
             SystemPropertyScope period = setSystemProperty(PERIOD_MILLIS_PROPERTY, "25");
             SystemPropertyScope changedRowsThreshold =
                     setSystemProperty(CHANGED_ROWS_THRESHOLD_PROPERTY, "1")) {
            runWithMaintenance(databaseName);
        }
    }

    private static void runWithMaintenance(String databaseName) throws Exception {
        long containerId;
        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table long_reader_purge_t "
                    + "(id int primary key, payload varchar(32)) using delos_mvcc");
            executeUpdate(setup, "insert into long_reader_purge_t values (1, 'v00')");
            setup.commit();
            containerId = mvccContainerId(setup, TABLE);
            setup.rollback();
        }

        assertTrue(mvccMaintenanceSnapshot(databaseName).maintenanceEnabled());

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false);
             Connection inspector = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            inspector.setAutoCommit(false);

            assertRows(reader,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v00");
            long runsBeforeUpdates = maintenanceTable(
                    mvccMaintenanceSnapshot(databaseName), containerId).runCount();

            int iterations = 12;
            for (int i = 1; i <= iterations; i++) {
                String value = String.format("v%02d", i);
                assertEquals(1, executeUpdate(writer,
                        "update long_reader_purge_t set payload = '" + value + "' where id = 1"));
                writer.commit();

                if (i % 3 == 0) {
                    inPlaceCompressTable(writer, TABLE);
                    writer.commit();
                }

                assertRows(reader,
                        "select id, payload from long_reader_purge_t where id = 1",
                        "1|v00");
                assertRows(writer,
                        "select id, payload from long_reader_purge_t where id = 1",
                        "1|" + value);
                assertRows(writer, "select count(*) from long_reader_purge_t", "1");
                writer.commit();
            }

            // Background maintenance must run against the reader's horizon,
            // not merely be enabled.
            waitUntil("background maintenance did not run while the reader was active", () ->
                    maintenanceTable(mvccMaintenanceSnapshot(databaseName), containerId)
                            .runCount() > runsBeforeUpdates);

            int versionsWhileReaderActive = storedVersions(inspector);
            assertTrue("active long reader must retain at least old-reader and latest versions, got "
                    + versionsWhileReaderActive,
                    versionsWhileReaderActive >= 2);
            assertRows(reader,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v00");
            assertRows(writer,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v12");
            writer.commit();

            reader.rollback();

            waitUntil("background maintenance did not prune history after the reader ended", () ->
                    storedVersions(inspector) < versionsWhileReaderActive);
            assertRows(writer, "select count(*) from long_reader_purge_t", "1");
            assertRows(writer,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v12");
            assertTableConsistent(writer);
            writer.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, payload from long_reader_purge_t where id = 1",
                    "1|v12");
            assertTableConsistent(reopened);
        }
    }

    private static int storedVersions(Connection inspector) throws Exception {
        try {
            return MvccRawStoreMetadataInspection.versions(inspector, TABLE).size();
        } finally {
            inspector.commit();
        }
    }

    private static void assertTableConsistent(Connection connection) throws Exception {
        assertRows(connection,
                "values syscs_util.syscs_check_table('APP', '" + TABLE + "')",
                "1");
    }
}
