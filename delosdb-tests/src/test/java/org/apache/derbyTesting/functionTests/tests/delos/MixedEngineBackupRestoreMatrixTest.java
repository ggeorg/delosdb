/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MixedEngineBackupRestoreMatrixTest

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
import java.sql.SQLException;

/** Mixed Derby heap plus delos_mvcc backup/restore matrix proof. */
public final class MixedEngineBackupRestoreMatrixTest extends MvccSqlTestSupport {
    public void testMixedHeapAndMvccBackupRestoreMatrix() throws Exception {
        String sourceDatabase = databaseName("mixed-engine-backup-restore-source-db");
        String restoredDatabase = databaseName("mixed-engine-backup-restore-restored-db");
        Path backupRoot = Path.of(databaseName("mixed-engine-backup-restore-copy-root"));

        deleteRecursively(Path.of(sourceDatabase));
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        long sourceMvccContainerId;
        try (Connection connection = openDatabase(sourceDatabase, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table heap_mixed_backup_t "
                            + "(id int primary key, name varchar(64), payload varchar(1024))");
            executeUpdate(connection,
                    "create index heap_mixed_backup_name_idx on heap_mixed_backup_t(name)");
            executeUpdate(connection,
                    "create table mvcc_mixed_backup_t "
                            + "(id int primary key, name varchar(64), payload varchar(4096)) using delos_mvcc");
            executeUpdate(connection,
                    "create index mvcc_mixed_backup_name_idx on mvcc_mixed_backup_t(name)");

            executeUpdate(connection,
                    "insert into heap_mixed_backup_t values "
                            + "(1, 'heap-alpha', '" + repeated('h', 64) + "')");
            executeUpdate(connection,
                    "insert into heap_mixed_backup_t values "
                            + "(2, 'heap-beta', '" + repeated('i', 128) + "')");
            connection.commit();
            executeUpdate(connection,
                    "insert into mvcc_mixed_backup_t values "
                            + "(10, 'mvcc-alpha', '" + repeated('m', 512) + "')");
            executeUpdate(connection,
                    "insert into mvcc_mixed_backup_t values "
                            + "(20, 'mvcc-beta', '" + repeated('n', 2048) + "')");
            executeUpdate(connection,
                    "update mvcc_mixed_backup_t set payload = '" + repeated('u', 1536) + "' where id = 20");
            connection.commit();

            assertRows(connection,
                    "select id, name from heap_mixed_backup_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(connection,
                    "select id, name from mvcc_mixed_backup_t order by id",
                    "10|mvcc-alpha",
                    "20|mvcc-beta");
            assertRows(connection,
                    "select id from heap_mixed_backup_t where name = 'heap-beta'",
                    "2");
            assertRows(connection,
                    "select id from mvcc_mixed_backup_t where name = 'mvcc-beta'",
                    "20");
            sourceMvccContainerId = mvccContainerId(connection, "MVCC_MIXED_BACKUP_T");
            assertConglomeratePresent(connection, sourceMvccContainerId);
            connection.commit();

            backupDatabase(connection, backupRoot);
        }

        Path backupDatabase = backupRoot.resolve(Path.of(sourceDatabase).getFileName());
        assertTrue("mixed backup should include inherited Derby heap/raw-store files",
                regularFileCount(backupDatabase.resolve("seg0")) > 0L);
        assertNoRetiredMvccSidecars(backupDatabase);

        shutdownDatabase(sourceDatabase);

        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom=" + backupDatabase.toAbsolutePath())) {
            assertRows(restored,
                    "select id, name from heap_mixed_backup_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(restored,
                    "select id, name from mvcc_mixed_backup_t order by id",
                    "10|mvcc-alpha",
                    "20|mvcc-beta");
            assertRows(restored,
                    "select id from heap_mixed_backup_t where name = 'heap-beta'",
                    "2");
            assertRows(restored,
                    "select id from mvcc_mixed_backup_t where name = 'mvcc-beta'",
                    "20");
            assertEquals(sourceMvccContainerId,
                    mvccContainerId(restored, "MVCC_MIXED_BACKUP_T"));
            assertConglomeratePresent(restored, sourceMvccContainerId);

            restored.setAutoCommit(false);
            executeUpdate(restored,
                    "insert into heap_mixed_backup_t values "
                            + "(3, 'heap-gamma', '" + repeated('j', 96) + "')");
            executeUpdate(restored,
                    "update heap_mixed_backup_t set name = 'heap-beta-restored' where id = 2");
            restored.commit();
            executeUpdate(restored,
                    "insert into mvcc_mixed_backup_t values "
                            + "(30, 'mvcc-gamma', '" + repeated('p', 1024) + "')");
            executeUpdate(restored,
                    "update mvcc_mixed_backup_t set name = 'mvcc-beta-restored' where id = 20");
            restored.commit();

            assertRows(restored,
                    "select id, name from heap_mixed_backup_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta-restored",
                    "3|heap-gamma");
            assertRows(restored,
                    "select id, name from mvcc_mixed_backup_t order by id",
                    "10|mvcc-alpha",
                    "20|mvcc-beta-restored",
                    "30|mvcc-gamma");
            restored.commit();
        }

        shutdownDatabase(restoredDatabase);

        try (Connection reopened = openDatabase(restoredDatabase, false)) {
            assertRows(reopened,
                    "select id, name from heap_mixed_backup_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta-restored",
                    "3|heap-gamma");
            assertRows(reopened,
                    "select id, name from mvcc_mixed_backup_t order by id",
                    "10|mvcc-alpha",
                    "20|mvcc-beta-restored",
                    "30|mvcc-gamma");
            assertRows(reopened,
                    "select id from heap_mixed_backup_t where name = 'heap-beta-restored'",
                    "2");
            assertRows(reopened,
                    "select id from mvcc_mixed_backup_t where name = 'mvcc-beta-restored'",
                    "20");
        }

        shutdownDatabase(restoredDatabase);
    }

    private static void assertNoRetiredMvccSidecars(Path backupDatabase) {
        assertFalse("RawStore-backed MVCC backup must not contain the retired sidecar directory",
                Files.exists(backupDatabase.resolve("delos_mvcc").resolve("inherited-store")));
        assertFalse("RawStore-backed MVCC backup must not contain the retired sidecar manifest",
                Files.exists(backupDatabase.resolve("delos_mvcc.BACKUP-MANIFEST")));
    }

    private static void backupDatabase(Connection connection, Path backupRoot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("call syscs_util.syscs_backup_database(?)")) {
            statement.setString(1, backupRoot.toAbsolutePath().toString());
            statement.execute();
        }
    }

    private static long regularFileCount(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return 0L;
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static String repeated(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
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
