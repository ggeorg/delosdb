/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccDrdaConcurrentNetworkClientTest

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

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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

/** Concurrent DRDA/JDBC network-client stress gate for delos_mvcc tables. */
public final class MvccDrdaConcurrentNetworkClientTest extends BaseJDBCTestCase {
    private static final String TABLE = "mvcc_drda_concurrent_t";
    private static final String TABLE_UPPER = "MVCC_DRDA_CONCURRENT_T";

    public MvccDrdaConcurrentNetworkClientTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(MvccDrdaConcurrentNetworkClientTest.class);
        return TestConfiguration.clientServerDecorator(new CleanDatabaseTestSetup(suite));
    }

    public void testConcurrentNetworkClientsLobsIndexesRollbackVacuumAndReopen()
            throws Exception {
        assertTrue("test must run through Derby network client",
                getTestConfiguration().getJDBCClient().isDerbyNetClient());
        assertTrue("configured JDBC URL should be network-client URL: "
                        + getTestConfiguration().getJDBCUrl(),
                getTestConfiguration().getJDBCUrl().startsWith("jdbc:derby://"));

        TestConfiguration configuration = getTestConfiguration();
        DelosStorageDiagnostics diagnostics = MvccSqlTestSupport.mvccDiagnostics();
        long containerId;

        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            createSchema(connection);
            for (int id = 1; id <= 24; id++) {
                insertRow(connection, id, id % 6, "base-" + id,
                        blobPayload(4_096 + id, id),
                        clobPayload(4_096 + id, (char) ('a' + (id % 5))));
            }
            connection.commit();
            containerId = MvccSqlTestSupport.mvccContainerId(connection, TABLE_UPPER);
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(2);
        CountDownLatch vacuumDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> runConcurrentTask(start, failure, () -> {
            try {
                updateBaseRows(configuration);
            } finally {
                writersDone.countDown();
            }
        })));
        futures.add(executor.submit(() -> runConcurrentTask(start, failure, () -> {
            try {
                insertAndDeleteRows(configuration);
            } finally {
                writersDone.countDown();
            }
        })));
        futures.add(executor.submit(() -> runConcurrentTask(start, failure, () -> {
            await(writersDone);
            try (Connection connection = configuration.openDefaultConnection()) {
                connection.setAutoCommit(false);
                MvccSqlTestSupport.inPlaceCompressTable(connection, TABLE_UPPER);
                connection.commit();
            } finally {
                vacuumDone.countDown();
            }
        })));
        futures.add(executor.submit(() -> runConcurrentTask(start, failure, () -> {
            try (Connection connection = configuration.openDefaultConnection()) {
                connection.setAutoCommit(true);
                while (vacuumDone.getCount() > 0L && failure.get() == null) {
                    assertReadableSnapshot(connection);
                    Thread.yield();
                }
                assertReadableSnapshot(connection);
            }
        })));

        start.countDown();
        executor.shutdown();
        assertTrue("concurrent DRDA MVCC stress tasks did not finish",
                executor.awaitTermination(120, TimeUnit.SECONDS));
        for (Future<?> future : futures) {
            future.get();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent DRDA MVCC stress task failed", failure.get());
        }

        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            assertRows(connection,
                    "select count(*), min(id), max(id) from " + TABLE,
                    "28|1|107");
            assertRows(connection,
                    "select id, bucket, note from " + TABLE
                            + " where id in (1, 12, 20, 24, 100, 107, 900) order by id",
                    "1|1|network-a-1",
                    "12|0|network-a-12",
                    "100|4|network-inserted-100",
                    "107|5|network-inserted-107");
            assertLobRow(connection, 1,
                    blobPayload(72_000 + 1, 101),
                    clobPayload(72_000 + 1, 'k'), "network-a-1");
            assertLobRow(connection, 100,
                    blobPayload(68_000 + 100, 200),
                    clobPayload(68_000 + 100, 'x'), "network-inserted-100");
            assertNoRow(connection, 900);
            diagnostics.assertConsistentForTesting(0, containerId);
            assertEquals("expected no MVCC consistency errors", 0,
                    diagnostics.consistencyDiagnosticsForTesting(0, containerId).errorCount());
            assertTrue("concurrent DRDA LOB stress should retain MVCC overflow pages",
                    diagnostics.overflowPageCountForTesting(0, containerId) > 0L);
            connection.rollback();
        }

        TestConfiguration.getCurrent().shutdownDatabase();
        diagnostics.clearRuntimeStateForTesting();

        try (Connection reopened = openDefaultConnection()) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = MvccSqlTestSupport.mvccContainerId(reopened, TABLE_UPPER);
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertRows(reopened,
                    "select count(*), min(id), max(id) from " + TABLE,
                    "28|1|107");
            assertLobRow(reopened, 12,
                    blobPayload(72_000 + 12, 112),
                    clobPayload(72_000 + 12, 'k'), "network-a-12");
            assertLobRow(reopened, 107,
                    blobPayload(68_000 + 107, 207),
                    clobPayload(68_000 + 107, 'x'), "network-inserted-107");
            assertNoRow(reopened, 900);
            reopened.rollback();
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + TABLE + " ("
                    + "id int primary key, "
                    + "bucket int not null, "
                    + "note varchar(64), "
                    + "blob_payload blob(262144), "
                    + "clob_payload clob(262144)) using delos_mvcc");
            statement.executeUpdate("create index mvcc_drda_concurrent_bucket_idx on "
                    + TABLE + "(bucket)");
        }
    }

    private static void updateBaseRows(TestConfiguration configuration) throws Exception {
        awaitOpenAndRun(configuration, connection -> {
            for (int id = 1; id <= 12; id++) {
                Savepoint savepoint = connection.setSavepoint("network_update_" + id);
                updateRow(connection, id, id % 6, "rolled-back-" + id,
                        blobPayload(12_000 + id, 50 + id),
                        clobPayload(12_000 + id, 'q'));
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                updateRow(connection, id, id % 6, "network-a-" + id,
                        blobPayload(72_000 + id, 100 + id),
                        clobPayload(72_000 + id, 'k'));
                if (id % 4 == 0) {
                    connection.commit();
                }
            }
            connection.commit();
        });
    }

    private static void insertAndDeleteRows(TestConfiguration configuration) throws Exception {
        awaitOpenAndRun(configuration, connection -> {
            for (int id = 100; id <= 107; id++) {
                insertRow(connection, id, id % 6, "network-inserted-" + id,
                        blobPayload(68_000 + id, 100 + id),
                        clobPayload(68_000 + id, 'x'));
                if (id % 3 == 1) {
                    connection.commit();
                }
            }
            Savepoint savepoint = connection.setSavepoint("network_rollback_insert_delete");
            insertRow(connection, 900, 0, "network-rolled-back-insert",
                    blobPayload(70_000, 7), clobPayload(70_000, 'z'));
            deleteRows(connection, "id between 100 and 101");
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            deleteRows(connection, "id between 20 and 24");
            connection.commit();
        });
    }

    private static void awaitOpenAndRun(TestConfiguration configuration, SqlConnectionAction action)
            throws Exception {
        Connection connection = configuration.openDefaultConnection();
        try {
            connection.setAutoCommit(false);
            action.run(connection);
        } finally {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Best-effort cleanup. Test failure will be reported by the original exception.
            }
            connection.close();
        }
    }

    private static void assertReadableSnapshot(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "select bucket, count(*), sum(length(note)) from " + TABLE
                            + " where bucket between 0 and 5 group by bucket order by bucket")) {
                int groups = 0;
                while (rs.next()) {
                    assertTrue("bucket should stay indexed in expected range", rs.getInt(1) >= 0);
                    assertTrue("row count should never be negative", rs.getInt(2) >= 0);
                    assertTrue("note length aggregate should stay non-negative", rs.getInt(3) >= 0);
                    groups++;
                }
                assertTrue("reader should observe at least one indexed bucket", groups > 0);
            }
            try (ResultSet rs = statement.executeQuery(
                    "select count(*) from " + TABLE + " where id in (1, 12, 100, 107)")) {
                assertTrue(rs.next());
                assertTrue("reader should observe a stable non-negative probe count",
                        rs.getInt(1) >= 0);
                assertFalse(rs.next());
            }
        }
    }

    private static void insertRow(
            Connection connection,
            int id,
            int bucket,
            String note,
            byte[] blobPayload,
            String clobPayload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + TABLE + " values (?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, bucket);
            statement.setString(3, note);
            statement.setBinaryStream(4, new ByteArrayInputStream(blobPayload), blobPayload.length);
            statement.setCharacterStream(5, new StringReader(clobPayload), clobPayload.length());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateRow(
            Connection connection,
            int id,
            int bucket,
            String note,
            byte[] blobPayload,
            String clobPayload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update " + TABLE + " set bucket = ?, note = ?, "
                        + "blob_payload = ?, clob_payload = ? where id = ?")) {
            statement.setInt(1, bucket);
            statement.setString(2, note);
            statement.setBinaryStream(3, new ByteArrayInputStream(blobPayload), blobPayload.length);
            statement.setCharacterStream(4, new StringReader(clobPayload), clobPayload.length());
            statement.setInt(5, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void deleteRows(Connection connection, String predicate) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from " + TABLE + " where " + predicate);
        }
    }

    private static void assertRows(Connection connection, String sql, String... expectedRows)
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
        assertEquals(List.of(expectedRows), rows);
    }

    private static void assertLobRow(
            Connection connection,
            int id,
            byte[] expectedBlob,
            String expectedClob,
            String expectedNote) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select blob_payload, clob_payload, note from " + TABLE + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected LOB row " + id, rs.next());
                assertTrue("BLOB payload mismatch for row " + id,
                        Arrays.equals(expectedBlob, rs.getBytes(1)));
                assertEquals("CLOB payload mismatch for row " + id, expectedClob, rs.getString(2));
                assertEquals("note mismatch for row " + id, expectedNote, rs.getString(3));
                assertFalse("expected one LOB row " + id, rs.next());
            }
        }
    }

    private static void assertNoRow(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + TABLE + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertFalse("expected no row " + id, rs.next());
            }
        }
    }

    private static byte[] blobPayload(int size, int seed) {
        byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((seed + (i * 19)) & 0xff);
        }
        return payload;
    }

    private static String clobPayload(int size, char base) {
        StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append((char) (base + (i % 11)));
        }
        return builder.toString();
    }

    private static void runConcurrentTask(
            CountDownLatch start,
            AtomicReference<Throwable> failure,
            ThrowingRunnable action) {
        try {
            await(start);
            if (failure.get() == null) {
                action.run();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
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
