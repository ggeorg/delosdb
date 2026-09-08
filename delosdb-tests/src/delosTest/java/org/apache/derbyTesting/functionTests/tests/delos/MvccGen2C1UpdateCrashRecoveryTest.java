/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccGen2C1UpdateCrashRecoveryTest

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.impl.jdbc.EmbedConnection;

/** Current/history UPDATE recovery at the existing RawStore commit failure points. */
public final class MvccGen2C1UpdateCrashRecoveryTest extends MvccSqlTestSupport {
    private static final String C2_PROPERTY =
            "delosdb.experimental.mvccGen2C2.archivedUndo.enabled";
    private static final String C1_PROPERTY =
            "delosdb.experimental.mvccGen2C1.history.enabled";
    private static final String FAILURE_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final String MAINTENANCE_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.enabled";
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String REPORT_PROPERTY =
            "delosdb.gen2C1UpdateRecovery.reportDirectory";
    private static final String TABLE = "C1_CRASH_T";

    public void testNarrowBatchBeforeRawCommit() throws Exception {
        verifyBothEngines(Shape.NARROW_BATCH, Boundary.BEFORE_RAW_COMMIT);
    }

    public void testNarrowBatchAfterRawCommit() throws Exception {
        verifyBothEngines(Shape.NARROW_BATCH, Boundary.AFTER_RAW_COMMIT);
    }

    public void testWideSparseChainBeforeRawCommit() throws Exception {
        verifyBothEngines(Shape.WIDE_SPARSE_CHAIN, Boundary.BEFORE_RAW_COMMIT);
    }

    public void testWideSparseChainAfterRawCommit() throws Exception {
        verifyBothEngines(Shape.WIDE_SPARSE_CHAIN, Boundary.AFTER_RAW_COMMIT);
    }

    public void testWideFullChainBeforeRawCommit() throws Exception {
        verifyBothEngines(Shape.WIDE_FULL_CHAIN, Boundary.BEFORE_RAW_COMMIT);
    }

    public void testWideFullChainAfterRawCommit() throws Exception {
        verifyBothEngines(Shape.WIDE_FULL_CHAIN, Boundary.AFTER_RAW_COMMIT);
    }

    public void testGrowthAndNullBeforeRawCommit() throws Exception {
        verifyBothEngines(Shape.GROW_NULL_CHAIN, Boundary.BEFORE_RAW_COMMIT);
    }

    public void testGrowthAndNullAfterRawCommit() throws Exception {
        verifyBothEngines(Shape.GROW_NULL_CHAIN, Boundary.AFTER_RAW_COMMIT);
    }

    public void testSavepointRetryBeforeRawCommit() throws Exception {
        verifyBothEngines(Shape.SAVEPOINT_RETRY_CHAIN, Boundary.BEFORE_RAW_COMMIT);
    }

    public void testSavepointRetryAfterRawCommit() throws Exception {
        verifyBothEngines(Shape.SAVEPOINT_RETRY_CHAIN, Boundary.AFTER_RAW_COMMIT);
    }

    public void testOrderedIndexInspectionRestoresContextAfterReopen() throws Exception {
        String database = Path.of("gen2-c1-inspection-context-"
                + Long.toUnsignedString(System.nanoTime())).toAbsolutePath().toString();
        try (SystemPropertyScope a1 = clearSystemProperty(
                        "delosdb.experimental.mvccGen2A1.enabled");
             SystemPropertyScope b1 = clearSystemProperty(
                        "delosdb.experimental.mvccGen2B.pk.enabled");
             SystemPropertyScope failure = clearSystemProperty(FAILURE_PROPERTY);
             SystemPropertyScope maintenance = setSystemProperty(MAINTENANCE_PROPERTY, "false");
             SystemPropertyScope enabled = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope c1 = setSystemProperty(C1_PROPERTY, "false")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup, "create table CONTEXT_BARE (id int, payload varchar(64)) "
                        + "using delos_mvcc");
                executeUpdate(setup, "insert into CONTEXT_BARE values (1, 'bare')");
                executeUpdate(setup, "create table CONTEXT_PK (id int primary key, "
                        + "payload varchar(64)) using delos_mvcc");
                executeUpdate(setup, "insert into CONTEXT_PK values (1, 'one'), (2, 'two')");
                setup.commit();
                c1.set("true");
                executeUpdate(setup, "create table CONTEXT_C1 (id int, payload varchar(64)) "
                        + "using delos_mvcc");
                executeUpdate(setup, "insert into CONTEXT_C1 values (1, 'current')");
                setup.commit();
            }
            shutdownDatabase(database);
            c1.set("false");

