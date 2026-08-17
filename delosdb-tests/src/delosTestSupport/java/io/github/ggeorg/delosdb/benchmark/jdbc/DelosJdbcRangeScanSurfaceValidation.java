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
    private static final int FILTERED_RANGE_ROWS = 500;
    private static final int FILTERED_MIN_QUANTITY = 5_000;

    private DelosJdbcRangeScanSurfaceValidation() {
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of("jdbc-range-scan-surface-db") : Path.of(args[0]);
        ProviderSemantic expected = null;
        for (DelosBenchmarkProvider provider : DelosBenchmarkProvider.values()) {
            Path database = Path.of(root + "-" + provider.id());
            ProviderSemantic actual = DelosBenchmarkSupport.withFreshEmbeddedDatabase(
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

    private static ProviderSemantic validateProvider(
            Connection connection, DelosBenchmarkProvider provider) throws Exception {
        if (Boolean.getBoolean("delosdb.experimental.heapPageLocalIndexBaseFetch")) {
            connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
            if (connection.getHoldability() != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
                throw new IllegalStateException(
                        "Page-local index-to-base validation requires CLOSE_CURSORS_AT_COMMIT");
            }
        }
        DelosBenchmarkConfig config = DelosBenchmarkConfig.smoke();
        DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(connection, provider, config);
        scenario.prepare();
        Map<Integer, RangeSemantic> semantics = new LinkedHashMap<>();
        for (int requestedRows : RANGE_ROWS) {
            int rangeRows = Math.min(requestedRows, config.rowCount());
            int start = 1 + (config.rowCount() - rangeRows) / 3;
            int endExclusive = start + rangeRows;
            RangeSemantic first = executeRange(connection, scenario.tableName(), start, endExclusive, false);
            RangeSemantic second = executeRange(connection, scenario.tableName(), start, endExclusive, false);
            RangeSemantic indexOnlyFirst = executeRange(
                    connection, scenario.tableName(), start, endExclusive, true);
            RangeSemantic indexOnlySecond = executeRange(
                    connection, scenario.tableName(), start, endExclusive, true);
            if (!first.equals(second)) {
                throw new IllegalStateException("Repeated range scan changed for provider=" + provider
                        + ", rangeRows=" + rangeRows + ": first=" + first + ", second=" + second);
            }
            if (!indexOnlyFirst.equals(indexOnlySecond)) {
                throw new IllegalStateException("Repeated index-only range scan changed for provider=" + provider
                        + ", rangeRows=" + rangeRows + ": first=" + indexOnlyFirst
                        + ", second=" + indexOnlySecond);
            }
            if (first.rows() != rangeRows || indexOnlyFirst.rows() != rangeRows) {
                throw new IllegalStateException("Range scan returned " + first.rows()
                        + " rows instead of " + rangeRows + " for provider=" + provider);
            }
            if (first.indexOnlyFingerprint() != indexOnlyFirst.indexOnlyFingerprint()) {
                throw new IllegalStateException("Index-only and index-to-base scans disagree on ordered ids for provider="
                        + provider + ", rangeRows=" + rangeRows);
            }
            semantics.put(rangeRows, new RangeSemantic(
                    first.rows(),
                    indexOnlyFirst.indexOnlyFingerprint(),
                    first.indexToBaseFingerprint()));
        }
        int filteredStart = 1 + (config.rowCount() - FILTERED_RANGE_ROWS) / 3;
        int filteredEndExclusive = filteredStart + FILTERED_RANGE_ROWS;
        FilteredRangeSemantic filteredFirst = executeFilteredRange(
                connection, scenario.tableName(), filteredStart, filteredEndExclusive);
        FilteredRangeSemantic filteredSecond = executeFilteredRange(
                connection, scenario.tableName(), filteredStart, filteredEndExclusive);
        if (!filteredFirst.equals(filteredSecond)) {
            throw new IllegalStateException("Repeated filtered range scan changed for provider="
                    + provider + ": first=" + filteredFirst + ", second=" + filteredSecond);
        }
        if (filteredFirst.rows() <= 0 || filteredFirst.rows() >= FILTERED_RANGE_ROWS) {
            throw new IllegalStateException("Filtered range must keep some but not all rows for provider="
                    + provider + ": " + filteredFirst);
        }

        connection.rollback();
        return new ProviderSemantic(Map.copyOf(semantics), filteredFirst);
    }

    private static RangeSemantic executeRange(
            Connection connection,
            String table,
            int start,
            int endExclusive,
            boolean indexOnly) throws Exception {
        long indexOnlyFingerprint = 1L;
        long indexToBaseFingerprint = 1L;
        int rows = 0;
        String sql = indexOnly
                ? "select id from " + table + " where id >= ? and id < ? order by id"
                : "select id, quantity from " + table + " where id >= ? and id < ? order by id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, start);
            statement.setInt(2, endExclusive);
            try (ResultSet resultSet = statement.executeQuery()) {
                int expectedId = start;
                while (resultSet.next()) {
                    int id = resultSet.getInt(1);
                    if (id != expectedId++) {
                        throw new IllegalStateException("Range scan returned unexpected id order: id=" + id
                                + ", expected=" + (expectedId - 1));
                    }
                    indexOnlyFingerprint = mix(indexOnlyFingerprint, id);
                    if (!indexOnly) {
                        int quantity = resultSet.getInt(2);
                        indexToBaseFingerprint = mix(mix(indexToBaseFingerprint, id), quantity);
                    }
                    rows++;
                }
            }
        }
        return new RangeSemantic(rows, indexOnlyFingerprint, indexToBaseFingerprint);
    }

    private static FilteredRangeSemantic executeFilteredRange(
            Connection connection,
            String table,
            int start,
            int endExclusive) throws Exception {
        long fingerprint = 1L;
        int rows = 0;
        int previousId = start - 1;
        String sql = "select id, quantity from " + table
                + " where id >= ? and id < ? and quantity >= ? order by id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, start);
            statement.setInt(2, endExclusive);
            statement.setInt(3, FILTERED_MIN_QUANTITY);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int id = resultSet.getInt(1);
                    int quantity = resultSet.getInt(2);
                    if (id <= previousId || id < start || id >= endExclusive) {
                        throw new IllegalStateException(
                                "Filtered range returned invalid id order/bounds: id=" + id
                                        + ", previousId=" + previousId);
                    }
                    if (quantity < FILTERED_MIN_QUANTITY) {
                        throw new IllegalStateException(
                                "Filtered range returned quantity below predicate: id=" + id
                                        + ", quantity=" + quantity);
                    }
                    previousId = id;
                    fingerprint = mix(mix(fingerprint, id), quantity);
                    rows++;
                }
            }
        }
        return new FilteredRangeSemantic(rows, fingerprint);
    }

    private static long mix(long current, long value) {
        return 31L * current + value;
    }

    private record ProviderSemantic(
            Map<Integer, RangeSemantic> ranges,
            FilteredRangeSemantic filteredRange) {
    }

    private record RangeSemantic(
            int rows,
            long indexOnlyFingerprint,
            long indexToBaseFingerprint) {
    }

    private record FilteredRangeSemantic(
            int rows,
            long fingerprint) {
    }
}
