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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
        assertTrue("test must run through Derby network client",
                getTestConfiguration().getJDBCClient().isDerbyNetClient());

        try (Connection setup = openDefaultConnection()) {
            setup.setAutoCommit(false);
            createTable(setup, HEAP_TABLE, false);
            createTable(setup, MVCC_TABLE, true);
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
                assertRow(survivor, HEAP_TABLE, 1, 1);
                assertNoRow(survivor, HEAP_TABLE, 2);
                assertRow(survivor, MVCC_TABLE, 1, 1);
                assertNoRow(survivor, MVCC_TABLE, 2);

                assertEquals(1, executeUpdate(survivor,
                        "update " + HEAP_TABLE
                                + " set marker = marker + 1 where id = 1"));
                assertEquals(1, executeUpdate(survivor,
                        "update " + MVCC_TABLE
                                + " set marker = marker + 1 where id = 1"));
                survivor.commit();

                assertRow(survivor, HEAP_TABLE, 1, 2);
                assertRow(survivor, MVCC_TABLE, 1, 2);
                survivor.rollback();
            }

            DelosStorageDiagnostics diagnostics =
                    MvccSqlTestSupport.mvccDiagnostics();
            try (Connection check = openDefaultConnection()) {
                check.setAutoCommit(false);
                long containerId = MvccSqlTestSupport.mvccContainerId(
                        check, MVCC_TABLE.toUpperCase(java.util.Locale.ROOT));
                diagnostics.assertConsistentForTesting(0, containerId);
                check.rollback();
            }
        } finally {
            closeAfterServerTermination(heapVictim);
            closeAfterServerTermination(mvccVictim);
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
        assertEquals(1, executeUpdate(connection,
                "update " + table + " set marker = " + marker + " where id = 1"));
        assertEquals(1, executeUpdate(connection,
                "insert into " + table + " values (2, " + marker + ")"));
        assertRow(connection, table, 1, marker);
        assertRow(connection, table, 2, marker);
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
                    sqlState.startsWith("08") || "XJ012".equals(sqlState));
        }
    }

    private static void closeAfterServerTermination(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The server has already terminated this network connection.
        }
    }

    private static void createTable(Connection connection,
                                    String table,
                                    boolean mvcc) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + table
                    + " (id int primary key, marker int not null)"
                    + (mvcc ? " using delos_mvcc" : ""));
            statement.executeUpdate("insert into " + table + " values (1, 1)");
        }
    }

    private static int executeUpdate(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static void assertRow(Connection connection,
                                  String table,
                                  int id,
                                  int expectedMarker) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select marker from " + table + " where id = ?")) {
            query.setInt(1, id);
            try (ResultSet resultSet = query.executeQuery()) {
                assertTrue("row " + id + " missing from " + table,
                        resultSet.next());
                assertEquals("unexpected marker for row " + id + " in " + table,
                        expectedMarker, resultSet.getInt(1));
                assertFalse("duplicate row " + id + " in " + table,
                        resultSet.next());
            }
        }
    }

    private static void assertNoRow(Connection connection,
                                    String table,
                                    int id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select id from " + table + " where id = ?")) {
            query.setInt(1, id);
            try (ResultSet resultSet = query.executeQuery()) {
                assertFalse("unexpected row " + id + " in " + table,
                        resultSet.next());
            }
        }
    }
}
