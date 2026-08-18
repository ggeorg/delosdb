/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Audits the benchmark assets that form the starting point for the permanent
 * DelosDB architecture-fitness matrix.
 *
 * <p>This is inventory infrastructure, not a performance benchmark. It keeps
 * Step 1 executable by verifying the source assets we intend to reuse and by
 * writing one canonical coverage report for the thirteen frozen workload
 * families.</p>
 */
public final class DelosArchitectureFitnessInventory {
    private static final String PROJECT_DIRECTORY_PROPERTY =
            "delosdb.benchmark.fitnessInventory.projectDirectory";
    private static final String REPORT_DIRECTORY_PROPERTY =
            "delosdb.benchmark.fitnessInventory.reportDirectory";

    private DelosArchitectureFitnessInventory() {
    }

    public static void main(String[] args) throws Exception {
        Path projectDirectory = requiredDirectory(PROJECT_DIRECTORY_PROPERTY);
        Path reportDirectory = Path.of(requiredProperty(REPORT_DIRECTORY_PROPERTY));
        List<Family> families = families();

        verifySources(projectDirectory, families);
        Files.createDirectories(reportDirectory);
        writeTsv(reportDirectory.resolve("architecture-fitness-inventory.tsv"), families);
        writeSummary(reportDirectory.resolve("architecture-fitness-inventory.txt"), families);

        System.out.println("DelosDB architecture fitness inventory complete: " + reportDirectory);
    }

