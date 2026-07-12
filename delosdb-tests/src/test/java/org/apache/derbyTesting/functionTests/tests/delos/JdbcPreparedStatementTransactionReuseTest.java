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

/**
 * Regression proof for prepared DELETE reuse across rollback/commit boundaries.
 *
 * <p>The critical sequence is rollback, commit, rollback while the same JDBC
 * prepared statements remain open. The committed delete/reinsert changes the
 * heap row location. A subsequent execution must refresh the row-location
 * column copied from the index source before generating secondary-index undo
 * records.</p>
 */
public final class JdbcPreparedStatementTransactionReuseTest extends MvccSqlTestSupport {
    private static final int ROW_COUNT = 1_000;
    private static final int TARGET_ID = ROW_COUNT - 1;

    public void testHeapPreparedDeleteReinsertSurvivesCommitThenRollback() throws Exception {
        runPreparedDeleteReinsertProof(
                "jdbc-prepared-write-reuse-heap-db",
                "JDBC_PREPARED_WRITE_REUSE_HEAP_T",
                "");
    }

    public void testMvccPreparedDeleteReinsertSurvivesCommitThenRollback() throws Exception {
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
        int expectedQuantity = quantity(TARGET_ID);

        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table " + tableName
                    + " (id int not null primary key, category int not null, bucket int not null,"
                    + " quantity int not null, payload varchar(128) not null)"
                    + createTableSuffix);
            executeUpdate(connection, "create index " + tableName + "_CATEGORY_IDX on "
                    + tableName + " (category)");
            executeUpdate(connection, "create index " + tableName + "_RANGE_IDX on "
                    + tableName + " (bucket, quantity)");
            insertFixture(connection, tableName);
            connection.commit();

            try (PreparedStatement source = connection.prepareStatement(
                        "select category, bucket, quantity, payload from " + tableName + " where id = ?");
                 PreparedStatement delete = connection.prepareStatement(
                        "delete from " + tableName + " where id = ?");
                 PreparedStatement insert = connection.prepareStatement(
                        "insert into " + tableName
                                + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)");
                 PreparedStatement verify = connection.prepareStatement(
                        "select id, quantity from " + tableName + " where id = ?")) {
                executeDeleteReinsert(source, delete, insert, verify);
                connection.rollback();

                executeDeleteReinsert(source, delete, insert, verify);
                connection.commit();

                executeDeleteReinsert(source, delete, insert, verify);
                connection.rollback();
            }

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
            connection.rollback();
        }

        shutdownDatabase(database);

        try (Connection reopened = openDatabase(database, false)) {
            assertRows(reopened,
                    "select id, category, bucket, quantity from " + tableName + " where id = " + TARGET_ID,
                    TARGET_ID + "|" + expectedCategory + "|" + expectedBucket + "|" + expectedQuantity);
        }
    }

    private static void insertFixture(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + tableName
                        + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)")) {
            for (int id = 1; id <= ROW_COUNT; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % 17);
                insert.setInt(3, id % 11);
                insert.setInt(4, quantity(id));
                insert.setString(5, "payload-" + id);
                insert.addBatch();
                if (id % 100 == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
        }
    }

    private static void executeDeleteReinsert(
            PreparedStatement source,
            PreparedStatement delete,
            PreparedStatement insert,
            PreparedStatement verify) throws SQLException {
        source.setInt(1, TARGET_ID);
        int category;
        int bucket;
        int quantity;
        String payload;
        try (ResultSet rows = source.executeQuery()) {
            assertTrue("prepared source row should exist", rows.next());
            category = rows.getInt(1);
            bucket = rows.getInt(2);
            quantity = rows.getInt(3);
            payload = rows.getString(4);
            assertFalse("prepared source query should return one row", rows.next());
        }

        delete.setInt(1, TARGET_ID);
        assertEquals("prepared delete count", 1, delete.executeUpdate());

        insert.setInt(1, TARGET_ID);
        insert.setInt(2, category);
        insert.setInt(3, bucket);
        insert.setInt(4, quantity);
        insert.setString(5, payload);
        assertEquals("prepared reinsert count", 1, insert.executeUpdate());

        verify.setInt(1, TARGET_ID);
        try (ResultSet rows = verify.executeQuery()) {
            assertTrue("reinserted row should be visible", rows.next());
            assertEquals(TARGET_ID, rows.getInt(1));
            assertEquals(quantity, rows.getInt(2));
            assertFalse("prepared verification should return one row", rows.next());
        }
    }

    private static int quantity(int id) {
        return Math.floorMod(id * 37, 10_000);
    }
}
