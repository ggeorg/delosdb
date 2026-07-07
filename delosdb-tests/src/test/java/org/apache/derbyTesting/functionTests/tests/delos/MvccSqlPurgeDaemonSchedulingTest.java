/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPurgeDaemonSchedulingTest

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

/** SQL gate for deterministic MVCC purge-daemon scheduling. */
public final class MvccSqlPurgeDaemonSchedulingTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY = "delosdb.mvcc.purgeDaemon.enabled";
    private static final String THRESHOLD_PROPERTY = "delosdb.mvcc.purgeDaemon.changedRowsThreshold";
    private static final String VISIBILITY_DEBT_THRESHOLD_PROPERTY =
            "delosdb.mvcc.purgeDaemon.visibilityDebtThreshold";

    public void testPurgeDaemonRunsDeterministicallyAfterCommittedWriteBurst() throws Exception {
        String databaseName = databaseName("mvcc-purge-daemon-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (SystemPropertyScope enabled = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope threshold = setSystemProperty(THRESHOLD_PROPERTY, "1");
             Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table purge_daemon_t "
                    + "(id int primary key, payload varchar(64)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "PURGE_DAEMON_T");
            connection.rollback();

            executeUpdate(connection, "insert into purge_daemon_t values (1, 'v1')");
            connection.commit();
            long runsAfterInsert = diagnostics.purgeDaemonRunCountForTesting(0, containerId);

            executeUpdate(connection, "update purge_daemon_t set payload = 'v2' where id = 1");
            connection.commit();
            executeUpdate(connection, "update purge_daemon_t set payload = 'v3' where id = 1");
            connection.commit();

            assertTrue("purge daemon should schedule automatic work after committed updates",
                    diagnostics.purgeDaemonScheduleCountForTesting(0, containerId) >= 2L);
            assertTrue("purge daemon should run after committed updates",
                    diagnostics.purgeDaemonRunCountForTesting(0, containerId) > runsAfterInsert);
            assertEquals("last trigger should be the single committed update row",
                    1L, diagnostics.purgeDaemonLastTriggerChangedRowsForTesting(0, containerId));
            assertTrue("last decision should record a ran outcome",
                    diagnostics.purgeDaemonLastDecisionForTesting(0, containerId).startsWith("ran:"));
            assertFalse("automatic purge should not be skipped without a retained reader",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("automatic purge should remove obsolete versions without manual compress",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) > 0);
            assertTrue("automatic purge should still enqueue obsolete versions before drain",
                    diagnostics.purgeQueueEnqueueCountForTesting(0, containerId) > 0L);
            assertTrue("automatic purge should drain queued obsolete versions",
                    diagnostics.purgeQueueDrainCountForTesting(0, containerId) > 0L);
            assertEquals("purge queue should be empty after daemon drain",
                    0L, diagnostics.purgeQueuePendingCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection, "select id, payload from purge_daemon_t", "1|v3");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, payload from purge_daemon_t", "1|v3");
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }

    public void testPurgeDaemonUsesVisibilityDebtPolicy() throws Exception {
        String databaseName = databaseName("mvcc-purge-daemon-visibility-debt-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (SystemPropertyScope enabled = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope changedRowsThreshold = setSystemProperty(THRESHOLD_PROPERTY, "1");
             SystemPropertyScope debtThreshold = setSystemProperty(VISIBILITY_DEBT_THRESHOLD_PROPERTY, "100");
             Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table purge_debt_t "
                    + "(id int primary key, payload varchar(64)) using delos_mvcc");
            connection.commit();
            long containerId = mvccContainerId(connection, "PURGE_DEBT_T");
            connection.rollback();

            executeUpdate(connection, "insert into purge_debt_t values (1, 'v1')");
            connection.commit();
            executeUpdate(connection, "update purge_debt_t set payload = 'v2' where id = 1");
            connection.commit();

            assertEquals("changed-row threshold alone must not schedule purge when visibility debt is too low",
                    0L, diagnostics.purgeDaemonScheduleCountForTesting(0, containerId));
            assertEquals("purge should not run below the visibility-debt threshold",
                    0L, diagnostics.purgeDaemonRunCountForTesting(0, containerId));
            assertTrue("last decision should explain the visibility-debt policy",
                    diagnostics.purgeDaemonLastDecisionForTesting(0, containerId)
                            .contains("visibility debt below threshold 100"));
            assertTrue("visibility-debt diagnostics should expose obsolete version pressure",
                    diagnostics.purgeDaemonLastVisibilityDebtScoreForTesting(0, containerId) > 0L);
            assertTrue("visibility-debt summary should include obsolete version pressure",
                    diagnostics.purgeDaemonLastVisibilityDebtSummaryForTesting(0, containerId)
                            .contains("obsoleteVersions="));

            debtThreshold.set("1");
            executeUpdate(connection, "update purge_debt_t set payload = 'v3' where id = 1");
            connection.commit();

            assertTrue("purge should schedule when visibility debt reaches the configured threshold",
                    diagnostics.purgeDaemonScheduleCountForTesting(0, containerId) > 0L);
            assertTrue("purge should run when visibility debt reaches the configured threshold",
                    diagnostics.purgeDaemonRunCountForTesting(0, containerId) > 0L);
            assertTrue("last decision should include the measured visibility debt",
                    diagnostics.purgeDaemonLastDecisionForTesting(0, containerId).contains("debt score="));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection, "select id, payload from purge_debt_t", "1|v3");
            connection.rollback();
        }
    }

    public void testPurgeDaemonIsPausedByDefault() throws Exception {
        String databaseName = databaseName("mvcc-purge-daemon-default-paused-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (SystemPropertyScope enabled = clearSystemProperty(ENABLED_PROPERTY);
             SystemPropertyScope threshold = clearSystemProperty(THRESHOLD_PROPERTY);
             Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table purge_daemon_paused_t "
                    + "(id int primary key, payload varchar(64)) using delos_mvcc");
            connection.commit();
            long containerId = mvccContainerId(connection, "PURGE_DAEMON_PAUSED_T");
            connection.rollback();

            executeUpdate(connection, "insert into purge_daemon_paused_t values (1, 'v1')");
            connection.commit();
            executeUpdate(connection, "update purge_daemon_paused_t set payload = 'v2' where id = 1");
            connection.commit();

            assertEquals("purge daemon should not schedule when the opt-in property is absent",
                    0L, diagnostics.purgeDaemonScheduleCountForTesting(0, containerId));
            assertEquals("purge daemon should not run when the opt-in property is absent",
                    0L, diagnostics.purgeDaemonRunCountForTesting(0, containerId));
            assertTrue("default-paused daemon should report the disabled decision",
                    diagnostics.purgeDaemonLastDecisionForTesting(0, containerId).contains("disabled"));
            assertRows(connection, "select id, payload from purge_daemon_paused_t", "1|v2");
            connection.rollback();
        }
    }
}
