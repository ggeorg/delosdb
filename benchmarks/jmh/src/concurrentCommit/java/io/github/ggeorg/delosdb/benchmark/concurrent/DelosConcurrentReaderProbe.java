/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.concurrent;

import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.ReaderWorkload;
import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Scenario;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** One reader's prepared SQL, deterministic fingerprint, and transaction lifetime. */
final class DelosConcurrentReaderProbe implements AutoCloseable {
    private final Connection connection;
    private final ReaderWorkload workload;
    private final PreparedStatement statement;
    private final long targetId;
    private final int category;
    private final long rangeStart;
    private final long rangeEnd;
    private final int fixtureRows;
    private Integer snapshotValue;
    private String snapshotPayload;

    private DelosConcurrentReaderProbe(
            Connection connection,
            ReaderWorkload workload,
            PreparedStatement statement,
            long targetId,
            int category,
            long rangeStart,
            long rangeEnd,
            int fixtureRows) {
        this.connection = connection;
        this.workload = workload;
        this.statement = statement;
        this.targetId = targetId;
        this.category = category;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.fixtureRows = fixtureRows;
    }

    static DelosConcurrentReaderProbe open(
            Connection connection,
            DelosConcurrentScenarioEnvironment environment,
            Scenario scenario,
            int readerId) throws SQLException {
        int partition = environment.readerPartition(readerId);
        int fixtureRows = environment.fixtureRowsPerPartition();
        long partitionStart = DelosConcurrentScenarioEnvironment.fixtureId(partition, 0);
        int targetRows = scenario.operation() == DelosConcurrentScenario.Operation.UPDATE
                && scenario.writers() > 0
                ? scenario.rowsPerTransaction()
                : fixtureRows;
        long targetId = DelosConcurrentScenarioEnvironment.fixtureId(
                partition,
                readerId % targetRows);
        int category = readerId % DelosConcurrentScenarioEnvironment.readerCategoryCount();
        long rangeStart = partitionStart;
        long rangeEnd = DelosConcurrentScenarioEnvironment.fixtureId(
                partition,
                Math.max(0, fixtureRows / 2 - 1));
        String table = environment.readerTableName(readerId);
        PreparedStatement statement;
        switch (scenario.readerWorkload()) {
            case PRIMARY -> statement = connection.prepareStatement(
                    "select id from " + table + " where id = ?");
            case SECONDARY -> statement = connection.prepareStatement(
                    "select id from " + table
                            + " where owner_id = ? and id between ? and ? order by id");
            case RANGE -> statement = connection.prepareStatement(
                    "select id from " + table + " where id between ? and ? order by id");
            case RETAINED_SNAPSHOT -> {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                statement = connection.prepareStatement(
                        "select value, payload from " + table + " where id = ?");
            }
            case NONE -> throw new IllegalArgumentException("reader probe requires a workload");
            default -> throw new IllegalStateException("unhandled reader workload");
        }
        DelosConcurrentReaderProbe probe = new DelosConcurrentReaderProbe(
                connection,
                scenario.readerWorkload(),
                statement,
                targetId,
                category,
                rangeStart,
                rangeEnd,
                fixtureRows);
        if (scenario.readerWorkload() == ReaderWorkload.RETAINED_SNAPSHOT) {
            probe.captureSnapshot();
        }
        return probe;
    }

    void executeAndVerify() throws SQLException {
        switch (workload) {
            case PRIMARY -> verifyPrimary();
            case SECONDARY -> verifySecondary();
            case RANGE -> verifyRange();
            case RETAINED_SNAPSHOT -> verifyRetainedSnapshot();
            case NONE -> throw new IllegalStateException("reader probe has no workload");
            default -> throw new IllegalStateException("unhandled reader workload");
        }
    }

    void complete() throws SQLException {
        if (workload == ReaderWorkload.RETAINED_SNAPSHOT) {
            connection.rollback();
        }
    }

    private void captureSnapshot() throws SQLException {
        statement.setLong(1, targetId);
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("retained-snapshot target is missing: " + targetId);
            }
            snapshotValue = result.getInt(1);
            snapshotPayload = result.getString(2);
            if (result.next()) {
                throw new IllegalStateException("retained-snapshot target is duplicated: " + targetId);
            }
        }
    }

    private void verifyPrimary() throws SQLException {
        statement.setLong(1, targetId);
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getLong(1) != targetId || result.next()) {
                throw new IllegalStateException("primary reader fingerprint mismatch for " + targetId);
            }
        }
    }

    private void verifySecondary() throws SQLException {
        int partition = partitionOf(rangeStart);
        long lastFixtureId = DelosConcurrentScenarioEnvironment.fixtureId(partition, fixtureRows - 1);
        statement.setInt(1, category);
        statement.setLong(2, rangeStart);
        statement.setLong(3, lastFixtureId);
        int expectedCount = 0;
        long expected = rangeStart + category;
        try (ResultSet result = statement.executeQuery()) {
            while (expected <= lastFixtureId) {
                if (!result.next() || result.getLong(1) != expected) {
                    throw new IllegalStateException("secondary reader fingerprint mismatch at " + expected);
                }
                expectedCount++;
                expected += DelosConcurrentScenarioEnvironment.readerCategoryCount();
            }
            if (result.next()) {
                throw new IllegalStateException("secondary reader returned extra rows for category " + category);
            }
        }
        int categories = DelosConcurrentScenarioEnvironment.readerCategoryCount();
        int mathematicallyExpected = (fixtureRows + categories - 1 - category) / categories;
        if (expectedCount != mathematicallyExpected) {
            throw new IllegalStateException("secondary reader count mismatch: expected="
                    + mathematicallyExpected + ", actual=" + expectedCount);
        }
    }

    private void verifyRange() throws SQLException {
        statement.setLong(1, rangeStart);
        statement.setLong(2, rangeEnd);
        long expected = rangeStart;
        try (ResultSet result = statement.executeQuery()) {
            while (expected <= rangeEnd) {
                if (!result.next() || result.getLong(1) != expected) {
                    throw new IllegalStateException("range reader fingerprint mismatch at " + expected);
                }
                expected++;
            }
            if (result.next()) {
                throw new IllegalStateException("range reader returned extra rows");
            }
        }
    }

    private void verifyRetainedSnapshot() throws SQLException {
        statement.setLong(1, targetId);
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("retained-snapshot target disappeared: " + targetId);
            }
            int value = result.getInt(1);
            String payload = result.getString(2);
            if (!snapshotValue.equals(value) || !snapshotPayload.equals(payload) || result.next()) {
                throw new IllegalStateException("retained-snapshot fingerprint changed for " + targetId
                        + ": expected=" + snapshotValue + '/' + snapshotPayload
                        + ", actual=" + value + '/' + payload);
            }
        }
    }

    private static int partitionOf(long id) {
        return Math.toIntExact((id - 1L) / 100_000L);
    }

    @Override
    public void close() throws SQLException {
        statement.close();
    }
}
