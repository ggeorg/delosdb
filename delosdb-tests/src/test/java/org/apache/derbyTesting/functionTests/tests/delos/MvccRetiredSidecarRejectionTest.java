/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRetiredSidecarRejectionTest

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Fail-closed proof for the retired external {@code delos_mvcc} format. */
public final class MvccRetiredSidecarRejectionTest extends MvccSqlTestSupport {
    private static final String RETIRED_DIRECTORY = "delos_mvcc";
    private static final String RETIRED_MANIFEST = "delos_mvcc.BACKUP-MANIFEST";
    private static final String RETIRED_MARKER = "delos_mvcc.BACKUP-IN-PROGRESS";

    public void testBackupRejectsRetiredExternalStateBeforeCreatingBackup()
            throws Exception {
        String database = databaseName("mvcc-retired-sidecar-backup-source-db");
        Path databaseDirectory = Path.of(database);
        Path backupRoot = Path.of(databaseName("mvcc-retired-sidecar-backup-root"));

        deleteRecursively(databaseDirectory);
        deleteRecursively(backupRoot);

        try (Connection connection = openDatabase(database, true)) {
            executeUpdate(connection,
                    "create table retired_sidecar_backup_t "
                            + "(id int primary key, name varchar(64)) using delos_mvcc");
            executeUpdate(connection,
                    "insert into retired_sidecar_backup_t values (1, 'rawstore')");

            Path retainedFile = databaseDirectory.resolve(RETIRED_DIRECTORY)
                    .resolve("inherited-store")
                    .resolve("legacy-state.bin");
            Files.createDirectories(retainedFile.getParent());
            Files.writeString(retainedFile, "retired\n", StandardCharsets.UTF_8);

            SQLException rejection = null;
            try {
                backupDatabase(connection, backupRoot);
            } catch (SQLException expected) {
                rejection = expected;
            }
            assertRetiredFormatRejection(rejection, "backup");
            assertFalse("rejected backup must not create a backup image",
                    Files.exists(backupRoot.resolve(databaseDirectory.getFileName())));

            deleteRecursively(databaseDirectory.resolve(RETIRED_DIRECTORY));
            backupDatabase(connection, backupRoot);
        }

        Path backupDatabase = backupRoot.resolve(databaseDirectory.getFileName());
        assertFalse("normal RawStore backup must not create a retired directory",
                Files.exists(backupDatabase.resolve(RETIRED_DIRECTORY)));
        assertFalse("normal RawStore backup must not create a retired manifest",
                Files.exists(backupDatabase.resolve(RETIRED_MANIFEST)));
        assertFalse("normal RawStore backup must not create a retired marker",
                Files.exists(backupDatabase.resolve(RETIRED_MARKER)));
        shutdownDatabase(database);
    }

