/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.util.concurrent.TimeUnit;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2RConcurrencyMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Distribution;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ExplainCapture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared legacy explain, profile and measurement support. */
final class BenchmarkExperimentSupport {
    private BenchmarkExperimentSupport() {
    }

    static double phase2RMedian(
            Map<String, Phase2RConcurrencyMeasurement> measurements, String key) {
        return measurements.get(key).distribution().median();
    }

    static DelosMeasurementValidityContract.Status phase2RStatus(
            Map<String, Phase2RConcurrencyMeasurement> measurements) {
        return DelosMeasurementValidityContract.combine(
                measurements.values().stream()
                        .map(measurement -> measurement.decision().status())
                        .toArray(DelosMeasurementValidityContract.Status[]::new));
    }

    static String phase2RRunRangeStatus(
            Map<String, Phase2RConcurrencyMeasurement> measurements) {
        for (Phase2RConcurrencyMeasurement measurement : measurements.values()) {
            Distribution distribution = measurement.distribution();
            if (distribution.min() == 0.0d
                    || distribution.max() / distribution.min() > 1.20d) {
                return "INVALID";
            }
        }
        return "VALID";
    }

    static String phase2BPrimaryKeyIndex(
            Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        try (ResultSet indexes = metadata.getIndexInfo(null, "APP", table, false, false)) {
            while (indexes.next()) {
                String indexName = indexes.getString("INDEX_NAME");
                String columnName = indexes.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(indexName, ignored -> new ArrayList<>()).add(columnName);
                uniqueByIndex.put(indexName, !indexes.getBoolean("NON_UNIQUE"));
            }
        }
        for (Map.Entry<String, List<String>> entry : columnsByIndex.entrySet()) {
            if (Boolean.TRUE.equals(uniqueByIndex.get(entry.getKey()))
                    && entry.getValue().size() == 1
                    && column.equalsIgnoreCase(entry.getValue().get(0))) {
                return entry.getKey();
            }
        }
        throw new SQLException("Unable to discover single-column primary/unique index for " + table);
    }

    static long phase2EIndexToBaseNextMillis(String analyzeText) {
        return phase2ENodeFieldTotal(analyzeText, "SCAN/INDEX_TO_BASE_ROW", "nextMillis");
    }

    static long phase2EIndexScanNextMillis(String analyzeText) {
        return phase2ENodeFieldTotal(analyzeText, "SCAN/INDEX_SCAN", "nextMillis");
    }

    static long phase2ENodeFieldTotal(String analyzeText, String planKind, String field) {
        List<String> nodeIds = new ArrayList<>();
        Matcher planMatcher = Pattern.compile(
                "(?m)^\\s*(n\\d+)\\s+" + Pattern.quote(planKind) + "(?:\\s|$)")
                .matcher(analyzeText);
        while (planMatcher.find()) {
            nodeIds.add(planMatcher.group(1));
        }
        long total = 0L;
        for (String nodeId : nodeIds) {
            Matcher executionMatcher = Pattern.compile(
                    "(?m)^\\s*" + Pattern.quote(nodeId)
                            + "\\s+observed=true\\b[^\\r\\n]*\\b"
                            + Pattern.quote(field) + "=(\\d+)")
                    .matcher(analyzeText);
            if (!executionMatcher.find()) {
                throw new IllegalStateException(
                        "Phase-2E missing execution field " + field + " for " + nodeId);
            }
            total += Long.parseLong(executionMatcher.group(1));
        }
        return total;
    }

    static String phase2PStatisticsSnapshot(
            Connection connection, List<String> tables) throws SQLException {
        String sql = "select cast(t.tablename as varchar(128)), "
                + "cast(c.conglomeratename as varchar(128)), s.colcount, "
                + "cast(s.statistics as varchar(128)) "
                + "from sys.sysstatistics s, sys.sysconglomerates c, sys.systables t "
                + "where s.referenceid = c.conglomerateid and s.tableid = t.tableid "
                + "and t.tablename = ? order by 2, 3";
        StringBuilder output = new StringBuilder("table\tindex\tcolCount\tstatistics\n");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String table : tables) {
                statement.setString(1, table.toUpperCase(Locale.ROOT));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        output.append(rs.getString(1)).append('\t')
                                .append(rs.getString(2)).append('\t')
                                .append(rs.getInt(3)).append('\t')
                                .append(rs.getString(4)).append('\n');
                    }
                }
            }
        }
        return output.toString();
    }

    static Connection openPhase2AConnection(String jdbcUrl) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }

    static void writePhase2ACapture(
            Path reportDirectory, String baseName, ExplainCapture capture) throws IOException {
        Files.writeString(
                reportDirectory.resolve(baseName + ".txt"),
                capture.text(),
                StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve(baseName + ".json"),
                capture.json(),
                StandardCharsets.UTF_8);
    }

    static long sumPhase2AField(String text, String fieldName) {
        Pattern pattern = Pattern.compile("(?:^|\\s)" + Pattern.quote(fieldName) + "=(\\d+)");
        Matcher matcher = pattern.matcher(text);
        long total = 0L;
        while (matcher.find()) {
            total = Math.addExact(total, Long.parseLong(matcher.group(1)));
        }
        return total;
    }
    static String phase2UPlanShape(String analyzeText) {
        List<String> shape = new ArrayList<>();
        if (analyzeText.contains("JOIN/JOIN join=HASH")
                || analyzeText.contains("Hash Join ResultSet")) {
            shape.add("HASH_JOIN");
        }
        if (analyzeText.contains("JOIN/JOIN join=NESTEDLOOP")
                || analyzeText.contains("Nested Loop Join ResultSet")) {
            shape.add("NESTED_LOOP");
        }
        if (analyzeText.contains("SORT") || analyzeText.contains("Sort ResultSet")) {
            shape.add("SORT");
        }
        if (analyzeText.contains("SCAN/INDEX_TO_BASE_ROW")) {
            shape.add("INDEX_TO_BASE_ROW");
        }
        if (analyzeText.contains("SCAN/INDEX_SCAN")) {
            shape.add("INDEX_SCAN");
        }
        if (analyzeText.contains("SCAN/TABLE_SCAN")) {
            shape.add("TABLE_SCAN");
        }
        return shape.isEmpty() ? "UNKNOWN" : String.join("+", shape);
    }
    static ExplainCapture capturePhase2NExplain(
            Connection connection, String sql, boolean analyze) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(
                (analyze ? "explain analyze " : "explain ") + sql);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Phase-2N EXPLAIN returned no row");
            }
            String text = resultSet.getString(1);
            String json = resultSet.getString(2);
            if (resultSet.next()) {
                throw new SQLException("Phase-2N EXPLAIN returned more than one row");
            }
            return new ExplainCapture(
                    text,
                    json,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
}
