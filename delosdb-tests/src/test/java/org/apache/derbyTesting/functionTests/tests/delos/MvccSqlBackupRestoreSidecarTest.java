/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlBackupRestoreSidecarTest

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

/** SQL backup/restore regression tests for delos_mvcc provider sidecar state. */
public final class MvccSqlBackupRestoreSidecarTest extends MvccSqlTestSupport {
    public void testBackupAndCreateFromRestoreIncludesMvccSidecars() throws Exception {
        String sourceDatabase = databaseName("mvcc-backup-sidecar-source-db");
        String restoredDatabase = databaseName("mvcc-backup-sidecar-restored-db");
        Path backupRoot = Path.of(databaseName("mvcc-backup-sidecar-copy-root"));

        deleteRecursively(Path.of(sourceDatabase));
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        try (Connection connection = openDatabase(sourceDatabase, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table mvcc_backup_restore_t "
                            + "(id int primary key, name varchar(64), payload varchar(4096)) using delos_mvcc");
            executeUpdate(connection,
                    "insert into mvcc_backup_restore_t values "
                            + "(1, 'alpha', '" + repeated('a', 128) + "')");
            executeUpdate(connection,
                    "insert into mvcc_backup_restore_t values "
                            + "(2, 'beta', '" + repeated('b', 2048) + "')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_backup_restore_t order by id",
                    "1|alpha",
                    "2|beta");
            assertTrue("expected source delos_mvcc sidecar files before backup",
                    inheritedMvccStateFileCount(sourceDatabase) > 0L);

            backupDatabase(connection, backupRoot);
        }

        Path backupDatabase = backupRoot.resolve(Path.of(sourceDatabase).getFileName());
        Path backupSidecars = backupDatabase.resolve("delos_mvcc").resolve("inherited-store");
        assertTrue("backup must include delos_mvcc inherited-store sidecar directory: " + backupSidecars,
                Files.isDirectory(backupSidecars));
        assertTrue("backup must include delos_mvcc inherited-store sidecar files",
                regularFileCount(backupSidecars) > 0L);

        shutdownDatabase(sourceDatabase);

        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom=" + backupDatabase.toAbsolutePath())) {
            assertRows(restored,
                    "select id, name from mvcc_backup_restore_t order by id",
                    "1|alpha",
                    "2|beta");
            assertTrue("restored database must include delos_mvcc inherited-store files",
                    inheritedMvccStateFileCount(restoredDatabase) > 0L);
        }

        shutdownDatabase(restoredDatabase);

        try (Connection reopened = openDatabase(restoredDatabase, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_backup_restore_t order by id",
                    "1|alpha",
                    "2|beta");
        }

        shutdownDatabase(restoredDatabase);
    }

    public void testHeapOnlyBackupDoesNotCreateMvccSidecars() throws Exception {
        String sourceDatabase = databaseName("heap-backup-no-sidecar-source-db");
        String restoredDatabase = databaseName("heap-backup-no-sidecar-restored-db");
        Path backupRoot = Path.of(databaseName("heap-backup-no-sidecar-copy-root"));

        deleteRecursively(Path.of(sourceDatabase));
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        try (Connection connection = openDatabase(sourceDatabase, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_backup_restore_t (id int primary key, name varchar(64))");
            executeUpdate(connection, "insert into heap_backup_restore_t values (1, 'heap')");
            connection.commit();

            assertEquals("heap-only database should not have delos_mvcc sidecar files before backup",
                    0L,
                    inheritedMvccStateFileCount(sourceDatabase));

            backupDatabase(connection, backupRoot);
        }

        Path backupDatabase = backupRoot.resolve(Path.of(sourceDatabase).getFileName());
        assertFalse("heap-only backup should not synthesize a delos_mvcc directory",
                Files.exists(backupDatabase.resolve("delos_mvcc")));

        shutdownDatabase(sourceDatabase);

        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom=" + backupDatabase.toAbsolutePath())) {
            assertRows(restored,
                    "select id, name from heap_backup_restore_t order by id",
                    "1|heap");
            assertEquals("heap-only restore should not synthesize delos_mvcc sidecar files",
                    0L,
                    inheritedMvccStateFileCount(restoredDatabase));
        }

        shutdownDatabase(restoredDatabase);
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