    public void testRestoreRejectsEveryRetiredBackupArtifact() throws Exception {
        String sourceDatabase = databaseName("mvcc-retired-sidecar-restore-source-db");
        Path sourceDirectory = Path.of(sourceDatabase);
        Path backupRoot = Path.of(databaseName("mvcc-retired-sidecar-restore-root"));

        deleteRecursively(sourceDirectory);
        deleteRecursively(backupRoot);

        try (Connection source = openDatabase(sourceDatabase, true)) {
            executeUpdate(source,
                    "create table retired_sidecar_restore_t "
                            + "(id int primary key, name varchar(64)) using delos_mvcc");
            executeUpdate(source,
                    "insert into retired_sidecar_restore_t values (1, 'rawstore')");
            backupDatabase(source, backupRoot);
        }
        shutdownDatabase(sourceDatabase);

        Path backupDatabase = backupRoot.resolve(sourceDirectory.getFileName());
        for (RetiredArtifact artifact : RetiredArtifact.values()) {
            artifact.install(backupDatabase);
            String targetDatabase = databaseName(
                    "mvcc-retired-sidecar-restore-target-" + artifact.name().toLowerCase());
            deleteRecursively(Path.of(targetDatabase));

            SQLException rejection = null;
            try {
                DriverManager.getConnection(
                        "jdbc:derby:" + targetDatabase + ";createFrom="
                                + backupDatabase.toAbsolutePath());
            } catch (SQLException expected) {
                rejection = expected;
            }
            assertRetiredFormatRejection(rejection, "restore");
            assertFalse("restore rejection must not copy retired state into the target",
                    Files.exists(Path.of(targetDatabase).resolve(RETIRED_DIRECTORY)));
            artifact.remove(backupDatabase);
            deleteRecursively(Path.of(targetDatabase));
        }

        String restoredDatabase = databaseName("mvcc-retired-sidecar-restore-clean-target");
        deleteRecursively(Path.of(restoredDatabase));
        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom="
                        + backupDatabase.toAbsolutePath())) {
            assertRows(restored,
                    "select id, name from retired_sidecar_restore_t order by id",
                    "1|rawstore");
        }
        shutdownDatabase(restoredDatabase);
    }

    private static void assertRetiredFormatRejection(
            SQLException rejection, String operation) {
        assertNotNull("retired format must reject " + operation, rejection);
        assertTrue("expected SQLState 0A000 in: " + rejection,
                containsSqlState(rejection, "0A000"));
        assertTrue("expected retired-format message in: " + rejection,
                containsMessage(rejection, "retired external delos_mvcc format"));
    }

    private static void backupDatabase(Connection connection, Path backupRoot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "call syscs_util.syscs_backup_database(?)")) {
            statement.setString(1, backupRoot.toAbsolutePath().toString());
            statement.execute();
        }
    }

    private static boolean containsSqlState(SQLException failure, String prefix) {
        for (SQLException current = failure; current != null;
             current = current.getNextException()) {
            String sqlState = current.getSQLState();
            if (sqlState != null && sqlState.startsWith(prefix)) {
                return true;
            }
            for (Throwable cause = current.getCause(); cause != null;
                 cause = cause.getCause()) {
                if (cause instanceof SQLException nested) {
                    String nestedState = nested.getSQLState();
                    if (nestedState != null && nestedState.startsWith(prefix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted((left, right) -> Integer.compare(
                            right.getNameCount(), left.getNameCount()))
                    .forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (IOException failure) {
                            throw new DeleteFailure(failure);
                        }
                    });
        } catch (DeleteFailure failure) {
            throw failure.ioException;
        }
    }

    private enum RetiredArtifact {
        DIRECTORY {
            @Override
            void install(Path backupDatabase) throws IOException {
                Path file = backupDatabase.resolve(RETIRED_DIRECTORY)
                        .resolve("inherited-store")
                        .resolve("legacy-state.bin");
                Files.createDirectories(file.getParent());
                Files.writeString(file, "retired\n", StandardCharsets.UTF_8);
            }

            @Override
            void remove(Path backupDatabase) throws IOException {
                deleteRecursively(backupDatabase.resolve(RETIRED_DIRECTORY));
            }
        },
        MANIFEST {
            @Override
            void install(Path backupDatabase) throws IOException {
                Files.writeString(backupDatabase.resolve(RETIRED_MANIFEST),
                        "retired\n", StandardCharsets.UTF_8);
            }

            @Override
            void remove(Path backupDatabase) throws IOException {
                Files.deleteIfExists(backupDatabase.resolve(RETIRED_MANIFEST));
            }
        },
        IN_PROGRESS_MARKER {
            @Override
            void install(Path backupDatabase) throws IOException {
                Files.writeString(backupDatabase.resolve(RETIRED_MARKER),
                        "retired\n", StandardCharsets.UTF_8);
            }

            @Override
            void remove(Path backupDatabase) throws IOException {
                Files.deleteIfExists(backupDatabase.resolve(RETIRED_MARKER));
            }
        };

        abstract void install(Path backupDatabase) throws IOException;

        abstract void remove(Path backupDatabase) throws IOException;
    }

    private static final class DeleteFailure extends RuntimeException {
        private final IOException ioException;

        private DeleteFailure(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }
    }
}
