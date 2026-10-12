/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.MeasuredSpec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import junit.framework.TestCase;

/** Real embedded cases qualify measurement budgets, fixtures, diagnostics and cleanup. */
public final class BenchmarkCaseMeasurementTest extends TestCase {
    private static final String PREFIX = BenchmarkConfiguration.PREFIX;
    private Properties original;
    private Path directory;

    @Override
    protected void setUp() throws Exception {
        original = (Properties) System.getProperties().clone();
        for (String name : original.stringPropertyNames()) {
            if (name.startsWith(PREFIX)) System.clearProperty(name);
        }
        directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos-case-");
        property("target", "delos_heap");
        property("run", "1");
        property("rows", "200");
        property("clients", "2");
        property("widths", "1");
        property("workloads", "PRIMARY_KEY_READ_DISJOINT");
        property("transactionsPerClient", "2");
        property("payload", "32");
        property("fixtureBatch", "100");
        property("warmups", "0");
        property("iterations", "1");
        property("databaseRoot", directory.resolve("databases").toString());
        property("reportDirectory", directory.toString());
    }

    @Override
    protected void tearDown() {
        System.setProperties(original);
    }

    public void testFixedAndAdaptiveBudgetsOracleAndDiagnosticFiles() throws Exception {
        property("sqlSemanticOracle", "true");
        property("pageLatchDiagnostics", "true");
        property("warmups", "1");
        property("iterations", "2");
        MeasuredSpec fixed = measureReadCase();
        assertEquals(1, fixed.measurement().warmups());
        assertEquals(2, fixed.measurement().iterations());
        assertEquals(8L, fixed.measurement().measuredTransactions());
        assertEquals(8L, fixed.measurement().measuredOperations());
        assertNotNull(fixed.oracleEvidence());
        assertDatabaseFilesRemoved();
        property("warmups", "0");
        property("iterations", "1");
        property("minimumWarmupSeconds", "0.000000001");
        property("maximumWarmupIterations", "1");
        property("minimumMeasuredSeconds", "0.000000001");
        property("maximumMeasuredIterations", "3");
        MeasuredSpec adaptive = measureReadCase();
        assertEquals(1, adaptive.measurement().warmups());
        // The oracle-bearing interval remains part of adaptive measured work.
        assertEquals(2, adaptive.measurement().iterations());
        assertEquals(8L, adaptive.measurement().measuredTransactions());
        assertEquals(8L, adaptive.measurement().measuredOperations());
        assertEquals(fixed.measurement().semanticFingerprint(), adaptive.measurement().semanticFingerprint());
        assertEquals(fixed.oracleEvidence(), adaptive.oracleEvidence());
        assertDatabaseFilesRemoved();
        List<String> diagnostics = Files.readAllLines(directory.resolve("page-latch-diagnostics-delos_heap-run-1.csv"));
        assertEquals(3, diagnostics.size());
        int operations = Arrays.asList(diagnostics.getFirst().split(",")).indexOf("measuredOperations");
        assertEquals("8", diagnostics.get(1).split(",")[operations]);
        assertEquals("8", diagnostics.get(2).split(",")[operations]);
    }

