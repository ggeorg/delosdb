/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/** Per-thread embedded database fixture used by the standalone JDBC benchmarks. */
@State(Scope.Thread)
public class DelosJdbcJmhState {
    private static final long SEED = 0x5DE10DBL;
    private static final String TABLE = "DELOS_JMH";
    private static final int CATEGORY_COUNT = 17;
    private static final int BUCKET_COUNT = 11;
    private static final long CHECKSUM_SEED = 0x6A09E667F3BCC909L;
    private static final long CHECKSUM_MULTIPLIER = 0x9E3779B185EBCA87L;

    @Param({"heap", "mvcc"})
    public String provider;

    @Param({"100"})
    public int rows;

    @Param({"128"})
    public int payloadSize;

    @Param({"100"})
    public int commitBatchSize;

    private Path databaseRoot;
    private String databaseName;
    private Connection connection;
    private PreparedStatement primaryKeyLookup;
    private PreparedStatement secondaryEqualityLookup;
    private PreparedStatement compositeRangeScan;
    private PreparedStatement fullScan;
    private PreparedStatement aggregate;
    private List<FixtureRow> model;
    private int primaryCursor;
    private int categoryCursor;
    private int bucketCursor;
    private long transactionSequence;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        validateParameters();
        Throwable failure = null;
        try {
            Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
            databaseRoot = Files.createTempDirectory("delosdb-jmh-");
            databaseName = databaseRoot.resolve("database").toAbsolutePath().toString();
            connection = DriverManager.getConnection("jdbc:derby:" + databaseName + ";create=true");
            connection.setAutoCommit(false);
            model = createFixture();
            prepareStatements();
            verifySemanticSurface();
            connection.rollback();
            primaryCursor = Math.max(1, rows / 2);
            categoryCursor = 0;
            bucketCursor = 0;
        } catch (Throwable setupFailure) {
            failure = setupFailure;
            throw setupFailure;
        } finally {
            if (failure != null) {
                try {
                    closeResources();
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    @TearDown(Level.Iteration)
    public void rollbackIteration() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.rollback();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        closeResources();
    }

    long primaryKeyLookup() throws SQLException {
        int id = primaryCursor;
        primaryCursor = id == rows ? 1 : id + 1;
        primaryKeyLookup.setInt(1, id);
        return queryTwoColumns(primaryKeyLookup);
    }

    long secondaryEqualityLookup() throws SQLException {
        int category = categoryCursor;
        categoryCursor = (category + 1) % CATEGORY_COUNT;
        secondaryEqualityLookup.setInt(1, category);
        return queryTwoColumns(secondaryEqualityLookup);
    }

    long compositeRangeScan() throws SQLException {
        int bucket = bucketCursor;
        bucketCursor = (bucket + 1) % BUCKET_COUNT;
        compositeRangeScan.setInt(1, bucket);
        return queryTwoColumns(compositeRangeScan);
    }

    long fullScan() throws SQLException {
        return queryTwoColumns(fullScan);
    }

    long aggregate() throws SQLException {
        long checksum = CHECKSUM_SEED;
        int count = 0;
        try (ResultSet resultSet = aggregate.executeQuery()) {
            while (resultSet.next()) {
                checksum = mix(checksum, resultSet.getInt(1));
                checksum = mix(checksum, resultSet.getLong(2));
                checksum = mix(checksum, resultSet.getLong(3));
                count++;
            }
        }
        return finish(checksum, count);
    }

    long emptyCommit() throws SQLException {
        connection.commit();
        return ++transactionSequence;
    }

    long emptyRollback() throws SQLException {
        connection.rollback();
        return ++transactionSequence;
    }

    private void validateParameters() {
        String normalizedProvider = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        if (!normalizedProvider.equals("heap") && !normalizedProvider.equals("mvcc")) {
            throw new IllegalArgumentException("provider must be heap or mvcc: " + provider);
        }
        provider = normalizedProvider;
        if (rows < 100) {
            throw new IllegalArgumentException("rows must be at least 100: " + rows);
        }
        if (payloadSize < 16 || payloadSize > 4096) {
            throw new IllegalArgumentException(
                    "payloadSize must be between 16 and 4096: " + payloadSize);
        }
        if (commitBatchSize < 1) {
            throw new IllegalArgumentException("commitBatchSize must be positive: " + commitBatchSize);
        }
    }

    private List<FixtureRow> createFixture() throws SQLException {
        String tableSuffix = provider.equals("mvcc") ? " using delos_mvcc" : "";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + TABLE
                    + " (id int not null primary key, category int not null, bucket int not null,"
                    + " quantity int not null, payload varchar(4096) not null)"
                    + tableSuffix);
            statement.executeUpdate("create index " + TABLE + "_CATEGORY_IDX on " + TABLE + " (category)");
            statement.executeUpdate("create index " + TABLE + "_RANGE_IDX on " + TABLE + " (bucket, quantity)");
        }

        int effectiveCommitBatch = Math.min(commitBatchSize, rows);
        List<FixtureRow> generated = new ArrayList<>(rows);
        Random random = new Random(SEED);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + TABLE
                        + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)")) {
            for (int id = 1; id <= rows; id++) {
                int category = id % CATEGORY_COUNT;
                int bucket = id % BUCKET_COUNT;
                int quantity = random.nextInt(10_000);
                generated.add(new FixtureRow(id, category, bucket, quantity));
                insert.setInt(1, id);
                insert.setInt(2, category);
                insert.setInt(3, bucket);
                insert.setInt(4, quantity);
                insert.setString(5, payload(id, payloadSize));
                insert.addBatch();
                if (id % effectiveCommitBatch == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            if (rows % effectiveCommitBatch != 0) {
                insert.executeBatch();
                connection.commit();
            }
        }
        return List.copyOf(generated);
    }

