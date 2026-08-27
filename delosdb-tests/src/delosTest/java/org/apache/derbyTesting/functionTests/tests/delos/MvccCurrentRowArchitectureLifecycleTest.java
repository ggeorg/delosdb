/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccCurrentRowArchitectureLifecycleTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lifecycle safety proof for the complete transient MVCC current-row read path.
 * The test deliberately crosses mutation, rollback, history, vacuum, reopen,
 * recreation and crash-recovery boundaries with the permanent current-row read path active.
 */
public final class MvccCurrentRowArchitectureLifecycleTest extends MvccSqlTestSupport {
    private static final String DATABASE = "mvcc-current-row-architecture-lifecycle";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final String VERTICAL_SLICE_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String CURRENT_ROW_READ_CACHE_SLOTS_PROPERTY =
            "delosdb.mvcc.currentRowReadCache.slots";

    public void testCommittedUpdateDeleteReinsertAndVacuumNeverServeStaleState()
            throws Exception {
        String database = databaseName(DATABASE + "-mutation");
        String table = "LIFECYCLE_MUTATION_T";
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection, table, 2048);
            assertWarmCurrent(connection, table, 1024, 10240, "row-1024");

            updateRow(connection, table, 1024, 20000, "committed-v2");
            connection.commit();
            assertWarmCurrent(connection, table, 1024, 20000, "committed-v2");

            executeUpdate(connection, "delete from " + table + " where id = 1024");
            connection.commit();
            assertNull(measuredRead(connection, table, 1024).row());

            executeUpdate(connection,
                    "insert into " + table + " values (1024, 30000, 'reinserted-v3')");
            connection.commit();
            assertWarmCurrent(connection, table, 1024, 30000, "reinserted-v3");

            updateRow(connection, table, 1024, 40000, "committed-v4");
            connection.commit();
            assertWarmCurrent(connection, table, 1024, 40000, "committed-v4");

            inPlaceCompressTable(connection, table);
            connection.commit();
            assertWarmCurrent(connection, table, 1024, 40000, "committed-v4");
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testSavepointRollbackAndAbortCannotPublishUncommittedState()
            throws Exception {
        String database = databaseName(DATABASE + "-rollback");
        String table = "LIFECYCLE_ROLLBACK_T";
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection, table, 64);
            assertWarmCurrent(connection, table, 1, 10, "row-1");

            Savepoint savepoint = connection.setSavepoint("FAST_PATH_SAVEPOINT");
            updateRow(connection, table, 1, 111, "savepoint-write");
            assertRow(row(connection, table, 1), 111, "savepoint-write");
            connection.rollback(savepoint);
            assertWarmCurrent(connection, table, 1, 10, "row-1");

            updateRow(connection, table, 1, 222, "abort-write");
            assertRow(row(connection, table, 1), 222, "abort-write");
            connection.rollback();
            assertWarmCurrent(connection, table, 1, 10, "row-1");