    public void testAdaptiveDurationCapPreservesFailureAndRemovesDatabase() throws Exception {
        property("minimumMeasuredSeconds", "3600");
        property("maximumMeasuredIterations", "1");
        try {
            measureReadCase();
            fail("Unreachable measured duration accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unable to reach minimum measured duration"));
        }
        assertDatabaseFilesRemoved();
    }

    public void testFixtureFamiliesAndCoveringIndexOnRealDatabase() throws Exception {
        property("rows", "1000");
        property("transactionsPerClient", "20");
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        DelosBenchmarkConfig fixture = new DelosBenchmarkConfig(1000, 32, BenchmarkModel.SEED, 100);
        for (Workload workload : List.of(Workload.JOIN_INDEXED_1TO1, Workload.JOIN_INDEXED_FANOUT,
                Workload.JOIN_3WAY_SELECTIVE, Workload.GROUP_HIGH_CARD, Workload.BANK_TRANSACTION,
                Workload.ORDER_ENTRY_MIX, Workload.PRIVATE_TABLE_DISJOINT, Workload.RANGE_SCAN_COVERING_1000)) {
            Path database = directory.resolve("fixture-" + workload.name().toLowerCase(java.util.Locale.ROOT));
            try {
                try (Connection connection = BenchmarkCaseMeasurement.connect(configuration, database)) {
                    try {
                        List<String> tables = BenchmarkFixtures.prepareTables(connection, configuration,
                                new Spec(workload, 2, 1), fixture);
                        assertEquals(workload == Workload.PRIVATE_TABLE_DISJOINT ? 2 : 1, tables.size());
                        for (String table : tables) assertEquals(1000L, count(connection, table));
                        String table = tables.getFirst();
                        switch (workload) {
                            case JOIN_INDEXED_1TO1 -> assertEquals(1000L, count(connection, BenchmarkFixtures.joinDimensionTableName(table)));
                            case JOIN_INDEXED_FANOUT -> {
                                assertEquals(100L, count(connection, BenchmarkFixtures.joinFanoutParentTableName(table)));
                                assertEquals(1000L, count(connection, BenchmarkFixtures.joinFanoutChildTableName(table)));
                            }
                            case JOIN_3WAY_SELECTIVE -> {
                                assertEquals(100L, count(connection, BenchmarkFixtures.multiJoinCustomerTableName(table)));
                                assertEquals(400L, count(connection, BenchmarkFixtures.multiJoinOrderTableName(table)));
                                assertEquals(1200L, count(connection, BenchmarkFixtures.multiJoinLineTableName(table)));
                                assertEquals(100L, count(connection, BenchmarkFixtures.multiJoinItemTableName(table)));
                            }
                            case GROUP_HIGH_CARD -> assertEquals(1000L, count(connection, BenchmarkFixtures.highCardGroupTableName(table)));
                            case BANK_TRANSACTION -> {
                                assertEquals(10L, count(connection, BenchmarkFixtures.bankBranchTableName(table)));
                                assertEquals(100L, count(connection, BenchmarkFixtures.bankTellerTableName(table)));
                                assertEquals(0L, count(connection, BenchmarkFixtures.bankHistoryTableName(table)));
                            }
                            case ORDER_ENTRY_MIX -> {
                                assertEquals(4L, count(connection, BenchmarkFixtures.orderWarehouseTableName(table)));
                                assertEquals(1000L, count(connection, BenchmarkFixtures.orderCustomerTableName(table)));
                                assertEquals(2L, count(connection, BenchmarkFixtures.orderTableName(table)));
                                assertEquals(0L, count(connection, BenchmarkFixtures.orderLineTableName(table)));
                            }
                            case RANGE_SCAN_COVERING_1000 -> {
                                boolean found = false;
                                try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, "APP", table.toUpperCase(java.util.Locale.ROOT), false, false)) {
                                    while (indexes.next()) found |= BenchmarkFixtures.rangeCoveringIndexName(table)
                                            .equalsIgnoreCase(indexes.getString("INDEX_NAME"));
                                }
                                assertTrue("Covering index missing", found);
                            }
                            default -> { }
                        }
                        connection.commit();
                    } finally {
                        connection.rollback();
                    }
                }
            } finally {
                assertNull(BenchmarkCaseMeasurement.shutdownDerby(database, null));
                BenchmarkCaseMeasurement.deleteRecursively(database);
            }
        }
    }

    public void testDiagnosticMathAppendAndPrimaryFailureRetention() throws Exception {
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        Spec spec = new Spec(Workload.PRIMARY_KEY_READ_DISJOINT, 2, 1);
        DelosBenchmarkConfig fixture = new DelosBenchmarkConfig(200, 32, BenchmarkModel.SEED, 100);
        long[] counters = {10, 2, 3, 1000, 0, 0, 1, 1};
        BenchmarkCaseDiagnostics.writePageLatchDiagnostics(configuration, spec, fixture, 4, counters);
        BenchmarkCaseDiagnostics.writePageLatchDiagnostics(configuration, spec, fixture, 0, new long[8]);
        List<String> lines = Files.readAllLines(directory.resolve("page-latch-diagnostics-delos_heap-run-1.csv"));
        assertEquals(3, lines.size());
        List<String> header = Arrays.asList(lines.getFirst().split(","));
        assertEquals("20.000000", lines.get(1).split(",")[header.indexOf("contendedPercent")]);
        assertEquals("250.000000", lines.get(1).split(",")[header.indexOf("ownerWaitNanosPerOperation")]);
        assertEquals("0.000000", lines.get(2).split(",")[header.indexOf("ownerWaitNanosPerOperation")]);
        Exception primary = new Exception("measurement failed");
        Exception cleanup = new Exception("cleanup failed");
        assertSame(primary, BenchmarkCaseMeasurement.preserve(primary, cleanup));
        assertSame(cleanup, primary.getSuppressed()[0]);
        assertSame(primary, BenchmarkCaseMeasurement.preserve(primary, primary));
        assertEquals(1, primary.getSuppressed().length);
    }

    private MeasuredSpec measureReadCase() throws Exception {
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        configuration.validate();
        return BenchmarkCaseMeasurement.measureSpec(configuration,
                new DelosBenchmarkConfig(200, 32, BenchmarkModel.SEED, 100), new Spec(Workload.PRIMARY_KEY_READ_DISJOINT, 2, 1));
    }

    private void assertDatabaseFilesRemoved() throws Exception {
        try (var files = Files.walk(directory.resolve("databases"))) {
            assertEquals(0L, files.filter(Files::isRegularFile).count());
        }
    }

    private static long count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void property(String name, String value) {
        System.setProperty(PREFIX + name, value);
    }
}
