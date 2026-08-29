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
import java.sql.Statement;
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
        if (provider == DelosBenchmarkProvider.HEAP) {
            validateCoveringRangeProxy(connection, scenario.tableName(), semantics, config.rowCount());
        } else if (provider == DelosBenchmarkProvider.MVCC) {
            validateMvccNaturalOrder(connection, scenario.tableName(), semantics, config.rowCount());
        }
        connection.rollback();
        return Map.copyOf(semantics);
    }

    private static void validateMvccNaturalOrder(
            Connection connection,
            String table,
            Map<Integer, RangeSemantic> baselineSemantics,
            int rowCount) throws Exception {
        int rangeRows = Math.min(500, rowCount);
        int start = 1 + (rowCount - rangeRows) / 3;
        int endExclusive = start + rangeRows;
        RangeSemantic expected = baselineSemantics.get(rangeRows);
        if (expected == null) {
            throw new IllegalStateException(
                    "Missing MVCC baseline semantics for natural-order range=" + rangeRows);
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("call syscs_util.syscs_set_runtimestatistics(1)");
        }
        // This is a native-MVCC mechanism proof, not an optimizer-choice proof.
        // The normal F02 benchmark is intentionally optimizer-selected and may
        // choose the inherited SQL primary-key B-tree plus IndexRowToBaseRow.
        // Force index=null here so both diagnostic arms reach the native MVCC
        // scan controller and its RawStore ordered-index candidate path.
        String orderedSql = "select id, quantity from " + table
                + " --DERBY-PROPERTIES index=null\n"
                + " where id >= ? and id < ? order by id";
        RangeSemantic ordered = executeRangeSql(
                connection, orderedSql, start, endExclusive, false);
        String orderedStatistics = runtimeStatistics(connection);

        String naturalSql = "select id, quantity from " + table
                + " --DERBY-PROPERTIES index=null\n"
                + " where id >= ? and id < ?";
        RangeSemantic natural = executeRangeSql(
                connection, naturalSql, start, endExclusive, false);
        String naturalStatistics = runtimeStatistics(connection);

        if (!expected.equals(ordered) || !expected.equals(natural)) {
            throw new IllegalStateException(
                    "MVCC natural ordered-index range changed semantics: expected=" + expected
                            + ", ordered=" + ordered + ", natural=" + natural);
        }
        if (!orderedStatistics.contains("delos_mvcc_rawstore_ordered_index")
                || !naturalStatistics.contains("delos_mvcc_rawstore_ordered_index")) {
            throw new IllegalStateException(
                    "MVCC range did not use RawStore ordered index in both arms; ordered="
                            + orderedStatistics + ", natural=" + naturalStatistics);
        }
        if (!orderedStatistics.contains("Sort ResultSet:")) {
            throw new IllegalStateException(
                    "MVCC ORDER BY range unexpectedly avoided the SQL sort; statistics="
                            + orderedStatistics);
        }
        if (naturalStatistics.contains("Sort ResultSet:")) {
            throw new IllegalStateException(
                    "MVCC natural-order range unexpectedly used a SQL sort; statistics="
                            + naturalStatistics);
        }
    }

    private static void validateCoveringRangeProxy(
            Connection connection,
            String table,
            Map<Integer, RangeSemantic> baselineSemantics,
            int rowCount) throws Exception {
        String index = table + "_PK_COVER_IDX";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create index " + index + " on " + table + " (id, quantity)");
        }
        connection.commit();

        int rangeRows = Math.min(500, rowCount);
        int start = 1 + (rowCount - rangeRows) / 3;
        int endExclusive = start + rangeRows;
        RangeSemantic expected = baselineSemantics.get(rangeRows);
        if (expected == null) {
            throw new IllegalStateException("Missing baseline semantics for covering proxy range=" + rangeRows);
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("call syscs_util.syscs_set_runtimestatistics(1)");
        }
        RangeSemantic covering = executeCoveringRange(connection, table, index, start, endExclusive);
        if (covering.rows() != expected.rows()
                || covering.indexOnlyFingerprint() != expected.indexOnlyFingerprint()
                || covering.indexToBaseFingerprint() != expected.indexToBaseFingerprint()) {
            throw new IllegalStateException("Heap covering range proxy changed semantics: expected="
                    + expected + ", actual=" + covering);
        }

        String statistics = runtimeStatistics(connection);
        if (!statistics.toUpperCase(java.util.Locale.ROOT).contains(index.toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("Heap covering range did not use forced covering index "
                    + index + ": " + statistics);
        }
        if (statistics.contains("Index Row to Base Row ResultSet")) {
            throw new IllegalStateException("Heap covering range unexpectedly fetched the base row: "
                    + statistics);
        }
    }

    private static RangeSemantic executeCoveringRange(
            Connection connection,
            String table,
            String index,
            int start,
            int endExclusive) throws Exception {
        String sql = "select id, quantity from " + table
                + " --DERBY-PROPERTIES index=" + index + "\n"
                + " where id >= ? and id < ? order by id";
        return executeRangeSql(connection, sql, start, endExclusive, false);
    }

    private static String runtimeStatistics(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "values syscs_util.syscs_get_runtimestatistics()")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Runtime statistics query returned no row");
            }
            return resultSet.getString(1);
        }
    }

    private static RangeSemantic executeRange(
            Connection connection,
            String table,
            int start,
            int endExclusive,
            boolean indexOnly) throws Exception {
        String sql = indexOnly
                ? "select id from " + table + " where id >= ? and id < ? order by id"
                : "select id, quantity from " + table + " where id >= ? and id < ? order by id";
        return executeRangeSql(connection, sql, start, endExclusive, indexOnly);
    }

    private static RangeSemantic executeRangeSql(
            Connection connection,
            String sql,
            int start,
            int endExclusive,
            boolean indexOnly) throws Exception {
        long indexOnlyFingerprint = 1L;
        long indexToBaseFingerprint = 1L;
        int rows = 0;
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

    private static long mix(long current, long value) {
        return 31L * current + value;
    }

    private record RangeSemantic(
            int rows,
            long indexOnlyFingerprint,
            long indexToBaseFingerprint) {
    }
}
