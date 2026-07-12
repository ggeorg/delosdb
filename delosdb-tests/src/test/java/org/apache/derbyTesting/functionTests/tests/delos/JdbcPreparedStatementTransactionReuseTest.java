/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.JdbcPreparedStatementTransactionReuseTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

/**
 * Regression proof for prepared DELETE reuse across rollback/commit boundaries.
 *
 * <p>The proof mirrors the benchmark fixture and transaction ordering. Fresh
 * statements first move the target row through several committed physical row
 * locations. One prepared delete/reinsert operation is then retained across a
 * rollback, a commit, and another rollback. Every delete must snapshot the row
 * identity owned by the current cursor, not a projected wrapper retained from
 * an earlier execution.</p>
 */
public final class JdbcPreparedStatementTransactionReuseTest extends MvccSqlTestSupport {
    private static final int ROW_COUNT = 1_000;
    private static final int TARGET_ID = ROW_COUNT - 1;
    private static final int PAYLOAD_SIZE = 128;
    private static final long SEED = 0x5DE10DBL;

    public void testHeapPreparedDeleteReinsertSurvivesBenchmarkTransactionSequence() throws Exception {
        runPreparedDeleteReinsertProof(
                "jdbc-prepared-write-reuse-heap-db",
                "JDBC_PREPARED_WRITE_REUSE_HEAP_T",
                "");
    }

    public void testMvccPreparedDeleteReinsertSurvivesBenchmarkTransactionSequence() throws Exception {
        runPreparedDeleteReinsertProof(
                "jdbc-prepared-write-reuse-mvcc-db",
                "JDBC_PREPARED_WRITE_REUSE_MVCC_T",
                " using delos_mvcc");
    }

    private static void runPreparedDeleteReinsertProof(
            String databaseBaseName,
            String tableName,
            String createTableSuffix) throws Exception {
        String database = databaseName(databaseBaseName);
        int expectedCategory = TARGET_ID % 17;
        int expectedBucket = TARGET_ID % 11;
        int expectedQuantity = targetQuantity();

        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table " + tableName
                    + " (id int not null primary key, category int not null, bucket int not null,"
                    + " quantity int not null, payload varchar(4096) not null)"
                    + createTableSuffix);
            executeUpdate(connection, "create index " + tableName + "_CATEGORY_IDX on "
                    + tableName + " (category)");
            executeUpdate(connection, "create index " + tableName + "_RANGE_IDX on "
                    + tableName + " (bucket, quantity)");
            insertFixture(connection, tableName);

            runFreshCycle(connection, tableName, false);
            runFreshCycle(connection, tableName, true);
            runFreshCycle(connection, tableName, false);
            runFreshCycle(connection, tableName, false);
            runFreshCycle(connection, tableName, true);
            runFreshCycle(connection, tableName, true);

            try (DeleteReinsertOperation reusable = prepareDeleteReinsert(connection, tableName)) {
                reusable.execute();
                connection.rollback();

                reusable.execute();
                connection.commit();

                reusable.execute();
                connection.rollback();
            }

