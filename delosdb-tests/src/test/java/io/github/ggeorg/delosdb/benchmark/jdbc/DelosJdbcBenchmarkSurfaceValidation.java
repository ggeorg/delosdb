/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

/** Executable correctness proof for the stable provider-neutral benchmark surface. */
public final class DelosJdbcBenchmarkSurfaceValidation {
    private DelosJdbcBenchmarkSurfaceValidation() {
    }

    public static void main(String[] args) throws Exception {
        Path databaseRoot = args.length == 0
                ? Path.of("build", "delos-benchmark-surface")
                : Path.of(args[0]);
        deleteRecursively(databaseRoot);
        Path parent = databaseRoot.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        DelosBenchmarkConfig config = DelosBenchmarkConfig.smoke();
        Map<DelosBenchmarkOperation, DelosBenchmarkResult> heap = run(databaseRoot, DelosBenchmarkProvider.HEAP, config);
        Map<DelosBenchmarkOperation, DelosBenchmarkResult> mvcc = run(databaseRoot, DelosBenchmarkProvider.MVCC, config);
        if (!heap.equals(mvcc)) {
            throw new IllegalStateException("Heap/MVCC benchmark semantic mismatch: heap=" + heap + ", mvcc=" + mvcc);
        }
        System.out.println("DelosDB JDBC benchmark surface validation passed: " + heap);
    }

    private static Map<DelosBenchmarkOperation, DelosBenchmarkResult> run(
            Path databaseRoot,
            DelosBenchmarkProvider provider,
            DelosBenchmarkConfig config) throws SQLException {
        String databaseName = databaseRoot + "-" + provider.id();
        try {
            deleteRecursively(Path.of(databaseName));
        } catch (Exception e) {
            throw new SQLException("Unable to reset benchmark database " + databaseName, e);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + databaseName + ";create=true")) {
            try {
                DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(connection, provider, config);
                scenario.prepare();
                return scenario.executeSemanticMatrix();
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.delete(candidate);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
