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
import java.util.zip.CRC32;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

import io.github.ggeorg.delosdb.storage.io.page.DelosPageIo;

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


    public void testSequentialHeapAndMvccTransactionsSurviveProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-mixed-commit-db");

        runCrashBoundaryWorker("commit-sequential-heap-mvcc-inserts", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_crash_commit_t order by id",
                    "1|heap-committed");
            assertRows(reopened,
                    "select id, name from mvcc_crash_mixed_commit_t order by id",
                    "1|mvcc-committed");
        }
    }


    public void testIndependentUncommittedHeapAndMvccTransactionsDoNotSurviveProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-mixed-uncommitted-db");

        runCrashBoundaryWorker("uncommitted-independent-heap-mvcc-inserts", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_crash_uncommitted_t order by id");
            assertRows(reopened,
                    "select id, name from mvcc_crash_mixed_uncommitted_t order by id");
        }
    }


    public void testHeapAndMvccCommittedWorkloadRemainEquivalentAfterProcessHalt() throws Exception {
        String databaseName = databaseName("heap-mvcc-recovery-differential-db");

        runCrashBoundaryWorker("heap-mvcc-differential-workload", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRecoveryDifferentialState(reopened, "heap_recovery_diff_t");
            assertRecoveryDifferentialState(reopened, "mvcc_recovery_diff_t");

            executeUpdate(reopened, "insert into heap_recovery_diff_t values "
                    + "(8, 'after-recovery', 'ready', 80, 'heap-after-recovery')");
            executeUpdate(reopened, "insert into mvcc_recovery_diff_t values "
                    + "(8, 'after-recovery', 'ready', 80, 'mvcc-after-recovery')");
        }

        shutdownDatabase(databaseName);

        try (Connection reopenedAgain = openDatabase(databaseName, false)) {
            assertRows(reopenedAgain,
                    "select id, code, status, quantity from heap_recovery_diff_t where id = 8",
                    "8|after-recovery|ready|80");
            assertRows(reopenedAgain,
                    "select id, code, status, quantity from mvcc_recovery_diff_t where id = 8",
                    "8|after-recovery|ready|80");
            long containerId = mvccContainerId(reopenedAgain, "MVCC_RECOVERY_DIFF_T");
            assertMvccConsistent(mvccDiagnostics(databaseName), containerId);
        }
    }

    private static void assertRecoveryDifferentialState(Connection connection, String table) throws Exception {
        assertRows(connection,
                "select id, code, status, quantity, length(payload) from " + table + " order by id",
                "1|alpha|ready|10|13",
                "2|beta-u|ready|25|1000",
                "4|delta|done|40|13",
                "5|epsilon|pending|50|15");
        assertRows(connection,
                "select id, quantity from " + table + " where code = 'beta-u'",
                "2|25");
        assertRows(connection,
                "select status, count(*), sum(quantity) from " + table
                        + " group by status order by status",
                "done|1|40",
                "pending|1|50",
                "ready|2|35");
        assertRows(connection,
                "select id from " + table + " where quantity between 20 and 60 order by quantity, id",
                "2", "4", "5");
        assertRows(connection,
                "select id from " + table + " where code in ('transient', 'uncommitted', 'rolled-back') order by id");
    }


    public void testVacuumedMvccTableRecoversWhenProcessHaltsWithStaleCheckpointMetadata() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-vacuum-checkpoint-db");

        runCrashBoundaryWorker("vacuum-stale-checkpoint", databaseName);

        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
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


    public void testCorruptMvccPageRecordBodyFailsCleanlyOnReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-page-record-header-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        Path pageFile;

        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection, "create table mvcc_page_record_header_t "
                    + "(id int primary key, name varchar(40)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_page_record_header_t values "
                    + "(1, 'alpha'), (2, 'beta')");
            long containerId = mvccContainerId(connection, "MVCC_PAGE_RECORD_HEADER_T");
            pageFile = diagnostics.pageVolumeStateFileForTesting(0, containerId);
            assertTrue("expected MVCC page file to exist: " + pageFile, Files.exists(pageFile));
            assertMvccConsistent(diagnostics, containerId);
        }
        shutdownDatabase(databaseName);

        corruptMvccPageRecordBodyButKeepPageChecksumValid(pageFile);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, name from mvcc_page_record_header_t order by id");
            fail("Expected corrupted MVCC page-record body to fail the reopened query");
        } catch (java.sql.SQLException expected) {
            assertTrue("expected page-record consistency failure, got: " + expected,
                    containsMessage(expected, "page-record")
                            || containsMessage(expected, "Invalid durable MVCC state"));
        }
    }


    public void testCorruptMvccPageChecksumFailsCleanlyOnReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-page-checksum-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
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

        corruptPageBodyAndAssertChecksumRejects(pageFile);

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

    private static void corruptMvccPageRecordBodyButKeepPageChecksumValid(Path pageFile) throws Exception {
        byte[] bytes = Files.readAllBytes(pageFile);
        assertTrue("expected at least one complete MVCC page in " + pageFile, bytes.length >= 8192);
        int pageRecordMagicOffset = indexOf(bytes, new byte[] {0x44, 0x4d, 0x50, 0x52});
        if (pageRecordMagicOffset < 0) {
            fail("could not find MVCC page-record magic in page file " + pageFile);
        }
        int bodyOffset = pageRecordMagicOffset + 24;
        assertTrue("expected MVCC page-record body to fit inside page file",
                bodyOffset >= 0 && bodyOffset < bytes.length);
        bytes[bodyOffset] ^= 0x5a;
        recomputePageChecksum(bytes, pageRecordMagicOffset);
        Files.write(pageFile, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void recomputePageChecksum(byte[] bytes, int absoluteOffsetInsidePage) {
        int pageStart = (absoluteOffsetInsidePage / 8192) * 8192;
        int checksumOffset = pageStart + 8188;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(checksumOffset, 0);
        CRC32 crc = new CRC32();
        crc.update(bytes, pageStart, 8188);
        buffer.putInt(checksumOffset, (int) crc.getValue());
    }

    private static void corruptPageBodyAndAssertChecksumRejects(Path pageFile) throws Exception {
        byte[] bytes = Files.readAllBytes(pageFile);
        assertTrue("expected at least one complete MVCC page in " + pageFile, bytes.length >= 8192);
        bytes[128] ^= 0x5a;
        Files.write(pageFile, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

        try {
            DelosPageIo.decode(java.util.Arrays.copyOf(bytes, 8192));
            fail("Expected direct MVCC page decode to reject the corrupted checksum");
        } catch (IllegalArgumentException expected) {
            assertTrue("expected checksum failure, got: " + expected,
                    containsMessage(expected, "checksum"));
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return offset;
            }
        }
        return -1;
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
                System.err.println(failureSummary(t));
                Runtime.getRuntime().halt(2);
            }
        }

        private static String failureSummary(Throwable failure) {
            StringBuilder builder = new StringBuilder();
            builder.append(failure.getClass().getName());
            String message = failure.getMessage();
            if (message != null && !message.isBlank()) {
                builder.append(": ").append(message);
            }
            for (StackTraceElement element : failure.getStackTrace()) {
                builder.append(System.lineSeparator()).append("    at ").append(element);
            }
            Throwable cause = failure.getCause();
            while (cause != null) {
                builder.append(System.lineSeparator()).append("Caused by: ").append(cause.getClass().getName());
                String causeMessage = cause.getMessage();
                if (causeMessage != null && !causeMessage.isBlank()) {
                    builder.append(": ").append(causeMessage);
                }
                cause = cause.getCause();
            }
            return builder.toString();
        }

        private static void runScenario(String scenario, String databaseName) throws Exception {
            switch (scenario) {
            case "commit-mvcc-insert":
                commitMvccInsert(databaseName);
                break;
            case "uncommitted-mvcc-insert":
                uncommittedMvccInsert(databaseName);
                break;
            case "commit-sequential-heap-mvcc-inserts":
                commitSequentialHeapMvccInserts(databaseName);
                break;
            case "uncommitted-independent-heap-mvcc-inserts":
                uncommittedIndependentHeapMvccInserts(databaseName);
                break;
            case "vacuum-stale-checkpoint":
                vacuumWithStaleCheckpoint(databaseName);
                break;
            case "heap-mvcc-differential-workload":
                heapMvccDifferentialWorkload(databaseName);
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

        private static void commitSequentialHeapMvccInserts(String databaseName) throws Exception {
            Connection connection = openDatabase(databaseName, true);
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_crash_commit_t (id int primary key, name varchar(32))");
            executeUpdate(connection, "create table mvcc_crash_mixed_commit_t (id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();
            executeUpdate(connection, "insert into heap_crash_commit_t values (1, 'heap-committed')");
            connection.commit();
            executeUpdate(connection, "insert into mvcc_crash_mixed_commit_t values (1, 'mvcc-committed')");
            connection.commit();
            Runtime.getRuntime().halt(0);
        }


        private static void heapMvccDifferentialWorkload(String databaseName) throws Exception {
            try (Connection setup = openDatabase(databaseName, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup, "create table heap_recovery_diff_t "
                        + "(id int primary key, code varchar(32), status varchar(16), quantity int, "
                        + "payload varchar(1200))");
                executeUpdate(setup, "create table mvcc_recovery_diff_t "
                        + "(id int primary key, code varchar(32), status varchar(16), quantity int, "
                        + "payload varchar(1200)) using delos_mvcc");
                executeUpdate(setup, "create index heap_recovery_diff_code_idx on heap_recovery_diff_t(code)");
                executeUpdate(setup, "create index mvcc_recovery_diff_code_idx on mvcc_recovery_diff_t(code)");
                executeUpdate(setup, "create index heap_recovery_diff_status_qty_idx "
                        + "on heap_recovery_diff_t(status, quantity)");
                executeUpdate(setup, "create index mvcc_recovery_diff_status_qty_idx "
                        + "on mvcc_recovery_diff_t(status, quantity)");
                setup.commit();
            }

            Connection heapConnection = openDatabase(databaseName, false);
            Connection mvccConnection = openDatabase(databaseName, false);
            heapConnection.setAutoCommit(false);
            mvccConnection.setAutoCommit(false);

            seedRecoveryDifferentialTable(heapConnection, "heap_recovery_diff_t");
            heapConnection.commit();
            seedRecoveryDifferentialTable(mvccConnection, "mvcc_recovery_diff_t");
            mvccConnection.commit();

            applyCommittedRecoveryMutations(heapConnection, "heap_recovery_diff_t");
            heapConnection.commit();
            applyCommittedRecoveryMutations(mvccConnection, "mvcc_recovery_diff_t");
            mvccConnection.commit();

            java.sql.Savepoint heapSavepoint = heapConnection.setSavepoint("HEAP_RECOVERY_DIFF_ROLLBACK");
            java.sql.Savepoint mvccSavepoint = mvccConnection.setSavepoint("MVCC_RECOVERY_DIFF_ROLLBACK");
            executeUpdate(heapConnection, "update heap_recovery_diff_t set code = 'transient' where id = 1");
            executeUpdate(mvccConnection, "update mvcc_recovery_diff_t set code = 'transient' where id = 1");
            executeUpdate(heapConnection, "insert into heap_recovery_diff_t values "
                    + "(6, 'rolled-back', 'ready', 60, 'rolled-back')");
            executeUpdate(mvccConnection, "insert into mvcc_recovery_diff_t values "
                    + "(6, 'rolled-back', 'ready', 60, 'rolled-back')");
            heapConnection.rollback(heapSavepoint);
            mvccConnection.rollback(mvccSavepoint);
            heapConnection.commit();
            mvccConnection.commit();

            executeUpdate(heapConnection, "update heap_recovery_diff_t set code = 'uncommitted' where id = 4");
            executeUpdate(mvccConnection, "update mvcc_recovery_diff_t set code = 'uncommitted' where id = 4");
            executeUpdate(heapConnection, "delete from heap_recovery_diff_t where id = 1");
            executeUpdate(mvccConnection, "delete from mvcc_recovery_diff_t where id = 1");
            executeUpdate(heapConnection, "insert into heap_recovery_diff_t values "
                    + "(7, 'uncommitted', 'pending', 70, 'uncommitted')");
            executeUpdate(mvccConnection, "insert into mvcc_recovery_diff_t values "
                    + "(7, 'uncommitted', 'pending', 70, 'uncommitted')");
            Runtime.getRuntime().halt(0);
        }

        private static void seedRecoveryDifferentialTable(Connection connection, String table) throws Exception {
            executeUpdate(connection, "insert into " + table + " values "
                    + "(1, 'alpha', 'ready', 10, 'alpha-payload'), "
                    + "(2, 'beta', 'pending', 20, 'beta-payload'), "
                    + "(3, 'gamma', 'ready', 30, 'gamma-payload'), "
                    + "(4, 'delta', 'done', 40, 'delta-payload')");
        }

        private static void applyCommittedRecoveryMutations(Connection connection, String table) throws Exception {
            String largePayload = "x".repeat(1000);
            executeUpdate(connection, "update " + table + " set code = 'beta-u', status = 'ready', "
                    + "quantity = 25, payload = '" + largePayload + "' where id = 2");
            executeUpdate(connection, "delete from " + table + " where id = 3");
            executeUpdate(connection, "insert into " + table + " values "
                    + "(5, 'epsilon', 'pending', 50, 'epsilon-payload')");
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
                DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
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

        private static void uncommittedIndependentHeapMvccInserts(String databaseName) throws Exception {
            try (Connection setup = openDatabase(databaseName, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup, "create table heap_crash_uncommitted_t (id int primary key, name varchar(32))");
                executeUpdate(setup, "create table mvcc_crash_mixed_uncommitted_t (id int primary key, name varchar(32)) using delos_mvcc");
                setup.commit();
            }

            Connection heapConnection = openDatabase(databaseName, false);
            Connection mvccConnection = openDatabase(databaseName, false);
            heapConnection.setAutoCommit(false);
            mvccConnection.setAutoCommit(false);
            executeUpdate(heapConnection, "insert into heap_crash_uncommitted_t values (1, 'heap-uncommitted')");
            executeUpdate(mvccConnection, "insert into mvcc_crash_mixed_uncommitted_t values (1, 'mvcc-uncommitted')");
            Runtime.getRuntime().halt(0);
        }
    }

}
