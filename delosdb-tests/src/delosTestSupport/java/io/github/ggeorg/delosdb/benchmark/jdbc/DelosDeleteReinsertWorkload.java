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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.impl.store.raw.data.RawStoreIoFaultInjectionTestSupport;
import org.apache.derbyTesting.functionTests.tests.delos.DelosDeleteReinsertPageTopologyTestSupport;

/** Prepared public-JDBC delete/reinsert workload with phase and RawStore I/O attribution. */
final class DelosDeleteReinsertWorkload implements AutoCloseable {
    private final Connection connection;
    private final DelosBenchmarkProvider provider;
    private final Path database;
    private final DelosJdbcDeleteReinsertAttribution.KeyMode keyMode;
    private final DelosJdbcDeleteReinsertAttribution.TransactionBoundary transactionBoundary;
    private final DelosBenchmarkTransactionOutcome outcome;
    private final int firstId;
    private final int alternateId;
    private final PreparedStatement source;
    private final PreparedStatement delete;
    private final PreparedStatement insert;
    private final PreparedStatement verify;
    private int currentId;

    DelosDeleteReinsertWorkload(
            Connection connection,
            DelosBenchmarkProvider provider,
            Path database,
            String table,
            int rowCount,
            DelosJdbcDeleteReinsertAttribution.KeyMode keyMode,
            DelosJdbcDeleteReinsertAttribution.TransactionBoundary transactionBoundary,
            DelosBenchmarkTransactionOutcome outcome) throws SQLException {
        this.connection = connection;
        this.provider = provider;
        this.database = database;
        this.keyMode = keyMode;
        this.transactionBoundary = transactionBoundary;
        this.outcome = outcome;
        firstId = rowCount - 1;
        alternateId = rowCount + 1;
        currentId = firstId;

        PreparedStatement localSource = null;
        PreparedStatement localDelete = null;
        PreparedStatement localInsert = null;
        PreparedStatement localVerify = null;
        try {
            localSource = connection.prepareStatement(
                    "select category, bucket, quantity, payload from " + table + " where id = ?");
            localDelete = connection.prepareStatement("delete from " + table + " where id = ?");
            localInsert = connection.prepareStatement(
                    "insert into " + table
                            + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)");
            localVerify = connection.prepareStatement(
                    "select id, category, bucket, quantity, payload from " + table
                            + " where id in (?, ?) order by id");
            source = localSource;
            delete = localDelete;
            insert = localInsert;
            verify = localVerify;
        } catch (SQLException failure) {
            closeAfterFailure(failure, localVerify, localInsert, localDelete, localSource);
            throw failure;
        }
    }

    List<PageTopologyObservation> capturePageTopology(
            DelosDeleteReinsertPageTopologyTestSupport.Layout layout) throws SQLException {
        RowValue row = read(currentId);
        int originalId = currentId;
        int targetId = keyMode == DelosJdbcDeleteReinsertAttribution.KeyMode.SAME_KEY
                ? originalId
                : (originalId == firstId ? alternateId : firstId);
        List<PageTopologyObservation> observations = new ArrayList<>();

        observations.add(recordPageTopology(
                "DELETE", layout, () -> delete(originalId)));
        if (transactionBoundary
                == DelosJdbcDeleteReinsertAttribution.TransactionBoundary.TWO_TRANSACTIONS) {
            observations.add(recordPageTopology(
                    "DELETE_TRANSACTION_END", layout, connection::commit));
        }
        observations.add(recordPageTopology(
                "INSERT", layout, () -> insert(targetId, row)));
        observations.add(recordPageTopology(
                "FINAL_TRANSACTION_END",
                layout,
                outcome == DelosBenchmarkTransactionOutcome.COMMIT
                        ? connection::commit
                        : connection::rollback));

        verifyAndRestore(originalId, targetId, row);
        return List.copyOf(observations);
    }

