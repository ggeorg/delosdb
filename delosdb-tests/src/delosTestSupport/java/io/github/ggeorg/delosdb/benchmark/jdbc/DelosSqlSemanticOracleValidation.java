/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executable Phase 0A proof for the SQL-authoritative semantic-oracle contract. */
public final class DelosSqlSemanticOracleValidation {
    private DelosSqlSemanticOracleValidation() {
    }

    public static void main(String[] args) throws Exception {
        Path databaseRoot = Path.of(System.getProperty(
                "delosdb.benchmark.sqlOracle.databaseRoot",
                "build/tmp/delos-sql-semantic-oracle"));
        Path reportDirectory = Path.of(System.getProperty(
                "delosdb.benchmark.sqlOracle.reportDirectory",
                "build/reports/delosdb/benchmarks/sql-semantic-oracle"));
        DelosBenchmarkSupport.deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);

        OracleMatrix heap = runProvider(databaseRoot, DelosBenchmarkProvider.HEAP);
        OracleMatrix mvcc = runProvider(databaseRoot, DelosBenchmarkProvider.MVCC);
        requireEqual("Heap/MVCC SQL oracle matrix", heap, mvcc);
        requireOracleProperties(heap);

        String report = report(heap);
        DelosBenchmarkSupport.writeUtf8(reportDirectory.resolve("sql-semantic-oracle.txt"), report);
        System.out.println("DelosDB SQL semantic oracle validation complete: " + reportDirectory.toAbsolutePath());
    }

    private static OracleMatrix runProvider(Path databaseRoot, DelosBenchmarkProvider provider)
            throws Exception {
        Path database = Path.of(databaseRoot + "-" + provider.id());
        return DelosBenchmarkSupport.withFreshEmbeddedDatabase(database, connection -> {
            connection.setAutoCommit(false);
            String table = "DELOS_SQL_ORACLE_" + provider.id().toUpperCase(java.util.Locale.ROOT);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("create table " + table
                        + " (id int not null primary key, grp int not null, amount decimal(12,2) not null,"
                        + " label varchar(40) not null, optional_value int)"
                        + provider.createTableSuffix());
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into " + table + " (id, grp, amount, label, optional_value) values (?, ?, ?, ?, ?)")) {
                insert(insert, 1, 1, "10.00", "alpha", 11);
                insert(insert, 2, 0, "20.50", "beta", null);
                insert(insert, 3, 1, "30.00", "gamma", 33);
                insert(insert, 4, 0, "40.25", "delta", 44);
            }
            connection.commit();

            DelosSqlSemanticOracle.Result orderedAsc = query(
                    connection,
                    "select id, grp, amount, label, optional_value from " + table + " order by id",
                    DelosSqlSemanticOracle.RowOrder.ORDERED);
            DelosSqlSemanticOracle.Result orderedDesc = query(
                    connection,
                    "select id, grp, amount, label, optional_value from " + table + " order by id desc",
                    DelosSqlSemanticOracle.RowOrder.ORDERED);
            DelosSqlSemanticOracle.Result unorderedAsc = query(
                    connection,
                    "select id, grp, amount, label, optional_value from " + table + " order by id",
                    DelosSqlSemanticOracle.RowOrder.UNORDERED);
            DelosSqlSemanticOracle.Result unorderedDesc = query(
                    connection,
                    "select id, grp, amount, label, optional_value from " + table + " order by id desc",
                    DelosSqlSemanticOracle.RowOrder.UNORDERED);
            DelosSqlSemanticOracle.Result duplicateMultiset = query(
                    connection,
                    "select grp from " + table + " order by id",
                    DelosSqlSemanticOracle.RowOrder.UNORDERED);
            DelosSqlSemanticOracle.Result distinctMultiset = query(
                    connection,
                    "select distinct grp from " + table + " order by grp",
                    DelosSqlSemanticOracle.RowOrder.UNORDERED);
            DelosSqlSemanticOracle.Result aggregate = query(
                    connection,
                    "select grp, count(*), sum(amount) from " + table + " group by grp order by grp",
                    DelosSqlSemanticOracle.RowOrder.ORDERED);

            DelosSqlSemanticOracle.Result beforeMutation = query(
                    connection,
                    "select id, amount from " + table + " order by id",
                    DelosSqlSemanticOracle.RowOrder.ORDERED);
            int affected;
            try (PreparedStatement update = connection.prepareStatement(
                    "update " + table + " set amount = amount + 1 where id = 2")) {
                affected = update.executeUpdate();
            }
            DelosSqlSemanticOracle.Result finalState = query(
                    connection,
                    "select id, grp, amount, label, optional_value from " + table + " order by id",
                    DelosSqlSemanticOracle.RowOrder.ORDERED);
            DelosSqlSemanticOracle.Result mutation = DelosSqlSemanticOracle.mutation(affected, finalState);
            DelosSqlSemanticOracle.Result snapshotSequence = DelosSqlSemanticOracle.sequence(
                    "SNAPSHOT_SEQUENCE", List.of(beforeMutation, finalState));
            DelosSqlSemanticOracle.Result snapshotSequenceReversed = DelosSqlSemanticOracle.sequence(
                    "SNAPSHOT_SEQUENCE", List.of(finalState, beforeMutation));

            Map<String, DelosSqlSemanticOracle.Result> transactionParts = new LinkedHashMap<>();
            transactionParts.put("affectedRows", DelosSqlSemanticOracle.scalar("affectedRows", affected));
            transactionParts.put("aggregate", aggregate);
            transactionParts.put("finalState", finalState);
            DelosSqlSemanticOracle.Result transaction = DelosSqlSemanticOracle.composite(
                    "TRANSACTION", transactionParts);

            Map<String, DelosSqlSemanticOracle.Result> reversedParts = new LinkedHashMap<>();
            reversedParts.put("finalState", finalState);
            reversedParts.put("aggregate", aggregate);
            reversedParts.put("affectedRows", DelosSqlSemanticOracle.scalar("affectedRows", affected));
            DelosSqlSemanticOracle.Result transactionReordered = DelosSqlSemanticOracle.composite(
                    "TRANSACTION", reversedParts);

            connection.rollback();
            return new OracleMatrix(
                    orderedAsc,
                    orderedDesc,
                    unorderedAsc,
                    unorderedDesc,
                    duplicateMultiset,
                    distinctMultiset,
                    aggregate,
                    mutation,
                    snapshotSequence,
                    snapshotSequenceReversed,
                    transaction,
                    transactionReordered);
        });
    }

    private static void insert(
            PreparedStatement insert,
            int id,
            int group,
            String amount,
            String label,
            Integer optional) throws Exception {
        insert.setInt(1, id);
        insert.setInt(2, group);
        insert.setBigDecimal(3, new java.math.BigDecimal(amount));
        insert.setString(4, label);
        if (optional == null) {
            insert.setNull(5, java.sql.Types.INTEGER);
        } else {
            insert.setInt(5, optional);
        }
        if (insert.executeUpdate() != 1) {
            throw new IllegalStateException("Oracle fixture insert did not affect exactly one row");
        }
    }

    private static DelosSqlSemanticOracle.Result query(
            Connection connection,
            String sql,
            DelosSqlSemanticOracle.RowOrder rowOrder) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            return DelosSqlSemanticOracle.query(resultSet, rowOrder);
        }
    }

    private static void requireOracleProperties(OracleMatrix matrix) {
        if (matrix.orderedAsc().equals(matrix.orderedDesc())) {
            throw new IllegalStateException("Ordered oracle failed to preserve SQL row order");
        }
        requireEqual("unordered multiset order independence", matrix.unorderedAsc(), matrix.unorderedDesc());
        if (matrix.duplicateMultiset().equals(matrix.distinctMultiset())) {
            throw new IllegalStateException("Unordered multiset oracle lost duplicate-row multiplicity");
        }
        if (matrix.snapshotSequence().equals(matrix.snapshotSequenceReversed())) {
            throw new IllegalStateException("Snapshot sequence oracle failed to preserve sequence semantics");
        }
        requireEqual(
                "composite label-order independence",
                matrix.transaction(),
                matrix.transactionReordered());
        if (matrix.mutation().count() != 1) {
            throw new IllegalStateException("Mutation oracle lost affected-row count: " + matrix.mutation());
        }
    }

    private static void requireEqual(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " differs: expected=" + expected + " actual=" + actual);
        }
    }

    private static String report(OracleMatrix matrix) {
        return """
                DelosDB v1 SQL-authoritative semantic oracle
                ============================================

                Phase 0A decision: SQL-visible values and committed/snapshot state are the correctness authority.
                Storage-engine internals may provide diagnostics but may not define benchmark truth.

                Oracle contracts:
                - Ordered query: SHA-256 over canonical JDBC values in SQL row order.
                - Unordered query: SHA-256 over the sorted multiset of canonical row digests; duplicates remain significant.
                - Aggregate: ordinary SQL query result using ordered or unordered semantics as declared by the sentinel.
                - Mutation: affected-row count + authoritative SQL-visible post-state.
                - Snapshot sequence: order-sensitive sequence of SQL-visible semantic states.
                - Concurrent workload: labeled composite of reader/writer observations, counters, and final SQL state.
                - Multi-statement transaction: labeled composite of transaction results/invariants and committed final state.
                - Unsupported JDBC types fail closed until an explicit cross-driver canonical representation exists.

                Canonical value families in v1:
                exact numeric, floating, boolean, text, date, time, timestamp, binary, BLOB, CLOB, NULL.

                Validation:
                - Heap/MVCC oracle matrices are identical.
                - Ordered fingerprints change when row order changes.
                - Unordered fingerprints do not change when only row production order changes.
                - Duplicate rows remain significant because unordered mode is a multiset, not a set.
                - Mutation affected-row count is retained.
                - Snapshot sequences are order-sensitive.
                - Labeled composite fingerprints are independent of map insertion order.

                Validation fingerprints:
                orderedAsc=%s
                unordered=%s
                aggregate=%s
                mutation=%s
                snapshotSequence=%s
                transaction=%s
                """.formatted(
                matrix.orderedAsc().fingerprint(),
                matrix.unorderedAsc().fingerprint(),
                matrix.aggregate().fingerprint(),
                matrix.mutation().fingerprint(),
                matrix.snapshotSequence().fingerprint(),
                matrix.transaction().fingerprint());
    }

    private record OracleMatrix(
            DelosSqlSemanticOracle.Result orderedAsc,
            DelosSqlSemanticOracle.Result orderedDesc,
            DelosSqlSemanticOracle.Result unorderedAsc,
            DelosSqlSemanticOracle.Result unorderedDesc,
            DelosSqlSemanticOracle.Result duplicateMultiset,
            DelosSqlSemanticOracle.Result distinctMultiset,
            DelosSqlSemanticOracle.Result aggregate,
            DelosSqlSemanticOracle.Result mutation,
            DelosSqlSemanticOracle.Result snapshotSequence,
            DelosSqlSemanticOracle.Result snapshotSequenceReversed,
            DelosSqlSemanticOracle.Result transaction,
            DelosSqlSemanticOracle.Result transactionReordered) {
    }
}
