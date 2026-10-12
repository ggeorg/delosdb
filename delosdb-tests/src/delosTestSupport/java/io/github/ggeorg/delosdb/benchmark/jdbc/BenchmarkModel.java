/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Measurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ShapeKey;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Locale;
import java.util.Objects;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaProtocolEvidenceEnabled;

/** Shared workload, target and result contracts for benchmark execution and reports. */
final class BenchmarkModel {
    private BenchmarkModel() {
    }

    static final long SEED = 0x5DE10DBL;

    static final String CSV_HEADER =
            "target,product,productVersion,driverVersion,workload,clients,operationsPerTransaction,"
                    + "transactionsPerClient,rowCount,payloadSize,fixtureCommitBatchSize,warmups,warmupElapsedNanos,iterations,"
                    + "measuredTransactions,measuredOperations,retryableConflictRetries,elapsedNanos,"
                    + "transactionsPerSecond,operationsPerSecond,inverseThroughputNanosPerTransaction,"
                    + "semanticFingerprint,run";

    static final String ORACLE_CSV_HEADER =
            "target,workload,clients,operationsPerTransaction,rowCount,kind,count,fingerprint,run";

    static final String DRDA_PROTOCOL_CSV_HEADER =
            "target,product,productVersion,driverVersion,workload,clients,operationsPerTransaction,rowCount,run,"
                    + "measuredTransactions,measuredOperations,measuredResultRows,setupPrepareCommands,"
                    + "requestFlushes,requestBytes,replySocketReads,replyBytes,measuredPrepareCommands,"
                    + "openQueryCommands,continueQueryCommands,executeCommands,commitCommands,rollbackCommands,"
                    + "closeQueryCommands,queryDataBlocks,fetchRequests,requestFlushesPerOperation,"
                    + "requestFlushesPerTransaction,rowsPerFetchRequest,replyBytesPerResultRow,"
                    + "measuredElapsedNanos,openQueryFlowNanos,continueQueryFlowNanos,executeFlowNanos,"
                    + "commitFlowNanos,totalTimedFlowNanos,averageOpenQueryFlowMicros,"
                    + "averageContinueQueryFlowMicros,averageExecuteFlowMicros,averageCommitFlowMicros,"
                    + "timedFlowShareOfMeasuredElapsed";

