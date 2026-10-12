/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DeleteRow;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.SplittableRandom;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.BANK_BRANCHES;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.BANK_TELLERS_PER_BRANCH;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_NEW_LINE_COUNT;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_WAREHOUSES;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.fixtureQuantities;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.highCardGroupTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinDimensionTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinFanoutChildTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinFanoutParentTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinParentRows;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinCustomerTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinItemTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinLineTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinOrderTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.SEED;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.mix;

/** Shared workload SQL, identifier selection and expected-value calculations. */
final class BenchmarkWorkloadSql {
    private BenchmarkWorkloadSql() {
    }

    static final int FITNESS_CATEGORY = 7;

    static int joinSelectiveParents(int rowCount) {
        return Math.min(100, joinParentRows(rowCount));
    }

    static int joinBucketParents(int rowCount, int bucket) {
        int parents = joinParentRows(rowCount);
        if (parents < bucket) {
            return 0;
        }
        return ((parents - bucket) / 10) + 1;
    }

    static int bankAccountId(int globalOrdinal, int rowCount) {
        return 1 + Math.floorMod(globalOrdinal * 7919, rowCount);
    }

    static int bankBranchId(int accountId, int rowCount) {
        return 1 + Math.min(BANK_BRANCHES - 1,
                (int) (((long) (accountId - 1) * BANK_BRANCHES) / rowCount));
    }

    static int bankTellerId(int branchId, int globalOrdinal) {
        return (branchId - 1) * BANK_TELLERS_PER_BRANCH
                + 1 + Math.floorMod(globalOrdinal * 7, BANK_TELLERS_PER_BRANCH);
    }

    static int bankDelta(int globalOrdinal) {
        int delta = Math.floorMod(globalOrdinal * 37 + 17, 199) - 99;
        return delta == 0 ? 1 : delta;
    }

    static int orderWarehouseId(int clientIndex) {
        return 1 + Math.floorMod(clientIndex, ORDER_WAREHOUSES);
    }

    static int orderStockId(int globalOrdinal, int line, int rowCount) {
        return 1 + Math.floorMod(globalOrdinal * 3 + line * 97, rowCount);
    }

    static int orderNewLineAmount(int line) {
        if (line < 1 || line > ORDER_NEW_LINE_COUNT) {
            throw new IllegalArgumentException("Unexpected Order Entry line number: " + line);
        }
        return 10 + line;
    }

    static int orderNewTotalAmount() {
        int total = 0;
        for (int line = 1; line <= ORDER_NEW_LINE_COUNT; line++) {
            total = Math.addExact(total, orderNewLineAmount(line));
        }
        return total;
    }

    static int orderPaymentAmount(int globalOrdinal) {
        return 5 + Math.floorMod(globalOrdinal * 11, 20);
    }

    static void bindFitnessInsert(PreparedStatement statement, int id, int payloadSize)
            throws SQLException {
        bindFitnessInsert(statement, 1, id, payloadSize);
    }

    static void bindFitnessInsert(
            PreparedStatement statement, int firstParameter, int id, int payloadSize)
            throws SQLException {
        statement.setInt(firstParameter, id);
        statement.setInt(firstParameter + 1, id % 17);
        statement.setInt(firstParameter + 2, id % 11);
        statement.setInt(firstParameter + 3, fitnessInsertQuantity(id));
        statement.setString(firstParameter + 4, fitnessPayload(id, payloadSize));
    }

    static String fitnessInsertSql(String table, int rowsPerStatement) {
        StringBuilder sql = new StringBuilder(
                "insert into " + table
                        + " (id, category, bucket, quantity, payload) values ");
        for (int row = 0; row < rowsPerStatement; row++) {
            if (row != 0) {
                sql.append(", ");
            }
            sql.append("(?, ?, ?, ?, ?)");
        }
        return sql.toString();
    }

    static int fitnessInsertQuantity(int id) {
        return Math.floorMod(id * 31, 10_000);
    }

    static String fitnessPayload(int id, int length) {
        String prefix = "insert-" + id + '-';
        StringBuilder value = new StringBuilder(length);
        while (value.length() < length) {
            value.append(prefix);
        }
        return value.substring(0, length);
    }

