/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Freezes the compact Phase-1 sentinel matrix for the thirteen permanent
 * DelosDB architecture-fitness workload families.
 *
 * <p>This class defines workload intent only. It does not execute performance
 * measurements or change engine behavior. Later Phase-1 steps promote or add
 * the missing workload surfaces behind these stable case identifiers.</p>
 */
public final class DelosArchitectureFitnessSentinelMatrix {
    private static final String PROJECT_DIRECTORY_PROPERTY =
            "delosdb.benchmark.fitnessMatrix.projectDirectory";
    private static final String REPORT_DIRECTORY_PROPERTY =
            "delosdb.benchmark.fitnessMatrix.reportDirectory";
    private static final int FROZEN_FAMILY_COUNT = 13;
    private static final int MIN_SENTINELS = 25;
    private static final int MAX_SENTINELS = 40;

    private DelosArchitectureFitnessSentinelMatrix() {
    }

    public static void main(String[] args) throws Exception {
        Path projectDirectory = requiredDirectory(PROJECT_DIRECTORY_PROPERTY);
        Path reportDirectory = Path.of(requiredProperty(REPORT_DIRECTORY_PROPERTY));
        List<Sentinel> sentinels = sentinels();

        validateMatrix(projectDirectory, sentinels);
        Files.createDirectories(reportDirectory);
        writeTsv(reportDirectory.resolve("architecture-fitness-sentinel-matrix.tsv"), sentinels);
        writeSummary(reportDirectory.resolve("architecture-fitness-sentinel-matrix.txt"), sentinels);

        System.out.println("DelosDB architecture fitness sentinel matrix complete: " + reportDirectory);
    }

