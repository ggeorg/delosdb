/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlBackupRestoreTest

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

/** SQL backup/restore regression tests for RawStore-backed delos_mvcc tables. */
public final class MvccSqlBackupRestoreTest extends MvccSqlTestSupport {
    public void testBackupAndCreateFromRestorePreservesMvccRawStoreState() throws Exception {
        String sourceDatabase = databaseName("mvcc-backup-rawstore-source-db");
        String restoredDatabase = databaseName("mvcc-backup-rawstore-restored-db");
        Path backupRoot = Path.of(databaseName("mvcc-backup-rawstore-copy-root"));

        deleteRecursively(Path.of(sourceDatabase));
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        long sourceContainerId;
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
            connection.setAutoCommit(true);

            assertRows(connection,
                    "select id, name from mvcc_backup_restore_t order by id",
                    "1|alpha",
                    "2|beta");
            sourceContainerId = mvccContainerId(connection, "MVCC_BACKUP_RESTORE_T");
            assertConglomeratePresent(connection, sourceContainerId);

            backupDatabase(connection, backupRoot);
        }

        Path backupDatabase = backupRoot.resolve(Path.of(sourceDatabase).getFileName());
        assertTrue("backup must include RawStore segment files",
                regularFileCount(backupDatabase.resolve("seg0")) > 0L);
        assertNoRetiredMvccSidecars(backupDatabase);

        shutdownDatabase(sourceDatabase);

        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom=" + backupDatabase.toAbsolutePath())) {
            assertRows(restored,
                    "select id, name from mvcc_backup_restore_t order by id",
                    "1|alpha",
                    "2|beta");
            assertEquals(sourceContainerId,
                    mvccContainerId(restored, "MVCC_BACKUP_RESTORE_T"));
            assertConglomeratePresent(restored, sourceContainerId);
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

    public void testHeapOnlyBackupDoesNotCreateRetiredMvccArtifacts() throws Exception {
        String sourceDatabase = databaseName("heap-backup-rawstore-source-db");
        String restoredDatabase = databaseName("heap-backup-rawstore-restored-db");
        Path backupRoot = Path.of(databaseName("heap-backup-rawstore-copy-root"));

        deleteRecursively(Path.of(sourceDatabase));
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        try (Connection connection = openDatabase(sourceDatabase, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_backup_restore_t (id int primary key, name varchar(64))");
            executeUpdate(connection, "insert into heap_backup_restore_t values (1, 'heap')");
            connection.commit();
            connection.setAutoCommit(true);

            backupDatabase(connection, backupRoot);
        }

        Path backupDatabase = backupRoot.resolve(Path.of(sourceDatabase).getFileName());
        assertNoRetiredMvccSidecars(backupDatabase);

        shutdownDatabase(sourceDatabase);

        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom=" + backupDatabase.toAbsolutePath())) {
            assertRows(restored,
                    "select id, name from heap_backup_restore_t order by id",
                    "1|heap");
        }

        shutdownDatabase(restoredDatabase);
    }

    private static void assertNoRetiredMvccSidecars(Path databaseDirectory) {
        assertFalse("backup must not contain the retired MVCC sidecar directory",
                Files.exists(databaseDirectory.resolve("delos_mvcc").resolve("inherited-store")));
        assertFalse("backup must not contain the retired MVCC sidecar manifest",
                Files.exists(databaseDirectory.resolve("delos_mvcc.BACKUP-MANIFEST")));
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
