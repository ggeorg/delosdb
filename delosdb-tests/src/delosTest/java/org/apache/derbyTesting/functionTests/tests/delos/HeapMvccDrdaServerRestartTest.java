/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccDrdaServerRestartTest

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
import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.Test;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * DRDA server shutdown/restart proof with active heap and MVCC transactions.
 * Server shutdown must terminate both sessions, roll back their uncommitted
 * work, and permit a clean database shutdown/reboot followed by new work.
 */
public final class HeapMvccDrdaServerRestartTest extends BaseJDBCTestCase {
    private static final String HEAP_TABLE = "drda_restart_heap";
    private static final String MVCC_TABLE = "drda_restart_mvcc";

    public HeapMvccDrdaServerRestartTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(HeapMvccDrdaServerRestartTest.class);
        return TestConfiguration.clientServerDecorator(new CleanDatabaseTestSetup(suite));
    }

    public void testServerRestartRollsBackActiveHeapAndMvccTransactions()
            throws Exception {
        HeapMvccDrdaFailureTestSupport.assertNetworkClient(
                getTestConfiguration());

        try (Connection setup = openDefaultConnection()) {
            setup.setAutoCommit(false);
            HeapMvccDrdaFailureTestSupport.createMarkerTable(
                    setup, HEAP_TABLE, false, 1);
            HeapMvccDrdaFailureTestSupport.createMarkerTable(
                    setup, MVCC_TABLE, true, 1);
            setup.commit();
        }

        Connection heapVictim = openDefaultConnection();
        Connection mvccVictim = openDefaultConnection();
        boolean serverStopped = false;
        try {
            stageUncommittedMutation(heapVictim, HEAP_TABLE, 91);
            stageUncommittedMutation(mvccVictim, MVCC_TABLE, 92);

            getTestConfiguration().stopNetworkServer();
            serverStopped = true;

            assertConnectionTerminated(heapVictim, HEAP_TABLE);
            assertConnectionTerminated(mvccVictim, MVCC_TABLE);

            // Reboot the database engine as well as the DRDA listener. This
            // forces both heap and MVCC state to be rehydrated from durable
            // state rather than surviving only in same-JVM caches.
            MvccSqlTestSupport.shutdownDatabase(
                    getTestConfiguration().getDefaultDatabaseName());

            getTestConfiguration().startNetworkServer();
            serverStopped = false;

            try (Connection survivor = openDefaultConnection()) {
                survivor.setAutoCommit(false);
                HeapMvccDrdaFailureTestSupport.assertMarkerRow(survivor, HEAP_TABLE, 1, 1);
                HeapMvccDrdaFailureTestSupport.assertNoRow(survivor, HEAP_TABLE, 2);
                HeapMvccDrdaFailureTestSupport.assertMarkerRow(survivor, MVCC_TABLE, 1, 1);
                HeapMvccDrdaFailureTestSupport.assertNoRow(survivor, MVCC_TABLE, 2);

                assertEquals(1, HeapMvccDrdaFailureTestSupport.executeUpdate(survivor,
                        "update " + HEAP_TABLE
                                + " set marker = marker + 1 where id = 1"));
                assertEquals(1, HeapMvccDrdaFailureTestSupport.executeUpdate(survivor,
                        "update " + MVCC_TABLE
                                + " set marker = marker + 1 where id = 1"));
                survivor.commit();

                HeapMvccDrdaFailureTestSupport.assertMarkerRow(survivor, HEAP_TABLE, 1, 2);
                HeapMvccDrdaFailureTestSupport.assertMarkerRow(survivor, MVCC_TABLE, 1, 2);
                survivor.rollback();
            }

            DelosStorageDiagnostics diagnostics =
                    MvccSqlTestSupport.mvccDiagnostics(
                            getTestConfiguration().getDatabasePath(
                                    getTestConfiguration().getDefaultDatabaseName()));
            try (Connection check = openDefaultConnection()) {
                check.setAutoCommit(false);
                long containerId = MvccSqlTestSupport.mvccContainerId(
                        check, MVCC_TABLE.toUpperCase(java.util.Locale.ROOT));
                diagnostics.assertConsistentForTesting(0, containerId);
                check.rollback();
            }
        } finally {
            HeapMvccDrdaFailureTestSupport.closeBrokenConnection(heapVictim);
            HeapMvccDrdaFailureTestSupport.closeBrokenConnection(mvccVictim);
            if (serverStopped) {
                getTestConfiguration().startNetworkServer();
            }
        }
    }

    private static void stageUncommittedMutation(Connection connection,
                                                  String table,
                                                  int marker)
            throws SQLException {
        connection.setAutoCommit(false);
        assertEquals(1, HeapMvccDrdaFailureTestSupport.executeUpdate(connection,
                "update " + table + " set marker = " + marker + " where id = 1"));
        assertEquals(1, HeapMvccDrdaFailureTestSupport.executeUpdate(connection,
                "insert into " + table + " values (2, " + marker + ")"));
        HeapMvccDrdaFailureTestSupport.assertMarkerRow(connection, table, 1, marker);
        HeapMvccDrdaFailureTestSupport.assertMarkerRow(connection, table, 2, marker);
    }

    private static void assertConnectionTerminated(Connection connection,
                                                   String table)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select marker from " + table + " where id = 1");
            fail("server shutdown should terminate active client for " + table);
        } catch (SQLException expected) {
            String sqlState = expected.getSQLState();
            assertNotNull("terminated connection should report SQLState for " + table,
                    sqlState);
            assertTrue("unexpected SQLState after server shutdown for " + table
                            + ": " + sqlState,
                    HeapMvccDrdaFailureTestSupport.isTransportFailure(expected));
        }
    }

}
