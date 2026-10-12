/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Measurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Row;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import junit.framework.TestCase;

/** Parent command execution, legacy CLI routing and report acceptance contracts. */
public final class BenchmarkParentOrchestrationTest extends TestCase {
    private Properties original;

    @Override
    protected void setUp() {
        original = (Properties) System.getProperties().clone();
        for (String name : original.stringPropertyNames()) {
            if (name.startsWith(BenchmarkConfiguration.PREFIX) || name.startsWith("delosdb.phase2.")) System.clearProperty(name);
        }
    }

    @Override
    protected void tearDown() {
        System.setProperties(original);
    }

    public void testParentCommandCapturesOutputStatusAndSpaces() throws Exception {
        var result = BenchmarkParentCommand.runCommand(10, command("failure"));
        assertEquals(7, result.exitCode());
        assertTrue(result.output(), result.output().contains("argument with spaces"));
        assertTrue(result.output(), result.output().contains("stderr marker"));
    }

    public void testParentCommandTimeoutTerminatesRealChild() throws Exception {
        var result = BenchmarkParentCommand.runCommand(1, command("sleep"));
        assertEquals(124, result.exitCode());
        assertTrue(result.output(), result.output().contains("Timed out:"));
        String pid = result.output().lines().filter(line -> line.startsWith("pid=")).findFirst().orElseThrow().substring(4);
        assertFalse(ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false));
    }

    public void testAllLegacyCliModesRetainRequiredConfigurationPreflight() throws Exception {
        for (String mode : List.of("phase2a-f05-join-decomposition", "phase2b-f05-cardinality-costing-proof",
                "phase2d-f05-residual-indexed-access", "phase2e-f05-base-fetch-attribution",
                "phase2h-f05-post-hint-residual-attribution", "phase2j-f05-compatibility-native-path",
                "phase2k-f03-projection-materialization", "phase2n-f04-f06-relational-decomposition",
                "phase2o-f06-group-input-jfr", "phase2p-f06-canonical-plan-falsification",
                "phase2q-f07-sort-decomposition", "phase2r-f07-embedded-concurrency",
                "phase2s-f07-embedded-jfr", "phase2t-f07-streaming-falsification",
                "phase2u-f04-current-join-decomposition", "phase2v-f04-embedded-concurrency")) {
            try {
                DelosJdbcCrossEngineConcurrency.main(new String[] {mode});
                fail("Missing experiment paths accepted for " + mode);
            } catch (IllegalArgumentException expected) {
                assertTrue(mode + ": " + expected.getMessage(), expected.getMessage().startsWith("Missing -Ddelosdb.phase2."));
            }
        }
    }

    public void testReportsRejectMissingMeasurementsAndSemanticDrift() throws Exception {
        Path directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos-parent-reports-");
        property("targets", "delos_heap,upstream_derby"); property("runs", "4");
        property("rows", "1000"); property("clients", "1"); property("widths", "1");
        property("workloads", "PRIMARY_KEY_READ_DISJOINT"); property("reportDirectory", directory.toString());
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        List<Row> rows = new ArrayList<>();
        for (String target : List.of("delos_heap", "upstream_derby")) {
            for (int run = 1; run <= 4; run++) rows.add(row(target, run, 123L));
        }
        BenchmarkReports.validateRows(configuration, rows);
        BenchmarkReports.writeMergedCsv(configuration, rows);
        List<String> lines = Files.readAllLines(directory.resolve("cross-engine-concurrency-results.csv"));
        assertEquals(9, lines.size());
        assertEquals(rows.getFirst(), Row.parse(lines.get(1)));
        try {
            BenchmarkReports.validateRows(configuration, rows.subList(1, rows.size()));
            fail("Missing measurement accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("count mismatch"));
        }
        rows.set(7, row("upstream_derby", 4, 456L));
        try {
            BenchmarkReports.validateRows(configuration, rows);
            fail("Semantic drift accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("semantic mismatch"));
        }
    }

    private static Row row(String target, int run, long fingerprint) {
        return Row.parse(new Measurement(target, "fixture", "1", "1", Workload.PRIMARY_KEY_READ_DISJOINT,
                1, 1, 2, 1000, 32, 100, 0, 0, 1, 2, 2, 0, 1000,
                2000000.0, 2000000.0, 500.0, fingerprint, run).csv());
    }

    private static List<String> command(String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), mode, "argument with spaces");
    }

    private static void property(String name, String value) {
        System.setProperty(BenchmarkConfiguration.PREFIX + name, value);
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.out.println("pid=" + ProcessHandle.current().pid());
            System.out.println(args[1]);
            System.err.println("stderr marker");
            System.out.flush();
            if (args[0].equals("sleep")) Thread.sleep(30000L);
            else System.exit(7);
        }
    }
}
