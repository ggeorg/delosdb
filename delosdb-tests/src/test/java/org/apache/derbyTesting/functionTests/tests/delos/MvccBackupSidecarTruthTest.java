/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccBackupSidecarTruthTest

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
import java.util.List;

/**
 * Runtime truth test for the retained {@code delos_mvcc} backup sidecar path.
 *
 * <p>The test separates three facts which must not be conflated:</p>
 *
 * <ol>
 *   <li>Current RawStore-backed MVCC durable state is backed up through normal
 *       Derby RawStore files and does not create a sidecar directory.</li>
 *   <li>If external retained state is injected, the sidecar helper still copies
 *       it and verifies it through a manifest.</li>
 *   <li>A database containing current MVCC catalog state does not consume that
 *       retained external state; boot fails closed with the retired-format
 *       rejection.</li>
 * </ol>
 */
public final class MvccBackupSidecarTruthTest extends MvccSqlTestSupport {
    private static final String SIDECAR_DIRECTORY = "delos_mvcc";
    private static final String BACKUP_MANIFEST = "delos_mvcc.BACKUP-MANIFEST";
    private static final String BACKUP_IN_PROGRESS = "delos_mvcc.BACKUP-IN-PROGRESS";

    public void testCurrentAndRetainedSidecarBackupTruth() throws Exception {
        CurrentLaneResult currentLane = verifyCurrentRawStoreLane();
        RetainedTransportResult retainedTransport = verifyRetainedSidecarTransport();
        String rejectedSqlState = verifyCurrentMvccRejectsRetainedExternalState();

        String report = "DelosDB MVCC backup sidecar truth\n"
                + "==================================\n"
                + "Current source sidecar directory: " + currentLane.sourceSidecarPresent() + "\n"
                + "Current backup sidecar directory: " + currentLane.backupSidecarPresent() + "\n"
                + "Current backup manifest: " + currentLane.backupManifestPresent() + "\n"
                + "Current backup RawStore files: " + currentLane.rawStoreFileCount() + "\n"
                + "Current restore data/container proof: PASS\n"
                + "Injected retained files copied to backup: "
                + retainedTransport.backupSidecarFileCount() + "\n"
                + "Injected retained files restored: "
                + retainedTransport.restoredSidecarFileCount() + "\n"
                + "Injected retained manifest fileCount: "
                + retainedTransport.manifestFileCount() + "\n"
                + "Current MVCC boot with retained external state SQLState: "
                + rejectedSqlState + "\n"
                + "Conclusion: CURRENT_DURABILITY_IS_RAWSTORE; "
                + "SIDECAR_IS_RETAINED_STATE_TRANSPORT_AND_FAIL_CLOSED_BOUNDARY\n";
        System.out.print(report);
        writeReport(report);
    }

