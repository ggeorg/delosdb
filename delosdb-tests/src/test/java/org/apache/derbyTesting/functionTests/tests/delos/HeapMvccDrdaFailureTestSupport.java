/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccDrdaFailureTestSupport

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

import java.lang.reflect.Field;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.Assert;

import org.apache.derbyTesting.junit.TestConfiguration;

/** Shared mechanics for heap/MVCC DRDA failure-path tests. */
final class HeapMvccDrdaFailureTestSupport {
    private HeapMvccDrdaFailureTestSupport() {
    }

    static void assertNetworkClient(TestConfiguration configuration) {
        Assert.assertTrue("test must run through Derby network client",
                configuration.getJDBCClient().isDerbyNetClient());
        Assert.assertTrue("configured JDBC URL should be network-client URL: "
                        + configuration.getJDBCUrl(),
                configuration.getJDBCUrl().startsWith("jdbc:derby://"));
    }

    static void createMarkerTable(Connection connection,
                                  String table,
                                  boolean mvcc,
                                  int rowCount) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + table
                    + " (id int primary key, marker int not null)"
                    + (mvcc ? " using delos_mvcc" : ""));
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " values (?, ?)")) {
            for (int id = 1; id <= rowCount; id++) {
                insert.setInt(1, id);
                insert.setInt(2, 1);
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            Assert.assertEquals("unexpected inserted row count for " + table,
                    rowCount, counts.length);
        }
    }

    static int executeUpdate(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    static void assertMarkerRow(Connection connection,
                                String table,
                                int id,
                                int expectedMarker) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select marker from " + table + " where id = ?")) {
            query.setInt(1, id);
            try (ResultSet resultSet = query.executeQuery()) {
                Assert.assertTrue("row " + id + " missing from " + table,
                        resultSet.next());
                Assert.assertEquals("unexpected marker for row " + id
                                + " in " + table,
                        expectedMarker, resultSet.getInt(1));
                Assert.assertFalse("duplicate row " + id + " in " + table,
                        resultSet.next());
            }
        }
    }

    static void assertNoRow(Connection connection, String table, int id)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select id from " + table + " where id = ?")) {
            query.setInt(1, id);
            try (ResultSet resultSet = query.executeQuery()) {
                Assert.assertFalse("unexpected row " + id + " in " + table,
                        resultSet.next());
            }
        }
    }


    interface ConnectionSupplier {
        Connection open() throws SQLException;
    }

    static void awaitRollbackAndCommit(ConnectionSupplier connections,
                                       String table,
                                       int uncommittedRowId) throws Exception {
        SQLException lastLockFailure = null;
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try (Connection survivor = connections.open()) {
                survivor.setAutoCommit(false);
                try {
                    assertMarkerRow(survivor, table, 1, 1);
                    assertNoRow(survivor, table, uncommittedRowId);
                    Assert.assertEquals("post-disconnect update count for " + table,
                            1, executeUpdate(survivor,
                                    "update " + table
                                            + " set marker = marker + 1 where id = 1"));
                    survivor.commit();
                    assertMarkerRow(survivor, table, 1, 2);
                    survivor.rollback();
                    return;
                } catch (SQLException failure) {
                    survivor.rollback();
                    if (!isConcurrencyFailure(failure)) {
                        throw failure;
                    }
                    lastLockFailure = failure;
                }
            }
            Thread.sleep(100L);
        }
        AssertionError timeout = new AssertionError(
                "server did not release disconnected transaction state for " + table);
        if (lastLockFailure != null) {
            timeout.initCause(lastLockFailure);
        }
        throw timeout;
    }

    static boolean isConcurrencyFailure(SQLException failure) {
        String sqlState = failure.getSQLState();
        return "40XL1".equals(sqlState)
                || "40XL2".equals(sqlState)
                || "40001".equals(sqlState);
    }

    static boolean isTransportFailure(SQLException failure) {
        String sqlState = failure.getSQLState();
        return sqlState != null && (sqlState.startsWith("08")
                || "58009".equals(sqlState)
                || "XJ012".equals(sqlState));
    }

    static void closeBrokenConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Expected after server termination or deliberate socket reset.
        }
    }

    static void resetPhysicalClientSocket(Connection connection)
            throws Exception {
        Object netAgent = readRequiredField(connection, "netAgent_");
        Socket socket = (Socket) readRequiredField(netAgent, "socket_");
        socket.setSoLinger(true, 0);
        socket.close();
    }

    private static Object readRequiredField(Object target, String name)
            throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        Object value = field.get(target);
        Assert.assertNotNull(target.getClass().getName() + " has no " + name,
                value);
        return value;
    }

    private static Field findField(Class<?> type, String name)
            throws NoSuchFieldException {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException notHere) {
                // Continue through the client implementation hierarchy.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