    private PageTopologyObservation recordPageTopology(
            String phase,
            DelosDeleteReinsertPageTopologyTestSupport.Layout layout,
            SqlAction action) throws SQLException {
        DelosRawStoreIoSnapshot before = rawStoreSnapshot();
        String databaseIdentity = before.databaseIdentity();
        RawStoreIoFaultInjectionTestSupport.installRecording(
                databaseIdentity, "delete-reinsert-page-topology-" + phase);
        RawStoreIoFaultInjectionTestSupport.Evidence evidence;
        DelosRawStoreIoSnapshot after;
        try {
            action.run();
            after = rawStoreSnapshot();
            evidence = RawStoreIoFaultInjectionTestSupport.evidence(databaseIdentity);
        } finally {
            RawStoreIoFaultInjectionTestSupport.clear(databaseIdentity);
        }
        if (evidence.discardedHits() != 0L) {
            throw new IllegalStateException(
                    "RawStore page-topology recorder overflowed in " + phase
                            + ": discarded=" + evidence.discardedHits());
        }

        boolean mvcc = provider == DelosBenchmarkProvider.MVCC;
        EnumMap<DelosDeleteReinsertPageTopologyTestSupport.Role, MutableTopology> byRole =
                new EnumMap<>(DelosDeleteReinsertPageTopologyTestSupport.Role.class);
        MutableTopology all = new MutableTopology();
        for (RawStoreIoFaultInjectionTestSupport.HitEvidence hit : evidence.hits()) {
            if (!"AFTER_PAGE_WRITE".equals(hit.point())) {
                continue;
            }
            PageIdentity page = new PageIdentity(
                    hit.segmentId(), hit.containerId(), hit.pageNumber());
            all.add(page, hit.length());
            DelosDeleteReinsertPageTopologyTestSupport.Role role =
                    layout.role(hit.containerId(), mvcc);
            byRole.computeIfAbsent(role, ignored -> new MutableTopology())
                    .add(page, hit.length());
        }

        RoleTopology allTopology = all.freeze(
                DelosDeleteReinsertPageTopologyTestSupport.Role.ALL);
        long expectedWrites = IoDelta.delta(
                before.pageWriteOperations(), after.pageWriteOperations());
        long expectedBytes = IoDelta.delta(before.pageWriteBytes(), after.pageWriteBytes());
        if (allTopology.pageWrites() != expectedWrites
                || allTopology.pageWriteBytes() != expectedBytes) {
            throw new IllegalStateException(
                    "RawStore page-topology mismatch in " + phase
                            + ": recordedWrites=" + allTopology.pageWrites()
                            + ", counterWrites=" + expectedWrites
                            + ", recordedBytes=" + allTopology.pageWriteBytes()
                            + ", counterBytes=" + expectedBytes);
        }

        List<RoleTopology> roles = new ArrayList<>();
        roles.add(allTopology);
        for (Map.Entry<DelosDeleteReinsertPageTopologyTestSupport.Role, MutableTopology> entry
                : byRole.entrySet()) {
            roles.add(entry.getValue().freeze(entry.getKey()));
        }
        return new PageTopologyObservation(phase, List.copyOf(roles));
    }

    CycleObservation execute(boolean measured) throws SQLException {
        DelosRawStoreIoSnapshot before = measured ? rawStoreSnapshot() : null;

        long started = System.nanoTime();
        RowValue row = read(currentId);
        long completed = System.nanoTime();
        long sourceReadNanos = measured ? completed - started : 0L;

        int originalId = currentId;
        int targetId = keyMode == DelosJdbcDeleteReinsertAttribution.KeyMode.SAME_KEY
                ? originalId
                : (originalId == firstId ? alternateId : firstId);

        started = System.nanoTime();
        delete(originalId);
        completed = System.nanoTime();
        long deleteExecuteNanos = measured ? completed - started : 0L;

        long deleteTransactionEndNanos = 0L;
        if (transactionBoundary
                == DelosJdbcDeleteReinsertAttribution.TransactionBoundary.TWO_TRANSACTIONS) {
            started = System.nanoTime();
            connection.commit();
            completed = System.nanoTime();
            deleteTransactionEndNanos = measured ? completed - started : 0L;
        }

        started = System.nanoTime();
        insert(targetId, row);
        completed = System.nanoTime();
        long insertExecuteNanos = measured ? completed - started : 0L;

        started = System.nanoTime();
        if (outcome == DelosBenchmarkTransactionOutcome.COMMIT) {
            connection.commit();
        } else {
            connection.rollback();
        }
        completed = System.nanoTime();
        long finalTransactionEndNanos = measured ? completed - started : 0L;

        IoDelta ioDelta = measured
                ? IoDelta.between(before, rawStoreSnapshot())
                : IoDelta.EMPTY;
        long semanticFingerprint = verifyAndRestore(originalId, targetId, row);
        return new CycleObservation(
                sourceReadNanos,
                deleteExecuteNanos,
                deleteTransactionEndNanos,
                insertExecuteNanos,
                finalTransactionEndNanos,
                ioDelta,
                semanticFingerprint);
    }

    private DelosRawStoreIoSnapshot rawStoreSnapshot() {
        return provider == DelosBenchmarkProvider.HEAP
                ? DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(database)
                : DelosStorageDiagnosticsRegistry.mvccDatabaseRawStoreIoSnapshot(database);
    }