    private static List<Family> families() {
        return List.of(
                family(1, "Simple indexed reads", Coverage.STRONG, Lane.BOTH,
                        "Existing cross-engine and server sentinels; retain as permanent regression fitness.",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcBenchmarkScenario.java",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcCrossEngineConcurrency.java",
                        "delosdb-tests/build.gradle"),
                family(2, "Range/index scans", Coverage.STRONG, Lane.BOTH,
                        "Existing range, full-scan, index-only and server comparison coverage.",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcRangeScanSurfaceValidation.java",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcCrossEngineConcurrency.java",
                        "delosdb-tests/build.gradle"),
                family(3, "Projection/materialization", Coverage.PARTIAL, Lane.EMBEDDED,
                        "Promote existing JMH width/projection shapes and inherited index-scan width cases into the unified SQL fitness surface.",
                        "benchmarks/jmh/src/jmh/java/io/github/ggeorg/delosdb/benchmark/jmh/DelosJdbcJmhState.java",
                        "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/basic/jdbc/IndexScanTest.java"),
                family(4, "Simple joins", Coverage.SOURCE_ONLY, Lane.EMBEDDED,
                        "Reuse inherited index-join workload semantics; build a modern cross-engine fitness case.",
                        "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/clients/IndexJoinClient.java"),
                family(5, "Multi-way joins", Coverage.MISSING, Lane.NONE,
                        "Build a new SQL-authoritative multi-way join sentinel; no suitable current fitness workload was found."),
                family(6, "Aggregation / GROUP BY", Coverage.PARTIAL, Lane.EMBEDDED,
                        "Promote existing aggregate SQL and inherited controllable GROUP BY workload into the unified matrix.",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcBenchmarkScenario.java",
                        "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/clients/GroupByClient.java"),
                family(7, "Sort / ORDER BY", Coverage.PARTIAL, Lane.EMBEDDED,
                        "Promote dedicated inherited sort cases and ordered-access cases into an isolated sort fitness family.",
                        "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/basic/jdbc/SortTest.java",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcBenchmarkScenario.java"),
                family(8, "INSERT", Coverage.SOURCE_ONLY, Lane.EMBEDDED,
                        "Fixture/load insert machinery exists, but an authoritative timed INSERT family must be added.",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcBenchmarkScenario.java",
                        "delosdb-tests/src/test/java/org/apache/derbyTesting/system/oe/load/SimpleInsert.java"),
                family(9, "Indexed UPDATE", Coverage.STRONG, Lane.BOTH,
                        "Existing transaction, disjoint/contended concurrency and server-target machinery is reusable.",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcBenchmarkScenario.java",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcCrossEngineConcurrency.java"),
                family(10, "DELETE / reinsert", Coverage.PARTIAL, Lane.EMBEDDED,
                        "Strong embedded and RawStore attribution coverage exists; server-lane fitness remains to be unified.",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcBenchmarkScenario.java",
                        "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcDeleteReinsertAttribution.java"),
                family(11, "Mixed readers + writers", Coverage.MISSING, Lane.NONE,
                        "Build a deterministic mixed reader/writer sentinel using the existing concurrency target/runtime machinery."),
                family(12, "Long reader + writers", Coverage.SOURCE_ONLY, Lane.EMBEDDED,
                        "Correctness/stress proof exists; add decision-quality throughput and interference evidence.",
                        "delosdb-tests/src/delosTest/java/org/apache/derbyTesting/functionTests/tests/delos/MvccSqlLongReaderPurgeStressTest.java"),
                family(13, "Realistic transactional workload", Coverage.SOURCE_ONLY, Lane.EMBEDDED,
                        "Adapt the inherited bank transaction as the compact sentinel and Order Entry as the richer adversarial workload.",
                        "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/clients/BankTransactionClient.java",
                        "delosdb-tests/src/test/java/org/apache/derbyTesting/system/oe/client/Submitter.java",
                        "delosdb-tests/src/test/java/org/apache/derbyTesting/system/oe/direct/Standard.java"));
    }

    private static Family family(
            int id,
            String name,
            Coverage coverage,
            Lane lane,
            String nextAction,
            String... sources) {
        return new Family(id, name, coverage, lane, nextAction, List.of(sources));
    }

    private static void verifySources(Path projectDirectory, List<Family> families) throws IOException {
        List<String> missing = new ArrayList<>();
        for (Family family : families) {
            for (String source : family.sources()) {
                if (!Files.isRegularFile(projectDirectory.resolve(source))) {
                    missing.add(String.format(Locale.ROOT, "%02d %s -> %s", family.id(), family.name(), source));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Architecture-fitness inventory references missing source assets:\n"
                    + String.join("\n", missing));
        }
    }

    private static void writeTsv(Path output, List<Family> families) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("familyId\tworkloadFamily\tcoverage\tlane\tnextAction\treusableSources\n");
        for (Family family : families) {
            text.append(family.id()).append('\t')
                    .append(family.name()).append('\t')
                    .append(family.coverage()).append('\t')
                    .append(family.lane()).append('\t')
                    .append(family.nextAction()).append('\t')
                    .append(String.join(";", family.sources()))
                    .append('\n');
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path output, List<Family> families) throws IOException {
        long strong = count(families, Coverage.STRONG);
        long partial = count(families, Coverage.PARTIAL);
        long sourceOnly = count(families, Coverage.SOURCE_ONLY);
        long missing = count(families, Coverage.MISSING);

        StringBuilder text = new StringBuilder();
        text.append("DelosDB v1 architecture fitness inventory\n")
                .append("======================================\n\n")
                .append("Frozen workload families: ").append(families.size()).append('\n')
                .append("Strong reusable coverage: ").append(strong).append('\n')
                .append("Partial reusable coverage: ").append(partial).append('\n')
                .append("Source/reference workload only: ").append(sourceOnly).append('\n')
                .append("Missing modern fitness workload: ").append(missing).append("\n\n")
                .append("Step 1 decision:\n")
                .append("- Reuse the existing provider-neutral JDBC, cross-engine, server, JMH, and inherited Derby workload assets.\n")
                .append("- Do not create a second benchmark framework.\n")
                .append("- Step 2 must define the compact sentinel cases and promote/build only the missing fitness surfaces.\n")
                .append("- Phase 1 remains under the frozen performance-optimization ban.\n\n")
                .append("Coverage:\n");
        for (Family family : families) {
            text.append(String.format(Locale.ROOT, "%2d. %-34s %-11s %s%n",
                    family.id(), family.name(), family.coverage(), family.lane()));
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static long count(List<Family> families, Coverage coverage) {
        return families.stream().filter(family -> family.coverage() == coverage).count();
    }

    private static Path requiredDirectory(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(name + " is not a directory: " + path);
        }
        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + name);
        }
        return value;
    }

    private enum Coverage {
        STRONG,
        PARTIAL,
        SOURCE_ONLY,
        MISSING
    }

    private enum Lane {
        EMBEDDED,
        SERVER,
        BOTH,
        NONE
    }

    private record Family(
            int id,
            String name,
            Coverage coverage,
            Lane lane,
            String nextAction,
            List<String> sources) {
    }
}