    private void prepareStatements() throws SQLException {
        primaryKeyLookup = connection.prepareStatement(
                "select id, quantity from " + TABLE + " where id = ?");
        secondaryEqualityLookup = connection.prepareStatement(
                "select id, quantity from " + TABLE + " where category = ? order by id");
        compositeRangeScan = connection.prepareStatement(
                "select id, quantity from " + TABLE
                        + " where bucket = ? and quantity between 2000 and 8000 order by quantity, id");
        fullScan = connection.prepareStatement(
                "select id, quantity from " + TABLE + " order by id");
        aggregate = connection.prepareStatement(
                "select category, count(*), sum(quantity) from " + TABLE
                        + " group by category order by category");
    }

    private void verifySemanticSurface() throws SQLException {
        int[] primaryIds = {1, Math.max(1, rows / 2), rows};
        for (int id : primaryIds) {
            primaryKeyLookup.setInt(1, id);
            requireChecksum("primary key " + id, expectedPrimary(id), queryTwoColumns(primaryKeyLookup));
        }

        int[] categories = {0, 7, CATEGORY_COUNT - 1};
        for (int category : categories) {
            secondaryEqualityLookup.setInt(1, category);
            requireChecksum(
                    "secondary category " + category,
                    expectedSecondary(category),
                    queryTwoColumns(secondaryEqualityLookup));
        }

        int[] buckets = {0, 5, BUCKET_COUNT - 1};
        for (int bucket : buckets) {
            compositeRangeScan.setInt(1, bucket);
            requireChecksum(
                    "composite range bucket " + bucket,
                    expectedRange(bucket),
                    queryTwoColumns(compositeRangeScan));
        }

        requireChecksum("full scan", expectedFullScan(), queryTwoColumns(fullScan));
        requireChecksum("aggregate", expectedAggregate(), aggregate());
    }

    private long expectedPrimary(int id) {
        FixtureRow row = model.get(id - 1);
        return fingerprintRows(List.of(row));
    }

    private long expectedSecondary(int category) {
        List<FixtureRow> selected = model.stream()
                .filter(row -> row.category() == category)
                .toList();
        return fingerprintRows(selected);
    }

    private long expectedRange(int bucket) {
        List<FixtureRow> selected = model.stream()
                .filter(row -> row.bucket() == bucket)
                .filter(row -> row.quantity() >= 2000 && row.quantity() <= 8000)
                .sorted(Comparator.comparingInt(FixtureRow::quantity).thenComparingInt(FixtureRow::id))
                .toList();
        return fingerprintRows(selected);
    }

    private long expectedFullScan() {
        return fingerprintRows(model);
    }

