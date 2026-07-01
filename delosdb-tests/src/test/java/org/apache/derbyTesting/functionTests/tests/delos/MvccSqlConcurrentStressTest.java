/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlConcurrentStressTest

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
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL stress gate for the explicit MVCC locking refactor. */
public final class MvccSqlConcurrentStressTest extends MvccSqlTestSupport {
    public void testConcurrentReadersWritersLobsIndexesRollbackVacuumAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-concurrent-stress-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_concurrent_stress_t ("
                    + "id int primary key, "
                    + "category int not null, "
                    + "note varchar(64), "
                    + "blob_payload blob(131072), "
                    + "clob_payload clob(131072)) using delos_mvcc");
            executeUpdate(connection,
                    "create index mvcc_concurrent_stress_category_idx on mvcc_concurrent_stress_t(category)");
            for (int id = 1; id <= 30; id++) {
                insertRow(connection, id, id % 5, "base-" + id,
                        blobPayload(2_048 + id, id), clobPayload(2_048 + id, (char) ('a' + (id % 5))));
            }
            connection.commit();
            containerId = mvccContainerId(connection, "MVCC_CONCURRENT_STRESS_T");
            diagnostics.assertConsistentForTesting(0, containerId);
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(2);
        CountDownLatch vacuumDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> runConcurrentTask(start, failure, () -> {
            try {
                updateBaseRows(databaseName);
            } finally {
                writersDone.countDown();
            }
        })));
        futures.add(executor.submit(() -> runConcurrentTask(start, failure, () -> {
            try {
                insertAndDeleteRows(databaseName);
            } finally {
                writersDone.countDown();
            }
        })));
        futures.add(executor.submit(() -> runConcurrentTask(start, failure, () -> {
            await(writersDone);
            try (Connection connection = openDatabase(databaseName, false)) {
                connection.setAutoCommit(false);
                inPlaceCompressTable(connection, "MVCC_CONCURRENT_STRESS_T");
                connection.commit();
            } finally {
                vacuumDone.countDown();
            }
        })));
        futures.add(executor.submit(() -> runConcurrentTask(start, failure, () -> {
            try (Connection connection = openDatabase(databaseName, false)) {
                while (vacuumDone.getCount() > 0L && failure.get() == null) {
                    assertReadableSnapshot(connection);
                    Thread.yield();
                }
                assertReadableSnapshot(connection);
            }
        })));

        start.countDown();
        executor.shutdown();
        assertTrue("concurrent MVCC stress tasks did not finish",
                executor.awaitTermination(90, TimeUnit.SECONDS));
        for (Future<?> future : futures) {
            future.get();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent MVCC stress task failed", failure.get());
        }

        try (Connection connection = openDatabase(databaseName, false)) {
            assertRows(connection,
                    "select count(*), min(id), max(id) from mvcc_concurrent_stress_t",
                    "34|1|109");
            assertRows(connection,
                    "select id, category, note from mvcc_concurrent_stress_t "
                            + "where id in (1, 15, 25, 30, 100, 109, 900) order by id",
                    "1|1|writer-a-1",
                    "15|0|writer-a-15",
                    "100|0|inserted-100",
                    "109|4|inserted-109");
            assertLobRow(connection, 1,
                    blobPayload(12_000 + 1, 101), clobPayload(12_000 + 1, 'k'), "writer-a-1");
            assertLobRow(connection, 100,
                    blobPayload(9_000 + 100, 200), clobPayload(9_000 + 100, 'x'), "inserted-100");
            diagnostics.assertConsistentForTesting(0, containerId);
            assertEquals("expected no MVCC consistency errors", 0,
                    diagnostics.consistencyDiagnosticsForTesting(0, containerId).errorCount());
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_CONCURRENT_STRESS_T");
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertRows(reopened,
                    "select count(*), min(id), max(id) from mvcc_concurrent_stress_t",
                    "34|1|109");
            assertLobRow(reopened, 15,
                    blobPayload(12_000 + 15, 115), clobPayload(12_000 + 15, 'k'), "writer-a-15");
            assertNoRow(reopened, 900);
        }
    }

    private static void updateBaseRows(String databaseName) throws Exception {
        awaitOpenAndRun(databaseName, connection -> {
            for (int id = 1; id <= 15; id++) {
                Savepoint savepoint = connection.setSavepoint("stress_update_" + id);
                updateRow(connection, id, id % 5, "rolled-back-" + id,
                        blobPayload(4_096 + id, 50 + id), clobPayload(4_096 + id, 'q'));
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                updateRow(connection, id, id % 5, "writer-a-" + id,
                        blobPayload(12_000 + id, 100 + id), clobPayload(12_000 + id, 'k'));
                if (id % 3 == 0) {
                    connection.commit();
                }
            }
            connection.commit();
        });
    }

    private static void insertAndDeleteRows(String databaseName) throws Exception {
        awaitOpenAndRun(databaseName, connection -> {
            for (int id = 100; id <= 109; id++) {
                insertRow(connection, id, id % 5, "inserted-" + id,
                        blobPayload(9_000 + id, 100 + id), clobPayload(9_000 + id, 'x'));
                if (id % 3 == 1) {
                    connection.commit();
                }
            }
            Savepoint savepoint = connection.setSavepoint("stress_rollback_insert_delete");
            insertRow(connection, 900, 0, "rolled-back-insert",
                    blobPayload(10_000, 7), clobPayload(10_000, 'z'));
            executeUpdate(connection, "delete from mvcc_concurrent_stress_t where id between 100 and 102");
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            executeUpdate(connection, "delete from mvcc_concurrent_stress_t where id between 25 and 30");
            connection.commit();
        });
    }

    private static void awaitOpenAndRun(String databaseName, SqlConnectionAction action) throws Exception {
        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            action.run(connection);
        }
    }

    private static void assertReadableSnapshot(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "select category, count(*), sum(length(note)) from mvcc_concurrent_stress_t "
                            + "where category between 0 and 4 group by category order by category")) {
                int groups = 0;
                while (rs.next()) {
                    assertTrue("category should stay indexed in expected range", rs.getInt(1) >= 0);
                    assertTrue("row count should never be negative", rs.getInt(2) >= 0);
                    assertTrue("note length aggregate should stay non-negative", rs.getInt(3) >= 0);
                    groups++;
                }
                assertTrue("reader should observe at least one indexed category", groups > 0);
            }
            try (ResultSet rs = statement.executeQuery(
                    "select count(*) from mvcc_concurrent_stress_t where id in (1, 15, 100, 109)")) {
                assertTrue(rs.next());
                assertTrue("reader should observe a stable non-negative probe count", rs.getInt(1) >= 0);
                assertFalse(rs.next());
            }
        }
    }

    private static void insertRow(
            Connection connection,
            int id,
            int category,
            String note,
            byte[] blobPayload,
            String clobPayload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_concurrent_stress_t values (?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, category);
            statement.setString(3, note);
            statement.setBytes(4, blobPayload);
            statement.setString(5, clobPayload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateRow(
            Connection connection,
            int id,
            int category,
            String note,
            byte[] blobPayload,
            String clobPayload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update mvcc_concurrent_stress_t set category = ?, note = ?, "
                        + "blob_payload = ?, clob_payload = ? where id = ?")) {
            statement.setInt(1, category);
            statement.setString(2, note);
            statement.setBytes(3, blobPayload);
            statement.setString(4, clobPayload);
            statement.setInt(5, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertLobRow(
            Connection connection,
            int id,
            byte[] expectedBlob,
            String expectedClob,
            String expectedNote) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select blob_payload, clob_payload, note from mvcc_concurrent_stress_t where id = ?")) {
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
                "select id from mvcc_concurrent_stress_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertFalse("expected no row " + id, rs.next());
            }
        }
    }

    private static byte[] blobPayload(int size, int seed) {
        byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((seed + (i * 17)) & 0xff);
        }
        return payload;
    }

    private static String clobPayload(int size, char base) {
        StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append((char) (base + (i % 13)));
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