            assertTargetState(
                    connection,
                    tableName,
                    expectedCategory,
                    expectedBucket,
                    expectedQuantity);
            connection.rollback();
        }

        shutdownDatabase(database);

        try (Connection reopened = openDatabase(database, false)) {
            assertTargetState(
                    reopened,
                    tableName,
                    expectedCategory,
                    expectedBucket,
                    expectedQuantity);
        }
    }

    private static void runFreshCycle(
            Connection connection,
            String tableName,
            boolean commit) throws SQLException {
        try (DeleteReinsertOperation operation = prepareDeleteReinsert(connection, tableName)) {
            operation.execute();
        }
        if (commit) {
            connection.commit();
        } else {
            connection.rollback();
        }
    }

    private static DeleteReinsertOperation prepareDeleteReinsert(
            Connection connection,
            String tableName) throws SQLException {
        PreparedStatement source = null;
        PreparedStatement delete = null;
        PreparedStatement insert = null;
        PreparedStatement verify = null;
        try {
            source = connection.prepareStatement(
                    "select category, bucket, quantity, payload from " + tableName + " where id = ?");
            delete = connection.prepareStatement("delete from " + tableName + " where id = ?");
            insert = connection.prepareStatement(
                    "insert into " + tableName
                            + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)");
            verify = connection.prepareStatement(
                    "select id, quantity from " + tableName + " where id = ?");
            return new DeleteReinsertOperation(source, delete, insert, verify);
        } catch (SQLException failure) {
            closeAfterFailure(failure, verify, insert, delete, source);
            throw failure;
        }
    }

    private static void insertFixture(Connection connection, String tableName) throws SQLException {
        Random random = new Random(SEED);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + tableName
                        + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)")) {
            for (int id = 1; id <= ROW_COUNT; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % 17);
                insert.setInt(3, id % 11);
                insert.setInt(4, random.nextInt(10_000));
                insert.setString(5, payload(id));
                insert.addBatch();
                if (id % 100 == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
        }
    }

    private static int targetQuantity() {
        Random random = new Random(SEED);
        int quantity = 0;
        for (int id = 1; id <= TARGET_ID; id++) {
            quantity = random.nextInt(10_000);
        }
        return quantity;
    }

    private static String payload(int id) {
        String prefix = "row-" + id + '-';
        StringBuilder value = new StringBuilder(PAYLOAD_SIZE);
        while (value.length() < PAYLOAD_SIZE) {
            value.append(prefix);
        }
        return value.substring(0, PAYLOAD_SIZE);
    }

    private static void assertTargetState(
            Connection connection,
            String tableName,
            int expectedCategory,
            int expectedBucket,
            int expectedQuantity) throws SQLException {
        assertRows(connection,
                "select id, category, bucket, quantity from " + tableName + " where id = " + TARGET_ID,
                TARGET_ID + "|" + expectedCategory + "|" + expectedBucket + "|" + expectedQuantity);
        assertRows(connection,
                "select id from " + tableName + " where category = " + expectedCategory
                        + " and id = " + TARGET_ID,
                Integer.toString(TARGET_ID));
        assertRows(connection,
                "select id from " + tableName + " where bucket = " + expectedBucket
                        + " and quantity = " + expectedQuantity + " and id = " + TARGET_ID,
                Integer.toString(TARGET_ID));
    }

    private static void closeAfterFailure(
            Throwable failure,
            PreparedStatement... statements) {
        try {
            closeStatements(statements);
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeStatements(PreparedStatement... statements) throws SQLException {
        SQLException failure = null;
        for (PreparedStatement statement : statements) {
            if (statement == null) {
                continue;
            }
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static final class DeleteReinsertOperation implements AutoCloseable {
        private final PreparedStatement source;
        private final PreparedStatement delete;
        private final PreparedStatement insert;
        private final PreparedStatement verify;

        private DeleteReinsertOperation(
                PreparedStatement source,
                PreparedStatement delete,
                PreparedStatement insert,
                PreparedStatement verify) {
            this.source = source;
            this.delete = delete;
            this.insert = insert;
            this.verify = verify;
        }

        private void execute() throws SQLException {
            source.setInt(1, TARGET_ID);
            int category;
            int bucket;
            int quantity;
            String rowPayload;
            try (ResultSet rows = source.executeQuery()) {
                assertTrue("prepared source row should exist", rows.next());
                category = rows.getInt(1);
                bucket = rows.getInt(2);
                quantity = rows.getInt(3);
                rowPayload = rows.getString(4);
                assertFalse("prepared source query should return one row", rows.next());
            }

            delete.setInt(1, TARGET_ID);
            assertEquals("prepared delete count", 1, delete.executeUpdate());

            insert.setInt(1, TARGET_ID);
            insert.setInt(2, category);
            insert.setInt(3, bucket);
            insert.setInt(4, quantity);
            insert.setString(5, rowPayload);
            assertEquals("prepared reinsert count", 1, insert.executeUpdate());

            verify.setInt(1, TARGET_ID);
            try (ResultSet rows = verify.executeQuery()) {
                assertTrue("reinserted row should be visible", rows.next());
                assertEquals(TARGET_ID, rows.getInt(1));
                assertEquals(quantity, rows.getInt(2));
                assertFalse("prepared verification should return one row", rows.next());
            }
        }

        @Override
        public void close() throws SQLException {
            closeStatements(verify, insert, delete, source);
        }
    }
}