    private static List<Sentinel> sentinels() {
        return List.of(
                sentinel("F01-PK-HOT-1", 1, "Simple indexed reads", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row core fixture; primary key; clients 1 and 8; HOT id=1; 1 read/transaction",
                        "select quantity from T where id = ?",
                        "exact quantity + rolling semantic fingerprint",
                        Protocol.POINT_ROUND_TRIP,
                        "DelosJdbcCrossEngineConcurrency.PRIMARY_KEY_READ_HOT"),
                sentinel("F01-PK-DISJOINT-1", 1, "Simple indexed reads", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row core fixture; primary key; clients 1 and 8; private evenly spaced key/client; 1 read/transaction",
                        "select quantity from T where id = ?",
                        "exact quantity + rolling semantic fingerprint",
                        Protocol.POINT_ROUND_TRIP,
                        "DelosJdbcCrossEngineConcurrency.PRIMARY_KEY_READ_DISJOINT"),
                sentinel("F01-PK-DISJOINT-10", 1, "Simple indexed reads", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row core fixture; primary key; clients 1 and 8; private key/client; 10 reads/transaction",
                        "select quantity from T where id = ?",
                        "exact quantity + rolling semantic fingerprint",
                        Protocol.TRANSACTION_AMORTIZATION,
                        "DelosJdbcCrossEngineConcurrency.PRIMARY_KEY_READ_DISJOINT"),

                sentinel("F02-RANGE-100", 2, "Range/index scans", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row core fixture; ordered primary-key range of 100 rows; clients 1 and 8",
                        "select id, quantity from T where id >= ? and id < ? order by id",
                        "exact row count + ordered semantic fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcCrossEngineConcurrency.RANGE_SCAN_100"),
                sentinel("F02-RANGE-1000", 2, "Range/index scans", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row core fixture; ordered primary-key range of 1000 rows; clients 1 and 8",
                        "select id, quantity from T where id >= ? and id < ? order by id",
                        "exact row count + ordered semantic fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcCrossEngineConcurrency.RANGE_SCAN_1000"),
                sentinel("F02-FULL-10000", 2, "Range/index scans", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row core fixture; complete ordered primary-key scan; clients 1 and 8",
                        "select id, quantity from T where id >= ? and id < ? order by id",
                        "10000 rows + ordered semantic fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcCrossEngineConcurrency.RANGE_SCAN_FULL"),
                sentinel("F02-INDEX-ONLY-1000", 2, "Range/index scans", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row core fixture; ordered primary-key range of 1000 index-only rows; clients 1 and 8",
                        "select id from T where id >= ? and id < ? order by id",
                        "exact row count + ordered key fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcCrossEngineConcurrency.RANGE_SCAN_INDEX_ONLY_1000"),

                sentinel("F03-PROJECT-COVERED", 3, "Projection/materialization", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "10k-row core fixture; secondary equality returning approximately rowCount/17 rows; narrow covered projection",
                        "select category from T where category = ?",
                        "exact row count + projected-value fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcJmhState.secondaryEqualityCoveredLookup; IndexScanTest"),
                sentinel("F03-PROJECT-TWO-COLUMN", 3, "Projection/materialization", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "10k-row core fixture; same secondary predicate; two scalar output columns",
                        "select id, quantity from T where category = ? order by id",
                        "exact row count + ordered two-column fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcBenchmarkScenario.SECONDARY_EQUALITY_LOOKUP; DelosJdbcJmhState.secondaryEqualityLookup"),
                sentinel("F03-PROJECT-FULL-ROW", 3, "Projection/materialization", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "10k-row core fixture; same secondary predicate; id/category/bucket/quantity/payload with 1024-byte sentinel payload",
                        "select id, category, bucket, quantity, payload from T where category = ? order by id",
                        "exact row count + all-column/payload fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcJmhState.secondaryEqualityFullRowLookup; IndexScanTest"),

                sentinel("F04-JOIN-INDEXED-1TO1", 4, "Simple joins", Lane.BOTH, Readiness.ADAPT_REFERENCE,
                        "Wisconsin-style 10k fact rows + 1k dimension rows; indexed equality join; one matching dimension row per qualifying fact key",
                        "select a.unique1 from TENKTUP1 a join ONEKTUP b on a.unique1 = b.unique1",
                        "exact joined-row count + projected-key fingerprint",
                        Protocol.RESULT_FETCH,
                        "org.apache.derbyTesting.perf.clients.IndexJoinClient"),
                sentinel("F04-JOIN-INDEXED-FANOUT", 4, "Simple joins", Lane.BOTH, Readiness.BUILD_NEW,
                        "portable parent/child fixture; indexed equality join; deterministic 10-child fanout; selective parent range",
                        "select p.id, c.id from PARENT p join CHILD c on c.parent_id = p.id where p.id between ? and ? order by p.id, c.id",
                        "exact fanout row count + ordered pair fingerprint",
                        Protocol.RESULT_FETCH,
                        "New fitness workload using existing JDBC target/runtime machinery"),

                sentinel("F05-JOIN-3WAY-SELECTIVE", 5, "Multi-way joins", Lane.BOTH, Readiness.BUILD_NEW,
                        "portable customer/order/line fixture; selective customer range; indexed foreign-key joins",
                        "select c.id, o.id, l.id from CUSTOMER c join ORDERS o on o.customer_id=c.id join LINE l on l.order_id=o.id where c.id between ? and ?",
                        "exact row count + canonical tuple fingerprint independent of plan",
                        Protocol.RESULT_FETCH,
                        "New SQL-authoritative multi-way join fitness workload"),
                sentinel("F05-JOIN-4WAY-FANOUT", 5, "Multi-way joins", Lane.BOTH, Readiness.BUILD_NEW,
                        "portable customer/order/line/item fixture; deterministic fanout and moderate result cardinality",
                        "select c.id, o.id, l.line_no, i.id from CUSTOMER c join ORDERS o on o.customer_id=c.id join LINE l on l.order_id=o.id join ITEM i on i.id=l.item_id where c.bucket=?",
                        "exact row count + canonical tuple fingerprint independent of join order",
                        Protocol.RESULT_FETCH,
                        "New SQL-authoritative multi-way join fitness workload"),

                sentinel("F06-GROUP-LOW-CARD", 6, "Aggregation / GROUP BY", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "10k-row core fixture; 17 deterministic category groups",
                        "select category, count(*), sum(quantity) from T group by category order by category",
                        "exact 17 groups + count/sum fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcBenchmarkScenario.AGGREGATE; GroupByClient"),
                sentinel("F06-GROUP-HIGH-CARD", 6, "Aggregation / GROUP BY", Lane.BOTH, Readiness.ADAPT_REFERENCE,
                        "10k-row group fixture derived from the core rows with a materialized group_key=id%1000; 1000 deterministic groups with 10 rows/group",
                        "select group_key, count(*), sum(quantity) from T_GROUP group by group_key order by group_key",
                        "exact 1000 groups + count/sum fingerprint",
                        Protocol.RESULT_FETCH,
                        "GroupByClient controllable cardinality semantics"),

                sentinel("F07-ORDER-SATISFIED", 7, "Sort / ORDER BY", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "10k-row core fixture; ordered primary range whose access path can satisfy ORDER BY id",
                        "select id, quantity from T where id >= ? and id < ? order by id",
                        "ordered row fingerprint",
                        Protocol.RESULT_FETCH,
                        "DelosJdbcCrossEngineConcurrency.RANGE_SCAN_1000"),
                sentinel("F07-SORT-FULL", 7, "Sort / ORDER BY", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "10k-row core fixture; full result sorted by non-primary quantity then id",
                        "select id, quantity from T order by quantity desc, id",
                        "all rows + deterministic sorted fingerprint",
                        Protocol.RESULT_FETCH,
                        "SortTest dedicated sort semantics"),

                sentinel("F08-INSERT-1", 8, "INSERT", Lane.BOTH, Readiness.ADAPT_REFERENCE,
                        "preloaded core fixture; one new row/transaction; indexed table with primary + two secondary indexes",
                        "insert into T (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)",
                        "affected-row count + committed point-read/state fingerprint",
                        Protocol.WRITE_COMMIT,
                        "DelosJdbcBenchmarkScenario fixture insert; Order Entry SimpleInsert"),
                sentinel("F08-INSERT-100", 8, "INSERT", Lane.BOTH, Readiness.ADAPT_REFERENCE,
                        "same indexed table; 100 deterministic inserts/transaction using JDBC batch where supported by current target contract",
                        "insert into T (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)",
                        "100 affected rows + committed range/state fingerprint",
                        Protocol.TRANSACTION_AMORTIZATION,
                        "DelosJdbcBenchmarkScenario.prepare batch loader"),

                sentinel("F09-UPDATE-DISJOINT", 9, "Indexed UPDATE", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row indexed fixture; clients 1 and 8; each writer owns a disjoint key",
                        "update T set quantity = quantity + 1 where id = ?",
                        "affected-row count + final deterministic quantity/state fingerprint",
                        Protocol.WRITE_COMMIT,
                        "DelosJdbcCrossEngineConcurrency.DISJOINT_INDEXED_UPDATE"),
                sentinel("F09-UPDATE-CONTENDED", 9, "Indexed UPDATE", Lane.BOTH, Readiness.REUSE_NOW,
                        "10k-row indexed fixture; clients 1 and 8 contend on the same key",
                        "update T set quantity = quantity + 1 where id = ?",
                        "affected-row count + final quantity + retry/rollback accounting",
                        Protocol.WRITE_COMMIT,
                        "DelosJdbcCrossEngineConcurrency.CONTENDED_INDEXED_UPDATE"),

                sentinel("F10-DELETE-REINSERT-1", 10, "DELETE / reinsert", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "10k-row indexed fixture; delete one known row then reinsert the identical row in one transaction",
                        "delete from T where id=?; insert into T (id,category,bucket,quantity,payload) values (?,?,?,?,?)",
                        "affected rows + post-transaction full-row fingerprint",
                        Protocol.WRITE_COMMIT,
                        "DelosJdbcBenchmarkScenario.DELETE_REINSERT; DelosJdbcDeleteReinsertAttribution"),
                sentinel("F10-DELETE-REINSERT-10", 10, "DELETE / reinsert", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "same fixture; ten disjoint delete/reinsert pairs per transaction",
                        "10 x {delete by primary key; reinsert identical row}",
                        "affected rows + post-transaction key-range/state fingerprint",
                        Protocol.TRANSACTION_AMORTIZATION,
                        "Promote existing delete/reinsert semantics into shared transaction-width runner"),

                sentinel("F11-MIXED-80R20W", 11, "Mixed readers + writers", Lane.BOTH, Readiness.BUILD_NEW,
                        "10k-row fixture; 8 clients; deterministic 80% point readers / 20% disjoint indexed writers",
                        "read: select quantity from T where id=?; write: update T set quantity=quantity+1 where id=?",
                        "read fingerprints + exact committed write delta + zero lost updates",
                        Protocol.MIXED_TRANSACTION,
                        "New workload on DelosJdbcCrossEngineConcurrency target/runtime machinery"),
                sentinel("F11-MIXED-50R50W-HOT", 11, "Mixed readers + writers", Lane.BOTH, Readiness.BUILD_NEW,
                        "10k-row fixture; 8 clients; deterministic 50% readers / 50% writers sharing a hot key set",
                        "read primary key; indexed update primary key",
                        "read validity + final state + retry/rollback accounting",
                        Protocol.MIXED_TRANSACTION,
                        "New workload on DelosJdbcCrossEngineConcurrency target/runtime machinery"),

                sentinel("F12-LONG-READER-DISJOINT-WRITER", 12, "Long reader + writers", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "long ordered reader holds a snapshot while 4 writers repeatedly update disjoint keys outside/inside the scan range",
                        "reader: ordered full/range scan; writers: indexed quantity updates",
                        "reader snapshot fingerprint + exact writer final state + progress/interference metrics",
                        Protocol.CONCURRENT_LONG_RESULT,
                        "MvccSqlLongReaderPurgeStressTest semantics + existing concurrency machinery"),
                sentinel("F12-LONG-READER-HOT-WRITER", 12, "Long reader + writers", Lane.BOTH, Readiness.PROMOTE_EXISTING,
                        "long snapshot reader plus 4 writers repeatedly updating keys that the reader has or will visit",
                        "reader: ordered full/range scan; writers: contended indexed updates",
                        "stable reader snapshot + exact writer final state + retry/history-retention evidence",
                        Protocol.CONCURRENT_LONG_RESULT,
                        "MvccSqlLongReaderPurgeStressTest semantics + contended update machinery"),

                sentinel("F13-BANK-TRANSACTION", 13, "Realistic transactional workload", Lane.BOTH, Readiness.ADAPT_REFERENCE,
                        "compact TPC-B-like transaction: account update + history insert + teller update + branch update + account read + commit",
                        "UPDATE account; INSERT history; UPDATE teller; UPDATE branch; SELECT account balance; COMMIT",
                        "transaction success + invariant/final-balance fingerprint over deterministic input stream",
                        Protocol.MULTI_STATEMENT_TRANSACTION,
                        "org.apache.derbyTesting.perf.clients.BankTransactionClient"),
                sentinel("F13-ORDER-ENTRY-MIX", 13, "Realistic transactional workload", Lane.BOTH, Readiness.ADAPT_REFERENCE,
                        "compact deterministic Order Entry mix derived from Derby system/oe: New Order, Payment, Order Status, Delivery, Stock Level and rollback path",
                        "multi-table Order Entry transaction mix",
                        "transaction-specific results + committed database invariants + deterministic stream fingerprint",
                        Protocol.MULTI_STATEMENT_TRANSACTION,
                        "org.apache.derbyTesting.system.oe.client.Submitter; org.apache.derbyTesting.system.oe.direct.Standard"));
    }

