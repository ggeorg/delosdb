/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.concurrent;

import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Operation;
import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Scenario;
import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Topology;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Database, table, fixture, worker-location, and final semantic-checksum owner. */
final class DelosConcurrentScenarioEnvironment implements AutoCloseable {
    private static final String TABLE_PREFIX = "DELOS_CC_";
    private static final int READER_FIXTURE_ROWS = 32;
    private static final int READER_CATEGORY_COUNT = 4;
    private static final int INSERT_OWNER_BASE = 10_000;
    private static final long HASH_OFFSET = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    private final Scenario scenario;
    private final List<Resource> resources;
    private final int fixtureRowsPerPartition;
    private final int partitionsInSharedTable;

    private DelosConcurrentScenarioEnvironment(
            Scenario scenario,
            List<Resource> resources,
            int fixtureRowsPerPartition,
            int partitionsInSharedTable) {
        this.scenario = scenario;
        this.resources = resources;
        this.fixtureRowsPerPartition = fixtureRowsPerPartition;
        this.partitionsInSharedTable = partitionsInSharedTable;
    }

    static DelosConcurrentScenarioEnvironment create(Path root, Scenario scenario) throws SQLException {
        int resourceCount = scenario.topology() == Topology.SAME_TABLE
                ? 1
                : scenario.resourceCapacity();
        int databaseCount = scenario.topology() == Topology.DIFFERENT_DATABASES
                ? resourceCount
                : 1;
        List<String> urls = new ArrayList<>(databaseCount);
        for (int database = 0; database < databaseCount; database++) {
            String databaseName = root.resolve("database-" + database).toAbsolutePath().toString();
            urls.add("jdbc:derby:" + databaseName);
        }
        List<Resource> resources = resources(urls, resourceCount, scenario.topology());
        createDatabasesAndTables(urls, resources, scenario);

        int fixtureRows = Math.max(scenario.rowsPerTransaction(), READER_FIXTURE_ROWS);
        int sharedPartitions = scenario.topology() == Topology.SAME_TABLE
                ? Math.max(1, scenario.writers() > 0 ? scenario.writers() : scenario.readers())
                : 1;
        DelosConcurrentScenarioEnvironment environment = new DelosConcurrentScenarioEnvironment(
                scenario,
                List.copyOf(resources),
                fixtureRows,
                sharedPartitions);
        environment.seedFixtureRows();
        return environment;
    }

    String writerJdbcUrl(int writerId) {
        return writerResource(writerId).jdbcUrl();
    }

    String writerTableName(int writerId) {
        return writerResource(writerId).tableName();
    }

    String readerJdbcUrl(int readerId) {
        return readerResource(readerId).jdbcUrl();
    }

    String readerTableName(int readerId) {
        return readerResource(readerId).tableName();
    }

    int readerPartition(int readerId) {
        if (scenario.topology() != Topology.SAME_TABLE) {
            return 0;
        }
        int partitions = scenario.writers() > 0 ? scenario.writers() : partitionsInSharedTable;
        return readerId % partitions;
    }

    int fixtureRowsPerPartition() {
        return fixtureRowsPerPartition;
    }

