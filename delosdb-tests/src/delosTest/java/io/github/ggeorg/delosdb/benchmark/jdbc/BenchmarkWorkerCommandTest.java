/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ServerEndpoint;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import junit.framework.TestCase;

/** Configuration constraints and the actual child JVM argument contract. */
public final class BenchmarkWorkerCommandTest extends TestCase {
    private static final String PREFIX = "delosdb.benchmark.crossEngineConcurrency.";
    private Properties original;

    @Override
    protected void setUp() {
        original = (Properties) System.getProperties().clone();
        for (String name : original.stringPropertyNames()) {
            if (name.startsWith(PREFIX) || name.startsWith("delosdb.experimental.")
                    || name.equals("delosdb.diagnostic.heapPageReadImage")) {
                System.clearProperty(name);
            }
        }
    }

    @Override
    protected void tearDown() {
        System.setProperties(original);
    }

    public void testDefaultsAndInvalidCampaignOptions() {
        BenchmarkConfiguration defaults = BenchmarkConfiguration.fromSystemProperties();
        defaults.validate();
        assertEquals(List.of(Target.DELOS_HEAP, Target.DELOS_MVCC, Target.UPSTREAM_DERBY,
                Target.H2, Target.SQLITE), defaults.targetValues());
        assertEquals(4, defaults.runs());
        assertEquals(0, defaults.workerTimeoutSeconds());
        assertEquals(120, defaults.caseTimeoutSeconds());
        assertEquals("1g", defaults.childHeap());
        assertRejected("runs", "2", "runs must be a multiple of 4");
        assertRejected("clients", "10001", "clients cannot exceed rows");
        assertRejected("workloads", "VALUES_1,VALUES_1", "Duplicate concurrency workload");
        assertRejected("targets", "h2,h2", "Duplicate target");
        assertRejected("minimumMeasuredSeconds", "NaN", "Invalid concurrency benchmark numeric option");
        assertRejected("workerTimeoutSeconds", "-1", "Invalid concurrency benchmark numeric option");
        property("target", "delos_heap");
        assertRejected("run", "0", "worker run must be positive");
    }

    public void testTransactionBudgetsAndNormalizedWorkloads() {
        property("workloads", " range_scan_100 , pk_read_1_rc , primary_key_read_hot ");
        property("transactionsPerClient", "7");
        property("fixedWorkloadOperationBudgetPerClient", "230");
        property("rangeScanTargetRowsPerClient", "250");
        property("rangeScanMinQueriesPerClient", "3");
        property("rangeScanMaxQueriesPerClient", "10");
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        assertEquals(List.of(Workload.RANGE_SCAN_100, Workload.PK_READ_1_RC,
                Workload.PRIMARY_KEY_READ_HOT), configuration.workloadValues());
        assertEquals(3, configuration.transactionsPerClient(new Spec(Workload.RANGE_SCAN_100, 1, 2), 50));
        assertEquals(230, configuration.transactionsPerClient(new Spec(Workload.PK_READ_1_RC, 1, 1), 50));
        assertEquals(7, configuration.transactionsPerClient(new Spec(Workload.PRIMARY_KEY_READ_HOT, 1, 10), 50));
        assertEquals(230, configuration.transactionsPerClient(new Spec(Workload.EMPTY_TRANSACTION, 1, 0), 50));
    }

    public void testTargetClasspathsAndDrdaEvidenceClientSelection() throws Exception {
        property("benchmarkClasses", "benchmark classes with spaces");
        String[] properties = {"delosClasspath", "upstreamDerbyClasspath", "h2Classpath",
                "sqliteClasspath", "delosClientClasspath", "upstreamDerbyClientClasspath",
                "postgresqlClasspath", "mariadbClasspath", "firebirdClasspath"};
        for (String name : properties) {
            property(name, name + " with spaces");
        }
        String[] expected = {properties[0], properties[0], properties[1], properties[2],
                properties[3], properties[4], properties[4], properties[5], properties[2],
                properties[6], properties[7], properties[8]};
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        Target[] targets = Target.values();
        assertEquals(expected.length, targets.length);
        for (int index = 0; index < targets.length; index++) {
            List<String> command = BenchmarkWorkerCommand.build(configuration, targets[index], 2, null);
            assertEquals("benchmark classes with spaces" + File.pathSeparator
                    + expected[index] + " with spaces", command.get(command.indexOf("-cp") + 1));
            assertEquals(targets[index] == Target.SQLITE,
                    command.contains("--enable-native-access=ALL-UNNAMED"));
        }
        property("drdaProtocolEvidence", "true");
        List<String> evidence = BenchmarkWorkerCommand.build(configuration, Target.UPSTREAM_DERBY_DRDA, 2, null);
        assertEquals("benchmark classes with spaces" + File.pathSeparator
                + "delosClientClasspath with spaces", evidence.get(evidence.indexOf("-cp") + 1));
        assertTrue(evidence.contains("-Ddelosdb.diagnostic.drdaProtocolEvidence=true"));
        assertFalse(BenchmarkWorkerCommand.build(configuration, Target.H2, 2, null)
                .contains("-Ddelosdb.diagnostic.drdaProtocolEvidence=true"));
    }

