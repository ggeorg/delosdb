/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccMutationWriteContainerReuseTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;

/** Correctness coverage for transaction-scoped MVCC INSERT write-container reuse. */
public final class MvccMutationWriteContainerReuseTest extends MvccSqlTestSupport {
    private static final String REUSE_PROPERTY =
            "delosdb.experimental.mvccMutationWriteContainerReuse";
    private static final String TABLE = "B1_MUTATION_REUSE_T";

    public void testBatchCommitRollbackSavepointAndReopen() throws Exception {
        String database = databaseName("mvcc-mutation-write-container-reuse");
        try (SystemPropertyScope ignored = setSystemProperty(REUSE_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table " + TABLE + " ("
                            + "id int primary key, "
                            + "category int not null, "
                            + "bucket int not null, "
                            + "payload varchar(128) not null) using delos_mvcc");
            executeUpdate(connection,
                    "create index B1_MUTATION_REUSE_CATEGORY_IDX on " + TABLE + " (category)");
            executeUpdate(connection,
                    "create index B1_MUTATION_REUSE_BUCKET_IDX on " + TABLE + " (bucket)");
            connection.commit();

            assertEquals(100, batchInsert(connection, 1, 100));
            connection.commit();
            assertAggregate(connection, 100, 1, 100);
            connection.commit();

            assertEquals(20, batchInsert(connection, 101, 20));
            connection.rollback();
            assertAggregate(connection, 100, 1, 100);
            connection.commit();

            Savepoint savepoint = connection.setSavepoint("before_b1_reuse_batch");
            assertEquals(10, batchInsert(connection, 101, 10));
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            assertEquals(1, batchInsert(connection, 101, 1));
            connection.commit();
            assertAggregate(connection, 101, 1, 101);
            connection.commit();
        }
        shutdownDatabase(database);

        try (SystemPropertyScope ignored = setSystemProperty(REUSE_PROPERTY, "true");
             Connection reopened = openDatabase(database, false)) {
            reopened.setAutoCommit(false);
            assertAggregate(reopened, 101, 1, 101);
            assertEquals(1, batchInsert(reopened, 102, 1));
            reopened.commit();
            assertAggregate(reopened, 102, 1, 102);
            reopened.commit();
        }
        shutdownDatabase(database);
    }

    private static int batchInsert(Connection connection, int firstId, int count) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + TABLE
                        + " (id, category, bucket, payload) values (?, ?, ?, ?)")) {
            for (int offset = 0; offset < count; offset++) {
                int id = firstId + offset;
                insert.setInt(1, id);
                insert.setInt(2, id % 16);
                insert.setInt(3, id % 64);
                insert.setString(4, "payload-" + id);
                insert.addBatch();
            }
            int successful = 0;
            for (int result : insert.executeBatch()) {
                if (result == 1 || result == Statement.SUCCESS_NO_INFO) {
                    successful++;
                }
            }
            return successful;
        }
    }

    private static void assertAggregate(
            Connection connection,
            int expectedCount,
            int expectedMin,
            int expectedMax) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select count(*), min(id), max(id) from " + TABLE)) {
            assertTrue(resultSet.next());
            assertEquals(expectedCount, resultSet.getInt(1));
            assertEquals(expectedMin, resultSet.getInt(2));
            assertEquals(expectedMax, resultSet.getInt(3));
            assertFalse(resultSet.next());
        }
    }
}