    private long expectedAggregate() {
        long[] counts = new long[CATEGORY_COUNT];
        long[] sums = new long[CATEGORY_COUNT];
        for (FixtureRow row : model) {
            counts[row.category()]++;
            sums[row.category()] += row.quantity();
        }
        long checksum = CHECKSUM_SEED;
        int count = 0;
        for (int category = 0; category < CATEGORY_COUNT; category++) {
            if (counts[category] == 0) {
                continue;
            }
            checksum = mix(checksum, category);
            checksum = mix(checksum, counts[category]);
            checksum = mix(checksum, sums[category]);
            count++;
        }
        return finish(checksum, count);
    }

    private static long fingerprintRows(List<FixtureRow> selected) {
        long checksum = CHECKSUM_SEED;
        for (FixtureRow row : selected) {
            checksum = mix(checksum, row.id());
            checksum = mix(checksum, row.quantity());
        }
        return finish(checksum, selected.size());
    }

    private static long queryTwoColumns(PreparedStatement statement) throws SQLException {
        long checksum = CHECKSUM_SEED;
        int count = 0;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                checksum = mix(checksum, resultSet.getInt(1));
                checksum = mix(checksum, resultSet.getInt(2));
                count++;
            }
        }
        return finish(checksum, count);
    }

    private static void requireChecksum(String operation, long expected, long actual) throws SQLException {
        if (expected != actual) {
            throw new SQLException(
                    operation + " semantic mismatch: expected " + expected + " but found " + actual);
        }
    }

    private static long mix(long checksum, long value) {
        long mixed = checksum ^ (value + CHECKSUM_MULTIPLIER + (checksum << 6) + (checksum >>> 2));
        return Long.rotateLeft(mixed * CHECKSUM_MULTIPLIER, 17);
    }

    private static long finish(long checksum, int rowCount) {
        return mix(checksum, rowCount);
    }

    private static String payload(int id, int requestedSize) {
        String prefix = "row-" + id + '-';
        StringBuilder builder = new StringBuilder(requestedSize);
        builder.append(prefix);
        int offset = 0;
        while (builder.length() < requestedSize) {
            builder.append((char) ('a' + ((id + offset) % 26)));
            offset++;
        }
        return builder.substring(0, requestedSize);
    }

    private void closeResources() throws Exception {
        Throwable failure = null;
        failure = closePreparedStatement(aggregate, failure);
        aggregate = null;
        failure = closePreparedStatement(fullScan, failure);
        fullScan = null;
        failure = closePreparedStatement(compositeRangeScan, failure);
        compositeRangeScan = null;
        failure = closePreparedStatement(secondaryEqualityLookup, failure);
        secondaryEqualityLookup = null;
        failure = closePreparedStatement(primaryKeyLookup, failure);
        primaryKeyLookup = null;

        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.rollback();
                    connection.close();
                }
            } catch (Throwable closeFailure) {
                failure = preserve(failure, closeFailure);
            } finally {
                connection = null;
            }
        }

        if (databaseName != null) {
            try {
                shutdownDatabase(databaseName);
            } catch (Throwable shutdownFailure) {
                failure = preserve(failure, shutdownFailure);
            } finally {
                databaseName = null;
            }
        }

        if (databaseRoot != null) {
            try {
                deleteRecursively(databaseRoot);
            } catch (Throwable deleteFailure) {
                failure = preserve(failure, deleteFailure);
            } finally {
                databaseRoot = null;
            }
        }

        if (failure != null) {
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Unexpected cleanup failure", failure);
        }
    }

    private static Throwable closePreparedStatement(PreparedStatement statement, Throwable failure) {
        if (statement == null) {
            return failure;
        }
        try {
            statement.close();
            return failure;
        } catch (Throwable closeFailure) {
            return preserve(failure, closeFailure);
        }
    }

    private static Throwable preserve(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void shutdownDatabase(String name) throws SQLException {
        try {
            DriverManager.getConnection("jdbc:derby:" + name + ";shutdown=true");
            throw new SQLException("Database shutdown did not report the expected completion state");
        } catch (SQLException shutdown) {
            if (!"08006".equals(shutdown.getSQLState())) {
                throw shutdown;
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record FixtureRow(int id, int category, int bucket, int quantity) {
    }
}