    private static Sentinel sentinel(
            String id,
            int familyId,
            String family,
            Lane lane,
            Readiness readiness,
            String fixtureAndShape,
            String sqlOrTransaction,
            String oracle,
            Protocol protocol,
            String reuseSource) {
        return new Sentinel(
                id, familyId, family, lane, readiness, fixtureAndShape,
                sqlOrTransaction, oracle, protocol, reuseSource);
    }

    private static void validateMatrix(Path projectDirectory, List<Sentinel> sentinels) throws IOException {
        if (sentinels.size() < MIN_SENTINELS || sentinels.size() > MAX_SENTINELS) {
            throw new IOException("Sentinel matrix must contain " + MIN_SENTINELS + "-" + MAX_SENTINELS
                    + " cases, found " + sentinels.size());
        }

        Set<String> ids = new HashSet<>();
        Set<Integer> familyIds = new HashSet<>();
        for (Sentinel sentinel : sentinels) {
            if (!ids.add(sentinel.id())) {
                throw new IOException("Duplicate sentinel id: " + sentinel.id());
            }
            if (sentinel.familyId() < 1 || sentinel.familyId() > FROZEN_FAMILY_COUNT) {
                throw new IOException("Out-of-range family id for " + sentinel.id() + ": " + sentinel.familyId());
            }
            familyIds.add(sentinel.familyId());
            if (sentinel.fixtureAndShape().isBlank()
                    || sentinel.sqlOrTransaction().isBlank()
                    || sentinel.oracle().isBlank()
                    || sentinel.reuseSource().isBlank()) {
                throw new IOException("Incomplete sentinel contract: " + sentinel.id());
            }
        }
        if (familyIds.size() != FROZEN_FAMILY_COUNT) {
            throw new IOException("Sentinel matrix must cover all " + FROZEN_FAMILY_COUNT
                    + " frozen families, covered=" + familyIds);
        }
        for (int familyId = 1; familyId <= FROZEN_FAMILY_COUNT; familyId++) {
            if (!familyIds.contains(familyId)) {
                throw new IOException("Missing frozen workload family: " + familyId);
            }
        }

        requireSource(projectDirectory,
                "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosArchitectureFitnessInventory.java");
        requireSource(projectDirectory,
                "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcBenchmarkScenario.java");
        requireSource(projectDirectory,
                "delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/DelosJdbcCrossEngineConcurrency.java");
        requireSource(projectDirectory,
                "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/clients/IndexJoinClient.java");
        requireSource(projectDirectory,
                "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/clients/GroupByClient.java");
        requireSource(projectDirectory,
                "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/basic/jdbc/SortTest.java");
        requireSource(projectDirectory,
                "delosdb-tests/src/test/java/org/apache/derbyTesting/perf/clients/BankTransactionClient.java");
        requireSource(projectDirectory,
                "delosdb-tests/src/test/java/org/apache/derbyTesting/system/oe/client/Submitter.java");
        requireSource(projectDirectory,
                "delosdb-tests/src/delosTest/java/org/apache/derbyTesting/functionTests/tests/delos/MvccSqlLongReaderPurgeStressTest.java");
    }

