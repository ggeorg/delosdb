/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Worker launched in isolated stock-Derby or DelosDB JVMs for Phase 0B.2. */
public final class DelosHeapPhysicalCompatibilityWorker {
    private DelosHeapPhysicalCompatibilityWorker() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: <create|verify-mutate|verify-final> <database> <result-file>");
        }
        String mode = args[0];
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path resultFile = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(resultFile.getParent());

        WorkerResult result;
        switch (mode) {
            case "create" -> result = create(database);
            case "verify-mutate" -> result = verifyAndMutate(database);
            case "verify-final" -> result = verifyFinal(database);
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        }
        writeResult(resultFile, result);
        shutdown(database);
    }

    private static WorkerResult create(Path database) throws Exception {
        Files.createDirectories(database.getParent());
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database, true))) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE compat_meta (k VARCHAR(40) PRIMARY KEY, v VARCHAR(80) NOT NULL)");
                statement.executeUpdate("CREATE TABLE compat_parent ("
                        + "id INT PRIMARY KEY, "
                        + "code VARCHAR(32) NOT NULL UNIQUE, "
                        + "amount DECIMAL(12,2), "
                        + "note VARCHAR(200), "
                        + "payload BLOB(4096), "
                        + "body CLOB(4096), "
                        + "created DATE, "
                        + "event_ts TIMESTAMP)");
                statement.executeUpdate("CREATE INDEX compat_amount_idx ON compat_parent(amount)");
                statement.executeUpdate("CREATE TABLE compat_child ("
                        + "parent_id INT NOT NULL, "
                        + "seq INT NOT NULL, "
                        + "value VARCHAR(100), "
                        + "PRIMARY KEY(parent_id, seq), "
                        + "FOREIGN KEY(parent_id) REFERENCES compat_parent(id))");
                statement.executeUpdate("INSERT INTO compat_meta VALUES ('stage','INITIAL')");
            }
            insertParent(connection, 1, "alpha", "10.50", "first", bytes(1), "alpha-body", "2026-01-01", "2026-01-01 10:00:00.123456");
            insertParent(connection, 2, "beta", "20.00", null, bytes(2), "beta-body", "2026-01-02", "2026-01-02 11:00:00.654321");
            insertParent(connection, 3, "gamma", "30.25", "third", null, null, "2026-01-03", "2026-01-03 12:00:00.000001");
            insertChild(connection, 1, 1, "a-1");
            insertChild(connection, 1, 2, "a-2");
            insertChild(connection, 2, 1, "b-1");
            insertChild(connection, 3, 1, "g-1");
            connection.commit();
            assertConsistency(connection);
            checkpoint(connection);
            DelosSqlSemanticOracle.Result state = stateFingerprint(connection);
            return metadataResult(connection, state.fingerprint(), state.fingerprint(), state.fingerprint());
        }
    }

    private static WorkerResult verifyAndMutate(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database, false))) {
            connection.setAutoCommit(false);
            requireStage(connection, "INITIAL");
            assertConsistency(connection);
            DelosSqlSemanticOracle.Result before = stateFingerprint(connection);

            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE compat_parent SET amount = 999.99, note = 'rollback-proof' WHERE id = 1")) {
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Rollback proof update did not affect exactly one row");
                }
            }
            connection.rollback();
            DelosSqlSemanticOracle.Result afterRollback = stateFingerprint(connection);
            if (!before.fingerprint().equals(afterRollback.fingerprint())) {
                throw new IllegalStateException("Rollback changed SQL-visible state: before="
                        + before.fingerprint() + " after=" + afterRollback.fingerprint());
            }

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE compat_parent SET amount = 22.75, note = 'beta-updated' WHERE id = 2")) {
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Committed update did not affect exactly one row");
                }
            }
            insertParent(connection, 4, "delta", "44.40", "fourth", bytes(4), "delta-body", "2026-01-04", "2026-01-04 13:30:00.000004");
            try (Statement statement = connection.createStatement()) {
                if (statement.executeUpdate("DELETE FROM compat_child WHERE parent_id = 3 AND seq = 1") != 1) {
                    throw new IllegalStateException("DELETE compatibility mutation did not affect exactly one row");
                }
                if (statement.executeUpdate("UPDATE compat_meta SET v = 'FINAL' WHERE k = 'stage'") != 1) {
                    throw new IllegalStateException("Stage update did not affect exactly one row");
                }
            }
            insertChild(connection, 4, 1, "d-1");
            insertChild(connection, 4, 2, "d-2");
            connection.commit();
            requireStage(connection, "FINAL");
            assertConsistency(connection);
            checkpoint(connection);
            DelosSqlSemanticOracle.Result finalState = stateFingerprint(connection);
            return metadataResult(
                    connection,
                    before.fingerprint(),
                    afterRollback.fingerprint(),
                    finalState.fingerprint());
        }
    }

    private static WorkerResult verifyFinal(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database, false))) {
            connection.setAutoCommit(false);
            requireStage(connection, "FINAL");
            assertConsistency(connection);
            DelosSqlSemanticOracle.Result state = stateFingerprint(connection);
            connection.rollback();
            return metadataResult(connection, state.fingerprint(), state.fingerprint(), state.fingerprint());
        }
    }

    private static void insertParent(
            Connection connection,
            int id,
            String code,
            String amount,
            String note,
            byte[] payload,
            String body,
            String created,
            String timestamp) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO compat_parent(id,code,amount,note,payload,body,created,event_ts) VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setInt(1, id);
            statement.setString(2, code);
            statement.setBigDecimal(3, new BigDecimal(amount));
            if (note == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, note);
            }
            if (payload == null) {
                statement.setNull(5, Types.BLOB);
            } else {
                statement.setBytes(5, payload);
            }
            if (body == null) {
                statement.setNull(6, Types.CLOB);
            } else {
                statement.setString(6, body);
            }
            statement.setDate(7, java.sql.Date.valueOf(created));
            statement.setTimestamp(8, java.sql.Timestamp.valueOf(timestamp));
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Parent insert did not affect exactly one row: " + id);
            }
        }
    }

    private static void insertChild(Connection connection, int parentId, int seq, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO compat_child(parent_id,seq,value) VALUES (?,?,?)")) {
            statement.setInt(1, parentId);
            statement.setInt(2, seq);
            statement.setString(3, value);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Child insert did not affect exactly one row");
            }
        }
    }

    private static DelosSqlSemanticOracle.Result stateFingerprint(Connection connection) throws SQLException {
        Map<String, DelosSqlSemanticOracle.Result> components = new LinkedHashMap<>();
        components.put("meta", query(connection,
                "SELECT k,v FROM compat_meta ORDER BY k",
                DelosSqlSemanticOracle.RowOrder.ORDERED));
        components.put("parent", query(connection,
                "SELECT id,code,amount,note,payload,body,created,event_ts FROM compat_parent ORDER BY id",
                DelosSqlSemanticOracle.RowOrder.ORDERED));
        components.put("child", query(connection,
                "SELECT parent_id,seq,value FROM compat_child ORDER BY parent_id,seq",
                DelosSqlSemanticOracle.RowOrder.ORDERED));
        components.put("indexed-range", query(connection,
                "SELECT id,amount FROM compat_parent WHERE amount >= 20.00 ORDER BY amount,id",
                DelosSqlSemanticOracle.RowOrder.ORDERED));
        return DelosSqlSemanticOracle.composite("HEAP_COMPATIBILITY_STATE", components);
    }

    private static DelosSqlSemanticOracle.Result query(
            Connection connection, String sql, DelosSqlSemanticOracle.RowOrder order) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return DelosSqlSemanticOracle.query(resultSet, order);
        }
    }

    private static void requireStage(Connection connection, String expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT v FROM compat_meta WHERE k = 'stage'");
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next() || !expected.equals(resultSet.getString(1)) || resultSet.next()) {
                throw new IllegalStateException("Unexpected compatibility fixture stage; expected " + expected);
            }
        }
    }

    private static void assertConsistency(Connection connection) throws SQLException {
        checkTable(connection, "COMPAT_PARENT");
        checkTable(connection, "COMPAT_CHILD");
    }

    private static void checkTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "VALUES SYSCS_UTIL.SYSCS_CHECK_TABLE('APP','" + table + "')")) {
            if (!resultSet.next() || resultSet.getInt(1) != 1 || resultSet.next()) {
                throw new IllegalStateException("SYSCS_CHECK_TABLE failed for " + table);
            }
        }
    }

    private static void checkpoint(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CALL SYSCS_UTIL.SYSCS_CHECKPOINT_DATABASE()");
        }
    }

    private static WorkerResult metadataResult(
            Connection connection, String before, String afterRollback, String finalFingerprint)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        return new WorkerResult(
                metadata.getDatabaseProductName(),
                metadata.getDatabaseProductVersion(),
                metadata.getDriverName(),
                metadata.getDriverVersion(),
                before,
                afterRollback,
                finalFingerprint);
    }

    private static byte[] bytes(int seed) {
        byte[] value = new byte[257];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (seed * 31 + index * 17);
        }
        return value;
    }

    private static String jdbcUrl(Path database, boolean create) {
        return "jdbc:derby:" + database + (create ? ";create=true" : "");
    }

    private static void shutdown(Path database) {
        try {
            DriverManager.getConnection("jdbc:derby:" + database + ";shutdown=true");
            throw new IllegalStateException("Derby database shutdown returned normally");
        } catch (SQLException expected) {
            String state = expected.getSQLState();
            if (!"08006".equals(state) && !"XJ015".equals(state)) {
                throw new IllegalStateException("Unexpected shutdown SQLState " + state, expected);
            }
        }
    }

    private static void writeResult(Path resultFile, WorkerResult result) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("databaseProduct", result.databaseProduct());
        properties.setProperty("databaseVersion", result.databaseVersion());
        properties.setProperty("driverName", result.driverName());
        properties.setProperty("driverVersion", result.driverVersion());
        properties.setProperty("beforeFingerprint", result.beforeFingerprint());
        properties.setProperty("afterRollbackFingerprint", result.afterRollbackFingerprint());
        properties.setProperty("finalFingerprint", result.finalFingerprint());
        StringBuilder text = new StringBuilder();
        properties.stringPropertyNames().stream().sorted().forEach(name ->
                text.append(name).append('=').append(properties.getProperty(name)).append('\n'));
        Files.writeString(resultFile, text, StandardCharsets.UTF_8);
    }

    record WorkerResult(
            String databaseProduct,
            String databaseVersion,
            String driverName,
            String driverVersion,
            String beforeFingerprint,
            String afterRollbackFingerprint,
            String finalFingerprint) {
    }
}
