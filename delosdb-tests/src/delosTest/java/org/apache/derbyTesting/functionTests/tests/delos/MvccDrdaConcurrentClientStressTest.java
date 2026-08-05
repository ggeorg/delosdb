/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccDrdaConcurrentClientStressTest

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
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.Test;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * End-to-end DRDA/JDBC concurrent-client stress gate over mixed heap and
 * delos_mvcc tables.
 */
public final class MvccDrdaConcurrentClientStressTest extends BaseJDBCTestCase {
    private static final String HEAP_TABLE = "drda_client_stress_heap";
    private static final String MVCC_TABLE = "drda_client_stress_mvcc";
    private static final String MVCC_TABLE_UPPER = "DRDA_CLIENT_STRESS_MVCC";

    public MvccDrdaConcurrentClientStressTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(MvccDrdaConcurrentClientStressTest.class);
        return TestConfiguration.clientServerDecorator(new CleanDatabaseTestSetup(suite));
    }

    public void testConcurrentNetworkClientsAcrossHeapAndMvcc()
            throws Exception {
        assertTrue("test must run through Derby network client",
                getTestConfiguration().getJDBCClient().isDerbyNetClient());
        assertTrue("configured JDBC URL should be network-client URL: "
                        + getTestConfiguration().getJDBCUrl(),
                getTestConfiguration().getJDBCUrl().startsWith("jdbc:derby://"));

        TestConfiguration configuration = getTestConfiguration();
        DelosStorageDiagnostics diagnostics = MvccSqlTestSupport.mvccDiagnostics(
                getTestConfiguration().getDatabasePath(
                        getTestConfiguration().getDefaultDatabaseName()));
        long containerId;

        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            createSchema(connection);
            populateBaseRows(connection);
            connection.commit();
            containerId = MvccSqlTestSupport.mvccContainerId(
                    connection, MVCC_TABLE_UPPER);
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch mutatorsDone = new CountDownLatch(4);
        CountDownLatch maintenanceDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> runConcurrentTask(
                start, failure, mutatorsDone,
                () -> updateHeapRows(configuration))));
        futures.add(executor.submit(() -> runConcurrentTask(
                start, failure, mutatorsDone,
                () -> updateMvccLowRows(configuration))));
        futures.add(executor.submit(() -> runConcurrentTask(
                start, failure, mutatorsDone,
                () -> updateMvccHighRowsAndInsert(configuration))));
        futures.add(executor.submit(() -> runConcurrentTask(
                start, failure, mutatorsDone,
                () -> rollbackOnlyClient(configuration))));
        futures.add(executor.submit(() -> runConcurrentReader(
                start, maintenanceDone, failure, configuration)));
        futures.add(executor.submit(() -> runMaintenanceClient(
                start, mutatorsDone, maintenanceDone, failure, configuration)));

        start.countDown();
        executor.shutdown();
        assertTrue("DRDA concurrent-client stress tasks did not finish",
                executor.awaitTermination(120, TimeUnit.SECONDS));
        for (Future<?> future : futures) {
            future.get();
        }
        if (failure.get() != null) {
            throw new AssertionError(
                    "DRDA concurrent-client stress task failed", failure.get());
        }

        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            assertRows(connection,
                    "select count(*), min(id), max(id), sum(amount) from "
                            + HEAP_TABLE,
                    "16|1|16|9036");
            assertRows(connection,
                    "select count(*), min(id), max(id), sum(amount) from "
                            + MVCC_TABLE,
                    "20|1|103|56542");
            assertRows(connection,
                    "select id, amount, note from " + HEAP_TABLE
                            + " where id in (1, 8, 16, 900) order by id",
                    "1|1001|heap-a-1",
                    "8|1008|heap-a-8",
                    "16|160|heap-base-16");
            assertRows(connection,
                    "select id, amount, note from " + MVCC_TABLE
                            + " where id in (1, 9, 16, 100, 103, 900) order by id",
                    "1|2001|mvcc-a-1",
                    "9|3009|mvcc-b-9",
                    "16|3016|mvcc-b-16",
                    "100|4100|mvcc-inserted-100",
                    "103|4103|mvcc-inserted-103");
            assertBucketCounts(connection, HEAP_TABLE, "heap");
            assertBucketCounts(connection, MVCC_TABLE, "mvcc");
            assertNoRow(connection, HEAP_TABLE, 900);
            assertNoRow(connection, MVCC_TABLE, 900);
            diagnostics.assertConsistentForTesting(0, containerId);
            assertEquals("expected no MVCC consistency errors", 0,
                    diagnostics.consistencyDiagnosticsForTesting(0, containerId)
                            .errorCount());
            connection.rollback();
        }

        TestConfiguration.getCurrent().shutdownDatabase();
        diagnostics.clearRuntimeStateForTesting();

        try (Connection reopened = openDefaultConnection()) {
            reopened.setAutoCommit(false);
            try (Statement statement = reopened.createStatement()) {
                statement.executeUpdate(
                        "lock table " + MVCC_TABLE + " in share mode");
            }
            long reopenedContainerId = MvccSqlTestSupport.mvccContainerId(
                    reopened, MVCC_TABLE_UPPER);
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertRows(reopened,
                    "select count(*), min(id), max(id), sum(amount) from "
                            + HEAP_TABLE,
                    "16|1|16|9036");
            assertRows(reopened,
                    "select count(*), min(id), max(id), sum(amount) from "
                            + MVCC_TABLE,
                    "20|1|103|56542");
            assertRows(reopened,
                    "select id, amount, note from " + MVCC_TABLE
                            + " where id in (1, 16, 103) order by id",
                    "1|2001|mvcc-a-1",
                    "16|3016|mvcc-b-16",
                    "103|4103|mvcc-inserted-103");
            assertNoRow(reopened, HEAP_TABLE, 900);
            assertNoRow(reopened, MVCC_TABLE, 900);
            reopened.rollback();
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + HEAP_TABLE + " ("
                    + "id int primary key, "
                    + "bucket int not null, "
                    + "amount int not null, "
                    + "note varchar(64))");
            statement.executeUpdate("create index drda_client_stress_heap_bucket_idx on "
                    + HEAP_TABLE + "(bucket)");
            statement.executeUpdate("create table " + MVCC_TABLE + " ("
                    + "id int primary key, "
                    + "bucket int not null, "
                    + "amount int not null, "
                    + "note varchar(64)) using delos_mvcc");
            statement.executeUpdate("create index drda_client_stress_mvcc_bucket_idx on "
                    + MVCC_TABLE + "(bucket)");
        }
    }

    private static void populateBaseRows(Connection connection) throws SQLException {
        for (int id = 1; id <= 16; id++) {
            insertRow(connection, HEAP_TABLE, id, id % 4, id * 10,
                    "heap-base-" + id);
            insertRow(connection, MVCC_TABLE, id, id % 4, id * 10,
                    "mvcc-base-" + id);
        }
    }

    private static void updateHeapRows(TestConfiguration configuration)
            throws Exception {
        awaitOpenAndRun(configuration, connection -> {
            for (int id = 1; id <= 8; id++) {
                updateRow(connection, HEAP_TABLE, id, id % 4,
                        1000 + id, "heap-a-" + id);
                if (id % 2 == 0) {
                    connection.commit();
                }
            }
            connection.commit();
        });
    }

    private static void updateMvccLowRows(TestConfiguration configuration)
            throws Exception {
        awaitOpenAndRun(configuration, connection -> {
            for (int id = 1; id <= 8; id++) {
                Savepoint savepoint = connection.setSavepoint("mvcc_low_" + id);
                updateRow(connection, MVCC_TABLE, id, id % 4,
                        9000 + id, "mvcc-rolled-back-" + id);
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                updateRow(connection, MVCC_TABLE, id, id % 4,
                        2000 + id, "mvcc-a-" + id);
                if (id % 2 == 0) {
                    connection.commit();
                }
            }
            connection.commit();
        });
    }

    private static void updateMvccHighRowsAndInsert(
            TestConfiguration configuration) throws Exception {
        awaitOpenAndRun(configuration, connection -> {
            for (int id = 9; id <= 16; id++) {
                updateRow(connection, MVCC_TABLE, id, id % 4,
                        3000 + id, "mvcc-b-" + id);
                if (id % 2 == 0) {
                    connection.commit();
                }
            }
            for (int id = 100; id <= 103; id++) {
                insertRow(connection, MVCC_TABLE, id, id % 4,
                        4000 + id, "mvcc-inserted-" + id);
            }
            connection.commit();
        });
    }

    private static void rollbackOnlyClient(TestConfiguration configuration)
            throws Exception {
        awaitOpenAndRun(configuration, connection -> {
            insertRow(connection, HEAP_TABLE, 900, 0, 9000,
                    "heap-rolled-back-900");
            insertRow(connection, MVCC_TABLE, 900, 0, 9000,
                    "mvcc-rolled-back-900");
            insertRow(connection, MVCC_TABLE, 901, 1, 9001,
                    "mvcc-rolled-back-901");
            connection.rollback();
        });
    }

    private static void runMaintenanceClient(
            CountDownLatch start,
            CountDownLatch mutatorsDone,
            CountDownLatch maintenanceDone,
            AtomicReference<Throwable> failure,
            TestConfiguration configuration) {
        try {
            await(start);
            await(mutatorsDone);
            if (failure.get() == null) {
                try (Connection connection = configuration.openDefaultConnection()) {
                    connection.setAutoCommit(false);
                    MvccSqlTestSupport.inPlaceCompressTable(
                            connection, MVCC_TABLE_UPPER);
                    connection.commit();
                }
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        } finally {
            maintenanceDone.countDown();
        }
    }

    private static void runConcurrentReader(
            CountDownLatch start,
            CountDownLatch maintenanceDone,
            AtomicReference<Throwable> failure,
            TestConfiguration configuration) {
        try {
            await(start);
            try (Connection connection = configuration.openDefaultConnection()) {
                connection.setAutoCommit(true);
                while (maintenanceDone.getCount() > 0L && failure.get() == null) {
                    assertReadableState(connection);
                    Thread.yield();
                }
                assertReadableState(connection);
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void runConcurrentTask(
            CountDownLatch start,
            AtomicReference<Throwable> failure,
            CountDownLatch done,
            ThrowingRunnable action) {
        try {
            await(start);
            if (failure.get() == null) {
                action.run();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        } finally {
            done.countDown();
        }
    }

    private static void awaitOpenAndRun(
            TestConfiguration configuration,
            SqlConnectionAction action) throws Exception {
        Connection connection = configuration.openDefaultConnection();
        try {
            connection.setAutoCommit(false);
            action.run(connection);
        } finally {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Best-effort cleanup. The original task failure is reported separately.
            }
            connection.close();
        }
    }

    private static void assertReadableState(Connection connection)
            throws SQLException {
        assertNonNegativeCount(connection, HEAP_TABLE);
        assertNonNegativeCount(connection, MVCC_TABLE);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select bucket, count(*), sum(amount) from " + MVCC_TABLE
                             + " where bucket between 0 and 3 group by bucket order by bucket")) {
            int groups = 0;
            while (rs.next()) {
                assertTrue("bucket should stay in expected range", rs.getInt(1) >= 0);
                assertTrue("row count should never be negative", rs.getInt(2) >= 0);
                assertTrue("amount aggregate should stay non-negative", rs.getInt(3) >= 0);
                groups++;
            }
            assertTrue("reader should observe at least one MVCC bucket", groups > 0);
        }
    }

    private static void assertNonNegativeCount(Connection connection, String table)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            assertTrue(rs.next());
            assertTrue("reader should observe non-negative row count for " + table,
                    rs.getInt(1) >= 0);
            assertFalse(rs.next());
        }
    }

    private static void insertRow(
            Connection connection,
            String table,
            int id,
            int bucket,
            int amount,
            String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + table + " values (?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, bucket);
            statement.setInt(3, amount);
            statement.setString(4, note);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateRow(
            Connection connection,
            String table,
            int id,
            int bucket,
            int amount,
            String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update " + table
                        + " set bucket = ?, amount = ?, note = ? where id = ?")) {
            statement.setInt(1, bucket);
            statement.setInt(2, amount);
            statement.setString(3, note);
            statement.setInt(4, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertBucketCounts(
            Connection connection,
            String table,
            String label) throws SQLException {
        List<String> rows = rows(connection,
                "select bucket, count(*) from " + table
                        + " group by bucket order by bucket");
        assertEquals(label + " bucket count", 4, rows.size());
    }

    private static void assertNoRow(Connection connection, String table, int id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + table + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertFalse("expected no row " + table + "." + id, rs.next());
            }
        }
    }

    private static void assertRows(
            Connection connection,
            String sql,
            String... expectedRows) throws SQLException {
        assertEquals(List.of(expectedRows), rows(connection, sql));
    }

    private static List<String> rows(Connection connection, String sql)
            throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    row.append(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        while (!latch.await(1, TimeUnit.SECONDS)) {
            Thread.yield();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface SqlConnectionAction {
        void run(Connection connection) throws Exception;
    }
}
