/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccDrdaCancellationTest

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * DRDA cancellation-contract and post-timeout connection-health proof for
 * matched heap and delos_mvcc workloads. Derby network-client explicit
 * Statement.cancel() remains unsupported; server-side query timeout is the
 * supported statement-cancellation mechanism.
 */
public final class HeapMvccDrdaCancellationTest extends BaseJDBCTestCase {
    private static final String HEAP_TABLE = "drda_cancel_heap";
    private static final String MVCC_TABLE = "drda_cancel_mvcc";
    private static final int ROW_COUNT = 500;

    public HeapMvccDrdaCancellationTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(HeapMvccDrdaCancellationTest.class);
        return TestConfiguration.clientServerDecorator(new CleanDatabaseTestSetup(suite));
    }

    public void testDrdaTimeoutCancellationAndExplicitCancelContract() throws Exception {
        HeapMvccDrdaFailureTestSupport.assertNetworkClient(
                getTestConfiguration());

        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            HeapMvccDrdaFailureTestSupport.createMarkerTable(
                    connection, HEAP_TABLE, false, ROW_COUNT);
            HeapMvccDrdaFailureTestSupport.createMarkerTable(
                    connection, MVCC_TABLE, true, ROW_COUNT);
            connection.commit();
        }

        assertExplicitCancelUnsupported();
        assertTimeoutCancellationAndConnectionRecovery(HEAP_TABLE);
        assertTimeoutCancellationAndConnectionRecovery(MVCC_TABLE);
    }

    private void assertExplicitCancelUnsupported() throws Exception {
        try (Connection connection = openDefaultConnection();
             Statement statement = connection.createStatement()) {
            try {
                statement.cancel();
                fail("Derby network client must report explicit Statement.cancel() as unsupported");
            } catch (SQLException expected) {
                assertEquals("unexpected SQLState for unsupported network-client cancel",
                        "0A000", expected.getSQLState());
            }
        }
    }

    private void assertTimeoutCancellationAndConnectionRecovery(String table)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = openDefaultConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.setQueryTimeout(1);
            CountDownLatch executeStarted = new CountDownLatch(1);

            Future<String> execution = executor.submit(() -> {
                executeStarted.countDown();
                try (ResultSet resultSet = statement.executeQuery(
                        "select sum(a.id + b.id + c.id) from " + table
                                + " a, " + table + " b, " + table + " c "
                                + "where mod(a.id + b.id + c.id, 7) >= 0")) {
                    resultSet.next();
                    return "COMPLETED";
                } catch (SQLException failure) {
                    return failure.getSQLState();
                }
            });

            assertTrue("long-running DRDA statement did not start for " + table,
                    executeStarted.await(10, TimeUnit.SECONDS));

            String outcome = execution.get(20, TimeUnit.SECONDS);
            assertEquals("timed-out statement should report Derby cancellation SQLState for "
                    + table, "XCL52", outcome);

            connection.rollback();
            statement.setQueryTimeout(0);
            assertConnectionStillUsable(connection, table);
        } finally {
            executor.shutdownNow();
            assertTrue("timeout-cancellation executor did not stop",
                    executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void assertConnectionStillUsable(Connection connection,
                                                      String table)
            throws SQLException {
        assertEquals("post-cancellation update count for " + table, 1,
                HeapMvccDrdaFailureTestSupport.executeUpdate(connection,
                        "update " + table
                                + " set marker = marker + 1 where id = 1"));
        connection.commit();
        HeapMvccDrdaFailureTestSupport.assertMarkerRow(
                connection, table, 1, 2);
        connection.rollback();
    }

}