    private CurrentLaneResult verifyCurrentRawStoreLane() throws Exception {
        String sourceDatabase = databaseName("mvcc-sidecar-truth-current-source-db");
        String restoredDatabase = databaseName("mvcc-sidecar-truth-current-restored-db");
        Path backupRoot = Path.of(databaseName("mvcc-sidecar-truth-current-backup-root"));
        Path sourceDirectory = Path.of(sourceDatabase);

        deleteRecursively(sourceDirectory);
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        long sourceContainerId;
        try (Connection source = openDatabase(sourceDatabase, true)) {
            source.setAutoCommit(false);
            executeUpdate(source,
                    "create table mvcc_sidecar_truth_t "
                            + "(id int primary key, name varchar(64)) using delos_mvcc");
            executeUpdate(source,
                    "insert into mvcc_sidecar_truth_t values (1, 'rawstore-authority')");
            source.commit();
            source.setAutoCommit(true);
            sourceContainerId = mvccContainerId(source, "MVCC_SIDECAR_TRUTH_T");

            assertFalse("current RawStore-backed MVCC must not create a sidecar directory",
                    Files.exists(sourceDirectory.resolve(SIDECAR_DIRECTORY)));
            backupDatabase(source, backupRoot);
        }

        Path backupDatabase = backupRoot.resolve(sourceDirectory.getFileName());
        long rawStoreFiles = regularFileCount(backupDatabase.resolve("seg0"));
        assertTrue("current MVCC backup must contain RawStore segment files", rawStoreFiles > 0L);
        assertFalse("current MVCC backup must not contain a sidecar directory",
                Files.exists(backupDatabase.resolve(SIDECAR_DIRECTORY)));
        assertFalse("current MVCC backup must not contain a sidecar manifest",
                Files.exists(backupDatabase.resolve(BACKUP_MANIFEST)));
        assertFalse("current MVCC backup must not retain an in-progress marker",
                Files.exists(backupDatabase.resolve(BACKUP_IN_PROGRESS)));

        shutdownDatabase(sourceDatabase);
        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom="
                        + backupDatabase.toAbsolutePath())) {
            assertRows(restored,
                    "select id, name from mvcc_sidecar_truth_t order by id",
                    "1|rawstore-authority");
            assertEquals(sourceContainerId,
                    mvccContainerId(restored, "MVCC_SIDECAR_TRUTH_T"));
            assertConglomeratePresent(restored, sourceContainerId);
        }
        shutdownDatabase(restoredDatabase);

        return new CurrentLaneResult(false, false, false, rawStoreFiles);
    }

    private RetainedTransportResult verifyRetainedSidecarTransport() throws Exception {
        String sourceDatabase = databaseName("mvcc-sidecar-truth-retained-heap-source-db");
        String restoredDatabase = databaseName("mvcc-sidecar-truth-retained-heap-restored-db");
        Path backupRoot = Path.of(databaseName("mvcc-sidecar-truth-retained-heap-backup-root"));
        Path sourceDirectory = Path.of(sourceDatabase);

        deleteRecursively(sourceDirectory);
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        byte[] retainedContents = "retained-external-state\n".getBytes(StandardCharsets.UTF_8);
        Path relativeRetainedFile = Path.of("inherited-store", "legacy-state.bin");
        try (Connection source = openDatabase(sourceDatabase, true)) {
            executeUpdate(source,
                    "create table heap_sidecar_transport_t (id int primary key, name varchar(64))");
            executeUpdate(source,
                    "insert into heap_sidecar_transport_t values (1, 'heap-catalog')");

            Path retainedFile = sourceDirectory.resolve(SIDECAR_DIRECTORY)
                    .resolve(relativeRetainedFile);
            Files.createDirectories(retainedFile.getParent());
            Files.write(retainedFile, retainedContents);
            backupDatabase(source, backupRoot);
        }

        Path backupDatabase = backupRoot.resolve(sourceDirectory.getFileName());
        Path backupRetainedFile = backupDatabase.resolve(SIDECAR_DIRECTORY)
                .resolve(relativeRetainedFile);
        assertTrue("injected retained state must be copied to the backup",
                Files.isRegularFile(backupRetainedFile));
        assertTrue("retained backup contents must be preserved",
                java.util.Arrays.equals(retainedContents, Files.readAllBytes(backupRetainedFile)));
        assertFalse("completed retained-state backup must remove its in-progress marker",
                Files.exists(backupDatabase.resolve(BACKUP_IN_PROGRESS)));

        Path manifest = backupDatabase.resolve(BACKUP_MANIFEST);
        assertTrue("retained-state backup must contain a manifest", Files.isRegularFile(manifest));
        List<String> manifestLines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        assertTrue(manifestLines.contains("version=3"));
        assertTrue(manifestLines.contains("copyMode=coordinated-durable-mutation-boundary"));
        assertTrue(manifestLines.contains("directory=" + SIDECAR_DIRECTORY));
        long manifestFileCount = manifestLong(manifestLines, "fileCount");
        assertEquals(1L, manifestFileCount);
        assertEquals(retainedContents.length, manifestLong(manifestLines, "totalBytes"));
        assertTrue("manifest must contain a SHA-256 digest",
                manifestLines.stream().anyMatch(line -> line.matches("digest=[0-9a-f]{64}")));

        shutdownDatabase(sourceDatabase);
        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom="
                        + backupDatabase.toAbsolutePath())) {
            assertRows(restored,
                    "select id, name from heap_sidecar_transport_t order by id",
                    "1|heap-catalog");
        }
        shutdownDatabase(restoredDatabase);

        Path restoredRetainedFile = Path.of(restoredDatabase)
                .resolve(SIDECAR_DIRECTORY)
                .resolve(relativeRetainedFile);
        assertTrue("restore must copy retained external state",
                Files.isRegularFile(restoredRetainedFile));
        assertTrue("restored retained contents must be preserved",
                java.util.Arrays.equals(retainedContents, Files.readAllBytes(restoredRetainedFile)));

        return new RetainedTransportResult(
                regularFileCount(backupDatabase.resolve(SIDECAR_DIRECTORY)),
                regularFileCount(Path.of(restoredDatabase).resolve(SIDECAR_DIRECTORY)),
                manifestFileCount);
    }

    private String verifyCurrentMvccRejectsRetainedExternalState() throws Exception {
        String sourceDatabase = databaseName("mvcc-sidecar-truth-reject-source-db");
        String restoredDatabase = databaseName("mvcc-sidecar-truth-reject-restored-db");
        Path backupRoot = Path.of(databaseName("mvcc-sidecar-truth-reject-backup-root"));
        Path sourceDirectory = Path.of(sourceDatabase);

        deleteRecursively(sourceDirectory);
        deleteRecursively(Path.of(restoredDatabase));
        deleteRecursively(backupRoot);

        try (Connection source = openDatabase(sourceDatabase, true)) {
            executeUpdate(source,
                    "create table mvcc_sidecar_reject_t "
                            + "(id int primary key, name varchar(64)) using delos_mvcc");
            executeUpdate(source,
                    "insert into mvcc_sidecar_reject_t values (1, 'current-catalog')");

            Path retainedFile = sourceDirectory.resolve(SIDECAR_DIRECTORY)
                    .resolve("inherited-store")
                    .resolve("legacy-state.bin");
            Files.createDirectories(retainedFile.getParent());
            Files.writeString(retainedFile, "retired-format-state\n", StandardCharsets.UTF_8);
            backupDatabase(source, backupRoot);
        }
        shutdownDatabase(sourceDatabase);

        Path backupDatabase = backupRoot.resolve(sourceDirectory.getFileName());
        SQLException rejection = null;
        try (Connection restored = DriverManager.getConnection(
                "jdbc:derby:" + restoredDatabase + ";createFrom="
                        + backupDatabase.toAbsolutePath())) {
            try {
                assertRows(restored,
                        "select id, name from mvcc_sidecar_reject_t order by id",
                        "1|current-catalog");
            } catch (SQLException expected) {
                rejection = expected;
            }
        } catch (SQLException expected) {
            rejection = expected;
        }

        try {
            assertNotNull("current MVCC boot must reject retained external delos_mvcc state",
                    rejection);
            String sqlState = findSqlStatePrefix(rejection, "0A000");
            assertNotNull("expected NOT_IMPLEMENTED SQLState in exception chain: " + rejection,
                    sqlState);
            assertTrue("expected retained external-state rejection: " + rejection,
                    containsMessage(rejection,
                            "retained external delos_mvcc format has been retired"));
            return sqlState;
        } finally {
            shutdownIfBooted(restoredDatabase);
            deleteRecursively(Path.of(restoredDatabase));
        }
    }

    private static void shutdownIfBooted(String databaseName) {
        try {
            DriverManager.getConnection("jdbc:derby:" + databaseName + ";shutdown=true");
        } catch (SQLException expectedOrNotBooted) {
            // This is cleanup after an expected boot rejection; the database may never have opened.
        }
    }

    private static void backupDatabase(Connection connection, Path backupRoot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "call syscs_util.syscs_backup_database(?)")) {
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

    private static long manifestLong(List<String> lines, String key) {
        String prefix = key + "=";
        return lines.stream()
                .filter(line -> line.startsWith(prefix))
                .mapToLong(line -> Long.parseLong(line.substring(prefix.length())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing manifest field " + key));
    }

    private static String findSqlStatePrefix(SQLException failure, String prefix) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            String sqlState = current.getSQLState();
            if (sqlState != null && sqlState.startsWith(prefix)) {
                return sqlState;
            }
            for (Throwable cause = current.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException nested) {
                    String nestedState = nested.getSQLState();
                    if (nestedState != null && nestedState.startsWith(prefix)) {
                        return nestedState;
                    }
                }
            }
        }
        return null;
    }

    private static void writeReport(String report) throws IOException {
        String reportPath = System.getProperty("delosdb.mvccBackupSidecarTruth.report");
        if (reportPath == null || reportPath.isBlank()) {
            return;
        }
        Path target = Path.of(reportPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, report, StandardCharsets.UTF_8);
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
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException failure) {
            if (failure.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw failure;
        }
    }

    private record CurrentLaneResult(
            boolean sourceSidecarPresent,
            boolean backupSidecarPresent,
            boolean backupManifestPresent,
            long rawStoreFileCount) {
    }

    private record RetainedTransportResult(
            long backupSidecarFileCount,
            long restoredSidecarFileCount,
            long manifestFileCount) {
    }
}
