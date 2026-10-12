/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DrdaProtocolEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.OracleEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import junit.framework.TestCase;

/** Worker scheduling and report completeness, including an actual failing case. */
public final class BenchmarkWorkloadRunnerTest extends TestCase {
    private static final String PREFIX = BenchmarkConfiguration.PREFIX;
    private Properties original;

    @Override
    protected void setUp() {
        original = (Properties) System.getProperties().clone();
        for (String name : original.stringPropertyNames()) {
            if (name.startsWith(PREFIX)) System.clearProperty(name);
        }
    }

    @Override
    protected void tearDown() {
        System.setProperties(original);
    }

    public void testOrthogonalCaseOrderPreservesFixedAndVariableWidths() {
        property("clients", "1,2");
        property("widths", "1,3");
        property("workloads", "VALUES_10,PRIMARY_KEY_READ_HOT,EMPTY_TRANSACTION");
        List<Spec> forward = List.of(new Spec(Workload.VALUES_10, 1, 10),
                new Spec(Workload.PRIMARY_KEY_READ_HOT, 1, 1), new Spec(Workload.PRIMARY_KEY_READ_HOT, 1, 3),
                new Spec(Workload.EMPTY_TRANSACTION, 1, 0), new Spec(Workload.VALUES_10, 2, 10),
                new Spec(Workload.PRIMARY_KEY_READ_HOT, 2, 1), new Spec(Workload.PRIMARY_KEY_READ_HOT, 2, 3),
                new Spec(Workload.EMPTY_TRANSACTION, 2, 0));
        List<Spec> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        List<Integer> rows = List.of(10, 20);
        for (int run = 1; run <= 8; run++) {
            property("run", Integer.toString(run));
            BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
            assertEquals(8, BenchmarkWorkloadRunner.specsPerRun(configuration));
            int phase = (run - 1) % 4;
            assertEquals(phase == 1 || phase == 2 ? reverse : forward,
                    BenchmarkWorkloadRunner.specsForRun(configuration));
            assertEquals(run % 2 == 0 ? List.of(20, 10) : rows,
                    BenchmarkWorkloadRunner.ordered(rows, run));
        }
        assertEquals(List.of(10, 20), rows);
    }

    public void testEvidenceReportsRejectIncompleteCasesAndRoundTripCompleteData() throws Exception {
        Path directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos-runner-reports");
        property("reportDirectory", directory.toString());
        property("target", "delos_heap_drda");
        property("run", "3");
        property("rows", "100,200");
        property("clients", "1");
        property("widths", "1");
        property("workloads", "RANGE_SCAN_100");
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        OracleEvidence first = new OracleEvidence("delos_heap_drda", Workload.RANGE_SCAN_100,
                1, 1, 100, "fixture", 100, "123", 3);
        Path oracle = directory.resolve("sql-semantic-oracle-delos_heap_drda-run-3.csv");
        try {
            BenchmarkWorkloadRunner.writeWorkerOracleEvidence(configuration, List.of(first));
            fail("Incomplete oracle report accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("expected=2, actual=1"));
        }
        assertFalse(Files.exists(oracle));
        OracleEvidence second = new OracleEvidence("delos_heap_drda", Workload.RANGE_SCAN_100,
                1, 1, 200, "fixture", 200, "456", 3);
        BenchmarkWorkloadRunner.writeWorkerOracleEvidence(configuration, List.of(first, second));
        List<String> lines = Files.readAllLines(oracle);
        assertEquals(3, lines.size());
        assertEquals("target,workload,clients,operationsPerTransaction,rowCount,kind,count,fingerprint,run", lines.getFirst());
        assertEquals(first, OracleEvidence.parse(lines.get(1)));
        assertEquals(second, OracleEvidence.parse(lines.get(2)));
        long[] counters = new long[16];
        counters[0] = 10; counters[3] = 100; counters[5] = 2; counters[6] = 3;
        counters[12] = 100; counters[13] = 200; counters[14] = 300; counters[15] = 400;
        DrdaProtocolEvidence protocol = DrdaProtocolEvidence.from(configuration,
                new Spec(Workload.RANGE_SCAN_100, 1, 1), new DelosBenchmarkConfig(200, 128, 1L, 100),
                "fixture", "1", "1", 10, 10, 2000, null, counters);
        assertEquals(1000L, protocol.measuredResultRows());
        assertEquals(5L, protocol.fetchRequests());
        assertEquals(1000L, protocol.totalTimedFlowNanos());
        assertEquals(0.5d, protocol.timedFlowShareOfMeasuredElapsed());
        assertEquals(protocol.csv(), DrdaProtocolEvidence.parse(protocol.csv()).csv());
        Path report = directory.resolve("drda-protocol-evidence-delos_heap_drda-run-3.csv");
        try {
            BenchmarkWorkloadRunner.writeWorkerDrdaProtocolEvidence(configuration, List.of(protocol));
            fail("Incomplete protocol report accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("expected=2, actual=1"));
        }
        assertFalse(Files.exists(report));
        BenchmarkWorkloadRunner.writeWorkerDrdaProtocolEvidence(configuration, List.of(protocol, protocol));
        assertEquals(3, Files.readAllLines(report).size());
    }

    public void testFailedCaseDoesNotPublishCompletedWorkerReports() throws Exception {
        Path directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos-runner-failure");
        property("reportDirectory", directory.toString());
        property("databaseRoot", directory.resolve("database").toString());
        property("target", "postgresql");
        property("remoteJdbcUrl", "jdbc:delos-missing-fixture-driver:failure");
        property("run", "1");
        property("rows", "100");
        property("clients", "1");
        property("widths", "1");
        property("workloads", "VALUES_1");
        property("sqlSemanticOracle", "true");
        property("drdaProtocolEvidence", "true");
        try {
            BenchmarkWorkloadRunner.run(BenchmarkConfiguration.fromSystemProperties());
            fail("Failed case accepted");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("No suitable driver"));
        }
        assertFalse(Files.exists(directory.resolve("postgresql-run-1.csv")));
        assertFalse(Files.exists(directory.resolve("sql-semantic-oracle-postgresql-run-1.csv")));
        assertFalse(Files.exists(directory.resolve("drda-protocol-evidence-postgresql-run-1.csv")));
    }

    private static void property(String name, String value) {
        System.setProperty(PREFIX + name, value);
    }
}
