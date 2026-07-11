/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccConcurrentBackupRestoreTest

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Online backup proof while committed MVCC writes continue on another connection. */
public final class MvccConcurrentBackupRestoreTest extends MvccSqlTestSupport {
    private static final int MINIMUM_COMMITS_BEFORE_BACKUP = 40;

    public void testOnlineBackupRestoresConsistentMvccStateDuringConcurrentWrites() throws Exception {
        String sourceDatabase = databaseName("mvcc-concurrent-backup-source-db");
        String restoredDatabase = databaseName("mvcc-concurrent-backup-restored-db");
        Path backupRoot = Path.of(databaseName("mvcc-concurrent-backup-copy-root"));

        deleteRecursively(Path.of(sourceDatabase));
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        try (Connection setup = openDatabase(sourceDatabase, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup,
                    "create table mvcc_concurrent_backup_t "
                            + "(id int primary key, revision int not null, marker varchar(64) not null) "
                            + "using delos_mvcc");
            executeUpdate(setup,
                    "create index mvcc_concurrent_backup_marker_idx "
                            + "on mvcc_concurrent_backup_t(marker)");
            setup.commit();
        }

        AtomicBoolean keepWriting = new AtomicBoolean(true);
        AtomicInteger committed = new AtomicInteger();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        CountDownLatch writerStarted = new CountDownLatch(1);
        Thread writer = new Thread(() -> runWriter(
                sourceDatabase, keepWriting, committed, writerFailure, writerStarted),
                "delos-mvcc-concurrent-backup-writer");
        writer.start();

        assertTrue("concurrent MVCC writer should start",
                writerStarted.await(30, TimeUnit.SECONDS));
        waitForCommits(committed, writerFailure, MINIMUM_COMMITS_BEFORE_BACKUP);

        try (Connection backupConnection = openDatabase(sourceDatabase, false)) {
            backupDatabase(backupConnection, backupRoot);
        } finally {
            keepWriting.set(false);
            writer.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertFalse("concurrent MVCC writer should stop after backup", writer.isAlive());
        if (writerFailure.get() != null) {
            throw new AssertionError("concurrent MVCC writer failed", writerFailure.get());
        }
        assertTrue("writer must commit while online backup is active",
                committed.get() >= MINIMUM_COMMITS_BEFORE_BACKUP);

        Path backupDatabase = backupRoot.resolve(Path.of(sourceDatabase).getFileName());
        assertTrue("concurrent backup must contain MVCC sidecars",
                Files.isDirectory(backupDatabase.resolve("delos_mvcc")));
        assertTrue("concurrent backup must declare recovery-journals-last copy mode",
                Files.readAllLines(backupDatabase.resolve("delos_mvcc.BACKUP-MANIFEST"))
                        .contains("copyMode=fuzzy-recovery-journals-last"));

        shutdownDatabase(sourceDatabase);

        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom=" + backupDatabase.toAbsolutePath())) {
            assertRestoredRowsAreTransactionallyConsistent(restored);
            assertIndexAgreesWithTableScan(restored);

            restored.setAutoCommit(false);
            executeUpdate(restored,
                    "insert into mvcc_concurrent_backup_t values "
                            + "(1000000, 1000000, 'row-1000000-rev-1000000')");
            restored.commit();
            assertRows(restored,
                    "select id, revision, marker from mvcc_concurrent_backup_t where id = 1000000",
                    "1000000|1000000|row-1000000-rev-1000000");
        }

        shutdownDatabase(restoredDatabase);
        try (Connection reopened = openDatabase(restoredDatabase, false)) {
            assertRestoredRowsAreTransactionallyConsistent(reopened);
            assertRows(reopened,
                    "select id from mvcc_concurrent_backup_t where marker = 'row-1000000-rev-1000000'",
                    "1000000");
        }
        shutdownDatabase(restoredDatabase);
    }

    private static void runWriter(
            String databaseName,
            AtomicBoolean keepWriting,
            AtomicInteger committed,
            AtomicReference<Throwable> failure,
            CountDownLatch started) {
        try (Connection connection = openDatabase(databaseName, false);
             PreparedStatement insert = connection.prepareStatement(
                     "insert into mvcc_concurrent_backup_t values (?, ?, ?)");
             PreparedStatement update = connection.prepareStatement(
                     "update mvcc_concurrent_backup_t set revision = ?, marker = ? where id = ?")) {
            connection.setAutoCommit(false);
            started.countDown();
            int sequence = 1;
            while (keepWriting.get()) {
                int id = sequence;
                String marker = marker(id, sequence);
                insert.setInt(1, id);
                insert.setInt(2, sequence);
                insert.setString(3, marker);
                insert.executeUpdate();
                connection.commit();
                committed.incrementAndGet();

                if (id > 1) {
                    int updatedId = id - 1;
                    String updatedMarker = marker(updatedId, sequence);
                    update.setInt(1, sequence);
                    update.setString(2, updatedMarker);
                    update.setInt(3, updatedId);
                    update.executeUpdate();
                    connection.commit();
                    committed.incrementAndGet();
                }
                sequence++;
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
            started.countDown();
        }
    }

    private static void waitForCommits(
            AtomicInteger committed,
            AtomicReference<Throwable> failure,
            int minimum) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (committed.get() < minimum && System.nanoTime() < deadline) {
            if (failure.get() != null) {
                throw new AssertionError("concurrent MVCC writer failed before backup", failure.get());
            }
            Thread.sleep(10L);
        }
        assertTrue("concurrent writer did not reach the backup threshold: " + committed.get(),
                committed.get() >= minimum);
    }

    private static void assertRestoredRowsAreTransactionallyConsistent(Connection connection)
            throws SQLException {
        int rows = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select id, revision, marker from mvcc_concurrent_backup_t order by id")) {
            while (rs.next()) {
                int id = rs.getInt(1);
                int revision = rs.getInt(2);
                assertEquals("restored row must not expose a partial transaction",
                        marker(id, revision), rs.getString(3));
                rows++;
            }
        }
        assertTrue("restored concurrent backup should contain committed MVCC rows", rows > 0);
    }

    private static void assertIndexAgreesWithTableScan(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select id, revision, marker from mvcc_concurrent_backup_t order by id")) {
            while (rs.next()) {
                int expectedId = rs.getInt(1);
                String marker = rs.getString(3);
                try (PreparedStatement lookup = connection.prepareStatement(
                        "select id from mvcc_concurrent_backup_t where marker = ?")) {
                    lookup.setString(1, marker);
                    try (ResultSet indexed = lookup.executeQuery()) {
                        assertTrue("ordered index lookup should find restored row " + expectedId,
                                indexed.next());
                        assertEquals(expectedId, indexed.getInt(1));
                        assertFalse("marker should identify one restored row", indexed.next());
                    }
                }
            }
        }
    }

    private static String marker(int id, int revision) {
        return "row-" + id + "-rev-" + revision;
    }

    private static void backupDatabase(Connection connection, Path backupRoot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "call syscs_util.syscs_backup_database(?)")) {
            statement.setString(1, backupRoot.toAbsolutePath().toString());
            statement.execute();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()))
                    .forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
