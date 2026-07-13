/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Executable correctness proof for the stable provider-neutral benchmark surface. */
public final class DelosJdbcBenchmarkSurfaceValidation {
    private DelosJdbcBenchmarkSurfaceValidation() {
    }

    public static void main(String[] args) throws Exception {
        Path databaseRoot = args.length == 0
                ? Path.of("build", "delos-benchmark-surface")
                : Path.of(args[0]);
        DelosBenchmarkSupport.deleteRecursively(databaseRoot);
        Path parent = databaseRoot.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        DelosBenchmarkConfig config = DelosBenchmarkConfig.smoke();
        Map<DelosBenchmarkOperation, DelosBenchmarkResult> heap =
                run(databaseRoot, DelosBenchmarkProvider.HEAP, config);
        Map<DelosBenchmarkOperation, DelosBenchmarkResult> mvcc =
                run(databaseRoot, DelosBenchmarkProvider.MVCC, config);
        if (!heap.equals(mvcc)) {
            throw new IllegalStateException(
                    "Heap/MVCC benchmark semantic mismatch: heap=" + heap + ", mvcc=" + mvcc);
        }
        System.out.println("DelosDB JDBC benchmark surface validation passed: " + heap);
    }

    private static Map<DelosBenchmarkOperation, DelosBenchmarkResult> run(
            Path databaseRoot,
            DelosBenchmarkProvider provider,
            DelosBenchmarkConfig config) throws Exception {
        Path database = Path.of(databaseRoot + "-" + provider.id());
        return DelosBenchmarkSupport.withFreshEmbeddedDatabase(database, connection -> {
            DelosJdbcBenchmarkScenario scenario =
                    new DelosJdbcBenchmarkScenario(connection, provider, config);
            scenario.prepare();
            return scenario.executeSemanticMatrix();
        });
    }

}