    public void testEngineFlagsAndPairedOccupancyRemainTargetScoped() throws Exception {
        System.setProperty("delosdb.experimental.heapPageReadImage", "true");
        property("mvccGen2C3ReadServer", "true");
        property("mvccPhysicalScanCost", "false");
        property("mvccSnapshotLeaseRegistry", "true");
        property("mvccSnapshotLeaseRegistrySlots", " 128 ");
        property("rawStoreOccupancyCampaign", "true");
        property("rawStoreOccupancyEnabled", "paired");
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        for (int run = 1; run <= 4; run++) {
            boolean occupancy = run == 2 || run == 3;
            List<String> heap = BenchmarkWorkerCommand.build(configuration, Target.DELOS_HEAP, run, null);
            assertTrue(heap.contains("-Ddelosdb.experimental.heapPageReadImage=true"));
            assertEquals(occupancy, heap.contains("-Ddelosdb.diagnostic.rawStoreOccupancy.enabled=true"));
            assertEquals(occupancy, heap.contains("-Ddelosdb.diagnostic.rawStoreOccupancy.sampleEvery=32"));
            for (Target target : List.of(Target.DELOS_MVCC, Target.UPSTREAM_DERBY, Target.H2)) {
                List<String> command = BenchmarkWorkerCommand.build(configuration, target, run, null);
                assertTrue(command.contains("-D" + PREFIX + "rawStoreOccupancyMode=" + occupancy));
                assertFalse(command.contains("-Ddelosdb.diagnostic.rawStoreOccupancy.enabled=true"));
                assertFalse(command.contains("-Ddelosdb.experimental.heapPageReadImage=true"));
                assertEquals(target == Target.DELOS_MVCC,
                        command.contains("-Ddelosdb.experimental.mvccGen2C2.archivedUndo.enabled=true"));
                assertEquals(target == Target.DELOS_MVCC,
                        command.contains("-Ddelosdb.experimental.mvccPhysicalScanCost.enabled=false"));
                assertEquals(target == Target.DELOS_MVCC,
                        command.contains("-Ddelosdb.experimental.mvccSnapshotLeaseRegistry.slots=128"));
            }
        }
    }

    public void testBuiltCommandRunsWithSpacesEndpointAndProfiling() throws Exception {
        Path directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos command ").toRealPath();
        property("benchmarkClasses", System.getProperty("java.class.path"));
        property("h2Classpath", System.getProperty("java.class.path"));
        property("reportDirectory", directory.toString());
        property("childHeap", "128m");
        property("profileTargets", " H2 ");
        property("rangeBulkFetchDefault", " 16 ");
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        List<String> command = new ArrayList<>(BenchmarkWorkerCommand.build(configuration, Target.H2, 3,
                new ServerEndpoint("jdbc:fixture:with spaces", "fixture user", "fixture password")));
        assertEquals(DelosJdbcCrossEngineConcurrency.class.getName(), command.remove(command.size() - 2));
        assertEquals("worker", command.removeLast());
        command.add(Fixture.class.getName());
        Path log = directory.resolve("child.log");
        new BenchmarkWorkerProcess(command, directory, log, 20, "fixture", 3).execute(process -> () -> {});
        String output = Files.readString(log);
        assertTrue(output, output.contains("target=h2, run=3, bulkFetch=16"));
        assertTrue(output, output.contains("endpoint=jdbc:fixture:with spaces, user=fixture user, password=fixture password"));
        assertTrue(Files.size(directory.resolve("profiles/03-h2.jfr")) > 0);
        assertTrue(Files.size(directory.resolve("profiles/03-h2-hotspot.log")) > 0);
        assertFalse(BenchmarkWorkerCommand.build(configuration, Target.DELOS_HEAP, 3, null).stream()
                .anyMatch(argument -> argument.startsWith("-XX:StartFlightRecording=")));
    }

    private static void property(String name, String value) {
        System.setProperty(PREFIX + name, value);
    }

    private static void assertRejected(String name, String value, String reason) {
        String previous = System.getProperty(PREFIX + name);
        property(name, value);
        try {
            BenchmarkConfiguration.fromSystemProperties().validate();
            fail("Invalid " + name + " accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(reason));
        } finally {
            if (previous == null) System.clearProperty(PREFIX + name);
            else property(name, previous);
        }
    }

    public static final class Fixture {
        public static void main(String[] args) {
            System.out.println("target=" + System.getProperty(PREFIX + "target")
                    + ", run=" + System.getProperty(PREFIX + "run")
                    + ", bulkFetch=" + System.getProperty(PREFIX + "rangeBulkFetchDefault"));
            System.out.println("endpoint=" + System.getProperty(PREFIX + "remoteJdbcUrl")
                    + ", user=" + System.getProperty(PREFIX + "remoteUser")
                    + ", password=" + System.getProperty(PREFIX + "remotePassword"));
        }
    }
}