    private static void requireSource(Path projectDirectory, String relativePath) throws IOException {
        if (!Files.isRegularFile(projectDirectory.resolve(relativePath))) {
            throw new IOException("Required Step-2 source asset is missing: " + relativePath);
        }
    }

    private static void writeTsv(Path output, List<Sentinel> sentinels) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("caseId\tfamilyId\tworkloadFamily\trequiredLane\treadiness\tfixtureAndShape\t")
                .append("sqlOrTransaction\toracle\tserverProtocolEvidence\treuseSource\n");
        for (Sentinel sentinel : sentinels) {
            text.append(tsv(sentinel.id())).append('\t')
                    .append(sentinel.familyId()).append('\t')
                    .append(tsv(sentinel.family())).append('\t')
                    .append(sentinel.lane()).append('\t')
                    .append(sentinel.readiness()).append('\t')
                    .append(tsv(sentinel.fixtureAndShape())).append('\t')
                    .append(tsv(sentinel.sqlOrTransaction())).append('\t')
                    .append(tsv(sentinel.oracle())).append('\t')
                    .append(sentinel.protocol()).append('\t')
                    .append(tsv(sentinel.reuseSource())).append('\n');
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path output, List<Sentinel> sentinels) throws IOException {
        Map<Readiness, Integer> readinessCounts = new EnumMap<>(Readiness.class);
        for (Readiness readiness : Readiness.values()) {
            readinessCounts.put(readiness, 0);
        }
        int[] familyCounts = new int[FROZEN_FAMILY_COUNT + 1];
        for (Sentinel sentinel : sentinels) {
            readinessCounts.compute(sentinel.readiness(), (ignored, count) -> count + 1);
            familyCounts[sentinel.familyId()]++;
        }

        StringBuilder text = new StringBuilder();
        text.append("DelosDB v1 compact architecture-fitness sentinel matrix\n")
                .append("====================================================\n\n")
                .append("Frozen workload families: ").append(FROZEN_FAMILY_COUNT).append('\n')
                .append("Frozen sentinel cases: ").append(sentinels.size()).append('\n')
                .append("Target lanes: embedded/core + server/product for every family\n")
                .append("Phase-1 optimization freeze: ACTIVE\n\n")
                .append("Implementation readiness:\n");
        for (Readiness readiness : Readiness.values()) {
            text.append(String.format(Locale.ROOT, "  %-18s %2d%n", readiness, readinessCounts.get(readiness)));
        }
        text.append("\nFamily case counts:\n");
        for (int familyId = 1; familyId <= FROZEN_FAMILY_COUNT; familyId++) {
            Sentinel first = firstFamilySentinel(sentinels, familyId);
            text.append(String.format(Locale.ROOT, " %2d. %-34s %d%n",
                    familyId, first.family(), familyCounts[familyId]));
        }
        text.append("\nStep 2 decisions:\n")
                .append("- Freeze these case identifiers as the compact Phase-1 sentinel surface.\n")
                .append("- Do not add cases merely to increase coverage; additions require an uncovered architectural risk.\n")
                .append("- REUSE_NOW means existing modern runner semantics already exist.\n")
                .append("- PROMOTE_EXISTING means proven Delos/inherited semantics must be moved behind the unified fitness runner.\n")
                .append("- ADAPT_REFERENCE means reuse workload semantics but replace legacy measurement/control machinery.\n")
                .append("- BUILD_NEW is limited to gaps found in Step 1.\n")
                .append("- SQL-visible results remain the semantic authority for every promoted or new case.\n")
                .append("- Server protocol is cross-cutting evidence, not a fourteenth workload family.\n")
                .append("- No production optimization is permitted during Phase 1.\n\n")
                .append("Cases:\n");
        for (Sentinel sentinel : sentinels) {
            text.append(String.format(Locale.ROOT, "  %-26s family=%02d %-18s %s%n",
                    sentinel.id(), sentinel.familyId(), sentinel.readiness(), sentinel.protocol()));
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static Sentinel firstFamilySentinel(List<Sentinel> sentinels, int familyId) {
        for (Sentinel sentinel : sentinels) {
            if (sentinel.familyId() == familyId) {
                return sentinel;
            }
        }
        throw new IllegalStateException("No sentinel for family " + familyId);
    }

    private static String tsv(String value) {
        return value.replace('\t', ' ').replace('\n', ' ');
    }

    private static Path requiredDirectory(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(name + " is not a directory: " + path);
        }
        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + name);
        }
        return value;
    }

    private enum Lane {
        EMBEDDED,
        SERVER,
        BOTH
    }

    private enum Readiness {
        REUSE_NOW,
        PROMOTE_EXISTING,
        ADAPT_REFERENCE,
        BUILD_NEW
    }

    private enum Protocol {
        POINT_ROUND_TRIP,
        RESULT_FETCH,
        TRANSACTION_AMORTIZATION,
        WRITE_COMMIT,
        MIXED_TRANSACTION,
        CONCURRENT_LONG_RESULT,
        MULTI_STATEMENT_TRANSACTION
    }

    private record Sentinel(
            String id,
            int familyId,
            String family,
            Lane lane,
            Readiness readiness,
            String fixtureAndShape,
            String sqlOrTransaction,
            String oracle,
            Protocol protocol,
            String reuseSource) {
    }
}
