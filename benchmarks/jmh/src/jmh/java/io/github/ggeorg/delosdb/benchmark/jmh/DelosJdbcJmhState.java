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
    private static final int MAX_ROWS = 1_000;
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
    private PreparedStatement primaryKeyCoveredLookup;
    private PreparedStatement secondaryEqualityLookup;
    private PreparedStatement secondaryEqualityCoveredLookup;
    private PreparedStatement secondaryEqualityCoveredCount;
    private PreparedStatement secondaryEqualityPayloadLookup;
    private PreparedStatement secondaryEqualityFullRowLookup;
    private PreparedStatement candidateRangeCoveredCount;
    private PreparedStatement compositeRangeScan;
    private PreparedStatement compositeRangeCoveredScan;
    private PreparedStatement fullScan;
    private PreparedStatement aggregate;
    private PreparedStatement transactionProbe;
    private long[] primaryChecksums;
    private long[] primaryCoveredChecksums;
    private long[] categoryChecksums;
    private long[] categoryCoveredChecksums;
    private long[] categoryCountChecksums;
    private long[] categoryPayloadChecksums;
    private long[] categoryFullRowChecksums;
    private long[] bucketChecksums;
    private long[] bucketCoveredChecksums;
    private long fullScanChecksum;
    private long aggregateChecksum;
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
            List<FixtureRow> fixture = createFixture();
            initializeExpectedChecksums(fixture);
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
        long actual = queryTwoColumns(primaryKeyLookup);
        requireChecksum("primary key " + id, primaryChecksums[id - 1], actual);
        return actual;
    }

    long primaryKeyCoveredLookup() throws SQLException {
        int id = primaryCursor;
        primaryCursor = id == rows ? 1 : id + 1;
        primaryKeyCoveredLookup.setInt(1, id);
        long actual = queryOneColumn(primaryKeyCoveredLookup);
        requireChecksum("covered primary key " + id, primaryCoveredChecksums[id - 1], actual);
        return actual;
    }

    long secondaryEqualityLookup() throws SQLException {
        int category = categoryCursor;
        categoryCursor = (category + 1) % CATEGORY_COUNT;
        secondaryEqualityLookup.setInt(1, category);
        long actual = queryTwoColumns(secondaryEqualityLookup);
        requireChecksum("secondary category " + category, categoryChecksums[category], actual);
        return actual;
    }

    long secondaryEqualityCoveredLookup() throws SQLException {
        int category = categoryCursor;
        categoryCursor = (category + 1) % CATEGORY_COUNT;
        secondaryEqualityCoveredLookup.setInt(1, category);
        long actual = queryOneColumn(secondaryEqualityCoveredLookup);
        requireChecksum(
                "covered secondary category " + category,
                categoryCoveredChecksums[category],
                actual);
        return actual;
    }

    long secondaryEqualityCoveredCount() throws SQLException {
        int category = categoryCursor;
        categoryCursor = (category + 1) % CATEGORY_COUNT;
        secondaryEqualityCoveredCount.setInt(1, category);
        long actual = queryOneColumn(secondaryEqualityCoveredCount);
        requireChecksum(
                "covered secondary count " + category,
                categoryCountChecksums[category],
                actual);
        return actual;
    }

    long secondaryEqualityPayloadLookup() throws SQLException {
        int category = categoryCursor;
        categoryCursor = (category + 1) % CATEGORY_COUNT;
        secondaryEqualityPayloadLookup.setInt(1, category);
        long actual = queryIdAndPayload(secondaryEqualityPayloadLookup);
        requireChecksum(
                "secondary payload category " + category,
                categoryPayloadChecksums[category],
                actual);
        return actual;
    }

    long secondaryEqualityFullRowLookup() throws SQLException {
        int category = categoryCursor;
        categoryCursor = (category + 1) % CATEGORY_COUNT;
        secondaryEqualityFullRowLookup.setInt(1, category);
        long actual = queryFullRows(secondaryEqualityFullRowLookup);
        requireChecksum(
                "secondary full row category " + category,
                categoryFullRowChecksums[category],
                actual);
        return actual;
    }


    long candidateRangeCoveredCount(int candidateCount) throws SQLException {
        validateCandidateCount(candidateCount);
        candidateRangeCoveredCount.setInt(1, candidateCount);
        long actual = queryOneColumn(candidateRangeCoveredCount);
        requireChecksum(
                "candidate range covered count " + candidateCount,
                fingerprintCount(candidateCount),
                actual);
        return actual;
    }

    String candidateRangeRuntimeStatistics(int candidateCount) throws SQLException {
        validateCandidateCount(candidateCount);
        try (Statement statement = connection.createStatement()) {
            statement.execute("call syscs_util.syscs_set_runtimestatistics(1)");
        }
        candidateRangeCoveredCount(candidateCount);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "values syscs_util.syscs_get_runtimestatistics()")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("DelosDB runtime statistics returned no row");
            }
            String statistics = resultSet.getString(1);
            if (statistics == null || statistics.isBlank()) {
                throw new IllegalStateException("DelosDB runtime statistics are empty");
            }
            return statistics;
        } finally {
            try (Statement statement = connection.createStatement()) {
                statement.execute("call syscs_util.syscs_set_runtimestatistics(0)");
            }
            connection.rollback();
        }
    }

    private void validateCandidateCount(int candidateCount) {
        if (candidateCount < 1 || candidateCount > rows) {
            throw new IllegalArgumentException(
                    "candidateCount must be between 1 and rows: " + candidateCount);
        }
    }

    long compositeRangeScan() throws SQLException {
        int bucket = bucketCursor;
        bucketCursor = (bucket + 1) % BUCKET_COUNT;
        compositeRangeScan.setInt(1, bucket);
        long actual = queryTwoColumns(compositeRangeScan);
        requireChecksum("composite range bucket " + bucket, bucketChecksums[bucket], actual);
        return actual;
    }

    long compositeRangeCoveredScan() throws SQLException {
        int bucket = bucketCursor;
        bucketCursor = (bucket + 1) % BUCKET_COUNT;
        compositeRangeCoveredScan.setInt(1, bucket);
        long actual = queryTwoColumns(compositeRangeCoveredScan);
        requireChecksum(
                "covered composite range bucket " + bucket,
                bucketCoveredChecksums[bucket],
                actual);
        return actual;
    }

    long fullScan() throws SQLException {
        long actual = queryTwoColumns(fullScan);
        requireChecksum("full scan", fullScanChecksum, actual);
        return actual;
    }

    long aggregate() throws SQLException {
        long actual = queryAggregate();
        requireChecksum("aggregate", aggregateChecksum, actual);
        return actual;
    }

    long readTransactionCommit() throws SQLException {
        long probe = queryTransactionProbe();
        connection.commit();
        return mix(++transactionSequence, probe);
    }

    long readTransactionRollback() throws SQLException {
        long probe = queryTransactionProbe();
        connection.rollback();
        return mix(++transactionSequence, probe);
    }

    private void validateParameters() {
        String normalizedProvider = provider == null
                ? ""
                : provider.trim().toLowerCase(Locale.ROOT);
        if (!normalizedProvider.equals("heap") && !normalizedProvider.equals("mvcc")) {
            throw new IllegalArgumentException("provider must be heap or mvcc: " + provider);
        }
        provider = normalizedProvider;
        if (rows < 100 || rows > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "rows must be between 100 and " + MAX_ROWS + ": " + rows);
        }
        if (payloadSize < 16 || payloadSize > 4096) {
            throw new IllegalArgumentException(
                    "payloadSize must be between 16 and 4096: " + payloadSize);
        }
        if (commitBatchSize < 1 || commitBatchSize > rows) {
            throw new IllegalArgumentException(
                    "commitBatchSize must be between 1 and rows: " + commitBatchSize);
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

        List<FixtureRow> generated = new ArrayList<>(rows);
        Random random = new Random(SEED);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + TABLE
                        + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)")) {
            for (int id = 1; id <= rows; id++) {
                int category = id % CATEGORY_COUNT;
                int bucket = id % BUCKET_COUNT;
                int quantity = random.nextInt(10_000);
                String payloadValue = payload(id, payloadSize);
                generated.add(new FixtureRow(id, category, bucket, quantity, payloadValue));
                insert.setInt(1, id);
                insert.setInt(2, category);
                insert.setInt(3, bucket);
                insert.setInt(4, quantity);
                insert.setString(5, payloadValue);
                insert.addBatch();
                if (id % commitBatchSize == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            if (rows % commitBatchSize != 0) {
                insert.executeBatch();
                connection.commit();
            }
        }
        return List.copyOf(generated);
    }

    private void initializeExpectedChecksums(List<FixtureRow> fixture) {
        primaryChecksums = new long[rows];
        primaryCoveredChecksums = new long[rows];
        for (FixtureRow row : fixture) {
            primaryChecksums[row.id() - 1] = fingerprintRows(List.of(row));
            primaryCoveredChecksums[row.id() - 1] = fingerprintIds(List.of(row));
        }

        categoryChecksums = new long[CATEGORY_COUNT];
        categoryCoveredChecksums = new long[CATEGORY_COUNT];
        categoryCountChecksums = new long[CATEGORY_COUNT];
        categoryPayloadChecksums = new long[CATEGORY_COUNT];
        categoryFullRowChecksums = new long[CATEGORY_COUNT];
        for (int category = 0; category < CATEGORY_COUNT; category++) {
            int selectedCategory = category;
            List<FixtureRow> selected = fixture.stream()
                    .filter(row -> row.category() == selectedCategory)
                    .toList();
            categoryChecksums[category] = fingerprintRows(selected);
            categoryCoveredChecksums[category] = fingerprintCategories(selected);
            categoryCountChecksums[category] = fingerprintCount(selected.size());
            categoryPayloadChecksums[category] = fingerprintIdAndPayload(selected);
            categoryFullRowChecksums[category] = fingerprintFullRows(selected);
        }

        bucketChecksums = new long[BUCKET_COUNT];
        bucketCoveredChecksums = new long[BUCKET_COUNT];
        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            int selectedBucket = bucket;
            List<FixtureRow> selected = fixture.stream()
                    .filter(row -> row.bucket() == selectedBucket)
                    .filter(row -> row.quantity() >= 2000 && row.quantity() <= 8000)
                    .sorted(Comparator.comparingInt(FixtureRow::quantity).thenComparingInt(FixtureRow::id))
                    .toList();
            bucketChecksums[bucket] = fingerprintRows(selected);
            bucketCoveredChecksums[bucket] = fingerprintBucketAndQuantity(selected);
        }

        fullScanChecksum = fingerprintRows(fixture);
        aggregateChecksum = expectedAggregate(fixture);
    }

    private void prepareStatements() throws SQLException {
        primaryKeyLookup = connection.prepareStatement(
                "select id, quantity from " + TABLE + " where id = ?");
        primaryKeyCoveredLookup = connection.prepareStatement(
                "select id from " + TABLE + " where id = ?");
        secondaryEqualityLookup = connection.prepareStatement(
                "select id, quantity from " + TABLE + " where category = ? order by id");
        secondaryEqualityCoveredLookup = connection.prepareStatement(
                "select category from " + TABLE + " where category = ?");
        secondaryEqualityCoveredCount = connection.prepareStatement(
                "select count(*) from " + TABLE + " where category = ?");
        secondaryEqualityPayloadLookup = connection.prepareStatement(
                "select id, payload from " + TABLE + " where category = ? order by id");
        secondaryEqualityFullRowLookup = connection.prepareStatement(
                "select id, category, bucket, quantity, payload from " + TABLE
                        + " where category = ? order by id");
        candidateRangeCoveredCount = connection.prepareStatement(
                "select count(*) from " + TABLE + " where id between 1 and ?");
        compositeRangeScan = connection.prepareStatement(
                "select id, quantity from " + TABLE
                        + " where bucket = ? and quantity between 2000 and 8000 order by quantity, id");
        compositeRangeCoveredScan = connection.prepareStatement(
                "select bucket, quantity from " + TABLE
                        + " where bucket = ? and quantity between 2000 and 8000"
                        + " order by bucket, quantity");
        fullScan = connection.prepareStatement(
                "select id, quantity from " + TABLE + " order by id");
        aggregate = connection.prepareStatement(
                "select category, count(*), sum(quantity) from " + TABLE
                        + " group by category order by category");
        transactionProbe = connection.prepareStatement("values 1");
    }

    private void verifySemanticSurface() throws SQLException {
        int[] primaryIds = {1, Math.max(1, rows / 2), rows};
        for (int id : primaryIds) {
            primaryKeyLookup.setInt(1, id);
            requireChecksum(
                    "primary key " + id,
                    primaryChecksums[id - 1],
                    queryTwoColumns(primaryKeyLookup));
            primaryKeyCoveredLookup.setInt(1, id);
            requireChecksum(
                    "covered primary key " + id,
                    primaryCoveredChecksums[id - 1],
                    queryOneColumn(primaryKeyCoveredLookup));
        }

        for (int category = 0; category < CATEGORY_COUNT; category++) {
            secondaryEqualityLookup.setInt(1, category);
            requireChecksum(
                    "secondary category " + category,
                    categoryChecksums[category],
                    queryTwoColumns(secondaryEqualityLookup));
            secondaryEqualityCoveredLookup.setInt(1, category);
            requireChecksum(
                    "covered secondary category " + category,
                    categoryCoveredChecksums[category],
                    queryOneColumn(secondaryEqualityCoveredLookup));
            secondaryEqualityCoveredCount.setInt(1, category);
            requireChecksum(
                    "covered secondary count " + category,
                    categoryCountChecksums[category],
                    queryOneColumn(secondaryEqualityCoveredCount));
            secondaryEqualityPayloadLookup.setInt(1, category);
            requireChecksum(
                    "secondary payload category " + category,
                    categoryPayloadChecksums[category],
                    queryIdAndPayload(secondaryEqualityPayloadLookup));
            secondaryEqualityFullRowLookup.setInt(1, category);
            requireChecksum(
                    "secondary full row category " + category,
                    categoryFullRowChecksums[category],
                    queryFullRows(secondaryEqualityFullRowLookup));
        }

        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            compositeRangeScan.setInt(1, bucket);
            requireChecksum(
                    "composite range bucket " + bucket,
                    bucketChecksums[bucket],
                    queryTwoColumns(compositeRangeScan));
            compositeRangeCoveredScan.setInt(1, bucket);
            requireChecksum(
                    "covered composite range bucket " + bucket,
                    bucketCoveredChecksums[bucket],
                    queryTwoColumns(compositeRangeCoveredScan));
        }

        requireChecksum("full scan", fullScanChecksum, queryTwoColumns(fullScan));
        requireChecksum("aggregate", aggregateChecksum, queryAggregate());
        requireChecksum("transaction probe", expectedTransactionProbe(), queryTransactionProbe());
    }

    private static long expectedAggregate(List<FixtureRow> fixture) {
        long[] counts = new long[CATEGORY_COUNT];
        long[] sums = new long[CATEGORY_COUNT];
        for (FixtureRow row : fixture) {
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

    private static long fingerprintIds(List<FixtureRow> selected) {
        long checksum = CHECKSUM_SEED;
        for (FixtureRow row : selected) {
            checksum = mix(checksum, row.id());
        }
        return finish(checksum, selected.size());
    }

    private static long fingerprintCategories(List<FixtureRow> selected) {
        long checksum = CHECKSUM_SEED;
        for (FixtureRow row : selected) {
            checksum = mix(checksum, row.category());
        }
        return finish(checksum, selected.size());
    }

    private static long fingerprintBucketAndQuantity(List<FixtureRow> selected) {
        long checksum = CHECKSUM_SEED;
        for (FixtureRow row : selected) {
            checksum = mix(checksum, row.bucket());
            checksum = mix(checksum, row.quantity());
        }
        return finish(checksum, selected.size());
    }

    private static long fingerprintCount(int count) {
        return finish(mix(CHECKSUM_SEED, count), 1);
    }

    private static long fingerprintIdAndPayload(List<FixtureRow> selected) {
        long checksum = CHECKSUM_SEED;
        for (FixtureRow row : selected) {
            checksum = mix(checksum, row.id());
            checksum = fingerprintPayload(checksum, row.payload());
        }
        return finish(checksum, selected.size());
    }

    private static long fingerprintFullRows(List<FixtureRow> selected) {
        long checksum = CHECKSUM_SEED;
        for (FixtureRow row : selected) {
            checksum = mix(checksum, row.id());
            checksum = mix(checksum, row.category());
            checksum = mix(checksum, row.bucket());
            checksum = mix(checksum, row.quantity());
            checksum = fingerprintPayload(checksum, row.payload());
        }
        return finish(checksum, selected.size());
    }

    private static long fingerprintPayload(long checksum, String value) {
        checksum = mix(checksum, value.length());
        if (!value.isEmpty()) {
            checksum = mix(checksum, value.charAt(0));
            checksum = mix(checksum, value.charAt(value.length() / 2));
            checksum = mix(checksum, value.charAt(value.length() - 1));
        }
        return checksum;
    }

    private static long queryOneColumn(PreparedStatement statement) throws SQLException {
        long checksum = CHECKSUM_SEED;
        int count = 0;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                checksum = mix(checksum, resultSet.getLong(1));
                count++;
            }
        }
        return finish(checksum, count);
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

    private static long queryIdAndPayload(PreparedStatement statement) throws SQLException {
        long checksum = CHECKSUM_SEED;
        int count = 0;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                checksum = mix(checksum, resultSet.getInt(1));
                checksum = fingerprintPayload(checksum, resultSet.getString(2));
                count++;
            }
        }
        return finish(checksum, count);
    }

    private static long queryFullRows(PreparedStatement statement) throws SQLException {
        long checksum = CHECKSUM_SEED;
        int count = 0;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                checksum = mix(checksum, resultSet.getInt(1));
                checksum = mix(checksum, resultSet.getInt(2));
                checksum = mix(checksum, resultSet.getInt(3));
                checksum = mix(checksum, resultSet.getInt(4));
                checksum = fingerprintPayload(checksum, resultSet.getString(5));
                count++;
            }
        }
        return finish(checksum, count);
    }

    private long queryAggregate() throws SQLException {
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

    private long queryTransactionProbe() throws SQLException {
        long checksum = CHECKSUM_SEED;
        int count = 0;
        try (ResultSet resultSet = transactionProbe.executeQuery()) {
            while (resultSet.next()) {
                checksum = mix(checksum, resultSet.getInt(1));
                count++;
            }
        }
        long actual = finish(checksum, count);
        requireChecksum("transaction probe", expectedTransactionProbe(), actual);
        return actual;
    }

    private static long expectedTransactionProbe() {
        return finish(mix(CHECKSUM_SEED, 1), 1);
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
        failure = closePreparedStatement(transactionProbe, failure);
        transactionProbe = null;
        failure = closePreparedStatement(aggregate, failure);
        aggregate = null;
        failure = closePreparedStatement(fullScan, failure);
        fullScan = null;
        failure = closePreparedStatement(compositeRangeCoveredScan, failure);
        compositeRangeCoveredScan = null;
        failure = closePreparedStatement(compositeRangeScan, failure);
        compositeRangeScan = null;
        failure = closePreparedStatement(candidateRangeCoveredCount, failure);
        candidateRangeCoveredCount = null;
        failure = closePreparedStatement(secondaryEqualityFullRowLookup, failure);
        secondaryEqualityFullRowLookup = null;
        failure = closePreparedStatement(secondaryEqualityPayloadLookup, failure);
        secondaryEqualityPayloadLookup = null;
        failure = closePreparedStatement(secondaryEqualityCoveredCount, failure);
        secondaryEqualityCoveredCount = null;
        failure = closePreparedStatement(secondaryEqualityCoveredLookup, failure);
        secondaryEqualityCoveredLookup = null;
        failure = closePreparedStatement(secondaryEqualityLookup, failure);
        secondaryEqualityLookup = null;
        failure = closePreparedStatement(primaryKeyCoveredLookup, failure);
        primaryKeyCoveredLookup = null;
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

        primaryChecksums = null;
        primaryCoveredChecksums = null;
        categoryChecksums = null;
        categoryCoveredChecksums = null;
        categoryCountChecksums = null;
        categoryPayloadChecksums = null;
        categoryFullRowChecksums = null;
        bucketChecksums = null;
        bucketCoveredChecksums = null;

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

    private record FixtureRow(
            int id, int category, int bucket, int quantity, String payload) {
    }
}
