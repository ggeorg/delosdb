/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Interval;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import junit.framework.TestCase;

/** Real concurrent intervals prove restoration and borrowed/owned resource boundaries. */
public final class BenchmarkConcurrentCaseTest extends TestCase {
    private Properties original;
    private Path directory;

    @Override
    protected void setUp() throws Exception {
        original = (Properties) System.getProperties().clone();
        for (String name : original.stringPropertyNames()) {
            if (name.startsWith(BenchmarkConfiguration.PREFIX)) System.clearProperty(name);
        }
        directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos-concurrent-case-");
        property("target", "delos_heap");
        property("run", "1");
        property("rows", "1000");
        property("widths", "1");
        property("payload", "32");
        property("fixtureBatch", "100");
        property("caseTimeoutSeconds", "30");
        property("reportDirectory", directory.toString());
        property("sqlSemanticOracle", "true");
    }

    @Override
    protected void tearDown() {
        System.setProperties(original);
    }

    public void testInsertUpdateAndDeleteIntervalsRestoreCompleteRows() throws Exception {
        for (Workload workload : List.of(Workload.INSERT_100, Workload.DISJOINT_INDEXED_UPDATE,
                Workload.CONTENDED_INDEXED_UPDATE, Workload.DELETE_REINSERT)) {
            verifyRepeatedIntervals(workload, 2, 2, workload == Workload.INSERT_100 ? 100 : 1);
        }
    }

    public void testBankAndOrderEntryRestoreAuxiliaryState() throws Exception {
        verifyRepeatedIntervals(Workload.BANK_TRANSACTION, 2, 20, 1);
        verifyRepeatedIntervals(Workload.ORDER_ENTRY_MIX, 2, 20, 1);
    }

    public void testMixedAndLongReaderWriterIntervalsRestoreState() throws Exception {
        verifyRepeatedIntervals(Workload.MIXED_80R20W, 8, 10, 1);
        verifyRepeatedIntervals(Workload.LONG_READER_DISJOINT_WRITER, 4, 2, 1);
    }

    public void testBlockedWriterTimeoutCancelsClientsAndKeepsVerifierOpen() throws Exception {
        BenchmarkConfiguration configuration = configure(Workload.CONTENDED_INDEXED_UPDATE, 2, 1);
        property("caseTimeoutSeconds", "1");
        configuration = BenchmarkConfiguration.fromSystemProperties();
        Path database = directory.resolve("timeout");
        try {
            try (Connection verifier = BenchmarkCaseMeasurement.connect(configuration, database)) {
                List<String> tables = BenchmarkFixtures.prepareTables(verifier, configuration,
                        new Spec(Workload.CONTENDED_INDEXED_UPDATE, 2, 1), fixture());
                try (BenchmarkConcurrentCase concurrentCase = new BenchmarkConcurrentCase(
                        configuration, new Spec(Workload.CONTENDED_INDEXED_UPDATE, 2, 1), database, verifier, tables, 1000)) {
                    try (Connection blocker = BenchmarkCaseMeasurement.connect(configuration, database)) {
                        blocker.setAutoCommit(false);
                        try {
                            try (Statement statement = blocker.createStatement()) {
                                statement.executeUpdate("update " + tables.getFirst() + " set quantity=quantity");
                            }
                            try {
                                concurrentCase.runInterval(false);
                                fail("Blocked interval accepted");
                            } catch (IllegalStateException expected) {
                                assertTrue(expected.getMessage(), expected.getMessage().contains("concurrency interval exceeded 1 seconds"));
                                assertTrue(expected.getCause() instanceof java.util.concurrent.TimeoutException);
                            }
                        } finally {
                            blocker.rollback();
                        }
                    }
                }
                assertFalse(verifier.isClosed());
                verifier.rollback();
                assertNoClientThreads();
            }
        } finally {
            assertNull(BenchmarkCaseMeasurement.shutdownDerby(database, null));
            BenchmarkCaseMeasurement.deleteRecursively(database);
        }
    }

