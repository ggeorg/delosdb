/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccDrdaStreamingDisconnectTest

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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * DRDA transport-loss proof while a large result is being streamed. The test
 * closes the network client's physical socket rather than issuing JDBC
 * rollback, close, or abort, and then verifies server-side transaction and
 * cursor cleanup for matched heap and delos_mvcc tables.
 */
public final class HeapMvccDrdaStreamingDisconnectTest extends BaseJDBCTestCase {
    private static final String HEAP_TABLE = "drda_stream_heap";
    private static final String MVCC_TABLE = "drda_stream_mvcc";
    private static final int FIXTURE_ROWS = 96;
    private static final String PAYLOAD = repeat('x', 2048);

    public HeapMvccDrdaStreamingDisconnectTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(
                HeapMvccDrdaStreamingDisconnectTest.class);
        Test test = new CleanDatabaseTestSetup(suite);
        test = HeapMvccDrdaFailureTestSupport.withBoundedCleanupLockWaits(test);
        return TestConfiguration.clientServerDecorator(test);
    }

    public void testRawSocketLossDuringResultStreamingCleansHeapAndMvccSessions()
            throws Exception {
        HeapMvccDrdaFailureTestSupport.assertNetworkClient(
                getTestConfiguration());

        try (Connection setup = openDefaultConnection()) {
            setup.setAutoCommit(false);
            createFixture(setup, HEAP_TABLE, false);
            createFixture(setup, MVCC_TABLE, true);
            setup.commit();
        }

        assertStreamingDisconnectCleanup(HEAP_TABLE);
        assertStreamingDisconnectCleanup(MVCC_TABLE);

        DelosStorageDiagnostics diagnostics = MvccSqlTestSupport.mvccDiagnostics(
                getTestConfiguration().getDatabasePath(
                        getTestConfiguration().getDefaultDatabaseName()));
        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            long containerId = MvccSqlTestSupport.mvccContainerId(
                    connection, MVCC_TABLE.toUpperCase(Locale.ROOT));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }
    }

    private void assertStreamingDisconnectCleanup(String table) throws Exception {
        Connection victim = openDefaultConnection();
        victim.setAutoCommit(false);
        ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch firstRowFetched = new CountDownLatch(1);
        CountDownLatch continueFetching = new CountDownLatch(1);
        try {
            assertEquals(1, HeapMvccDrdaFailureTestSupport.executeUpdate(victim,
                    "update " + table + " set marker = 99 where id = 1"));
            assertEquals(1, HeapMvccDrdaFailureTestSupport.executeUpdate(victim,
                    "insert into " + table
                            + " values (1000, 99, 'uncommitted')"));

            Future<Integer> fetch = fetchExecutor.submit(() -> {
                int rows = 0;
                try (Statement statement = victim.createStatement()) {
                    statement.setFetchSize(1);
                    try (ResultSet resultSet = statement.executeQuery(
                            "select a.id, b.id, a.payload, b.payload"
                                    + " from " + table + " a, " + table + " b"
                                    + " order by a.id, b.id")) {
                        while (resultSet.next()) {
                            rows++;
                            if (rows == 1) {
                                firstRowFetched.countDown();
                                if (!continueFetching.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "streaming test was not released");
                                }
                            }
                            resultSet.getString(3);
                            resultSet.getString(4);
                        }
                    }
                }
                return rows;
            });

            assertTrue("streaming query did not produce its first row for " + table,
                    firstRowFetched.await(20, TimeUnit.SECONDS));
            HeapMvccDrdaFailureTestSupport.resetPhysicalClientSocket(victim);
            continueFetching.countDown();
            assertTransportFailure(fetch, table);
        } finally {
            continueFetching.countDown();
            fetchExecutor.shutdownNow();
            assertTrue("streaming fetch executor did not terminate for " + table,
                    fetchExecutor.awaitTermination(10, TimeUnit.SECONDS));
            HeapMvccDrdaFailureTestSupport.closeBrokenConnection(victim);
        }

        HeapMvccDrdaFailureTestSupport.awaitRollbackAndCommit(
                this::openDefaultConnection, table, 1000);
    }

    private static void assertTransportFailure(Future<Integer> fetch, String table)
            throws Exception {
        try {
            int rows = fetch.get(20, TimeUnit.SECONDS);
            fail("raw socket loss should interrupt streaming for " + table
                    + ", but query returned " + rows + " rows");
        } catch (ExecutionException expected) {
            Throwable cause = expected.getCause();
            if (!(cause instanceof SQLException)) {
                throw expected;
            }
            SQLException failure = (SQLException) cause;
            String sqlState = failure.getSQLState();
            assertNotNull("transport failure should report SQLState for " + table,
                    sqlState);
            assertTrue("unexpected streaming transport SQLState for " + table
                            + ": " + sqlState,
                    HeapMvccDrdaFailureTestSupport.isTransportFailure(failure));
        }
    }

    private static void createFixture(Connection connection,
                                      String table,
                                      boolean mvcc) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + table
                    + " (id int primary key, marker int not null,"
                    + " payload varchar(4096) not null)"
                    + (mvcc ? " using delos_mvcc" : ""));
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " values (?, ?, ?)")) {
            for (int id = 1; id <= FIXTURE_ROWS; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id);
                insert.setString(3, PAYLOAD + id);
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            assertEquals("fixture batch count for " + table,
                    FIXTURE_ROWS, counts.length);
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(value);
        }
        return text.toString();
    }
}
