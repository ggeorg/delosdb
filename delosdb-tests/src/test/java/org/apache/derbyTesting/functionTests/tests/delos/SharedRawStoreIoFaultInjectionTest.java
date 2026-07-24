/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.SharedRawStoreIoFaultInjectionTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.impl.store.raw.data.RawStoreIoFaultInjectionTestSupport;

/** Stage 8.3 deterministic shared RawStore I/O fault and replay proofs. */
public final class SharedRawStoreIoFaultInjectionTest extends MvccSqlTestSupport {
    private static final String RAWSTORE_ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final int HALT_STATUS = 93;

    public void testControllerIsExactSeededBoundedAndDisabledByDefault()
            throws Exception {
        RawStoreIoFaultInjectionTestSupport.ControllerProof proof =
                RawStoreIoFaultInjectionTestSupport
                        .exerciseDeterministicController();
        assertTrue(proof.exactOccurrenceFailed());
        assertEquals(3L, proof.exactHits());
        assertEquals(1L, proof.exactInjectedIoFailures());
        assertTrue(proof.seededSelectionStable());
        assertEquals(proof.maximumRecordedHits(), proof.retainedBoundedHits());
        assertEquals(44L, proof.discardedBoundedHits());
        assertEquals(300L, proof.totalBoundedHits());
        assertEquals(0L, proof.disabledHits());
        assertEquals(0L, proof.disabledInjectedIoFailures());
        assertEquals(1, proof.registryVersion());
    }

