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
    private final String createTableSuffix;
    private final boolean dropExistingTable;
    private final DelosBenchmarkConfig config;
    private final String table;
    private int indexedUpdateOriginalQuantity;
    private boolean fixturePrepared;

    public DelosJdbcBenchmarkScenario(
            Connection connection,
            DelosBenchmarkProvider provider,
            DelosBenchmarkConfig config) {
        this(
                connection,
                Objects.requireNonNull(provider, "provider").id(),
                provider.createTableSuffix(),
                true,
                config);
    }

    DelosJdbcBenchmarkScenario(
            Connection connection,
            String targetId,
            String createTableSuffix,
            boolean dropExistingTable,
            DelosBenchmarkConfig config) {
        this.connection = Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(targetId, "targetId");
        this.createTableSuffix = Objects.requireNonNull(createTableSuffix, "createTableSuffix");
        this.dropExistingTable = dropExistingTable;
        this.config = Objects.requireNonNull(config, "config");
        this.table = "DELOS_BENCH_" + targetId.toUpperCase(java.util.Locale.ROOT);
    }

    public void prepare() throws SQLException {
        fixturePrepared = false;
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            if (dropExistingTable) {
                dropIfPresent(statement, table);
            }
            statement.executeUpdate("create table " + table
                    + " (id int not null primary key, category int not null, bucket int not null,"
                    + " quantity int not null, payload varchar(4096) not null)"
                    + createTableSuffix);
            statement.executeUpdate("create index " + table + "_CATEGORY_IDX on " + table + " (category)");
            statement.executeUpdate("create index " + table + "_RANGE_IDX on " + table + " (bucket, quantity)");
        }
        // Publish fixture metadata before preparing DML. Some server engines keep DDL
        // transactional, so preparing the insert before commit can observe no table.
        connection.commit();

        int indexedUpdateId = config.rowCount() / 3;
        Random random = new Random(config.seed());
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)")) {
            for (int id = 1; id <= config.rowCount(); id++) {
                int quantity = random.nextInt(10_000);
                if (id == indexedUpdateId) {
                    indexedUpdateOriginalQuantity = quantity;
                }
                insert.setInt(1, id);
                insert.setInt(2, id % 17);
                insert.setInt(3, id % 11);
                insert.setInt(4, quantity);
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
        fixturePrepared = true;
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
        Objects.requireNonNull(operation, "operation");
        try {
            DelosBenchmarkResult result;
            try (PreparedOperation prepared = prepareOperation(operation)) {
                result = prepared.execute();
            }
            connection.rollback();
            return result;
        } catch (SQLException | RuntimeException failure) {
            rollbackAfterFailure(failure);
            throw failure;
        }
    }

    PreparedOperation prepareOperation(DelosBenchmarkOperation operation) throws SQLException {
        Objects.requireNonNull(operation, "operation");
        requirePreparedFixture();
        return switch (operation) {
            case PRIMARY_KEY_LOOKUP -> prepareQuery(
                    "select id, quantity from " + table + " where id = ?", config.rowCount() / 2);
            case SECONDARY_EQUALITY_LOOKUP -> prepareQuery(
                    "select id, quantity from " + table + " where category = ? order by id", 7);
            case COMPOSITE_RANGE_SCAN -> prepareQuery(
                    "select id, quantity from " + table
                            + " where bucket = ? and quantity between 2000 and 8000 order by quantity, id", 5);
            case FULL_SCAN -> prepareQuery("select id, quantity from " + table + " order by id");
            case AGGREGATE -> prepareQuery("select category, count(*), sum(quantity) from " + table
                    + " group by category order by category");
            case INDEXED_UPDATE -> prepareIndexedUpdate();
            case DELETE_REINSERT -> prepareDeleteReinsert();
        };
    }

    String tableName() {
        return table;
    }

    void restoreAfterCommittedOperation(DelosBenchmarkOperation operation) throws SQLException {
        if (operation != DelosBenchmarkOperation.INDEXED_UPDATE) {
            return;
        }
        int id = config.rowCount() / 3;
        try (PreparedStatement restore = connection.prepareStatement(
                "update " + table + " set category = ?, quantity = ? where id = ?")) {
            restore.setInt(1, id % 17);
            restore.setInt(2, indexedUpdateOriginalQuantity);
            restore.setInt(3, id);
            if (restore.executeUpdate() != 1) {
                throw new SQLException("Indexed update restoration did not affect exactly one row");
            }
        }
        connection.commit();
    }

    private PreparedOperation prepareQuery(String sql, int... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        return new PreparedOperation() {
            @Override
            public DelosBenchmarkResult execute() throws SQLException {
                bind(statement, parameters);
                return query(statement);
            }

            @Override
            public void close() throws SQLException {
                statement.close();
            }
        };
    }

    private PreparedOperation prepareIndexedUpdate() throws SQLException {
        PreparedStatement update = null;
        PreparedStatement select = null;
        try {
            update = connection.prepareStatement(
                    "update " + table + " set category = ?, quantity = ? where id = ?");
            select = connection.prepareStatement("select id, quantity from " + table + " where id = ?");
            PreparedStatement preparedUpdate = update;
            PreparedStatement preparedSelect = select;
            return new PreparedOperation() {
                @Override
                public DelosBenchmarkResult execute() throws SQLException {
                    int id = config.rowCount() / 3;
                    preparedUpdate.setInt(1, 16);
                    preparedUpdate.setInt(2, indexedUpdateOriginalQuantity + 1);
                    preparedUpdate.setInt(3, id);
                    if (preparedUpdate.executeUpdate() != 1) {
                        throw new SQLException("Indexed update did not affect exactly one row");
                    }
                    preparedSelect.setInt(1, id);
                    return query(preparedSelect);
                }

                @Override
                public void close() throws SQLException {
                    closeStatements(preparedSelect, preparedUpdate);
                }
            };
        } catch (SQLException failure) {
            closeAfterFailure(failure, select, update);
            throw failure;
        }
    }

    private PreparedOperation prepareDeleteReinsert() throws SQLException {
        PreparedStatement source = null;
        PreparedStatement delete = null;
        PreparedStatement insert = null;
        PreparedStatement verify = null;
        try {
            source = connection.prepareStatement(
                    "select category, bucket, quantity, payload from " + table + " where id = ?");
            delete = connection.prepareStatement("delete from " + table + " where id = ?");
            insert = connection.prepareStatement(
                    "insert into " + table + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)");
            verify = connection.prepareStatement("select id, quantity from " + table + " where id = ?");
            PreparedStatement preparedSource = source;
            PreparedStatement preparedDelete = delete;
            PreparedStatement preparedInsert = insert;
            PreparedStatement preparedVerify = verify;
            return new PreparedOperation() {
                @Override
                public DelosBenchmarkResult execute() throws SQLException {
                    int id = config.rowCount() - 1;
                    preparedSource.setInt(1, id);
                    int category;
                    int bucket;
                    int quantity;
                    String rowPayload;
                    try (ResultSet resultSet = preparedSource.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new SQLException("Delete/reinsert source row is missing");
                        }
                        category = resultSet.getInt(1);
                        bucket = resultSet.getInt(2);
                        quantity = resultSet.getInt(3);
                        rowPayload = resultSet.getString(4);
                        if (resultSet.next()) {
                            throw new SQLException("Delete/reinsert source query returned duplicate primary keys");
                        }
                    }

                    preparedDelete.setInt(1, id);
                    if (preparedDelete.executeUpdate() != 1) {
                        throw new SQLException("Delete did not affect exactly one row");
                    }

                    preparedInsert.setInt(1, id);
                    preparedInsert.setInt(2, category);
                    preparedInsert.setInt(3, bucket);
                    preparedInsert.setInt(4, quantity);
                    preparedInsert.setString(5, rowPayload);
                    if (preparedInsert.executeUpdate() != 1) {
                        throw new SQLException("Reinsert did not affect exactly one row");
                    }

                    preparedVerify.setInt(1, id);
                    return query(preparedVerify);
                }

                @Override
                public void close() throws SQLException {
                    closeStatements(preparedVerify, preparedInsert, preparedDelete, preparedSource);
                }
            };
        } catch (SQLException failure) {
            closeAfterFailure(failure, verify, insert, delete, source);
            throw failure;
        }
    }

    private void requirePreparedFixture() {
        if (!fixturePrepared) {
            throw new IllegalStateException("Benchmark fixture has not been prepared");
        }
    }

    private void rollbackAfterFailure(Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void bind(PreparedStatement statement, int[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setInt(i + 1, parameters[i]);
        }
    }

    private static DelosBenchmarkResult query(PreparedStatement statement) throws SQLException {
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

    private static void closeAfterFailure(
            Throwable failure,
            PreparedStatement... statements) {
        try {
            closeStatements(statements);
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeStatements(PreparedStatement... statements) throws SQLException {
        SQLException failure = null;
        for (PreparedStatement statement : statements) {
            if (statement == null) {
                continue;
            }
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void dropIfPresent(Statement statement, String table) throws SQLException {
        try (ResultSet tables = statement.getConnection().getMetaData()
                .getTables(null, null, null, new String[] {"TABLE"})) {
            while (tables.next()) {
                if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    statement.executeUpdate("drop table " + table);
                    return;
                }
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

    interface PreparedOperation extends AutoCloseable {
        DelosBenchmarkResult execute() throws SQLException;

        @Override
        void close() throws SQLException;
    }
}