    private RowValue read(int id) throws SQLException {
        source.setInt(1, id);
        try (ResultSet resultSet = source.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Delete/reinsert source row is missing: id=" + id);
            }
            RowValue row = new RowValue(
                    resultSet.getInt(1),
                    resultSet.getInt(2),
                    resultSet.getInt(3),
                    resultSet.getString(4));
            if (resultSet.next()) {
                throw new SQLException("Delete/reinsert source query returned duplicate id=" + id);
            }
            return row;
        }
    }

    private void delete(int id) throws SQLException {
        delete.setInt(1, id);
        if (delete.executeUpdate() != 1) {
            throw new SQLException("Delete did not affect exactly one row: id=" + id);
        }
    }

    private void insert(int id, RowValue row) throws SQLException {
        insert.setInt(1, id);
        insert.setInt(2, row.category());
        insert.setInt(3, row.bucket());
        insert.setInt(4, row.quantity());
        insert.setString(5, row.payload());
        if (insert.executeUpdate() != 1) {
            throw new SQLException("Reinsert did not affect exactly one row: id=" + id);
        }
    }

    private long verifyAndRestore(int originalId, int targetId, RowValue row) throws SQLException {
        if (transactionBoundary
                        == DelosJdbcDeleteReinsertAttribution.TransactionBoundary.TWO_TRANSACTIONS
                && outcome == DelosBenchmarkTransactionOutcome.ROLLBACK) {
            long missingFingerprint = verifyState(-1, row);
            insert(originalId, row);
            connection.commit();
            currentId = originalId;
            return mix(missingFingerprint, verifyState(currentId, row));
        }

        currentId = outcome == DelosBenchmarkTransactionOutcome.COMMIT
                ? targetId
                : originalId;
        return verifyState(currentId, row);
    }

    private long verifyState(int expectedId, RowValue expectedRow) throws SQLException {
        verify.setInt(1, firstId);
        verify.setInt(2, alternateId);
        long fingerprint = 1L;
        int rows = 0;
        try (ResultSet resultSet = verify.executeQuery()) {
            while (resultSet.next()) {
                rows++;
                int id = resultSet.getInt(1);
                RowValue actual = new RowValue(
                        resultSet.getInt(2),
                        resultSet.getInt(3),
                        resultSet.getInt(4),
                        resultSet.getString(5));
                if (id != expectedId || !expectedRow.equals(actual)) {
                    throw new IllegalStateException(
                            "Delete/reinsert state mismatch: expectedId=" + expectedId
                                    + ", actualId=" + id
                                    + ", expectedRow=" + expectedRow
                                    + ", actualRow=" + actual);
                }
                fingerprint = mix(mix(fingerprint, id), actual.hashCode());
            }
        }
        int expectedRows = expectedId < 0 ? 0 : 1;
        if (rows != expectedRows) {
            throw new IllegalStateException(
                    "Delete/reinsert row count mismatch: expected=" + expectedRows
                            + ", actual=" + rows);
        }
        return mix(fingerprint, rows);
    }

    @Override
    public void close() throws SQLException {
        closeStatements(verify, insert, delete, source);
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

    private static long mix(long fingerprint, long value) {
        return 31L * fingerprint + value;
    }

    record CycleObservation(
            long sourceReadNanos,
            long deleteExecuteNanos,
            long deleteTransactionEndNanos,
            long insertExecuteNanos,
            long finalTransactionEndNanos,
            IoDelta ioDelta,
            long semanticFingerprint) {
    }

    record IoDelta(
            long pageReadOperations,
            long pageReadBytes,
            long pageWriteOperations,
            long pageWriteBytes,
            long contentOnlyForceOperations,
            long metadataForceOperations) {
        private static final IoDelta EMPTY = new IoDelta(0L, 0L, 0L, 0L, 0L, 0L);

        private static IoDelta between(
                DelosRawStoreIoSnapshot before,
                DelosRawStoreIoSnapshot after) {
            requireActive(before);
            requireActive(after);
            return new IoDelta(
                    delta(before.pageReadOperations(), after.pageReadOperations()),
                    delta(before.pageReadBytes(), after.pageReadBytes()),
                    delta(before.pageWriteOperations(), after.pageWriteOperations()),
                    delta(before.pageWriteBytes(), after.pageWriteBytes()),
                    delta(before.contentOnlyForceOperations(), after.contentOnlyForceOperations()),
                    delta(before.metadataForceOperations(), after.metadataForceOperations()));
        }

        private static void requireActive(DelosRawStoreIoSnapshot snapshot) {
            if (!snapshot.runtimeActive()) {
                throw new IllegalStateException(
                        "RawStore I/O diagnostics are unavailable for " + snapshot.databaseIdentity());
            }
        }

        private static long delta(long before, long after) {
            if (after < before) {
                throw new IllegalStateException(
                        "RawStore I/O counter regressed: before=" + before + ", after=" + after);
            }
            return after - before;
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    record PageTopologyObservation(String phase, List<RoleTopology> roles) {
    }

    record RoleTopology(
            DelosDeleteReinsertPageTopologyTestSupport.Role role,
            long pageWrites,
            long distinctPages,
            long repeatedWrites,
            long pageWriteBytes) {
    }

    private static final class MutableTopology {
        private long pageWrites;
        private long pageWriteBytes;
        private final Set<PageIdentity> pages = new HashSet<>();

        private void add(PageIdentity page, int bytes) {
            pageWrites++;
            pageWriteBytes += bytes;
            pages.add(page);
        }

        private RoleTopology freeze(
                DelosDeleteReinsertPageTopologyTestSupport.Role role) {
            long distinctPages = pages.size();
            return new RoleTopology(
                    role,
                    pageWrites,
                    distinctPages,
                    pageWrites - distinctPages,
                    pageWriteBytes);
        }
    }

    private record PageIdentity(long segmentId, long containerId, long pageNumber) {
    }

    private record RowValue(int category, int bucket, int quantity, String payload) {
    }
}