    private void verifyRepeatedIntervals(Workload workload, int clients, int transactions, int width) throws Exception {
        BenchmarkConfiguration configuration = configure(workload, clients, transactions);
        Spec spec = new Spec(workload, clients, width);
        Path database = directory.resolve(workload.name().toLowerCase(java.util.Locale.ROOT));
        try {
            try (Connection verifier = BenchmarkCaseMeasurement.connect(configuration, database)) {
                List<String> tables = BenchmarkFixtures.prepareTables(verifier, configuration, spec, fixture());
                String table = tables.getFirst();
                List<String> stateTables = stateTables(workload, table);
                List<DelosSqlSemanticOracle.Result> baseline = snapshot(verifier, stateTables);
                verifier.rollback();
                try (BenchmarkConcurrentCase concurrentCase = new BenchmarkConcurrentCase(
                        configuration, spec, database, verifier, tables, 1000)) {
                    Interval first = concurrentCase.runInterval(true);
                    assertNotNull(first.sqlOracleResult());
                    assertEquals(workload.name(), baseline, snapshot(verifier, stateTables));
                    verifier.rollback();
                    Interval second = concurrentCase.runInterval(true);
                    assertEquals(first.semanticFingerprint(), second.semanticFingerprint());
                    assertEquals(first.sqlOracleResult(), second.sqlOracleResult());
                    assertEquals(workload.name(), baseline, snapshot(verifier, stateTables));
                    verifier.rollback();
                }
                assertFalse(verifier.isClosed());
                assertEquals(baseline, snapshot(verifier, stateTables));
                verifier.rollback();
                assertNoClientThreads();
            }
        } finally {
            assertNull(BenchmarkCaseMeasurement.shutdownDerby(database, null));
            BenchmarkCaseMeasurement.deleteRecursively(database);
        }
    }

    private BenchmarkConfiguration configure(Workload workload, int clients, int transactions) {
        property("workloads", workload.name());
        property("clients", Integer.toString(clients));
        property("transactionsPerClient", Integer.toString(transactions));
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        configuration.validate();
        return configuration;
    }

    private static List<String> stateTables(Workload workload, String table) {
        List<String> tables = new ArrayList<>(List.of(table));
        if (workload == Workload.BANK_TRANSACTION) {
            tables.addAll(List.of(BenchmarkFixtures.bankBranchTableName(table),
                    BenchmarkFixtures.bankTellerTableName(table), BenchmarkFixtures.bankHistoryTableName(table)));
        } else if (workload == Workload.ORDER_ENTRY_MIX) {
            tables.addAll(List.of(BenchmarkFixtures.orderWarehouseTableName(table),
                    BenchmarkFixtures.orderCustomerTableName(table), BenchmarkFixtures.orderTableName(table),
                    BenchmarkFixtures.orderLineTableName(table)));
        }
        return tables;
    }

    private static List<DelosSqlSemanticOracle.Result> snapshot(Connection connection, List<String> tables) throws Exception {
        List<DelosSqlSemanticOracle.Result> results = new ArrayList<>();
        for (String table : tables) {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("select * from " + table)) {
                results.add(DelosSqlSemanticOracle.query(rows, DelosSqlSemanticOracle.RowOrder.UNORDERED));
            }
        }
        return results;
    }

    private static DelosBenchmarkConfig fixture() {
        return new DelosBenchmarkConfig(1000, 32, BenchmarkModel.SEED, 100);
    }

    private static void assertNoClientThreads() {
        long active = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive).filter(thread -> thread.getName().startsWith("delos-bench-client-")).count();
        assertEquals("Case-owned client threads remained after close", 0L, active);
    }

    private static void property(String name, String value) {
        System.setProperty(BenchmarkConfiguration.PREFIX + name, value);
    }
}