    public void testReplayManifestIsStrictAndRoundTrips() {
        String digest = "0".repeat(64);
        DelosRawStoreIoFailureReplayManifest manifest =
                new DelosRawStoreIoFailureReplayManifest(
                        DelosRawStoreIoFailureReplayManifest
                                .CURRENT_SCHEMA_VERSION,
                        1,
                        "stage-8.3-source",
                        "jdk-25-test-runtime",
                        83L,
                        "file:/stage8.3-manifest-proof",
                        "single-process/file-database/heap+mvcc",
                        "AFTER_FORCE_METADATA#1:HALT(93)",
                        "reopen state matches the committed canonical state",
                        digest,
                        digest,
                        2,
                        1L);
        assertTrue(manifest.matchesExpectedState());
        assertEquals(manifest,
                DelosRawStoreIoFailureReplayManifest.parse(manifest.toText()));

        try {
            DelosRawStoreIoFailureReplayManifest.parse(
                    manifest.toText() + "unknown=value\n");
            fail("unknown replay manifest keys must fail closed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unknown"));
        }
        try {
            new DelosRawStoreIoFailureReplayManifest(
                    1,
                    1,
                    "stage-8.3-source",
                    "jdk-25-test-runtime",
                    83L,
                    "file:/stage8.3-manifest-proof",
                    "single-process/file-database/heap+mvcc",
                    "AFTER_FORCE_METADATA#1:HALT(93)",
                    "reopen state matches the committed canonical state",
                    "A".repeat(64),
                    digest,
                    2,
                    1L);
            fail("non-canonical SHA-256 digests must fail closed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
    }

    public void testDatabaseSchedulesAreIsolatedAndNormalApplicationsStayDisabled()
            throws Exception {
        Path first = databasePath(databaseName("shared-io-fault-isolation-a"));
        Path second = databasePath(databaseName("shared-io-fault-isolation-b"));
        deleteRecursively(first);
        deleteRecursively(second);

        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection firstConnection = openDatabase(first.toString(), true);
                 Connection secondConnection = openDatabase(second.toString(), true)) {
                executeUpdate(firstConnection,
                        "create table isolation_a (id int primary key, value int)");
                executeUpdate(secondConnection,
                        "create table isolation_b (id int primary key, value int)");
                firstConnection.commit();
                secondConnection.commit();

                String firstIdentity = snapshot(first).databaseIdentity();
                String secondIdentity = snapshot(second).databaseIdentity();
                assertFalse(firstIdentity.equals(secondIdentity));

                RawStoreIoFaultInjectionTestSupport.Evidence firstDisabled =
                        RawStoreIoFaultInjectionTestSupport.evidence(firstIdentity);
                RawStoreIoFaultInjectionTestSupport.Evidence secondDisabled =
                        RawStoreIoFaultInjectionTestSupport.evidence(secondIdentity);
                assertEquals("disabled", firstDisabled.scheduleId());
                assertEquals("disabled", secondDisabled.scheduleId());
                assertEquals(0, firstDisabled.scheduledSteps());
                assertEquals(0, secondDisabled.scheduledSteps());

                RawStoreIoFaultInjectionTestSupport.installThrow(
                        firstIdentity,
                        "database-a-only",
                        "BEFORE_PAGE_WRITE",
                        1000L);
                RawStoreIoFaultInjectionTestSupport.Evidence firstArmed =
                        RawStoreIoFaultInjectionTestSupport.evidence(firstIdentity);
                RawStoreIoFaultInjectionTestSupport.Evidence secondStillDisabled =
                        RawStoreIoFaultInjectionTestSupport.evidence(secondIdentity);
                assertEquals("database-a-only", firstArmed.scheduleId());
                assertEquals(1, firstArmed.scheduledSteps());
                assertEquals("disabled", secondStillDisabled.scheduleId());
                assertEquals(0, secondStillDisabled.scheduledSteps());

                executeUpdate(secondConnection,
                        "insert into isolation_b values (1, 10)");
                secondConnection.commit();
                executeUpdate(secondConnection,
                        "call syscs_util.syscs_checkpoint_database()");
                assertEquals(0L,
                        RawStoreIoFaultInjectionTestSupport
                                .evidence(secondIdentity).totalHits());

                RawStoreIoFaultInjectionTestSupport.clear(firstIdentity);
                assertEquals("disabled",
                        RawStoreIoFaultInjectionTestSupport
                                .evidence(firstIdentity).scheduleId());
            } finally {
                shutdownIfBooted(first.toString());
                shutdownIfBooted(second.toString());
            }
        } finally {
            deleteRecursively(first);
            deleteRecursively(second);
        }
    }

    public void testAfterMetadataForceHaltReplaysCommittedHeapAndMvccState()
            throws Exception {
        Path database = databasePath(databaseName("shared-io-fault-halt-replay"));
        deleteRecursively(database);
        String expectedCanonical =
                "heap=1|10,2|20;mvcc=1|100,2|200";
        String expectedDigest = sha256(expectedCanonical);

        try {
            try (SystemPropertyScope ignored =
                         setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
                try (Connection setup = openDatabase(database.toString(), true)) {
                    setup.setAutoCommit(false);
                    executeUpdate(setup,
                            "create table io_fault_heap_t "
                                    + "(id int primary key, value int)");
                    executeUpdate(setup,
                            "create table io_fault_mvcc_t "
                                    + "(id int primary key, value int) using delos_mvcc");
                    executeUpdate(setup,
                            "insert into io_fault_heap_t values (1, 10)");
                    executeUpdate(setup,
                            "insert into io_fault_mvcc_t values (1, 100)");
                    setup.commit();
                }
                shutdownDatabase(database.toString());
            }

            Process worker = new ProcessBuilder(
                    javaExecutable(),
                    "-D" + RAWSTORE_ENABLED_PROPERTY + "=true",
                    "-cp",
                    System.getProperty("java.class.path"),
                    HaltWorker.class.getName(),
                    database.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = worker.waitFor(
                    Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                worker.destroyForcibly();
                fail("RawStore I/O halt worker did not terminate");
            }
            String workerOutput = new String(
                    worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(
                    "worker must halt after a completed metadata force; output="
                            + workerOutput,
                    HALT_STATUS,
                    worker.exitValue());

            String firstObserved;
            String databaseIdentity;
            try (SystemPropertyScope ignored =
                         setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
                try (Connection recovered = openDatabase(database.toString(), false)) {
                    recovered.setAutoCommit(false);
                    assertRows(recovered,
                            "select id, value from io_fault_heap_t order by id",
                            "1|10", "2|20");
                    assertRows(recovered,
                            "select id, value from io_fault_mvcc_t order by id",
                            "1|100", "2|200");
                    assertTableAbsent(recovered, "IO_FAULT_UNCOMMITTED_T");
                    firstObserved = canonicalState(recovered);
                    databaseIdentity = snapshot(database).databaseIdentity();
                    recovered.commit();
                }
                shutdownDatabase(database.toString());
            }
            assertEquals(expectedCanonical, firstObserved);
            assertEquals(expectedDigest, sha256(firstObserved));

            String secondObserved;
            try (SystemPropertyScope ignored =
                         setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
                try (Connection replayed = openDatabase(database.toString(), false)) {
                    replayed.setAutoCommit(false);
                    assertTableAbsent(replayed, "IO_FAULT_UNCOMMITTED_T");
                    secondObserved = canonicalState(replayed);
                    replayed.commit();
                }
                shutdownDatabase(database.toString());
            }
            assertEquals(firstObserved, secondObserved);
            String observedDigest = sha256(secondObserved);
            assertEquals(expectedDigest, observedDigest);

            DelosRawStoreIoFailureReplayManifest manifest =
                    new DelosRawStoreIoFailureReplayManifest(
                            DelosRawStoreIoFailureReplayManifest
                                    .CURRENT_SCHEMA_VERSION,
                            1,
                            "stage-8.3-working-tree",
                            System.getProperty("java.vm.name") + " "
                                    + System.getProperty("java.version"),
                            0L,
                            databaseIdentity,
                            "single-jvm-parent/abrupt-child/file-database/heap+mvcc",
                            "AFTER_FORCE_METADATA#1:HALT(" + HALT_STATUS + ")",
                            "committed heap and MVCC rows survive ambiguous "
                                    + "post-force process termination; uncommitted DDL "
                                    + "remains absent after repeated reopen",
                            expectedDigest,
                            observedDigest,
                            2,
                            1L);
            assertTrue(manifest.matchesExpectedState());
            assertEquals(manifest,
                    DelosRawStoreIoFailureReplayManifest.parse(
                            manifest.toText()));
        } finally {
            shutdownIfBooted(database.toString());
            deleteRecursively(database);
        }
    }

    private static void assertTableAbsent(
            Connection connection,
            String tableName) throws Exception {
        try (var statement = connection.prepareStatement(
                "select count(*) from sys.systables t, sys.sysschemas s "
                        + "where t.schemaid = s.schemaid "
                        + "and s.schemaname = 'APP' and t.tablename = ?")) {
            statement.setString(1, tableName);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    private static String canonicalState(Connection connection) throws Exception {
        return "heap=" + rows(connection,
                "select id, value from io_fault_heap_t order by id")
                + ";mvcc=" + rows(connection,
                "select id, value from io_fault_mvcc_t order by id");
    }

    private static String rows(Connection connection, String sql) throws Exception {
        StringBuilder value = new StringBuilder();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            while (result.next()) {
                if (!value.isEmpty()) {
                    value.append(',');
                }
                value.append(result.getInt(1)).append('|').append(result.getInt(2));
            }
        }
        return value.toString();
    }

    private static DelosRawStoreIoSnapshot snapshot(Path database) {
        return DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(database);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void shutdownIfBooted(String database) {
        try {
            shutdownDatabase(database);
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    /** Child JVM that halts immediately after the scheduled metadata force. */
    public static final class HaltWorker {
        private HaltWorker() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length != 1) {
                throw new IllegalArgumentException("database path is required");
            }
            Path database = Path.of(args[0]).toAbsolutePath().normalize();
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:" + database)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "insert into io_fault_heap_t values (2, 20)");
                executeUpdate(connection,
                        "insert into io_fault_mvcc_t values (2, 200)");
                connection.commit();

                String identity = DelosStorageDiagnosticsRegistry
                        .heapDatabaseRawStoreIoSnapshot(database)
                        .databaseIdentity();
                RawStoreIoFaultInjectionTestSupport.installHalt(
                        identity,
                        "after-metadata-force-halt-replay",
                        "AFTER_FORCE_METADATA",
                        1L,
                        HALT_STATUS);
                executeUpdate(connection,
                        "create table io_fault_uncommitted_t "
                                + "(id int primary key, value int)");
            }
            Runtime.getRuntime().halt(94);
        }
    }
}
