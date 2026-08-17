/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic heap/MVCC validation for the ordered primary-key range-scan surface. */
public final class DelosJdbcRangeScanSurfaceValidation {
    private static final int[] RANGE_ROWS = {1, 10, 100, 500};

    private DelosJdbcRangeScanSurfaceValidation() {
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of("jdbc-range-scan-surface-db") : Path.of(args[0]);
        Map<Integer, RangeSemantic> expected = null;
        for (DelosBenchmarkProvider provider : DelosBenchmarkProvider.values()) {
            Path database = Path.of(root + "-" + provider.id());
            Map<Integer, RangeSemantic> actual = DelosBenchmarkSupport.withFreshEmbeddedDatabase(
                    database,
                    connection -> validateProvider(connection, provider));
            if (expected == null) {
                expected = actual;
            } else if (!expected.equals(actual)) {
                throw new IllegalStateException("Heap/MVCC range-scan semantics differ: expected="
                        + expected + ", actual=" + actual + ", provider=" + provider);
            }
        }
    }

    private static Map<Integer, RangeSemantic> validateProvider(
            Connection connection, DelosBenchmarkProvider provider) throws Exception {
        DelosBenchmarkConfig config = DelosBenchmarkConfig.smoke();
        DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(connection, provider, config);
        scenario.prepare();
        Map<Integer, RangeSemantic> semantics = new LinkedHashMap<>();
        for (int requestedRows : RANGE_ROWS) {
            int rangeRows = Math.min(requestedRows, config.rowCount());
            int start = 1 + (config.rowCount() - rangeRows) / 3;
            int endExclusive = start + rangeRows;
            RangeSemantic first = executeRange(connection, scenario.tableName(), start, endExclusive);
            RangeSemantic second = executeRange(connection, scenario.tableName(), start, endExclusive);
            if (!first.equals(second)) {
                throw new IllegalStateException("Repeated range scan changed for provider=" + provider
                        + ", rangeRows=" + rangeRows + ": first=" + first + ", second=" + second);
            }
            if (first.rows() != rangeRows) {
                throw new IllegalStateException("Range scan returned " + first.rows()
                        + " rows instead of " + rangeRows + " for provider=" + provider);
            }
            semantics.put(rangeRows, first);
        }
        connection.rollback();
        return Map.copyOf(semantics);
    }

    private static RangeSemantic executeRange(
            Connection connection, String table, int start, int endExclusive) throws Exception {
        long fingerprint = 1L;
        int rows = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, quantity from " + table + " where id >= ? and id < ? order by id")) {
            statement.setInt(1, start);
            statement.setInt(2, endExclusive);
            try (ResultSet resultSet = statement.executeQuery()) {
                int expectedId = start;
                while (resultSet.next()) {
                    int id = resultSet.getInt(1);
                    int quantity = resultSet.getInt(2);
                    if (id != expectedId++) {
                        throw new IllegalStateException("Range scan returned unexpected id order: id=" + id
                                + ", expected=" + (expectedId - 1));
                    }
                    fingerprint = mix(mix(fingerprint, id), quantity);
                    rows++;
                }
            }
        }
        return new RangeSemantic(rows, fingerprint);
    }

    private static long mix(long current, long value) {
        return 31L * current + value;
    }

    private record RangeSemantic(int rows, long fingerprint) {
    }
}
