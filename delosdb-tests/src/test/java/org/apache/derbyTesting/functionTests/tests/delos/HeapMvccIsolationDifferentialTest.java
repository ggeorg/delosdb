/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccIsolationDifferentialTest

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
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Compares the externally visible transaction-isolation guarantees of the
 * inherited heap provider and the delos_mvcc provider. The providers are not
 * required to use the same locking mechanism: heap may block a conflicting
 * writer while MVCC may let it commit behind an older snapshot. The required
 * SQL observations must nevertheless be equivalent.
 */
public final class HeapMvccIsolationDifferentialTest extends MvccSqlTestSupport {
    public void testReadCommittedRefreshesBetweenStatementsForBothProviders() throws Exception {
        assertReadCommittedRefresh("heap-isolation-rc-db", "heap_isolation_t", false);
        assertReadCommittedRefresh("mvcc-isolation-rc-db", "mvcc_isolation_t", true);
    }

    public void testRepeatableReadPreservesRowsAndOwnSavepointStateForBothProviders() throws Exception {
        assertRepeatableReadAndSavepoint("heap-isolation-rr-db", "heap_isolation_t", false);
        assertRepeatableReadAndSavepoint("mvcc-isolation-rr-db", "mvcc_isolation_t", true);
        assertRepeatableReadRejectsNonRepeatableObservation(
                "heap-isolation-rr-concurrent-db", "heap_isolation_t", false);
        assertRepeatableReadRejectsNonRepeatableObservation(
                "mvcc-isolation-rr-concurrent-db", "mvcc_isolation_t", true);
    }

    public void testSerializableSuppressesPhantomsForBothProviders() throws Exception {
        assertSerializablePhantomSuppression("heap-isolation-serializable-db", "heap_isolation_t", false);
        assertSerializablePhantomSuppression("mvcc-isolation-serializable-db", "mvcc_isolation_t", true);
    }

    private void assertReadCommittedRefresh(
            String databaseStem, String tableName, boolean mvcc) throws Exception {
        String databaseName = databaseName(databaseStem);
        createFixture(databaseName, tableName, mvcc);

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            writer.setAutoCommit(false);

            assertScalar(reader, "select payload from " + tableName + " where id = 1", "base");
            executeUpdate(writer, "update " + tableName + " set payload = 'committed' where id = 1");
            writer.commit();
            assertScalar(reader, "select payload from " + tableName + " where id = 1", "committed");
            reader.rollback();
        }

