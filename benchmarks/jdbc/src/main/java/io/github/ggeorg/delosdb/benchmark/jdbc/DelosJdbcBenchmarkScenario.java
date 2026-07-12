/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Provider-neutral, deterministic JDBC benchmark scenario.
 *
 * <p>The class exposes only JDBC-visible operations. It deliberately has no
 * dependency on heap or MVCC implementation classes so external drivers such
 * as JMH can reuse it without coupling to unstable storage internals.</p>
 */
public final class DelosJdbcBenchmarkScenario {
    private final Connection connection;
    private final DelosBenchmarkProvider provider;
    private final DelosBenchmarkConfig config;
    private final String table;

    public DelosJdbcBenchmarkScenario(
            Connection connection,
            DelosBenchmarkProvider provider,
            DelosBenchmarkConfig config) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.config = Objects.requireNonNull(config, "config");
        this.table = "DELOS_BENCH_" + provider.id().toUpperCase(java.util.Locale.ROOT);
    }

    public void prepare() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            dropIfPresent(statement, table);
            statement.executeUpdate("create table " + table
                    + " (id int not null primary key, category int not null, bucket int not null,"
                    + " quantity int not null, payload varchar(4096) not null)"
                    + provider.createTableSuffix());
            statement.executeUpdate("create index " + table + "_CATEGORY_IDX on " + table + " (category)");
            statement.executeUpdate("create index " + table + "_RANGE_IDX on " + table + " (bucket, quantity)");
        }

        Random random = new Random(config.seed());
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)")) {
            for (int id = 1; id <= config.rowCount(); id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % 17);
                insert.setInt(3, id % 11);
                insert.setInt(4, random.nextInt(10_000));
                insert.setString(5, payload(id, config.payloadSize()));
                insert.addBatch();
                if (id % config.commitBatchSize() == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            if (config.rowCount() % config.commitBatchSize() != 0) {
                insert.executeBatch();
                connection.commit();
            }
        }
    }

    public Map<DelosBenchmarkOperation, DelosBenchmarkResult> executeSemanticMatrix() throws SQLException {
        EnumMap<DelosBenchmarkOperation, DelosBenchmarkResult> results =
                new EnumMap<>(DelosBenchmarkOperation.class);
        for (DelosBenchmarkOperation operation : DelosBenchmarkOperation.values()) {
            results.put(operation, execute(operation));
        }
        return Map.copyOf(results);
    }

    public DelosBenchmarkResult execute(DelosBenchmarkOperation operation) throws SQLException {
        return switch (Objects.requireNonNull(operation, "operation")) {
            case PRIMARY_KEY_LOOKUP -> query(
                    "select id, quantity from " + table + " where id = ?", config.rowCount() / 2);
            case SECONDARY_EQUALITY_LOOKUP -> query(
                    "select id, quantity from " + table + " where category = ? order by id", 7);
            case COMPOSITE_RANGE_SCAN -> query(
                    "select id, quantity from " + table
                            + " where bucket = ? and quantity between 2000 and 8000 order by quantity, id", 5);
            case FULL_SCAN -> query("select id, quantity from " + table + " order by id");
            case AGGREGATE -> query("select category, count(*), sum(quantity) from " + table
                    + " group by category order by category");
            case INDEXED_UPDATE -> indexedUpdate();
            case DELETE_REINSERT -> deleteReinsert();
        };
    }

    private DelosBenchmarkResult indexedUpdate() throws SQLException {
        int id = config.rowCount() / 3;
        try (PreparedStatement update = connection.prepareStatement(
                "update " + table + " set category = ?, quantity = quantity + 1 where id = ?")) {
            update.setInt(1, 16);
            update.setInt(2, id);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Indexed update did not affect exactly one row");
            }
        }
        connection.rollback();
        return query("select id, quantity from " + table + " where id = ?", id);
    }

    private DelosBenchmarkResult deleteReinsert() throws SQLException {
        int id = config.rowCount() - 1;
        try (PreparedStatement delete = connection.prepareStatement("delete from " + table + " where id = ?")) {
            delete.setInt(1, id);
            if (delete.executeUpdate() != 1) {
                throw new SQLException("Delete did not affect exactly one row");
            }
        }
        connection.rollback();
        return query("select id, quantity from " + table + " where id = ?", id);
    }

    private DelosBenchmarkResult query(String sql, int... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setInt(i + 1, parameters[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                long rows = 0;
                long checksum = 1;
                int columns = resultSet.getMetaData().getColumnCount();
                while (resultSet.next()) {
                    rows++;
                    for (int column = 1; column <= columns; column++) {
                        Object value = resultSet.getObject(column);
                        checksum = 31 * checksum + (value == null ? 0 : value.hashCode());
                    }
                }
                return new DelosBenchmarkResult(rows, checksum);
            }
        }
    }

    private static void dropIfPresent(Statement statement, String table) throws SQLException {
        try {
            statement.executeUpdate("drop table " + table);
        } catch (SQLException e) {
            if (!"42Y55".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    private static String payload(int id, int length) {
        String prefix = "row-" + id + '-';
        StringBuilder value = new StringBuilder(length);
        while (value.length() < length) {
            value.append(prefix);
        }
        return value.substring(0, length);
    }
}
