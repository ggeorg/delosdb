/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlRecoveryTest

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

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL integration tests for delos_mvcc recovery behavior. */
public final class MvccSqlRecoveryTest extends MvccSqlTestSupport {
    public void testCommittedMvccInsertSurvivesProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-commit-db");

        runCrashBoundaryWorker("commit-mvcc-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_crash_commit_t order by id",
                    "1|committed-before-halt");
        }
    }


    public void testUncommittedMvccInsertDoesNotSurviveProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-uncommitted-db");

        runCrashBoundaryWorker("uncommitted-mvcc-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_crash_uncommitted_t order by id");
        }
    }


    public void testCommittedHeapAndMvccTransactionSurvivesProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-mixed-commit-db");

        runCrashBoundaryWorker("commit-mixed-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_crash_commit_t order by id",
                    "1|heap-committed");
            assertRows(reopened,
                    "select id, name from mvcc_crash_mixed_commit_t order by id",
                    "1|mvcc-committed");
        }
    }


    public void testUncommittedHeapAndMvccTransactionDoesNotSurviveProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-mixed-uncommitted-db");

        runCrashBoundaryWorker("uncommitted-mixed-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_crash_uncommitted_t order by id");
            assertRows(reopened,
                    "select id, name from mvcc_crash_mixed_uncommitted_t order by id");
        }
    }




    public void testVacuumedMvccTableRecoversWhenProcessHaltsWithStaleCheckpointMetadata() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-vacuum-checkpoint-db");

        runCrashBoundaryWorker("vacuum-stale-checkpoint", databaseName);

        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        try (Connection reopened = openDatabase(databaseName, false)) {
            long containerId = mvccContainerId(reopened, "MVCC_CRASH_VACUUM_T");
            assertRows(reopened,
                    "select id, name, tag from mvcc_crash_vacuum_t order by id",
                    "1|alpha-v3|green",
                    "3|gamma-v2|green",
                    "4|delta|blue");
            assertRows(reopened,
                    "select id, name from mvcc_crash_vacuum_t --DERBY-PROPERTIES index=mvcc_crash_vacuum_tag_idx\n "
                            + "where tag = 'green' order by id",
                    "1|alpha-v3",
                    "3|gamma-v2");
            assertEquals("stale checkpoint metadata should force recovery to validate durable page state directly",
                    "FALLBACK", diagnostics.checkpointStatusForTesting(0, containerId));
            assertEquals("vacuum should leave exactly the three visible rows as durable versions",
                    3, diagnostics.physicalVersionCountForTesting(0, containerId));
            assertEquals("vacuum should preserve the three logical survivors",
                    3, diagnostics.logicalRowCountForTesting(0, containerId));
            assertMvccConsistent(diagnostics, containerId);
        }
    }


    public void testCorruptMvccPageChecksumFailsCleanlyOnReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-page-checksum-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        Path pageFile;

        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection, "create table mvcc_page_checksum_t "
                    + "(id int primary key, name varchar(40)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_page_checksum_t values "
                    + "(1, 'alpha'), (2, 'beta')");
            long containerId = mvccContainerId(connection, "MVCC_PAGE_CHECKSUM_T");
            pageFile = diagnostics.pageVolumeStateFileForTesting(0, containerId);
            assertTrue("expected MVCC page file to exist: " + pageFile, Files.exists(pageFile));
            assertMvccConsistent(diagnostics, containerId);
        }
        shutdownDatabase(databaseName);

        byte[] bytes = Files.readAllBytes(pageFile);
        bytes[128] ^= 0x5a;
        Files.write(pageFile, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, name from mvcc_page_checksum_t order by id");
            fail("Expected corrupted MVCC page checksum to fail the reopened query");
        } catch (java.sql.SQLException expected) {
            assertTrue("expected checksum/corruption failure, got: " + expected,
                    containsMessage(expected, "checksum") || containsMessage(expected, "corrupt"));
        }
    }

    private static void runCrashBoundaryWorker(String scenario, String databaseName) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(classpath);
        command.add(CrashBoundaryWorker.class.getName());
        command.add(scenario);
        command.add(databaseName);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals("Crash-boundary worker failed. Output:\n" + output, 0, exitCode);
    }



    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        String summary = diagnostics.consistencySummaryForTesting(0, containerId);
        assertEquals("expected valid durable MVCC state, got " + summary,
                0, diagnostics.consistencyErrorCountForTesting(0, containerId));
        diagnostics.assertConsistentForTesting(0, containerId);
    }

    public static final class CrashBoundaryWorker {
        public static void main(String[] args) {
            try {
                if (args.length != 2) {
                    throw new IllegalArgumentException("expected scenario and database name");
                }
                runScenario(args[0], args[1]);
                Runtime.getRuntime().halt(0);
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                Runtime.getRuntime().halt(2);
            }
        }

        private static void runScenario(String scenario, String databaseName) throws Exception {
            switch (scenario) {
            case "commit-mvcc-insert":
                commitMvccInsert(databaseName);
                break;
            case "uncommitted-mvcc-insert":
                uncommittedMvccInsert(databaseName);
                break;
            case "commit-mixed-insert":
                commitMixedInsert(databaseName);
                break;
            case "uncommitted-mixed-insert":
                uncommittedMixedInsert(databaseName);
                break;
            case "vacuum-stale-checkpoint":
                vacuumWithStaleCheckpoint(databaseName);
                break;
            default:
                throw new IllegalArgumentException("unknown crash-boundary scenario: " + scenario);
            }
        }

        private static void commitMvccInsert(String databaseName) throws Exception {
            Connection connection = openDatabase(databaseName, true);
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_crash_commit_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_crash_commit_t values (1, 'committed-before-halt')");
            connection.commit();
            Runtime.getRuntime().halt(0);
        }

        private static void uncommittedMvccInsert(String databaseName) throws Exception {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table mvcc_crash_uncommitted_t (id int primary key, name varchar(32)) using delos_mvcc");
                connection.commit();
                executeUpdate(connection, "insert into mvcc_crash_uncommitted_t values (1, 'uncommitted-before-halt')");
                Runtime.getRuntime().halt(0);
            }
        }

        private static void commitMixedInsert(String databaseName) throws Exception {
            Connection connection = openDatabase(databaseName, true);
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_crash_commit_t (id int primary key, name varchar(32))");
            executeUpdate(connection, "create table mvcc_crash_mixed_commit_t (id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();
            executeUpdate(connection, "insert into heap_crash_commit_t values (1, 'heap-committed')");
            executeUpdate(connection, "insert into mvcc_crash_mixed_commit_t values (1, 'mvcc-committed')");
            connection.commit();
            Runtime.getRuntime().halt(0);
        }



        private static void vacuumWithStaleCheckpoint(String databaseName) throws Exception {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table mvcc_crash_vacuum_t "
                        + "(id int primary key, name varchar(32), tag varchar(16)) using delos_mvcc");
                executeUpdate(connection, "create index mvcc_crash_vacuum_tag_idx on mvcc_crash_vacuum_t(tag)");
                executeUpdate(connection, "insert into mvcc_crash_vacuum_t values (1, 'alpha', 'blue')");
                executeUpdate(connection, "insert into mvcc_crash_vacuum_t values (2, 'beta', 'red')");
                executeUpdate(connection, "insert into mvcc_crash_vacuum_t values (3, 'gamma', 'blue')");
                executeUpdate(connection, "insert into mvcc_crash_vacuum_t values (4, 'delta', 'blue')");
                connection.commit();

                requireOneRow(executeUpdate(connection,
                        "update mvcc_crash_vacuum_t set name = 'alpha-v2', tag = 'green' where id = 1"),
                        "alpha-v2 update");
                connection.commit();
                requireOneRow(executeUpdate(connection,
                        "update mvcc_crash_vacuum_t set name = 'alpha-v3' where id = 1"),
                        "alpha-v3 update");
                requireOneRow(executeUpdate(connection,
                        "update mvcc_crash_vacuum_t set name = 'gamma-v2', tag = 'green' where id = 3"),
                        "gamma-v2 update");
                requireOneRow(executeUpdate(connection,
                        "delete from mvcc_crash_vacuum_t where id = 2"),
                        "delete beta");
                connection.commit();

                long containerId = mvccContainerId(connection, "MVCC_CRASH_VACUUM_T");
                DelosStorageDiagnostics diagnostics = mvccDiagnostics();
                if (diagnostics.physicalVersionCountForTesting(0, containerId)
                        <= diagnostics.logicalRowCountForTesting(0, containerId)) {
                    throw new IllegalStateException("expected superseded versions before vacuum");
                }
                Path checkpoint = diagnostics.checkpointFileForTesting(0, containerId);
                if (checkpoint == null || !Files.exists(checkpoint)) {
                    throw new IllegalStateException("expected inherited MVCC checkpoint file: " + checkpoint);
                }
                byte[] staleCheckpoint = Files.readAllBytes(checkpoint);

                inPlaceCompressTable(connection, "MVCC_CRASH_VACUUM_T");
                connection.commit();

                if (diagnostics.physicalVersionCountForTesting(0, containerId) != 3) {
                    throw new IllegalStateException("vacuum should reduce the page-volume to the three visible survivors");
                }
                if (diagnostics.logicalRowCountForTesting(0, containerId) != 3) {
                    throw new IllegalStateException("vacuum should keep three logical survivors");
                }
                diagnostics.assertConsistentForTesting(0, containerId);

                writeAndForce(checkpoint, staleCheckpoint);
                Runtime.getRuntime().halt(0);
            }
        }





        private static void requireOneRow(int updatedRows, String action) {
            if (updatedRows != 1) {
                throw new IllegalStateException(action + " should affect one row, got " + updatedRows);
            }
        }

        private static void writeAndForce(Path path, byte[] bytes) throws Exception {
            try (FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
        }

        private static void uncommittedMixedInsert(String databaseName) throws Exception {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table heap_crash_uncommitted_t (id int primary key, name varchar(32))");
                executeUpdate(connection, "create table mvcc_crash_mixed_uncommitted_t (id int primary key, name varchar(32)) using delos_mvcc");
                connection.commit();
                executeUpdate(connection, "insert into heap_crash_uncommitted_t values (1, 'heap-uncommitted')");
                executeUpdate(connection, "insert into mvcc_crash_mixed_uncommitted_t values (1, 'mvcc-uncommitted')");
                Runtime.getRuntime().halt(0);
            }
        }
    }

}