    static long verifyInsertedState(
            Connection connection,
            String table,
            int firstId,
            int lastId,
            int baselineRowCount,
            long expectedRows,
            int payloadSize) throws SQLException {
        if (firstId != baselineRowCount + 1 || lastId < firstId) {
            throw new SQLException(
                    "Invalid INSERT verification range: " + firstId + ".." + lastId);
        }
        long rows = 0L;
        long fingerprint = 1L;
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, category, bucket, quantity, payload from " + table
                        + " where id >= ? and id <= ? order by id")) {
            statement.setInt(1, firstId);
            statement.setInt(2, lastId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int id = resultSet.getInt(1);
                    int category = resultSet.getInt(2);
                    int bucket = resultSet.getInt(3);
                    int quantity = resultSet.getInt(4);
                    String payload = resultSet.getString(5);
                    if (id != firstId + rows
                            || category != id % 17
                            || bucket != id % 11
                            || quantity != fitnessInsertQuantity(id)
                            || !fitnessPayload(id, payloadSize).equals(payload)) {
                        throw new SQLException("INSERT fitness post-state drift at id=" + id);
                    }
                    fingerprint = mix(fingerprint, id);
                    fingerprint = mix(fingerprint, category);
                    fingerprint = mix(fingerprint, bucket);
                    fingerprint = mix(fingerprint, quantity);
                    fingerprint = mix(fingerprint, payload.hashCode());
                    rows++;
                }
            }
        }
        if (rows != expectedRows) {
            throw new SQLException(
                    "INSERT fitness row-count drift: expected=" + expectedRows + ", actual=" + rows);
        }
        return mix(fingerprint, rows);
    }

    static DelosSqlSemanticOracle.Result authoritativeInsertState(
            Connection connection,
            String table,
            int firstId,
            int lastId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, category, bucket, quantity, payload from " + table
                        + " where id >= ? and id <= ? order by id")) {
            statement.setInt(1, firstId);
            statement.setInt(2, lastId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return DelosSqlSemanticOracle.query(
                        resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
            }
        }
    }

    static String fitnessReadSql(Workload workload, String table) {
        return switch (workload) {
            case PROJECTION_COVERED ->
                    "select category from " + table + " where category = ?";
            case PROJECTION_TWO_COLUMN ->
                    "select id, quantity from " + table + " where category = ? order by id";
            case PROJECTION_FULL_ROW ->
                    "select id, category, bucket, quantity, payload from " + table
                            + " where category = ? order by id";
            case GROUP_LOW_CARD ->
                    "select category, count(*), sum(quantity) from " + table
                            + " group by category order by category";
            case JOIN_INDEXED_1TO1 ->
                    "select a.id from " + table + " a join " + joinDimensionTableName(table)
                            + " b on a.id = b.id";
            case JOIN_INDEXED_FANOUT ->
                    "select p.id, c.id from " + joinFanoutParentTableName(table) + " p join "
                            + joinFanoutChildTableName(table)
                            + " c on c.parent_id = p.id where p.id between ? and ? order by p.id, c.id";
            case JOIN_3WAY_SELECTIVE ->
                    "select c.id, o.id, l.id from " + multiJoinCustomerTableName(table) + " c join "
                            + multiJoinOrderTableName(table) + " o on o.customer_id = c.id join "
                            + multiJoinLineTableName(table)
                            + " l on l.order_id = o.id where c.id between ? and ?";
            case JOIN_4WAY_FANOUT ->
                    "select c.id, o.id, l.line_no, i.id from " + multiJoinCustomerTableName(table)
                            + " c join " + multiJoinOrderTableName(table)
                            + " o on o.customer_id = c.id join " + multiJoinLineTableName(table)
                            + " l on l.order_id = o.id join " + multiJoinItemTableName(table)
                            + " i on i.id = l.item_id where c.bucket = ?";
            case GROUP_HIGH_CARD ->
                    "select group_key, count(*), sum(quantity) from " + highCardGroupTableName(table)
                            + " group by group_key order by group_key";
            case SORT_FULL ->
                    "select id, quantity from " + table + " order by quantity desc, id";
            default -> throw new IllegalArgumentException("Not a fitness read workload: " + workload);
        };
    }

    static void bindFitnessRead(PreparedStatement statement, Workload workload) throws SQLException {
        if (workload == Workload.PROJECTION_COVERED
                || workload == Workload.PROJECTION_TWO_COLUMN
                || workload == Workload.PROJECTION_FULL_ROW) {
            statement.setInt(1, FITNESS_CATEGORY);
        } else if (workload == Workload.JOIN_INDEXED_FANOUT
                || workload == Workload.JOIN_3WAY_SELECTIVE) {
            statement.setInt(1, 1);
            statement.setInt(2, 100);
        } else if (workload == Workload.JOIN_4WAY_FANOUT) {
            statement.setInt(1, 7);
        }
    }

    static int expectedFitnessRows(Workload workload, int rowCount) {
        return switch (workload) {
            case PROJECTION_COVERED, PROJECTION_TWO_COLUMN, PROJECTION_FULL_ROW -> {
                int count = 0;
                for (int id = 1; id <= rowCount; id++) {
                    if (id % 17 == FITNESS_CATEGORY) {
                        count++;
                    }
                }
                yield count;
            }
            case GROUP_LOW_CARD -> Math.min(17, rowCount);
            case JOIN_INDEXED_1TO1, GROUP_HIGH_CARD -> Math.min(1000, rowCount);
            case JOIN_INDEXED_FANOUT -> joinSelectiveParents(rowCount) * 10;
            case JOIN_3WAY_SELECTIVE -> joinSelectiveParents(rowCount) * 4 * 3;
            case JOIN_4WAY_FANOUT -> joinBucketParents(rowCount, 7) * 4 * 3;
            case SORT_FULL -> rowCount;
            default -> throw new IllegalArgumentException("Not a fitness read workload: " + workload);
        };
    }

    static long executeFitnessRead(
            PreparedStatement statement,
            Workload workload,
            int expectedRows) throws SQLException {
        bindFitnessRead(statement, workload);
        int rows = 0;
        long fingerprint = 1L;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                switch (workload) {
                    case PROJECTION_COVERED -> fingerprint = mix(fingerprint, resultSet.getInt(1));
                    case PROJECTION_TWO_COLUMN, SORT_FULL ->
                            fingerprint = mix(mix(fingerprint, resultSet.getInt(1)), resultSet.getInt(2));
                    case PROJECTION_FULL_ROW -> {
                        fingerprint = mix(fingerprint, resultSet.getInt(1));
                        fingerprint = mix(fingerprint, resultSet.getInt(2));
                        fingerprint = mix(fingerprint, resultSet.getInt(3));
                        fingerprint = mix(fingerprint, resultSet.getInt(4));
                        fingerprint = mix(fingerprint, resultSet.getString(5).hashCode());
                    }
                    case GROUP_LOW_CARD, GROUP_HIGH_CARD -> {
                        fingerprint = mix(fingerprint, resultSet.getInt(1));
                        fingerprint = mix(fingerprint, resultSet.getLong(2));
                        fingerprint = mix(fingerprint, resultSet.getLong(3));
                    }
                    case JOIN_INDEXED_1TO1 ->
                            fingerprint += mix(0x9E3779B97F4A7C15L, resultSet.getInt(1));
                    case JOIN_INDEXED_FANOUT -> {
                        fingerprint = mix(fingerprint, resultSet.getInt(1));
                        fingerprint = mix(fingerprint, resultSet.getInt(2));
                    }
                    case JOIN_3WAY_SELECTIVE -> {
                        long tuple = mix(0x9E3779B97F4A7C15L, resultSet.getInt(1));
                        tuple = mix(tuple, resultSet.getInt(2));
                        tuple = mix(tuple, resultSet.getInt(3));
                        fingerprint += tuple;
                    }
                    case JOIN_4WAY_FANOUT -> {
                        long tuple = mix(0x9E3779B97F4A7C15L, resultSet.getInt(1));
                        tuple = mix(tuple, resultSet.getInt(2));
                        tuple = mix(tuple, resultSet.getInt(3));
                        tuple = mix(tuple, resultSet.getInt(4));
                        fingerprint += tuple;
                    }
                    default -> throw new SQLException("Unexpected fitness read workload: " + workload);
                }
                rows++;
            }
        }
        if (rows != expectedRows) {
            throw new SQLException(
                    "Fitness read row-count drift for " + workload
                            + ": expected=" + expectedRows + ", actual=" + rows);
        }
        return mix(fingerprint, rows);
    }

    static int[] deleteReinsertIds(Spec spec, int rowCount) {
        int width = spec.operationsPerTransaction();
        if (width < 1 || (long) width * spec.clients() > rowCount) {
            throw new IllegalArgumentException(
                    "Delete/reinsert shape exceeds fixture rows: " + spec + ", rows=" + rowCount);
        }
        int[] ids = new int[Math.multiplyExact(width, spec.clients())];
        int partition = Math.max(width, rowCount / spec.clients());
        for (int client = 0; client < spec.clients(); client++) {
            int base = 1 + client * partition;
            for (int operation = 0; operation < width; operation++) {
                ids[client * width + operation] = base + operation;
            }
        }
        return ids;
    }

    static DeleteRow readDeleteRow(Connection connection, String table, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, category, bucket, quantity, payload from " + table + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Delete/reinsert source row is missing: id=" + id);
                }
                DeleteRow row = new DeleteRow(
                        resultSet.getInt(1),
                        resultSet.getInt(2),
                        resultSet.getInt(3),
                        resultSet.getInt(4),
                        resultSet.getString(5));
                if (resultSet.next()) {
                    throw new SQLException("Delete/reinsert source row is duplicated: id=" + id);
                }
                return row;
            }
        }
    }

    static int[] mutationIds(Spec spec, int rowCount) {
        if (spec.workload() == Workload.MIXED_80R20W) {
            if (spec.clients() != 8 || rowCount < 16) {
                throw new IllegalArgumentException(
                        "F11 80R20W sentinel requires exactly 8 clients and at least 16 rows: " + spec);
            }
            int[] ids = new int[spec.clients()];
            for (int index = 0; index < ids.length; index++) {
                ids[index] = 1 + index;
            }
            return ids;
        }
        if (spec.workload() == Workload.MIXED_50R50W_HOT) {
            if (spec.clients() != 8 || rowCount < 16) {
                throw new IllegalArgumentException(
                        "F11 50R50W hot sentinel requires exactly 8 clients and at least 16 rows: " + spec);
            }
            return new int[]{1, 2, 3, 4};
        }
        if (spec.workload() == Workload.LONG_READER_DISJOINT_WRITER) {
            if (spec.clients() != 4 || rowCount < 8) {
                throw new IllegalArgumentException(
                        "F12 disjoint long-reader sentinel requires 4 writers and at least 8 rows: " + spec);
            }
            int start = 1 + rowCount / 4;
            int endExclusive = 1 + (3 * rowCount) / 4;
            return new int[]{1, start, endExclusive - 1, rowCount};
        }
        if (spec.workload() == Workload.DISJOINT_INDEXED_UPDATE) {
            if (spec.clients() > rowCount) {
                throw new IllegalArgumentException(
                        "disjoint concurrency clients exceed fixture rows: clients="
                                + spec.clients() + ", rows=" + rowCount);
            }
            int[] ids = new int[spec.clients()];
            for (int index = 0; index < ids.length; index++) {
                ids[index] = 1 + (int) (((long) index * rowCount) / ids.length);
            }
            return ids;
        }
        if (spec.workload() == Workload.LONG_READER_HOT_WRITER) {
            if (spec.clients() != 4 || rowCount < 4) {
                throw new IllegalArgumentException(
                        "F12 hot long-reader sentinel requires 4 writers and at least 4 rows: " + spec);
            }
            return new int[]{1 + rowCount / 2};
        }
        return spec.workload() == Workload.CONTENDED_INDEXED_UPDATE ? new int[]{1} : new int[0];
    }

    static int[] readOperationIds(Spec spec, int rowCount, int client, int operations) {
        int[] ids = new int[operations];
        switch (spec.workload()) {
            case PRIMARY_KEY_READ_HOT -> Arrays.fill(ids, 1);
            case PRIMARY_KEY_READ_DISJOINT, PK_READ_1_RC, PK_READ_10_RC, PK_READ_100_RC, PK_READ_10_RU,
                    SAME_TABLE_DISJOINT, PRIVATE_TABLE_DISJOINT -> {
                int id = 1 + (int) (((long) client * rowCount) / spec.clients());
                Arrays.fill(ids, id);
            }
            case PRIMARY_KEY_READ_RANDOM -> {
                SplittableRandom random = new SplittableRandom(
                        SEED + 0x9E3779B97F4A7C15L * (client + 1L));
                for (int operation = 0; operation < ids.length; operation++) {
                    ids[operation] = 1 + random.nextInt(rowCount);
                }
            }
            default -> throw new IllegalArgumentException("Not a read workload: " + spec.workload());
        }
        return ids;
    }

    static long rangeIdFingerprint(int startInclusive, int endExclusive) {
        long fingerprint = 1L;
        for (int id = startInclusive; id < endExclusive; id++) {
            fingerprint = mix(fingerprint, id);
        }
        return fingerprint;
    }

    static long rangeFingerprint(
            int[] fixtureQuantities, int startInclusive, int endExclusive) {
        long fingerprint = 1L;
        for (int id = startInclusive; id < endExclusive; id++) {
            fingerprint = mix(mix(fingerprint, id), fixtureQuantities[id]);
        }
        return fingerprint;
    }

    static int quantity(Connection connection, String table, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select quantity from " + table + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Concurrent verification row missing: id=" + id);
                }
                int quantity = resultSet.getInt(1);
                if (resultSet.next()) {
                    throw new SQLException("Concurrent verification returned duplicate id=" + id);
                }
                return quantity;
            }
        }
    }

    static boolean isRetryableConflict(Target target, SQLException failure) {
        if (target == Target.SQLITE) {
            int primaryResultCode = failure.getErrorCode() & 0xff;
            if (primaryResultCode == 5 || primaryResultCode == 6) {
                return true;
            }
        }
        String sqlState = failure.getSQLState();
        return sqlState != null && sqlState.startsWith("40");
    }

    static void closeStatement(PreparedStatement statement, Throwable primary) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
