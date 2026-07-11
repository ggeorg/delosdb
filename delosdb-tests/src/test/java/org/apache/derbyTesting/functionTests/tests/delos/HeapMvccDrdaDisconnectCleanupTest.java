/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccDrdaDisconnectCleanupTest

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * DRDA connection-abort cleanup proof for matched heap and delos_mvcc
 * transactions. An aborted network connection must roll back uncommitted
 * changes and release all server-side transaction state before another client
 * continues work.
 */
public final class HeapMvccDrdaDisconnectCleanupTest extends BaseJDBCTestCase {
    private static final String HEAP_TABLE = "drda_disconnect_heap";
    private static final String MVCC_TABLE = "drda_disconnect_mvcc";

    public HeapMvccDrdaDisconnectCleanupTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(HeapMvccDrdaDisconnectCleanupTest.class);
        return TestConfiguration.clientServerDecorator(new CleanDatabaseTestSetup(suite));
    }

    public void testAbortRollsBackAndReleasesHeapAndMvccTransactions()
            throws Exception {
        assertTrue("test must run through Derby network client",
                getTestConfiguration().getJDBCClient().isDerbyNetClient());
        assertTrue("configured JDBC URL should be network-client URL: "
                        + getTestConfiguration().getJDBCUrl(),
                getTestConfiguration().getJDBCUrl().startsWith("jdbc:derby://"));

        try (Connection setup = openDefaultConnection()) {
            setup.setAutoCommit(false);
            createTable(setup, HEAP_TABLE, false);
            createTable(setup, MVCC_TABLE, true);
            setup.commit();
        }

        assertAbortRollsBackAndReleases(HEAP_TABLE);
        assertAbortRollsBackAndReleases(MVCC_TABLE);

        DelosStorageDiagnostics diagnostics = MvccSqlTestSupport.mvccDiagnostics();
        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            long containerId = MvccSqlTestSupport.mvccContainerId(
                    connection, MVCC_TABLE.toUpperCase(java.util.Locale.ROOT));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }
    }

    private void assertAbortRollsBackAndReleases(String table) throws Exception {
        Connection victim = openDefaultConnection();
        victim.setAutoCommit(false);
        try {
            executeUpdate(victim,
                    "update " + table + " set marker = 99 where id = 1");
            executeUpdate(victim,
                    "insert into " + table + " values (2, 99)");
            assertRow(victim, table, 1, 99);
            assertRow(victim, table, 2, 99);

            ExecutorService abortExecutor = Executors.newSingleThreadExecutor();
            try {
                victim.abort(abortExecutor);
            } finally {
                abortExecutor.shutdown();
                assertTrue("connection abort did not complete for " + table,
                        abortExecutor.awaitTermination(10, TimeUnit.SECONDS));
            }
            assertTrue("aborted client connection should be closed for " + table,
                    victim.isClosed());

            try (Connection survivor = openDefaultConnection()) {
                survivor.setAutoCommit(false);
                assertRow(survivor, table, 1, 1);
                assertNoRow(survivor, table, 2);

                assertEquals("post-disconnect update count for " + table, 1,
                        executeUpdate(survivor,
                                "update " + table
                                        + " set marker = marker + 1 where id = 1"));
                survivor.commit();
                assertRow(survivor, table, 1, 2);
                survivor.rollback();
            }
        } finally {
            if (!victim.isClosed()) {
                victim.close();
            }
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
