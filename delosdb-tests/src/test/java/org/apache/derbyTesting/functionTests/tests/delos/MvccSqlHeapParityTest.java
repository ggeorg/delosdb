/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlHeapParityTest

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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/**
 * SQL parity gate between the inherited Derby heap path and the opt-in
 * delos_mvcc path.
 */
public final class MvccSqlHeapParityTest extends MvccSqlTestSupport {
    private static final String HEAP_TABLE = "heap_parity_t";
    private static final String MVCC_TABLE = "mvcc_parity_t";

    public void testHeapAndMvccRemainEquivalentAcrossCommonSqlWorkload() throws Exception {
        String databaseName = databaseName("mvcc-sql-heap-parity-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long mvccContainerId;

        String alphaInitial = "short-alpha";
        String betaLong = longPayload("beta", 6500);
        String rolledBack = "rolled-back";
        String alphaCommitted = longPayload("alpha-u", 7000);
        String betaCommitted = "short-again";
        String epsilonCommitted = longPayload("epsilon", 9000);

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            createHeapAndMvccTables(connection);

            insertRow(connection, HEAP_TABLE, 1, "alpha", 10, "1.50", alphaInitial, 1);
            insertRow(connection, MVCC_TABLE, 1, "alpha", 10, "1.50", alphaInitial, 1);
            insertRow(connection, HEAP_TABLE, 2, "beta", 20, "2.75", betaLong, 0);
            insertRow(connection, MVCC_TABLE, 2, "beta", 20, "2.75", betaLong, 0);
            insertRow(connection, HEAP_TABLE, 3, "gamma", 30, null, null, null);
            insertRow(connection, MVCC_TABLE, 3, "gamma", 30, null, null, null);
            connection.commit();

            mvccContainerId = mvccContainerId(connection, "MVCC_PARITY_T");
            assertMvccConsistent(diagnostics, mvccContainerId);
            assertHeapAndMvccSummaries(connection,
                    summary(1, "alpha", 10, "1.50", alphaInitial, 1),
                    summary(2, "beta", 20, "2.75", betaLong, 0),
                    summary(3, "gamma", 30, "NULL", null, null));

            Savepoint savepoint = connection.setSavepoint("HEAP_MVCC_PARITY_SP");
            updatePayloadAndQuantity(connection, HEAP_TABLE, 1, 999, rolledBack);
            updatePayloadAndQuantity(connection, MVCC_TABLE, 1, 999, rolledBack);
            assertEquals(1, executeUpdate(connection, "delete from " + HEAP_TABLE + " where id = 2"));
            assertEquals(1, executeUpdate(connection, "delete from " + MVCC_TABLE + " where id = 2"));
            insertRow(connection, HEAP_TABLE, 4, "delta", 40, "4.40", rolledBack, 4);
            insertRow(connection, MVCC_TABLE, 4, "delta", 40, "4.40", rolledBack, 4);
            connection.rollback(savepoint);

            assertHeapAndMvccSummaries(connection,
                    summary(1, "alpha", 10, "1.50", alphaInitial, 1),
                    summary(2, "beta", 20, "2.75", betaLong, 0),
                    summary(3, "gamma", 30, "NULL", null, null));
            assertMvccConsistent(diagnostics, mvccContainerId);

            updateCommittedAlpha(connection, HEAP_TABLE, alphaCommitted);
            updateCommittedAlpha(connection, MVCC_TABLE, alphaCommitted);
            updateCommittedBeta(connection, HEAP_TABLE, betaCommitted);
            updateCommittedBeta(connection, MVCC_TABLE, betaCommitted);
            assertEquals(1, executeUpdate(connection, "delete from " + HEAP_TABLE + " where id = 3"));
            assertEquals(1, executeUpdate(connection, "delete from " + MVCC_TABLE + " where id = 3"));
            insertRow(connection, HEAP_TABLE, 5, "epsilon", 50, "5.55", epsilonCommitted, 5);
            insertRow(connection, MVCC_TABLE, 5, "epsilon", 50, "5.55", epsilonCommitted, 5);
            connection.commit();

            assertHeapAndMvccSummaries(connection,
                    summary(1, "alpha", 15, "11.50", alphaCommitted, 2),
                    summary(2, "beta-u", 20, "2.75", betaCommitted, 0),
                    summary(5, "epsilon", 50, "5.55", epsilonCommitted, 5));
            assertIndexedLookupMatches(connection, HEAP_TABLE, "beta-u", 2);
            assertIndexedLookupMatches(connection, MVCC_TABLE, "beta-u", 2);
            assertMvccConsistent(diagnostics, mvccContainerId);

            inPlaceCompressTable(connection, "HEAP_PARITY_T");
            inPlaceCompressTable(connection, "MVCC_PARITY_T");
            connection.commit();
            assertFalse("MVCC heap-parity vacuum should not be skipped",
                    diagnostics.lastVacuumSkippedForTesting(0, mvccContainerId));
            assertHeapAndMvccSummaries(connection,
                    summary(1, "alpha", 15, "11.50", alphaCommitted, 2),
                    summary(2, "beta-u", 20, "2.75", betaCommitted, 0),
                    summary(5, "epsilon", 50, "5.55", epsilonCommitted, 5));
            assertMvccConsistent(diagnostics, mvccContainerId);
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_PARITY_T");
            assertHeapAndMvccSummaries(reopened,
                    summary(1, "alpha", 15, "11.50", alphaCommitted, 2),
                    summary(2, "beta-u", 20, "2.75", betaCommitted, 0),
                    summary(5, "epsilon", 50, "5.55", epsilonCommitted, 5));
            assertIndexedLookupMatches(reopened, HEAP_TABLE, "beta-u", 2);
            assertIndexedLookupMatches(reopened, MVCC_TABLE, "beta-u", 2);
            assertMvccConsistent(diagnostics, reopenedContainerId);
        }
    }