            // Reopen populates the base-conglomerate cache through LOCK TABLE,
            // but must not rely on any SQL query warming the native B-trees.
            try (Connection inspected = openDatabase(database, false);
                 Connection other = openDatabase(database, false)) {
                inspected.setAutoCommit(false);
                other.setAutoCommit(false);
                ContextService service = ContextService.getFactory();
                ContextManager ownContext = ((EmbedConnection) inspected)
                        .getLanguageConnection().getContextManager();
                ContextManager otherContext = ((EmbedConnection) other)
                        .getLanguageConnection().getContextManager();
                assertNotSame("each connection must have its own context", ownContext, otherContext);
                assertNull("JDBC must release its thread binding before inspection",
                        service.getCurrentContextManager());
                assertTrue("Gen1 PK must exercise persisted native B-trees",
                        MvccRawStoreMetadataInspection.orderedIndexBtreeCount(
                                inspected, "CONTEXT_PK") > 0);
                assertEquals("C1 table must use its persisted current/history format", 3,
                        MvccRawStoreMetadataInspection.controlFormatVersion(inspected, "CONTEXT_C1"));

                // No current context: this is the original cold-reopen failure.
                assertInspectionContext(inspected, service, null);
                // Same context: the inspector must not pop its caller's binding.
                service.setCurrentContextManager(ownContext);
                try {
                    assertInspectionContext(inspected, service, ownContext);
                } finally {
                    service.resetCurrentContextManager(ownContext);
                }
                assertNull(service.getCurrentContextManager());
                // Different connection: install the owner temporarily and restore
                // the original connection even when the inspection throws.
                service.setCurrentContextManager(otherContext);
                try {
                    assertInspectionContext(inspected, service, otherContext);
                } finally {
                    service.resetCurrentContextManager(otherContext);
                }
                assertNull(service.getCurrentContextManager());
                inspected.commit();
                other.commit();
            }
            shutdownDatabase(database);
        }
    }

    private static void assertInspectionContext(
            Connection connection, ContextService service, ContextManager expected)
            throws Exception {
        assertEquals("Gen1 BARE candidate B-trees must remain empty", 0,
                MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "CONTEXT_BARE").size());
        assertSame("empty scan must restore its caller's context",
                expected, service.getCurrentContextManager());
        var entries = MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "CONTEXT_PK");
        assertEquals("inspection must read actual native entries, not bypass the scan", 2, entries.size());
        assertEquals(Set.of("1", "2"), Set.of(entries.get(0).key(), entries.get(1).key()));
        assertTrue(entries.stream().allMatch(entry -> entry.columnId() == 0));
        assertSame("populated scan must restore its caller's context",
                expected, service.getCurrentContextManager());
        assertEquals("C1 has no native index directory", 0,
                MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "CONTEXT_C1").size());
        assertSame("early return must restore its caller's context",
                expected, service.getCurrentContextManager());
        try {
            MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "CONTEXT_MISSING");
            fail("missing table must still fail inspection");
        } catch (AssertionError failure) {
            assertEquals("No base conglomerate for CONTEXT_MISSING", failure.getMessage());
        } finally {
            assertSame("failed inspection must restore its caller's context",
                    expected, service.getCurrentContextManager());
        }
    }

    private static void verifyBothEngines(Shape shape, Boundary boundary) throws Exception {
        try (SystemPropertyScope a1 = clearSystemProperty(
                        "delosdb.experimental.mvccGen2A1.enabled");
             SystemPropertyScope b1 = clearSystemProperty(
                        "delosdb.experimental.mvccGen2B.pk.enabled");
             SystemPropertyScope failure = clearSystemProperty(FAILURE_PROPERTY);
             SystemPropertyScope maintenance = setSystemProperty(MAINTENANCE_PROPERTY, "false");
             SystemPropertyScope enabled = setSystemProperty(ENABLED_PROPERTY, "true")) {
            for (Engine engine : Engine.values()) {
                verifyCase(shape, boundary, engine);
            }
        }
    }

    private static void verifyCase(Shape shape, Boundary boundary, Engine engine) throws Exception {
        String key = shape.name() + '-' + boundary.name() + '-' + engine.name();
        Path reports = Path.of(System.getProperty(
                REPORT_PROPERTY, "gen2-c1-update-recovery-reports")).toAbsolutePath();
        Files.createDirectories(reports);
        Path resultFile = reports.resolve(key + ".properties");
        Files.deleteIfExists(resultFile);
        String database = Path.of("gen2-c1-update-recovery-" + key + '-'
                + Long.toUnsignedString(System.nanoTime())).toAbsolutePath().toString();

        Snapshot before;
        try (SystemPropertyScope c1 = setSystemProperty(
                C1_PROPERTY, Boolean.toString(engine == Engine.GEN2_C1))) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                createFixture(setup, shape);
                assertEquals("persisted format", engine == Engine.GEN2_C1 ? 3 : 1,
                        MvccRawStoreMetadataInspection.controlFormatVersion(setup, TABLE));
                assertSqlState(setup, shape, shape.baselineStage(), false);
                before = snapshot(setup, engine, shape, shape.seededMutations());
                assertVersionPayloads(before, engine, shape, false, false);
                setup.commit();
            }
            shutdownDatabase(database);
        }

        Path workerLog = reports.resolve(key + ".worker.log");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx512m");
        command.add("-D" + ENABLED_PROPERTY + "=true");
        command.add("-D" + MAINTENANCE_PROPERTY + "=false");
        // Only creation selects the layout. Recovery must use the persisted format.
        command.add("-D" + C1_PROPERTY + "=false");
        command.add("-D" + C2_PROPERTY + "=" + Boolean.getBoolean(C2_PROPERTY));
        command.add("-Ddelosdb.experimental.mvccGen2A1.enabled=false");
        command.add("-Ddelosdb.experimental.mvccGen2B.pk.enabled=false");
        command.add("-Dderby.stream.error.file=" + reports.resolve(key + ".derby.log"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CrashWorker.class.getName());
        command.add(database);
        command.add(shape.name());
        command.add(boundary.name());
        command.add(engine.name());

        Process worker = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(workerLog.toFile())
                .start();
        try {
            boolean finished = worker.waitFor(90L, TimeUnit.SECONDS);
            if (!finished) {
                worker.destroyForcibly();
                worker.waitFor(10L, TimeUnit.SECONDS);
                fail("UPDATE crash worker timed out: " + key + "; log=" + workerLog);
            }
            String output = Files.readString(workerLog, StandardCharsets.UTF_8);
            assertEquals("wrong halt boundary: " + key + "; output=" + output,
                    boundary.exitCode, worker.exitValue());
            assertTrue("worker must finish its mutation/flush before arming the hook: " + output,
                    output.contains("READY " + key));
            assertTrue("worker must run the requested archived-undo mode: " + output,
                    output.contains("ARCHIVED_UNDO_ENABLED=" + Boolean.getBoolean(C2_PROPERTY)));
        } finally {
            if (worker.isAlive()) {
                worker.destroyForcibly();
                worker.waitFor(10L, TimeUnit.SECONDS);
            }
        }

        int expectedStage = boundary.committed ? 2 : shape.baselineStage();
        int expectedMutations = shape.seededMutations()
                + (boundary.committed ? shape.updatedRows : 0);
        Snapshot continued;
        try (SystemPropertyScope c1 = setSystemProperty(C1_PROPERTY, "false")) {
            try (Connection recovered = openDatabase(database, false)) {
                recovered.setAutoCommit(false);
                assertEquals("recovery must retain the persisted format",
                        engine == Engine.GEN2_C1 ? 3 : 1,
                        MvccRawStoreMetadataInspection.controlFormatVersion(recovered, TABLE));
                assertSqlState(recovered, shape, expectedStage, false);
                assertHeapMarker(recovered, boundary.committed ? 20 : 10);
                Snapshot after = snapshot(recovered, engine, shape, expectedMutations);
                assertVersionPayloads(after, engine, shape, boundary.committed, false);
                if (boundary.committed) {
                    assertCommittedTransition(before, after, engine, shape);
                } else {
                    assertEquals("rollback must restore heads and retained history exactly",
                            before, after);
                }
                recovered.commit();

                // Prove reconstructed IDs and post-recovery writes remain usable.
                updateRow(recovered, shape, 1, 3);
                recovered.commit();
                assertSqlState(recovered, shape, expectedStage, true);
                continued = snapshot(recovered, engine, shape, expectedMutations + 1);
                assertVersionPayloads(continued, engine, shape, boundary.committed, true);
                assertTrue("post-recovery version ID must not alias a retained image",
                        continued.directories.get(0).headVersionId() > maxVersion(after));
                recovered.commit();
            }
            shutdownDatabase(database);
            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertSqlState(reopened, shape, expectedStage, true);
                assertHeapMarker(reopened, boundary.committed ? 20 : 10);
                assertEquals("recovered state must persist through a subsequent clean reopen",
                        continued, snapshot(reopened, engine, shape, expectedMutations + 1));
                reopened.commit();
            }
            shutdownDatabase(database);
        }
        assertNoSidecarAuthority(Path.of(database));

        Properties result = new Properties();
        result.setProperty("status", "PASS");
        result.setProperty("scenario", shape.name());
        result.setProperty("boundary", boundary.name());
        result.setProperty("engine", engine.name());
        result.setProperty("exitCode", Integer.toString(boundary.exitCode));
        result.setProperty("outcome", boundary.committed ? "COMMIT" : "ROLLBACK");
        result.setProperty("currentRows", Integer.toString(shape.fixtureRows));
        result.setProperty("recoveredMutations", Integer.toString(expectedMutations));
        result.setProperty("historicalPayloads", "PASS");
        result.setProperty("postRecoveryUpdate", "PASS");
        result.setProperty("cleanReopen", "PASS");
        result.setProperty("creationFlagDuringRecovery", "false");
        result.setProperty("dirtyPageFlushBeforeCommit", "true");
        result.setProperty("archivedUndoEnabled", Boolean.toString(Boolean.getBoolean(C2_PROPERTY)));
        try (var writer = Files.newBufferedWriter(resultFile, StandardCharsets.UTF_8)) {
            result.store(writer, "Gen2-C1 UPDATE RawStore commit-boundary recovery");
        }
    }

    private static void createFixture(Connection connection, Shape shape) throws Exception {
        executeUpdate(connection, "create table " + TABLE
                + " (id int not null, payload varchar(16384), preserved varchar(4096),"
                + " tail varchar(128)) using delos_mvcc");
        executeUpdate(connection,
                "create table C1_CRASH_HEAP (id int primary key, marker int)");
        executeUpdate(connection, "insert into C1_CRASH_HEAP values (1, 10)");
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + TABLE + " values (?, ?, ?, ?)")) {
            for (int id = 1; id <= shape.fixtureRows; id++) {
                Values values = values(shape, id, 0);
                insert.setInt(1, id);
                insert.setString(2, values.payload);
                insert.setString(3, values.preserved);
                insert.setString(4, values.tail);
                assertEquals(1, insert.executeUpdate());
            }
        }
        connection.commit();
        if (shape.seeded) {
            for (int id = 1; id <= shape.updatedRows; id++) {
                updateRow(connection, shape, id, 1);
            }
            connection.commit();
        }
    }

    private static void updateRow(Connection connection, Shape shape, int id, int stage)
            throws Exception {
        Values values = values(shape, id, stage);
        String sql = shape == Shape.WIDE_FULL_CHAIN
                ? "update " + TABLE + " set payload = ?, preserved = ?, tail = ? where id = ?"
                : "update " + TABLE + " set payload = ? where id = ?";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, values.payload);
            if (shape == Shape.WIDE_FULL_CHAIN) {
                update.setString(2, values.preserved);
                update.setString(3, values.tail);
                update.setInt(4, id);
            } else {
                update.setInt(2, id);
            }
            assertEquals("UPDATE must target one logical row", 1, update.executeUpdate());
        }
    }

    private static void exerciseSavepoint(Connection connection, Shape shape) throws Exception {
        Savepoint savepoint = connection.setSavepoint("before_aborted_history");
        try (PreparedStatement update = connection.prepareStatement(
                "update " + TABLE + " set payload = ? where id = ?")) {
            for (int id = 1; id <= shape.updatedRows; id++) {
                update.setString(1, id == 1 ? text(8192, 991) : null);
                update.setInt(2, id);
                assertEquals(1, update.executeUpdate());
            }
        }
        assertEquals(1, executeUpdate(connection,
                "update C1_CRASH_HEAP set marker = 99 where id = 1"));
        DelosDeleteReinsertPageTopologyTestSupport.flushPageCache(connection);
        connection.rollback(savepoint);
        connection.releaseSavepoint(savepoint);
        assertSqlState(connection, shape, shape.baselineStage(), false);
        assertHeapMarker(connection, 10);
    }

    private static void assertSqlState(
            Connection connection, Shape shape, int touchedStage, boolean continued)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select id, payload, preserved, tail from " + TABLE + " order by id")) {
            for (int id = 1; id <= shape.fixtureRows; id++) {
                assertTrue("missing logical row " + id, rows.next());
                assertEquals(id, rows.getInt(1));
                int stage = continued && id == 1 ? 3 : id <= shape.updatedRows ? touchedStage : 0;
                Values expected = values(shape, id, stage);
                assertEquals("payload for row " + id, expected.payload, rows.getString(2));
                assertEquals("preserved field for row " + id, expected.preserved, rows.getString(3));
                assertEquals("tail for row " + id, expected.tail, rows.getString(4));
            }
            assertFalse("duplicate or unexpected current row", rows.next());
        }
    }

    private static void assertHeapMarker(Connection connection, int expected) throws Exception {
        assertRows(connection, "select id, marker from C1_CRASH_HEAP", "1|" + expected);
    }

    private static Snapshot snapshot(
            Connection connection, Engine engine, Shape shape, int mutations) throws Exception {
        var directories = MvccRawStoreMetadataInspection.directories(connection, TABLE);
        var versions = MvccRawStoreMetadataInspection.versions(connection, TABLE);
        assertEquals("one current/directory record per row", shape.fixtureRows, directories.size());
        assertEquals("exact retained-image count", mutations
                + (engine == Engine.GEN1 ? shape.fixtureRows : 0), versions.size());
        assertEquals("BARE table must not acquire a native index", 0,
                MvccRawStoreMetadataInspection.orderedIndexEntries(connection, TABLE).size());
        Set<Long> identities = new HashSet<>();
        for (var version : versions) {
            assertTrue("duplicate physical version ID", identities.add(version.versionId()));
            assertTrue("no uncommitted begin stamp may survive recovery",
                    version.beginCommitSequence() > 0L);
            assertTrue("version end must follow begin",
                    version.endCommitSequence() > version.beginCommitSequence());
            assertFalse("UPDATE must not create tombstones", version.tombstone());
            if (engine == Engine.GEN2_C1) {
                assertTrue("archived predecessor must be closed",
                        version.endCommitSequence() < Long.MAX_VALUE);
            }
        }
        for (var directory : directories) {
            if (engine == Engine.GEN2_C1) {
                assertTrue("CURRENT must not duplicate a historical version ID",
                        identities.add(directory.headVersionId()));
            } else {
                assertTrue("Gen1 head must identify a physical version",
                        identities.contains(directory.headVersionId()));
            }
        }
        var payloads = MvccRawStoreMetadataInspection.versionPayloads(connection, TABLE);
        assertEquals("every physical version must have a readable payload", versions.size(), payloads.size());
        return new Snapshot(List.copyOf(directories), List.copyOf(versions), payloads);
    }

    private static void assertVersionPayloads(
            Snapshot snapshot, Engine engine, Shape shape, boolean committed, boolean continued) {
        Map<Integer, List<List<String>>> actual = new HashMap<>();
        for (var version : snapshot.payloads) {
            assertEquals("four SQL payload fields", 4, version.payload().size());
            int id = Integer.parseInt(version.payload().get(0));
            actual.computeIfAbsent(id, ignored -> new ArrayList<>()).add(version.payload());
        }
        int expectedRowsWithVersions = 0;
        for (int id = 1; id <= shape.fixtureRows; id++) {
            List<Integer> stages = new ArrayList<>();
            if (engine == Engine.GEN1) {
                stages.add(0);
                if (shape.seeded && id <= shape.updatedRows) {
                    stages.add(1);
                }
                if (committed && id <= shape.updatedRows) {
                    stages.add(2);
                }
                if (continued && id == 1) {
                    stages.add(3);
                }
            } else {
                if (shape.seeded && id <= shape.updatedRows) {
                    stages.add(0);
                }
                if (committed && id <= shape.updatedRows) {
                    stages.add(shape.baselineStage());
                }
                if (continued && id == 1) {
                    stages.add(committed ? 2 : shape.baselineStage());
                }
            }
            List<List<String>> expected = new ArrayList<>();
            for (int stage : stages) {
                Values value = values(shape, id, stage);
                expected.add(java.util.Arrays.asList(Integer.toString(id),
                        value.payload, value.preserved, value.tail));
            }
            if (!expected.isEmpty()) {
                expectedRowsWithVersions++;
            }
            assertEquals("retained payload chronology for SQL row " + id,
                    expected, actual.getOrDefault(id, List.of()));
        }
        assertEquals("no orphan history payloads", expectedRowsWithVersions, actual.size());
    }

    private static void assertCommittedTransition(
            Snapshot before, Snapshot after, Engine engine, Shape shape) {
        Map<Long, MvccRawStoreMetadataInspection.VersionIdentity> versions = new HashMap<>();
        for (var version : after.versions) {
            versions.put(version.versionId(), version);
        }
        // Fixture rows are inserted in SQL-id order; the ordered physical list
        // captures the resulting logical identities rather than assuming id == rowId.
        for (int row = 0; row < shape.fixtureRows; row++) {
            var oldHead = before.directories.get(row);
            var newHead = after.directories.get(row);
            assertEquals("stable logical row identity", oldHead.rowId(), newHead.rowId());
            if (row >= shape.updatedRows) {
                assertEquals("untouched row head changed", oldHead, newHead);
                continue;
            }
            assertTrue("committed UPDATE must have a new version",
                    newHead.headVersionId() > oldHead.headVersionId());
            var predecessor = versions.get(oldHead.headVersionId());
            assertNotNull("committed old image must remain as history", predecessor);
            assertEquals(oldHead.rowId(), predecessor.rowId());
            assertTrue("predecessor end stamp must be committed",
                    predecessor.endCommitSequence() < Long.MAX_VALUE);
            var linked = engine == Engine.GEN2_C1
                    ? predecessor : versions.get(newHead.headVersionId());
            assertNotNull("head link target must exist", linked);
            assertEquals("head page hint", linked.physicalPage(), newHead.headHintPage());
            assertEquals("head record hint", linked.physicalRecord(), newHead.headHintRecord());
            if (engine == Engine.GEN1) {
                assertEquals(oldHead.headVersionId(), linked.previousVersionId());
                assertEquals(predecessor.endCommitSequence(), linked.beginCommitSequence());
            }
        }
    }

    private static long maxVersion(Snapshot snapshot) {
        long result = 0L;
        for (var directory : snapshot.directories) {
            result = Math.max(result, directory.headVersionId());
        }
        for (var version : snapshot.versions) {
            result = Math.max(result, version.versionId());
        }
        return result;
    }

    private static void assertNoSidecarAuthority(Path database) throws Exception {
        Path sidecar = database.resolve("delos_mvcc");
        if (Files.exists(sidecar)) {
            try (var files = Files.walk(sidecar)) {
                assertEquals("no independent MVCC persistence/recovery files", 0L,
                        files.filter(Files::isRegularFile).count());
            }
        }
    }

    private static Values values(Shape shape, int id, int stage) {
        int width = shape == Shape.NARROW_BATCH ? 96 : 1024;
        String payload = text(width, 31 * id + 101 * stage);
        String preserved = text(shape == Shape.GROW_NULL_CHAIN ? 2048 : width, 53 * id);
        String tail = "tail-" + id;
        if (shape == Shape.WIDE_FULL_CHAIN) {
            preserved = text(width, 53 * id + 211 * stage);
            tail = stage == 2 && id == 1 ? null : "tail-" + id + '-' + stage;
        } else if (shape == Shape.GROW_NULL_CHAIN) {
            if ((stage == 1 && id == 1) || (stage == 2 && id == 2)) {
                payload = null;
            } else if (stage == 2 && id == 1) {
                payload = text(8192, 701);
            }
        }
        return new Values(payload, preserved, tail);
    }

    private static String text(int length, int seed) {
        StringBuilder result = new StringBuilder(length);
        int state = seed;
        for (int index = 0; index < length; index++) {
            state = state * 1664525 + 1013904223;
            result.append((char) ('!' + ((state >>> 16) % 90)));
        }
        return result.toString();
    }

    private record Values(String payload, String preserved, String tail) {
    }

    private record Snapshot(
            List<MvccRawStoreMetadataInspection.DirectoryIdentity> directories,
            List<MvccRawStoreMetadataInspection.VersionIdentity> versions,
            List<MvccRawStoreMetadataInspection.VersionPayloadIdentity> payloads) {
    }

    private enum Engine {
        GEN1, GEN2_C1
    }

    private enum Shape {
        NARROW_BATCH(16, 12, false),
        WIDE_SPARSE_CHAIN(6, 2, true),
        WIDE_FULL_CHAIN(6, 2, true),
        GROW_NULL_CHAIN(6, 2, true),
        SAVEPOINT_RETRY_CHAIN(6, 2, true);

        private final int fixtureRows;
        private final int updatedRows;
        private final boolean seeded;

        Shape(int fixtureRows, int updatedRows, boolean seeded) {
            this.fixtureRows = fixtureRows;
            this.updatedRows = updatedRows;
            this.seeded = seeded;
        }

        private int baselineStage() {
            return seeded ? 1 : 0;
        }

        private int seededMutations() {
            return seeded ? updatedRows : 0;
        }
    }

    private enum Boundary {
        BEFORE_RAW_COMMIT("after-stamp-before-raw-commit", 91, false),
        AFTER_RAW_COMMIT("after-raw-commit-before-publication", 92, true);

        private final String failurePoint;
        private final int exitCode;
        private final boolean committed;

        Boundary(String failurePoint, int exitCode, boolean committed) {
            this.failurePoint = failurePoint;
            this.exitCode = exitCode;
            this.committed = committed;
        }
    }

    /** Child process; the selected production failure point must halt commit. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length != 4) {
                throw new IllegalArgumentException("expected database, shape, boundary, engine");
            }
            Shape shape = Shape.valueOf(args[1]);
            Boundary boundary = Boundary.valueOf(args[2]);
            Engine engine = Engine.valueOf(args[3]);
            String key = shape.name() + '-' + boundary.name() + '-' + engine.name();
            try (Connection connection = openDatabase(args[0], false)) {
                connection.setAutoCommit(false);
                assertSqlState(connection, shape, shape.baselineStage(), false);
                assertEquals(engine == Engine.GEN2_C1 ? 3 : 1,
                        MvccRawStoreMetadataInspection.controlFormatVersion(connection, TABLE));
                connection.commit();
                if (shape == Shape.SAVEPOINT_RETRY_CHAIN) {
                    exerciseSavepoint(connection, shape);
                }
                for (int id = 1; id <= shape.updatedRows; id++) {
                    updateRow(connection, shape, id, 2);
                }
                assertEquals(1, executeUpdate(connection,
                        "update C1_CRASH_HEAP set marker = 20 where id = 1"));
                assertSqlState(connection, shape, 2, false);
                // Deliberately persist dirty CURRENT/HISTORY pages while the
                // transaction is uncommitted. This invokes the existing data
                // cache flush, not a new log or a JDBC commit/checkpoint call.
                DelosDeleteReinsertPageTopologyTestSupport.flushPageCache(connection);
                System.out.println("ARCHIVED_UNDO_ENABLED=" + Boolean.getBoolean(C2_PROPERTY));
                System.out.println("READY " + key);
                System.out.flush();
                // Arm last: setup, activation, and savepoint operations must
                // not accidentally satisfy the expected child exit code.
                System.setProperty(FAILURE_PROPERTY, boundary.failurePoint);
                connection.commit();
            }
            throw new AssertionError("commit returned without the configured halt: " + key);
        }
    }
}