    DelosConcurrentWorkload.SemanticDigest verify(
            int warmupTransactions,
            int measuredTransactions,
            int measurementRounds) throws SQLException {
        long actualRows = 0L;
        long checksum = HASH_OFFSET;
        for (Resource resource : resources) {
            try (Connection connection = DriverManager.getConnection(resource.jdbcUrl());
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select id, owner_id, value, payload from "
                                 + resource.tableName() + " order by id")) {
                while (rows.next()) {
                    actualRows++;
                    checksum = hash(checksum, rows.getLong(1));
                    checksum = hash(checksum, rows.getInt(2));
                    checksum = hash(checksum, rows.getInt(3));
                    checksum = hash(checksum, rows.getString(4));
                }
            }
        }
        long expectedRows = seededRows()
                + (scenario.operation() == Operation.INSERT
                ? (long) scenario.writers()
                        * (warmupTransactions + (long) measurementRounds * measuredTransactions)
                        * scenario.rowsPerTransaction()
                : 0L);
        if (actualRows != expectedRows) {
            throw new IllegalStateException("semantic row-count mismatch for " + scenario
                    + ": expected=" + expectedRows + ", actual=" + actualRows);
        }
        if (scenario.operation() == Operation.UPDATE) {
            verifyUpdatedRows(measuredTransactions);
        }
        return new DelosConcurrentWorkload.SemanticDigest(actualRows, checksum);
    }

    static long fixtureId(int partition, int row) {
        return partition * 100_000L + row + 1L;
    }

    static int readerCategoryCount() {
        return READER_CATEGORY_COUNT;
    }

    static int insertOwner(int writerId) {
        return INSERT_OWNER_BASE + writerId;
    }

    static String writerPayload(int writerId, int transaction, int row) {
        return "writer=" + writerId + ";tx=" + transaction + ";row=" + row;
    }

    static void requireUpdatedRows(int[] counts, int expected) {
        if (counts.length != expected) {
            throw new IllegalStateException("expected " + expected + " batch results, found " + counts.length);
        }
        for (int count : counts) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("unexpected batch update count: " + count);
            }
        }
    }

    private static List<Resource> resources(
            List<String> urls,
            int resourceCount,
            Topology topology) {
        List<Resource> resources = new ArrayList<>(resourceCount);
        if (topology == Topology.DIFFERENT_TABLES) {
            for (int resource = 0; resource < resourceCount; resource++) {
                resources.add(new Resource(urls.get(0), TABLE_PREFIX + resource));
            }
        } else if (topology == Topology.DIFFERENT_DATABASES) {
            for (int resource = 0; resource < resourceCount; resource++) {
                resources.add(new Resource(urls.get(resource), TABLE_PREFIX + 0));
            }
        } else {
            resources.add(new Resource(urls.get(0), TABLE_PREFIX + 0));
        }
        return resources;
    }

    private void seedFixtureRows() throws SQLException {
        for (Resource resource : resources) {
            int partitions = scenario.topology() == Topology.SAME_TABLE
                    ? partitionsInSharedTable
                    : 1;
            try (Connection connection = DriverManager.getConnection(resource.jdbcUrl())) {
                connection.setAutoCommit(false);
                try (PreparedStatement insert = connection.prepareStatement(
                        "insert into " + resource.tableName()
                                + " (id, owner_id, value, payload) values (?, ?, ?, ?)")) {
                    for (int partition = 0; partition < partitions; partition++) {
                        for (int row = 0; row < fixtureRowsPerPartition; row++) {
                            insert.setLong(1, fixtureId(partition, row));
                            insert.setInt(2, row % READER_CATEGORY_COUNT);
                            insert.setInt(3, 0);
                            insert.setString(4, "seed");
                            insert.addBatch();
                        }
                    }
                    requireUpdatedRows(insert.executeBatch(), partitions * fixtureRowsPerPartition);
                }
                connection.commit();
            }
        }
    }

    private long seededRows() {
        long partitions = scenario.topology() == Topology.SAME_TABLE
                ? partitionsInSharedTable
                : resources.size();
        return partitions * fixtureRowsPerPartition;
    }

    private void verifyUpdatedRows(int measuredTransactions) throws SQLException {
        for (int writer = 0; writer < scenario.writers(); writer++) {
            Resource resource = writerResource(writer);
            int partition = scenario.topology() == Topology.SAME_TABLE ? writer : 0;
            try (Connection connection = DriverManager.getConnection(resource.jdbcUrl());
                 PreparedStatement query = connection.prepareStatement(
                         "select value, payload from " + resource.tableName() + " where id = ?")) {
                for (int row = 0; row < scenario.rowsPerTransaction(); row++) {
                    query.setLong(1, fixtureId(partition, row));
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("updated row is missing for writer="
                                    + writer + ", row=" + row);
                        }
                        int value = result.getInt(1);
                        String actualPayload = result.getString(2);
                        String expectedPayload = writerPayload(writer, measuredTransactions - 1, row);
                        if (value != measuredTransactions || !expectedPayload.equals(actualPayload)) {
                            throw new IllegalStateException("update fixture mismatch for writer="
                                    + writer + ", row=" + row
                                    + ": value=" + value + ", payload=" + actualPayload
                                    + ", expectedValue=" + measuredTransactions
                                    + ", expectedPayload=" + expectedPayload);
                        }
                        if (result.next()) {
                            throw new IllegalStateException("duplicate updated row for writer="
                                    + writer + ", row=" + row);
                        }
                    }
                }
            }
        }
    }

    private Resource writerResource(int writerId) {
        return scenario.topology() == Topology.SAME_TABLE
                ? resources.get(0)
                : resources.get(writerId % resources.size());
    }

    private Resource readerResource(int readerId) {
        return scenario.topology() == Topology.SAME_TABLE
                ? resources.get(0)
                : resources.get(readerId % resources.size());
    }

    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        for (String url : resources.stream().map(Resource::jdbcUrl).distinct().toList()) {
            try {
                DriverManager.getConnection(url + ";shutdown=true");
            } catch (SQLException expectedShutdown) {
                if (!"08006".equals(expectedShutdown.getSQLState())) {
                    if (failure == null) {
                        failure = expectedShutdown;
                    } else {
                        failure.addSuppressed(expectedShutdown);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void createDatabasesAndTables(
            List<String> urls,
            List<Resource> resources,
            Scenario scenario) throws SQLException {
        for (String url : urls) {
            try (Connection connection = DriverManager.getConnection(url + ";create=true")) {
                connection.setAutoCommit(false);
                for (Resource resource : resources) {
                    if (resource.jdbcUrl().equals(url)) {
                        createTable(connection, resource.tableName(), scenario);
                    }
                }
                connection.commit();
            }
        }
    }

    private static void createTable(
            Connection connection,
            String table,
            Scenario scenario) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + table
                    + " (id bigint not null primary key, owner_id int not null,"
                    + " value int not null, payload varchar(128) not null)"
                    + scenario.provider().tableSuffix());
            statement.executeUpdate("create index " + table + "_OWNER_ID on "
                    + table + " (owner_id, id)");
        }
    }

    private static long hash(long current, long value) {
        long next = current;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            next ^= (value >>> shift) & 0xffL;
            next *= HASH_PRIME;
        }
        return next;
    }

    private static long hash(long current, int value) {
        return hash(current, Integer.toUnsignedLong(value));
    }

    private static long hash(long current, String value) {
        long next = current;
        for (int index = 0; index < value.length(); index++) {
            next ^= value.charAt(index);
            next *= HASH_PRIME;
        }
        return next;
    }

    private record Resource(String jdbcUrl, String tableName) {
    }
}