    private static void createHeapAndMvccTables(Connection connection) throws SQLException {
        executeUpdate(connection, "create table " + HEAP_TABLE + " ("
                + "id int not null primary key, "
                + "code varchar(24) not null unique, "
                + "quantity int not null, "
                + "amount decimal(10,2), "
                + "payload varchar(12000), "
                + "flag smallint)");
        executeUpdate(connection, "create index heap_parity_quantity_idx on "
                + HEAP_TABLE + "(quantity)");

        executeUpdate(connection, "create table " + MVCC_TABLE + " ("
                + "id int not null primary key, "
                + "code varchar(24) not null unique, "
                + "quantity int not null, "
                + "amount decimal(10,2), "
                + "payload varchar(12000), "
                + "flag smallint) using delos_mvcc");
        executeUpdate(connection, "create index mvcc_parity_quantity_idx on "
                + MVCC_TABLE + "(quantity)");
    }

    private static void insertRow(
            Connection connection,
            String tableName,
            int id,
            String code,
            int quantity,
            String amount,
            String payload,
            Integer flag) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + tableName + " values (?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, code);
            statement.setInt(3, quantity);
            if (amount == null) {
                statement.setNull(4, Types.DECIMAL);
            } else {
                statement.setBigDecimal(4, new BigDecimal(amount));
            }
            if (payload == null) {
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setString(5, payload);
            }
            if (flag == null) {
                statement.setNull(6, Types.SMALLINT);
            } else {
                statement.setInt(6, flag);
            }
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updatePayloadAndQuantity(
            Connection connection,
            String tableName,
            int id,
            int quantity,
            String payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update " + tableName + " set quantity = ?, payload = ? where id = ?")) {
            statement.setInt(1, quantity);
            statement.setString(2, payload);
            statement.setInt(3, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateCommittedAlpha(Connection connection, String tableName, String payload)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update " + tableName + " set quantity = quantity + 5, "
                        + "amount = amount + 10, payload = ?, flag = 2 where id = 1")) {
            statement.setString(1, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateCommittedBeta(Connection connection, String tableName, String payload)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update " + tableName + " set code = 'beta-u', payload = ? where id = 2")) {
            statement.setString(1, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertIndexedLookupMatches(Connection connection, String tableName, String code, int expectedId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + tableName + " where code = ?")) {
            statement.setString(1, code);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected indexed lookup row for " + tableName, rs.next());
                assertEquals(expectedId, rs.getInt(1));
                assertFalse("expected one indexed lookup row for " + tableName, rs.next());
            }
        }
    }

    private static void assertHeapAndMvccSummaries(Connection connection, String... expected)
            throws SQLException {
        List<String> expectedRows = List.of(expected);
        List<String> heapRows = summaries(connection, HEAP_TABLE);
        List<String> mvccRows = summaries(connection, MVCC_TABLE);
        assertEquals("heap summary mismatch", expectedRows, heapRows);
        assertEquals("MVCC summary mismatch", expectedRows, mvccRows);
        assertEquals("heap and MVCC summaries must match", heapRows, mvccRows);
    }

    private static List<String> summaries(Connection connection, String tableName) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, code, quantity, amount, payload, flag from " + tableName + " order by id");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                BigDecimal amount = rs.getBigDecimal(4);
                String payload = rs.getString(5);
                int flag = rs.getInt(6);
                rows.add(summary(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getInt(3),
                        amount == null ? "NULL" : amount.toPlainString(),
                        payload,
                        rs.wasNull() ? null : flag));
            }
        }
        return rows;
    }

    private static String summary(
            int id,
            String code,
            int quantity,
            String amount,
            String payload,
            Integer flag) {
        return id + "|" + code + "|" + quantity + "|" + amount + "|"
                + payloadSummary(payload) + "|" + (flag == null ? "NULL" : flag);
    }

    private static String payloadSummary(String payload) {
        if (payload == null) {
            return "NULL|-|-";
        }
        return payload.length() + "|" + edge(payload, true) + "|" + edge(payload, false);
    }

    private static String edge(String payload, boolean first) {
        int edgeLength = Math.min(8, payload.length());
        if (first) {
            return payload.substring(0, edgeLength);
        }
        return payload.substring(payload.length() - edgeLength);
    }

    private static String longPayload(String seed, int length) {
        StringBuilder payload = new StringBuilder(length);
        while (payload.length() < length) {
            payload.append(seed).append('-').append(payload.length()).append(';');
        }
        return payload.substring(0, length);
    }

    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        diagnostics.assertConsistentForTesting(0, containerId);
        assertEquals("expected no MVCC consistency errors", 0,
                diagnostics.consistencyDiagnosticsForTesting(0, containerId).errorCount());
    }
}