            executeUpdate(connection, "delete from " + table + " where id = 1");
            assertNull(row(connection, table, 1));
            connection.rollback();
            assertWarmCurrent(connection, table, 1, 10, "row-1");
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testRetainedSnapshotSurvivesUpdateDeleteReinsertAndVacuum()
            throws Exception {
        String database = databaseName(DATABASE + "-history");
        String table = "LIFECYCLE_HISTORY_T";
        try (Connection setup = openDatabase(database, true)) {
            createFixture(setup, table, 128);
        }

        try (Connection reader = openDatabase(database, false);
             Connection writer = openDatabase(database, false);
             Connection vacuum = openDatabase(database, false)) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            writer.setAutoCommit(false);
            vacuum.setAutoCommit(false);

            assertRow(row(reader, table, 1), 10, "row-1");

            updateRow(writer, table, 1, 20, "writer-v2");
            writer.commit();
            executeUpdate(writer, "delete from " + table + " where id = 1");
            writer.commit();
            executeUpdate(writer,
                    "insert into " + table + " values (1, 30, 'writer-v3')");
            writer.commit();
            assertWarmCurrent(writer, table, 1, 30, "writer-v3");
            writer.commit();

            inPlaceCompressTable(vacuum, table);
            vacuum.commit();

            assertRow(row(reader, table, 1), 10, "row-1");
            reader.commit();
            assertWarmCurrent(reader, table, 1, 30, "writer-v3");
            reader.commit();

            inPlaceCompressTable(vacuum, table);
            vacuum.commit();
            assertWarmCurrent(vacuum, table, 1, 30, "writer-v3");
            vacuum.commit();
        }
        shutdownDatabase(database);
    }

    public void testReopenAndDropRecreateStartFromAuthoritativeRawStore()
            throws Exception {
        String database = databaseName(DATABASE + "-reopen");
        String table = "LIFECYCLE_REOPEN_T";
        long originalContainer;
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection, table, 32);
            originalContainer = mvccContainerId(connection, table);
            assertWarmCurrent(connection, table, 1, 10, "row-1");
            connection.commit();
        }
        shutdownDatabase(database);

        try (Connection reopened = openDatabase(database, false)) {
            Measurement first = measuredRead(reopened, table, 1);
            assertRow(first.row(), 10, "row-1");
            assertEquals(0L, metric(first.statistics(), "mvccCurrentRowAnchorHits"));
            assertEquals(1L, metric(first.statistics(), "mvccDirectoryPageAcquisitions"));
            assertWarmCurrent(reopened, table, 1, 10, "row-1");
            reopened.commit();

            executeUpdate(reopened, "drop table " + table);
            reopened.commit();
            executeUpdate(reopened,
                    "create table " + table
                            + " (id int not null primary key, quantity int not null,"
                            + " payload varchar(64) not null) using delos_mvcc");
            executeUpdate(reopened,
                    "insert into " + table + " values (1, 999, 'recreated')");
            reopened.commit();

            long recreatedContainer = mvccContainerId(reopened, table);
            assertFalse("drop/recreate must allocate a different MVCC base container",
                    originalContainer == recreatedContainer);
            assertWarmCurrent(reopened, table, 1, 999, "recreated");
            reopened.commit();
        }
        shutdownDatabase(database);
    }

    public void testCrashRecoveryIgnoresAllTransientFastPathState() throws Exception {
        runCrashCase("after-stamp-before-raw-commit", 91, 10);
        runCrashCase("after-raw-commit-before-publication", 92, 20);
    }

    private static void runCrashCase(
            String failurePoint, int expectedExit, int expectedQuantity) throws Exception {
        String database = Path.of(databaseName(DATABASE + "-crash-" + expectedExit))
                .toAbsolutePath()
                .normalize()
                .toString();
        String table = "LIFECYCLE_CRASH_T";
        try (Connection setup = openDatabase(database, true)) {
            createFixture(setup, table, 8);
            assertWarmCurrent(setup, table, 1, 10, "row-1");
            setup.commit();
        }
        shutdownDatabase(database);

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + VERTICAL_SLICE_PROPERTY + "=true",
                "-D" + FAILURE_POINT_PROPERTY + "=" + failurePoint,
                "-D" + CURRENT_ROW_READ_CACHE_SLOTS_PROPERTY + "=4096",
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(
                Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("current-row architecture crash worker did not terminate at " + failurePoint);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt at " + failurePoint + "; output=" + output,
                expectedExit, process.exitValue());

        try (Connection recovered = openDatabase(database, false)) {
            assertCrashRecoveryRebuildsFastPath(
                    recovered, table, 1, expectedQuantity,
                    expectedQuantity == 10 ? "row-1" : "crash-committed");
            recovered.commit();
        }
        shutdownDatabase(database);
    }

    private static void createFixture(
            Connection connection, String table, int rows) throws Exception {
        connection.setAutoCommit(false);
        executeUpdate(connection,
                "create table " + table
                        + " (id int not null primary key, quantity int not null,"
                        + " payload varchar(64) not null) using delos_mvcc");
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + table + " values (?, ?, ?)")) {
            for (int id = 1; id <= rows; id++) {
                statement.setInt(1, id);
                statement.setInt(2, id * 10);
                statement.setString(3, "row-" + id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        connection.commit();
    }

    private static void updateRow(
            Connection connection, String table, int id, int quantity, String payload)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "update " + table + " set quantity = ?, payload = ? where id = ?")) {
            statement.setInt(1, quantity);
            statement.setString(2, payload);
            statement.setInt(3, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertWarmCurrent(
            Connection connection,
            String table,
            int id,
            int expectedQuantity,
            String expectedPayload) throws Exception {
        Measurement first = measuredRead(connection, table, id);
        assertRow(first.row(), expectedQuantity, expectedPayload);
        Measurement second = measuredRead(connection, table, id);
        assertFullyWarmCurrent(second, expectedQuantity, expectedPayload);
    }

    private static void assertCrashRecoveryRebuildsFastPath(
            Connection connection,
            String table,
            int id,
            int expectedQuantity,
            String expectedPayload) throws Exception {
        Measurement cold = measuredRead(connection, table, id);
        assertRow(cold.row(), expectedQuantity, expectedPayload);
        assertEquals(0L, metric(cold.statistics(), "mvccCurrentRowAnchorChecks"));
        assertEquals(0L, metric(cold.statistics(), "mvccCurrentRowAnchorHits"));
        assertEquals(0L, metric(cold.statistics(), "mvccCurrentRowAnchorFallbacks"));
        assertEquals(1L, metric(cold.statistics(), "mvccDirectoryPageAcquisitions"));
        assertEquals(0L, metric(cold.statistics(), "mvccCurrentVersionReadImageChecks"));
        assertEquals(0L, metric(cold.statistics(), "mvccCurrentVersionReadImageHits"));
        assertEquals(0L, metric(cold.statistics(), "mvccCurrentVersionReadImageFallbacks"));
        assertTrue("cold recovery read must use authoritative version storage; statistics="
                        + cold.statistics(),
                metric(cold.statistics(), "mvccVersionPageAcquisitions") >= 1L);

        Measurement imageWarm = measuredRead(connection, table, id);
        assertRow(imageWarm.row(), expectedQuantity, expectedPayload);
        long anchorChecks = metric(imageWarm.statistics(), "mvccCurrentRowAnchorChecks");
        long anchorHits = metric(imageWarm.statistics(), "mvccCurrentRowAnchorHits");
        assertTrue("second recovery read must hit the rebuilt current-row anchor; statistics="
                        + imageWarm.statistics(),
                anchorHits >= 1L);
        assertEquals(anchorChecks, anchorHits);
        assertEquals(0L, metric(imageWarm.statistics(), "mvccCurrentRowAnchorFallbacks"));
        assertEquals(0L, metric(imageWarm.statistics(), "mvccDirectoryPageAcquisitions"));
        long imageChecks = metric(
                imageWarm.statistics(), "mvccCurrentVersionReadImageChecks");
        assertTrue("second recovery read must check the empty version image; statistics="
                        + imageWarm.statistics(),
                imageChecks >= 1L);
        assertEquals(0L, metric(imageWarm.statistics(), "mvccCurrentVersionReadImageHits"));
        assertEquals(imageChecks,
                metric(imageWarm.statistics(), "mvccCurrentVersionReadImageFallbacks"));
        assertTrue("second recovery read must rebuild the image from RawStore; statistics="
                        + imageWarm.statistics(),
                metric(imageWarm.statistics(), "mvccVersionPageAcquisitions") >= 1L);

        Measurement warm = measuredRead(connection, table, id);
        assertFullyWarmCurrent(warm, expectedQuantity, expectedPayload);
    }

    private static void assertFullyWarmCurrent(
            Measurement measurement,
            int expectedQuantity,
            String expectedPayload) throws Exception {
        assertRow(measurement.row(), expectedQuantity, expectedPayload);
        long anchorChecks = metric(measurement.statistics(), "mvccCurrentRowAnchorChecks");
        long anchorHits = metric(measurement.statistics(), "mvccCurrentRowAnchorHits");
        assertTrue("expected at least one current-row anchor hit; statistics="
                        + measurement.statistics(),
                anchorHits >= 1L);
        assertEquals("every current-row anchor check must hit on the warmed path",
                anchorChecks, anchorHits);
        assertEquals(0L,
                metric(measurement.statistics(), "mvccCurrentRowAnchorFallbacks"));
        assertEquals(0L, metric(measurement.statistics(), "mvccDirectoryPageAcquisitions"));

        long imageChecks = metric(
                measurement.statistics(), "mvccCurrentVersionReadImageChecks");
        long imageHits = metric(
                measurement.statistics(), "mvccCurrentVersionReadImageHits");
        assertTrue("expected at least one current-version image hit; statistics="
                        + measurement.statistics(),
                imageHits >= 1L);
        assertEquals("every current-version image check must hit on the warmed path",
                imageChecks, imageHits);
        assertEquals(0L,
                metric(measurement.statistics(), "mvccCurrentVersionReadImageFallbacks"));
        assertEquals(0L, metric(measurement.statistics(), "mvccVersionPageAcquisitions"));
    }

    private static Measurement measuredRead(Connection connection, String table, int id)
            throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        Row value = row(connection, table, id);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "values syscs_util.syscs_get_runtimestatistics()")) {
            assertTrue(resultSet.next());
            return new Measurement(value, resultSet.getString(1));
        }
    }

    private static Row row(Connection connection, String table, int id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select quantity, payload from " + table
                        + " --DERBY-PROPERTIES index=null\n where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                Row value = new Row(resultSet.getInt(1), resultSet.getString(2));
                assertFalse(resultSet.next());
                return value;
            }
        }
    }

    private static void assertRow(Row row, int quantity, String payload) {
        assertNotNull(row);
        assertEquals(quantity, row.quantity());
        assertEquals(payload, row.payload());
    }

    private static long metric(String statistics, String name) {
        Pattern pattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*(\\d+)\\s*$");
        Matcher matcher = pattern.matcher(statistics);
        assertTrue("missing MVCC scan metric " + name + "; statistics=" + statistics,
                matcher.find());
        return Long.parseLong(matcher.group(1));
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private record Measurement(Row row, String statistics) {
    }

    private record Row(int quantity, String payload) {
    }

    /** Child process warms the full fast path, mutates one row and halts at RawStore commit boundary. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("Expected database path");
            }
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + arguments[0])) {
                connection.setAutoCommit(false);
                readQuantity(connection, 1);
                readQuantity(connection, 1);
                try (PreparedStatement statement = connection.prepareStatement(
                        "update LIFECYCLE_CRASH_T set quantity = 20,"
                                + " payload = 'crash-committed' where id = 1")) {
                    statement.executeUpdate();
                }
                connection.commit();
            }
            throw new AssertionError("configured MVCC crash failure point did not halt child JVM");
        }

        private static int readQuantity(Connection connection, int id) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select quantity from LIFECYCLE_CRASH_T where id = ?")) {
                statement.setInt(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new AssertionError("missing crash fixture row " + id);
                    }
                    return resultSet.getInt(1);
                }
            }
        }
    }
}