        shutdownDatabase(databaseName);
    }

    private void assertRepeatableReadAndSavepoint(
            String databaseStem, String tableName, boolean mvcc) throws Exception {
        String databaseName = databaseName(databaseStem);
        createFixture(databaseName, tableName, mvcc);

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertRows(connection,
                    "select id, payload from " + tableName + " order by id",
                    "1|base", "2|stable");

            executeUpdate(connection, "update " + tableName + " set payload = 'own-write' where id = 1");
            assertScalar(connection, "select payload from " + tableName + " where id = 1", "own-write");

            Savepoint savepoint = connection.setSavepoint("ISOLATION_DIFF_SAVEPOINT");
            executeUpdate(connection, "insert into " + tableName + " values (3, 'rolled-back', 30)");
            executeUpdate(connection, "update " + tableName + " set payload = 'rolled-back' where id = 2");
            assertRows(connection,
                    "select id, payload from " + tableName + " order by id",
                    "1|own-write", "2|rolled-back", "3|rolled-back");

            connection.rollback(savepoint);
            assertRows(connection,
                    "select id, payload from " + tableName + " order by id",
                    "1|own-write", "2|stable");
            connection.commit();
        }

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, payload from " + tableName + " order by id",
                    "1|own-write", "2|stable");
        }

        shutdownDatabase(databaseName);
    }


    private void assertRepeatableReadRejectsNonRepeatableObservation(
            String databaseStem, String tableName, boolean mvcc) throws Exception {
        String databaseName = databaseName(databaseStem);
        createFixture(databaseName, tableName, mvcc);

        try (Connection reader = openDatabase(databaseName, false)) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertScalar(reader, "select payload from " + tableName + " where id = 1", "base");

            MutationOutcome writerOutcome = runConcurrentMutation(databaseName,
                    "update " + tableName + " set payload = 'other-committed' where id = 1");

            assertScalar(reader, "select payload from " + tableName + " where id = 1", "base");
            reader.commit();

            if (!writerOutcome.committed) {
                try (Connection writer = openDatabase(databaseName, false)) {
                    writer.setAutoCommit(false);
                    executeUpdate(writer,
                            "update " + tableName + " set payload = 'other-committed' where id = 1");
                    writer.commit();
                }
            }
        }

        try (Connection verifier = openDatabase(databaseName, false)) {
            assertScalar(verifier,
                    "select payload from " + tableName + " where id = 1",
                    "other-committed");
        }
        shutdownDatabase(databaseName);
    }

    private void assertSerializablePhantomSuppression(
            String databaseStem, String tableName, boolean mvcc) throws Exception {
        String databaseName = databaseName(databaseStem);
        createFixture(databaseName, tableName, mvcc);

        MutationOutcome writerOutcome;
        try (Connection reader = openDatabase(databaseName, false)) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            assertScalar(reader,
                    "select count(*) from " + tableName + " where bucket between 10 and 40",
                    "2");

            writerOutcome = runConcurrentMutation(databaseName,
                    "insert into " + tableName + " values (3, 'other-committed', 30)");

            assertScalar(reader,
                    "select count(*) from " + tableName + " where bucket between 10 and 40",
                    "2");
            reader.commit();
        }

        if (!writerOutcome.committed) {
            try (Connection writer = openDatabase(databaseName, false)) {
                writer.setAutoCommit(false);
                executeUpdate(writer,
                        "insert into " + tableName + " values (3, 'other-committed', 30)");
                writer.commit();
            }
        }

        try (Connection verifier = openDatabase(databaseName, false)) {
            assertScalar(verifier,
                    "select count(*) from " + tableName + " where bucket between 10 and 40",
                    "3");
        }
        shutdownDatabase(databaseName);
    }


    private MutationOutcome runConcurrentMutation(String databaseName, String sql) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MutationOutcome> future = executor.submit(new Callable<MutationOutcome>() {
                public MutationOutcome call() throws Exception {
                    try (Connection writer = openDatabase(databaseName, false)) {
                        writer.setAutoCommit(false);
                        try (Statement statement = writer.createStatement()) {
                            statement.execute("call syscs_util.syscs_set_database_property("
                                    + "'derby.locks.waitTimeout', '1')");
                        }
                        try {
                            executeUpdate(writer, sql);
                            writer.commit();
                            return new MutationOutcome(true, null);
                        } catch (Exception failure) {
                            writer.rollback();
                            return new MutationOutcome(false, failure);
                        }
                    }
                }
            });
            MutationOutcome outcome = future.get(10, TimeUnit.SECONDS);
            if (!outcome.committed && !(outcome.failure instanceof java.sql.SQLException)) {
                throw new AssertionError("concurrent mutation failed outside SQL locking semantics",
                        outcome.failure);
            }
            return outcome;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static final class MutationOutcome {
        private final boolean committed;
        private final Exception failure;

        private MutationOutcome(boolean committed, Exception failure) {
            this.committed = committed;
            this.failure = failure;
        }
    }

    private void createFixture(String databaseName, String tableName, boolean mvcc) throws Exception {
        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table " + tableName
                    + " (id int primary key, payload varchar(32), bucket int)"
                    + (mvcc ? " using delos_mvcc" : ""));
            executeUpdate(connection, "create index " + tableName + "_bucket_idx on "
                    + tableName + " (bucket)");
            executeUpdate(connection, "insert into " + tableName + " values (1, 'base', 10)");
            executeUpdate(connection, "insert into " + tableName + " values (2, 'stable', 20)");
            connection.commit();
        }
    }

    private static void assertScalar(Connection connection, String sql, String expected) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue("expected one row for " + sql, resultSet.next());
            assertEquals("unexpected scalar value for " + sql, expected, resultSet.getString(1));
            assertFalse("expected a single row for " + sql, resultSet.next());
        }
    }
}