    enum Workload {
        EMPTY_TRANSACTION(false, false, true, 0, Connection.TRANSACTION_READ_COMMITTED),
        VALUES_1(false, true, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        VALUES_10(false, true, true, 10, Connection.TRANSACTION_READ_COMMITTED),
        PK_READ_1_RC(true, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        PK_READ_10_RC(true, false, true, 10, Connection.TRANSACTION_READ_COMMITTED),
        PK_READ_100_RC(true, false, true, 100, Connection.TRANSACTION_READ_COMMITTED),
        PK_READ_10_RU(true, false, true, 10, Connection.TRANSACTION_READ_UNCOMMITTED),
        SAME_TABLE_DISJOINT(true, false, true, 100, Connection.TRANSACTION_READ_COMMITTED),
        PRIVATE_TABLE_DISJOINT(true, false, true, 100, Connection.TRANSACTION_READ_COMMITTED),
        PRIMARY_KEY_READ_HOT(true, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        PRIMARY_KEY_READ_DISJOINT(true, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        PRIMARY_KEY_READ_RANDOM(true, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_1(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_10(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_100(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_1000(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_FULL(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_INDEX_ONLY_100(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_INDEX_ONLY_1000(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_COVERING_1000(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_ROW_BEARING_1000(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        RANGE_SCAN_MVCC_NATURAL_ORDER_1000(false, false, true, -1, Connection.TRANSACTION_READ_COMMITTED),
        PROJECTION_COVERED(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        PROJECTION_TWO_COLUMN(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        PROJECTION_FULL_ROW(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        GROUP_LOW_CARD(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        JOIN_INDEXED_1TO1(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        JOIN_INDEXED_FANOUT(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        JOIN_3WAY_SELECTIVE(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        JOIN_4WAY_FANOUT(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        GROUP_HIGH_CARD(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        SORT_FULL(false, false, true, 1, Connection.TRANSACTION_READ_COMMITTED),
        INSERT_1(false, false, false, 1, Connection.TRANSACTION_READ_COMMITTED),
        INSERT_100(false, false, false, 100, Connection.TRANSACTION_READ_COMMITTED),
        BANK_TRANSACTION(false, false, false, 1, Connection.TRANSACTION_READ_COMMITTED),
        ORDER_ENTRY_MIX(false, false, false, 1, Connection.TRANSACTION_READ_COMMITTED),
        DELETE_REINSERT(false, false, false, -1, Connection.TRANSACTION_READ_COMMITTED),
        FRESH_INDEXED_UPDATE_100(false, false, false, 100, Connection.TRANSACTION_READ_COMMITTED),
        DISJOINT_INDEXED_UPDATE(false, false, false, -1, Connection.TRANSACTION_READ_COMMITTED),
        CONTENDED_INDEXED_UPDATE(false, false, false, -1, Connection.TRANSACTION_READ_COMMITTED),
        MIXED_80R20W(false, false, false, 1, Connection.TRANSACTION_READ_COMMITTED),
        MIXED_50R50W_HOT(false, false, false, 1, Connection.TRANSACTION_READ_COMMITTED),
        LONG_READER_DISJOINT_WRITER(false, false, false, 1, Connection.TRANSACTION_READ_COMMITTED),
        LONG_READER_HOT_WRITER(false, false, false, 1, Connection.TRANSACTION_READ_COMMITTED);

        private final boolean primaryKeyRead;
        private final boolean values;
        private final boolean readOnly;
        private final int fixedOperationsPerTransaction;
        private final int isolationLevel;

        Workload(
                boolean primaryKeyRead,
                boolean values,
                boolean readOnly,
                int fixedOperationsPerTransaction,
                int isolationLevel) {
            this.primaryKeyRead = primaryKeyRead;
            this.values = values;
            this.readOnly = readOnly;
            this.fixedOperationsPerTransaction = fixedOperationsPerTransaction;
            this.isolationLevel = isolationLevel;
        }

        boolean isPrimaryKeyRead() {
            return primaryKeyRead;
        }

        boolean isValues() {
            return values;
        }

        boolean isRangeScan() {
            return this == RANGE_SCAN_1
                    || this == RANGE_SCAN_10
                    || this == RANGE_SCAN_100
                    || this == RANGE_SCAN_1000
                    || this == RANGE_SCAN_FULL
                    || this == RANGE_SCAN_INDEX_ONLY_100
                    || this == RANGE_SCAN_INDEX_ONLY_1000
                    || this == RANGE_SCAN_COVERING_1000
                    || this == RANGE_SCAN_ROW_BEARING_1000
                    || this == RANGE_SCAN_MVCC_NATURAL_ORDER_1000;
        }

        boolean isIndexOnlyRangeScan() {
            return this == RANGE_SCAN_INDEX_ONLY_100
                    || this == RANGE_SCAN_INDEX_ONLY_1000;
        }

        boolean isCoveringRangeScan() {
            return this == RANGE_SCAN_COVERING_1000;
        }

        boolean isRowBearingComparisonRangeScan() {
            return this == RANGE_SCAN_ROW_BEARING_1000;
        }

        boolean isMvccNaturalOrderRangeScan() {
            return this == RANGE_SCAN_MVCC_NATURAL_ORDER_1000;
        }

        boolean usesFixtureQuantities() {
            return isPrimaryKeyRead() || isRangeScan() || isMixedReaderWriter() || isFreshIndexedUpdate();
        }

        int rangeRows(int rowCount) {
            return switch (this) {
                case RANGE_SCAN_1 -> 1;
                case RANGE_SCAN_10 -> Math.min(10, rowCount);
                case RANGE_SCAN_100, RANGE_SCAN_INDEX_ONLY_100 -> Math.min(100, rowCount);
                case RANGE_SCAN_1000, RANGE_SCAN_INDEX_ONLY_1000, RANGE_SCAN_COVERING_1000,
                        RANGE_SCAN_ROW_BEARING_1000, RANGE_SCAN_MVCC_NATURAL_ORDER_1000 ->
                        Math.min(1000, rowCount);
                case RANGE_SCAN_FULL -> rowCount;
                default -> throw new IllegalStateException("Not a range-scan workload: " + this);
            };
        }

        boolean isConglomerateLocalityDiagnostic() {
            return this == SAME_TABLE_DISJOINT || this == PRIVATE_TABLE_DISJOINT;
        }

        boolean usesPrivateTablePerClient() {
            return this == PRIVATE_TABLE_DISJOINT;
        }

        boolean isFitnessRead() {
            return this == PROJECTION_COVERED
                    || this == PROJECTION_TWO_COLUMN
                    || this == PROJECTION_FULL_ROW
                    || this == GROUP_LOW_CARD
                    || this == JOIN_INDEXED_1TO1
                    || this == JOIN_INDEXED_FANOUT
                    || this == JOIN_3WAY_SELECTIVE
                    || this == JOIN_4WAY_FANOUT
                    || this == GROUP_HIGH_CARD
                    || this == SORT_FULL;
        }

        boolean isInsert() {
            return this == INSERT_1 || this == INSERT_100;
        }

        boolean isRealisticTransaction() {
            return this == BANK_TRANSACTION || this == ORDER_ENTRY_MIX;
        }

        boolean isDeleteReinsert() {
            return this == DELETE_REINSERT;
        }

        boolean isReadOnly() {
            return readOnly;
        }

        boolean isMixedReaderWriter() {
            return this == MIXED_80R20W || this == MIXED_50R50W_HOT;
        }

        boolean isLongReaderWriter() {
            return this == LONG_READER_DISJOINT_WRITER || this == LONG_READER_HOT_WRITER;
        }

        boolean isFreshIndexedUpdate() {
            return this == FRESH_INDEXED_UPDATE_100;
        }

        boolean isIndexedUpdate() {
            return isFreshIndexedUpdate()
                    || this == DISJOINT_INDEXED_UPDATE
                    || this == CONTENDED_INDEXED_UPDATE
                    || isLongReaderWriter();
        }

        boolean isUpdate() {
            return !readOnly;
        }

        int fixedOperationsPerTransaction() {
            return fixedOperationsPerTransaction;
        }

        int isolationLevel() {
            return isolationLevel;
        }
    }

    enum Target {
        DELOS_HEAP("delos_heap", ""),
        DELOS_MVCC("delos_mvcc", " using delos_mvcc"),
        UPSTREAM_DERBY("upstream_derby", ""),
        H2("h2", ""),
        SQLITE("sqlite", ""),
        DELOS_HEAP_DRDA("delos_heap_drda", ""),
        DELOS_MVCC_DRDA("delos_mvcc_drda", " using delos_mvcc"),
        UPSTREAM_DERBY_DRDA("upstream_derby_drda", ""),
        H2_SERVER("h2_server", ""),
        POSTGRESQL("postgresql", ""),
        MARIADB("mariadb", ""),
        FIREBIRD("firebird", "");

        private final String id;
        private final String createTableSuffix;

        Target(String id, String createTableSuffix) {
            this.id = id;
            this.createTableSuffix = createTableSuffix;
        }

        String id() {
            return id;
        }

        String createTableSuffix() {
            return createTableSuffix;
        }

        boolean isEmbeddedDerby() {
            return this == DELOS_HEAP || this == DELOS_MVCC || this == UPSTREAM_DERBY;
        }

        boolean isContainer() {
            return this == DELOS_HEAP_DRDA || this == DELOS_MVCC_DRDA
                    || this == UPSTREAM_DERBY_DRDA || this == H2_SERVER
                    || this == POSTGRESQL || this == MARIADB || this == FIREBIRD;
        }

        boolean isDrda() {
            return this == DELOS_HEAP_DRDA || this == DELOS_MVCC_DRDA || this == UPSTREAM_DERBY_DRDA;
        }

        int containerPort() {
            return switch (this) {
                case DELOS_HEAP_DRDA, DELOS_MVCC_DRDA, UPSTREAM_DERBY_DRDA -> 1527;
                case H2_SERVER -> 9092;
                case POSTGRESQL -> 5432;
                case MARIADB -> 3306;
                case FIREBIRD -> 3050;
                default -> throw new IllegalStateException("Not a container target: " + this);
            };
        }

        String containerImage(BenchmarkConfiguration options) {
            return switch (this) {
                case DELOS_HEAP_DRDA, DELOS_MVCC_DRDA -> options.delosServerImage();
                case UPSTREAM_DERBY_DRDA -> options.upstreamDerbyServerImage();
                case H2_SERVER -> options.delosServerImage();
                case POSTGRESQL -> options.postgresqlImage();
                case MARIADB -> options.mariadbImage();
                case FIREBIRD -> options.firebirdImage();
                default -> throw new IllegalStateException("Not a container target: " + this);
            };
        }

        ServerEndpoint endpoint(int hostPort) {
            return switch (this) {
                case DELOS_HEAP_DRDA, DELOS_MVCC_DRDA, UPSTREAM_DERBY_DRDA -> new ServerEndpoint(
                        "jdbc:derby://127.0.0.1:" + hostPort + "/delosbench;create=true", "", "");
                case H2_SERVER -> new ServerEndpoint(
                        "jdbc:h2:tcp://127.0.0.1:" + hostPort + "/delosbench", "sa", "");
                case POSTGRESQL -> new ServerEndpoint(
                        "jdbc:postgresql://127.0.0.1:" + hostPort + "/delosbench", "delosbench", "delosbench");
                case MARIADB -> new ServerEndpoint(
                        "jdbc:mariadb://127.0.0.1:" + hostPort + "/delosbench", "delosbench", "delosbench");
                case FIREBIRD -> new ServerEndpoint(
                        "jdbc:firebird://127.0.0.1:" + hostPort
                                + "//var/lib/firebird/data/delosbench.fdb?encoding=UTF8",
                        "SYSDBA", "delosbench");
                default -> throw new IllegalStateException("Not a container target: " + this);
            };
        }

        String jdbcUrl(Path database, BenchmarkConfiguration options) {
            if (isContainer()) {
                if (options.remoteJdbcUrl().isBlank()) {
                    throw new IllegalStateException("Missing remote JDBC URL for " + id);
                }
                return options.remoteJdbcUrl();
            }
            if (this == H2) {
                return "jdbc:h2:file:" + database.resolve("database").toAbsolutePath().normalize()
                        + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE";
            }
            if (this == SQLITE) {
                String url = "jdbc:sqlite:" + database.resolve("database.sqlite").toAbsolutePath().normalize()
                        + "?journal_mode=WAL&synchronous=FULL&busy_timeout=3000";
                return options.sqliteSharedCache() ? url + "&shared_cache=true" : url;
            }
            return "jdbc:derby:" + database.toAbsolutePath().normalize() + ";create=true";
        }

        static Target parse(String value) {
            for (Target target : values()) {
                if (target.id.equalsIgnoreCase(value)) {
                    return target;
                }
            }
            throw new IllegalArgumentException("Unknown concurrency target: " + value);
        }
    }

    record Spec(Workload workload, int clients, int operationsPerTransaction) {
    }

    record ServerEndpoint(String jdbcUrl, String user, String password) {
    }

    record Measurement(
            String target,
            String product,
            String productVersion,
            String driverVersion,
            Workload workload,
            int clients,
            int operationsPerTransaction,
            int transactionsPerClient,
            int rowCount,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            long warmupElapsedNanos,
            int iterations,
            long measuredTransactions,
            long measuredOperations,
            long retryableRollbacks,
            long elapsedNanos,
            double transactionsPerSecond,
            double operationsPerSecond,
            double inverseThroughputNanosPerTransaction,
            long semanticFingerprint,
            int run) {
        String csv() {
            return String.join(",", target, product, productVersion, driverVersion, workload.name(),
                    Integer.toString(clients), Integer.toString(operationsPerTransaction),
                    Integer.toString(transactionsPerClient), Integer.toString(rowCount),
                    Integer.toString(payloadSize), Integer.toString(fixtureCommitBatchSize),
                    Integer.toString(warmups), Long.toString(warmupElapsedNanos), Integer.toString(iterations),
                    Long.toString(measuredTransactions), Long.toString(measuredOperations),
                    Long.toString(retryableRollbacks), Long.toString(elapsedNanos), format(transactionsPerSecond),
                    format(operationsPerSecond), format(inverseThroughputNanosPerTransaction),
                    Long.toString(semanticFingerprint),
                    Integer.toString(run));
        }
    }

    record MeasuredSpec(
            Measurement measurement,
            OracleEvidence oracleEvidence,
            DrdaProtocolEvidence protocolEvidence) {
    }

    record OracleEvidence(
            String target,
            Workload workload,
            int clients,
            int operationsPerTransaction,
            int rowCount,
            String kind,
            long count,
            String fingerprint,
            int run) {

        ShapeKey shape() {
            return new ShapeKey(rowCount, workload, clients, operationsPerTransaction);
        }

        String csv() {
            return target + ',' + workload + ',' + clients + ',' + operationsPerTransaction + ','
                    + rowCount + ',' + kind + ',' + count + ',' + fingerprint + ',' + run;
        }

        static OracleEvidence parse(String line) {
            String[] fields = line.split(",", -1);
            if (fields.length != 9) {
                throw new IllegalArgumentException(
                        "Unexpected Phase 0A SQL oracle CSV row: " + line);
            }
            return new OracleEvidence(
                    fields[0],
                    Workload.valueOf(fields[1]),
                    Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]),
                    fields[5],
                    Long.parseLong(fields[6]),
                    fields[7],
                    Integer.parseInt(fields[8]));
        }
    }

    record DrdaProtocolEvidence(
            String target,
            String product,
            String productVersion,
            String driverVersion,
            Workload workload,
            int clients,
            int operationsPerTransaction,
            int rowCount,
            int run,
            long measuredTransactions,
            long measuredOperations,
            long measuredResultRows,
            long setupPrepareCommands,
            long requestFlushes,
            long requestBytes,
            long replySocketReads,
            long replyBytes,
            long measuredPrepareCommands,
            long openQueryCommands,
            long continueQueryCommands,
            long executeCommands,
            long commitCommands,
            long rollbackCommands,
            long closeQueryCommands,
            long queryDataBlocks,
            long fetchRequests,
            double requestFlushesPerOperation,
            double requestFlushesPerTransaction,
            double rowsPerFetchRequest,
            double replyBytesPerResultRow,
            long measuredElapsedNanos,
            long openQueryFlowNanos,
            long continueQueryFlowNanos,
            long executeFlowNanos,
            long commitFlowNanos,
            long totalTimedFlowNanos,
            double averageOpenQueryFlowMicros,
            double averageContinueQueryFlowMicros,
            double averageExecuteFlowMicros,
            double averageCommitFlowMicros,
            double timedFlowShareOfMeasuredElapsed) {

        static DrdaProtocolEvidence from(
                BenchmarkConfiguration options,
                Spec spec,
                DelosBenchmarkConfig config,
                String product,
                String productVersion,
                String driverVersion,
                long measuredTransactions,
                long measuredOperations,
                long measuredElapsedNanos,
                long[] setup,
                long[] measured) {
            if (measured == null || measured.length != DRDA_PROTOCOL_COUNTER_COUNT) {
                throw new IllegalStateException("Missing measured DRDA protocol evidence for " + spec);
            }
            long setupPrepare = setup == null ? 0L : setup[DRDA_PREPARE_COMMANDS];
            long resultRows;
            if (spec.workload().isRangeScan()) {
                resultRows = Math.multiplyExact(
                        measuredOperations, spec.workload().rangeRows(config.rowCount()));
            } else if (spec.workload().isPrimaryKeyRead() || spec.workload().isValues()) {
                resultRows = measuredOperations;
            } else {
                resultRows = 0L;
            }
            long fetchRequests = Math.addExact(measured[5], measured[6]);
            long openQueryFlowNanos = measured[DRDA_OPEN_QUERY_FLOW_NANOS];
            long continueQueryFlowNanos = measured[DRDA_CONTINUE_QUERY_FLOW_NANOS];
            long executeFlowNanos = measured[DRDA_EXECUTE_FLOW_NANOS];
            long commitFlowNanos = measured[DRDA_COMMIT_FLOW_NANOS];
            long totalTimedFlowNanos = Math.addExact(
                    Math.addExact(openQueryFlowNanos, continueQueryFlowNanos),
                    Math.addExact(executeFlowNanos, commitFlowNanos));
            return new DrdaProtocolEvidence(
                    options.target().id(), product, productVersion, driverVersion,
                    spec.workload(), spec.clients(), spec.operationsPerTransaction(), config.rowCount(),
                    options.run(), measuredTransactions, measuredOperations, resultRows, setupPrepare,
                    measured[0], measured[1], measured[2], measured[3], measured[4], measured[5],
                    measured[6], measured[7], measured[8], measured[9], measured[10], measured[11],
                    fetchRequests,
                    ratio(measured[0], measuredOperations),
                    ratio(measured[0], measuredTransactions),
                    ratio(resultRows, fetchRequests),
                    ratio(measured[3], resultRows),
                    measuredElapsedNanos,
                    openQueryFlowNanos,
                    continueQueryFlowNanos,
                    executeFlowNanos,
                    commitFlowNanos,
                    totalTimedFlowNanos,
                    averageMicros(openQueryFlowNanos, measured[5]),
                    averageMicros(continueQueryFlowNanos, measured[6]),
                    averageMicros(executeFlowNanos, measured[7]),
                    averageMicros(commitFlowNanos, measured[8]),
                    ratio(totalTimedFlowNanos, measuredElapsedNanos));
        }

        private static double ratio(long numerator, long denominator) {
            return denominator == 0L ? 0.0 : numerator / (double) denominator;
        }

        private static double averageMicros(long totalNanos, long count) {
            return count == 0L ? 0.0 : totalNanos / (count * 1_000.0);
        }

        String csv() {
            return String.join(",",
                    target, product, productVersion, driverVersion, workload.name(),
                    Integer.toString(clients), Integer.toString(operationsPerTransaction),
                    Integer.toString(rowCount), Integer.toString(run),
                    Long.toString(measuredTransactions), Long.toString(measuredOperations),
                    Long.toString(measuredResultRows), Long.toString(setupPrepareCommands),
                    Long.toString(requestFlushes), Long.toString(requestBytes),
                    Long.toString(replySocketReads), Long.toString(replyBytes),
                    Long.toString(measuredPrepareCommands), Long.toString(openQueryCommands),
                    Long.toString(continueQueryCommands), Long.toString(executeCommands),
                    Long.toString(commitCommands), Long.toString(rollbackCommands),
                    Long.toString(closeQueryCommands), Long.toString(queryDataBlocks),
                    Long.toString(fetchRequests), format(requestFlushesPerOperation),
                    format(requestFlushesPerTransaction), format(rowsPerFetchRequest),
                    format(replyBytesPerResultRow), Long.toString(measuredElapsedNanos),
                    Long.toString(openQueryFlowNanos), Long.toString(continueQueryFlowNanos),
                    Long.toString(executeFlowNanos), Long.toString(commitFlowNanos),
                    Long.toString(totalTimedFlowNanos), format(averageOpenQueryFlowMicros),
                    format(averageContinueQueryFlowMicros), format(averageExecuteFlowMicros),
                    format(averageCommitFlowMicros), format(timedFlowShareOfMeasuredElapsed));
        }

        static DrdaProtocolEvidence parse(String line) {
            String[] fields = line.split(",", -1);
            if (fields.length != 41) {
                throw new IllegalArgumentException(
                        "Expected 41 DRDA protocol CSV fields, found " + fields.length + ": " + line);
            }
            return new DrdaProtocolEvidence(
                    fields[0], fields[1], fields[2], fields[3], Workload.valueOf(fields[4]),
                    Integer.parseInt(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
                    Integer.parseInt(fields[8]), Long.parseLong(fields[9]), Long.parseLong(fields[10]),
                    Long.parseLong(fields[11]), Long.parseLong(fields[12]), Long.parseLong(fields[13]),
                    Long.parseLong(fields[14]), Long.parseLong(fields[15]), Long.parseLong(fields[16]),
                    Long.parseLong(fields[17]), Long.parseLong(fields[18]), Long.parseLong(fields[19]),
                    Long.parseLong(fields[20]), Long.parseLong(fields[21]), Long.parseLong(fields[22]),
                    Long.parseLong(fields[23]), Long.parseLong(fields[24]), Long.parseLong(fields[25]),
                    Double.parseDouble(fields[26]), Double.parseDouble(fields[27]),
                    Double.parseDouble(fields[28]), Double.parseDouble(fields[29]),
                    Long.parseLong(fields[30]), Long.parseLong(fields[31]), Long.parseLong(fields[32]),
                    Long.parseLong(fields[33]), Long.parseLong(fields[34]), Long.parseLong(fields[35]),
                    Double.parseDouble(fields[36]), Double.parseDouble(fields[37]),
                    Double.parseDouble(fields[38]), Double.parseDouble(fields[39]),
                    Double.parseDouble(fields[40]));
        }

        ShapeKey shape() {
            return new ShapeKey(rowCount, workload, clients, operationsPerTransaction);
        }
    }

    record ShapeKey(int rowCount, Workload workload, int clients, int operationsPerTransaction) {
        String csv() {
            return rowCount + "," + workload + "," + clients + "," + operationsPerTransaction;
        }

        BaselineKey baselineKey() {
            return new BaselineKey(rowCount, workload, operationsPerTransaction);
        }
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
    record BaselineKey(int rowCount, Workload workload, int operationsPerTransaction) {
    }
    static final int DRDA_PROTOCOL_COUNTER_COUNT = 16;
    static final int DRDA_PREPARE_COMMANDS = 4;
    static final int DRDA_OPEN_QUERY_FLOW_NANOS = 12;
    static final int DRDA_CONTINUE_QUERY_FLOW_NANOS = 13;
    static final int DRDA_EXECUTE_FLOW_NANOS = 14;
    static final int DRDA_COMMIT_FLOW_NANOS = 15;
    record Interval(
            long elapsedNanos,
            long semanticFingerprint,
            long retryableRollbacks,
            DelosSqlSemanticOracle.Result sqlOracleResult,
            long[] drdaProtocolEvidence) {
    }

    record ExplainCapture(String text, String json, long wallMillis) {}

    enum OrderEntryType {
        NEW_ORDER,
        PAYMENT,
        ORDER_STATUS,
        DELIVERY,
        STOCK_LEVEL,
        NEW_ORDER_ROLLBACK
    }

    static String csvSafe(String value) {
        return value == null ? "" : value.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }
    record Verification(
            long legacyFingerprint,
            DelosSqlSemanticOracle.Result sqlOracleResult) {
    }

    record DeleteRow(int id, int category, int bucket, int quantity, String payload) {
        DeleteRow {
            Objects.requireNonNull(payload, "payload");
        }
    }

    static long mix(long fingerprint, long value) {
        return 31L * fingerprint + value;
    }
    static final class MvccFeatureNames {
        static final String PHYSICAL_ROW_LOCATION_COST =
                "delosdb.experimental.mvccPhysicalRowLocationCost.enabled";
        static final String SINGLE_PASS = "delosdb.experimental.mvccGen2SinglePassCurrentScan.enabled";
        static final String REUSABLE_TEMPLATE =
                "delosdb.experimental.mvccGen2ReusableCurrentScanTemplate.enabled";
        static final String STREAMING = "delosdb.experimental.mvccGen2StreamingBulkScan.enabled";
        static final String FAST_VISIBILITY =
                "delosdb.experimental.mvccGen2FastCurrentVisibilityFetch.enabled";
        static final String SKIP_TAGS = "delosdb.experimental.mvccGen2SkipCurrentTagFetch.enabled";
        static final String LAZY_CREATOR =
                "delosdb.experimental.mvccGen2LazyCurrentCreatorFetch.enabled";
        static final String LAZY_IDENTITY =
                "delosdb.experimental.mvccGen2LazyCurrentIdentityFetch.enabled";

        private MvccFeatureNames() {
        }
    }

    record DrdaServerPhaseEvidence(
            String target,
            Workload workload,
            int run,
            long connection,
            long captureFirst,
            long captureLast,
            long openQueries,
            long continueQueries,
            int resultColumns,
            long sqlHash,
            long openRows,
            long continueRows,
            long openParseNanos,
            long openExecuteNanos,
            long openMetadataNanos,
            long openQueryDataNanos,
            long openRowAdvanceNanos,
            long openSendNanos,
            long openTotalNanos,
            long continueParseNanos,
            long continueMetadataNanos,
            long continueQueryDataNanos,
            long continueRowAdvanceNanos,
            long continueSendNanos,
            long continueTotalNanos) {

        long openAccountedNanos() {
            return openParseNanos + openExecuteNanos + openMetadataNanos
                    + openQueryDataNanos + openSendNanos;
        }

        long continueAccountedNanos() {
            return continueParseNanos + continueMetadataNanos
                    + continueQueryDataNanos + continueSendNanos;
        }

        long openResidualNanos() {
            return Math.max(0L, openTotalNanos - openAccountedNanos());
        }

        long continueResidualNanos() {
            return Math.max(0L, continueTotalNanos - continueAccountedNanos());
        }

        double averageOpenTotalMicros() {
            return micros(openTotalNanos, openQueries);
        }

        double averageOpenExecuteMicros() {
            return micros(openExecuteNanos, openQueries);
        }

        double averageOpenQueryDataMicros() {
            return micros(openQueryDataNanos, openQueries);
        }

        double averageOpenRowAdvanceMicros() {
            return micros(openRowAdvanceNanos, openQueries);
        }

        double averageOpenQueryDataResidualMicros() {
            return micros(Math.max(0L, openQueryDataNanos - openRowAdvanceNanos), openQueries);
        }

        double averageOpenSendMicros() {
            return micros(openSendNanos, openQueries);
        }

        double averageContinueTotalMicros() {
            return micros(continueTotalNanos, continueQueries);
        }

        double averageContinueQueryDataMicros() {
            return micros(continueQueryDataNanos, continueQueries);
        }

        double averageContinueRowAdvanceMicros() {
            return micros(continueRowAdvanceNanos, continueQueries);
        }

        double averageContinueQueryDataResidualMicros() {
            return micros(Math.max(0L, continueQueryDataNanos - continueRowAdvanceNanos), continueQueries);
        }

        double averageContinueSendMicros() {
            return micros(continueSendNanos, continueQueries);
        }

        double openExecuteShare() {
            return share(openExecuteNanos, openTotalNanos);
        }

        double openQueryDataShare() {
            return share(openQueryDataNanos, openTotalNanos);
        }

        double openSendShare() {
            return share(openSendNanos, openTotalNanos);
        }

        double continueQueryDataShare() {
            return share(continueQueryDataNanos, continueTotalNanos);
        }

        double continueSendShare() {
            return share(continueSendNanos, continueTotalNanos);
        }

        String toCsv() {
            return String.join(",",
                    target, workload.name(), Integer.toString(run), Long.toString(connection),
                    Long.toString(openQueries), Long.toString(continueQueries),
                    Integer.toString(resultColumns), Long.toString(openRows), Long.toString(continueRows),
                    Long.toString(openParseNanos), Long.toString(openExecuteNanos),
                    Long.toString(openMetadataNanos), Long.toString(openQueryDataNanos),
                    Long.toString(openRowAdvanceNanos), Long.toString(openSendNanos),
                    Long.toString(openResidualNanos()),
                    Long.toString(openTotalNanos), Long.toString(continueParseNanos),
                    Long.toString(continueMetadataNanos), Long.toString(continueQueryDataNanos),
                    Long.toString(continueRowAdvanceNanos), Long.toString(continueSendNanos),
                    Long.toString(continueResidualNanos()),
                    Long.toString(continueTotalNanos), format(averageOpenTotalMicros()),
                    format(averageOpenExecuteMicros()), format(averageOpenQueryDataMicros()),
                    format(averageOpenRowAdvanceMicros()), format(averageOpenQueryDataResidualMicros()),
                    format(averageOpenSendMicros()), format(openExecuteShare()),
                    format(openQueryDataShare()), format(openSendShare()),
                    format(averageContinueTotalMicros()),
                    format(averageContinueQueryDataMicros()),
                    format(averageContinueRowAdvanceMicros()),
                    format(averageContinueQueryDataResidualMicros()),
                    format(averageContinueSendMicros()),
                    format(continueQueryDataShare()), format(continueSendShare()));
        }

        static double micros(long nanos, long count) {
            return count == 0L ? 0.0 : nanos / (count * 1_000.0);
        }

        static double share(long part, long total) {
            return total == 0L ? 0.0 : (double) part / total;
        }
    }

    record Row(
            String target,
            String product,
            String productVersion,
            String driverVersion,
            Workload workload,
            int clients,
            int operationsPerTransaction,
            int transactionsPerClient,
            int rowCount,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            long warmupElapsedNanos,
            int iterations,
            long measuredTransactions,
            long measuredOperations,
            long retryableRollbacks,
            long elapsedNanos,
            double transactionsPerSecond,
            double operationsPerSecond,
            double inverseThroughputNanosPerTransaction,
            long semanticFingerprint,
            int run) {
        static Row parse(String line) {
            String[] fields = line.split(",", -1);
            if (fields.length != 23) {
                throw new IllegalArgumentException(
                        "Expected 23 concurrency CSV fields, found " + fields.length + ": " + line);
            }
            return new Row(fields[0], fields[1], fields[2], fields[3], Workload.valueOf(fields[4]),
                    Integer.parseInt(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
                    Integer.parseInt(fields[8]), Integer.parseInt(fields[9]), Integer.parseInt(fields[10]),
                    Integer.parseInt(fields[11]), Long.parseLong(fields[12]), Integer.parseInt(fields[13]),
                    Long.parseLong(fields[14]), Long.parseLong(fields[15]), Long.parseLong(fields[16]),
                    Long.parseLong(fields[17]), Double.parseDouble(fields[18]), Double.parseDouble(fields[19]),
                    Double.parseDouble(fields[20]), Long.parseLong(fields[21]), Integer.parseInt(fields[22]));
        }

        ShapeKey shape() {
            return new ShapeKey(rowCount, workload, clients, operationsPerTransaction);
        }

        String csv() {
            return new Measurement(target, product, productVersion, driverVersion, workload, clients,
                    operationsPerTransaction, transactionsPerClient, rowCount, payloadSize,
                    fixtureCommitBatchSize, warmups, warmupElapsedNanos, iterations,
                    measuredTransactions, measuredOperations,
                    retryableRollbacks, elapsedNanos, transactionsPerSecond, operationsPerSecond,
                    inverseThroughputNanosPerTransaction, semanticFingerprint, run).csv();
        }
    }

    record ShapeTargetKey(ShapeKey shape, Target target) {
    }

    record Distribution(
            int count, double median, double q1, double q3, double iqr, double mad, double min, double max) {
    }
}
