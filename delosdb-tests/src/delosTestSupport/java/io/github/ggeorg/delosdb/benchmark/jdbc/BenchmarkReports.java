/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.BaselineKey;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Distribution;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DrdaProtocolEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DrdaServerPhaseEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.OracleEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Row;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ShapeKey;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ShapeTargetKey;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.CURRENT_BASELINE_EMBEDDED_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.CURRENT_BASELINE_SERVER_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.DRDA_PROTOCOL_EVIDENCE_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.DRDA_SERVER_PHASE_EVIDENCE_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.EMBEDDED_REFERENCE_CANARY_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.GEN2_C3_POSTGRESQL_UPDATE_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.HOST_RECOVERY_DIAGNOSTIC_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.MVCC_ONLY_DIAGNOSTIC_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.RANGE_BULK_FETCH_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.RANGE_SCAN_JFR_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.READ_DECOMPOSITION_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.SERVER_REFERENCE_CANARY_TARGETS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.benchmarkContractId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.btreeInsertBranchRoutingSnapshotServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.btreeInsertRootRoutingSnapshotServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaServerPhaseCaptureOpenQueries;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaServerPhaseEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaServerPhaseSkipOpenQueries;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f02EmbeddedConcurrencyDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f02ScaleSurfaceDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f04DrdaServerPhaseDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f04ServerPlanCostMatrixEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f07DrdaServerPhaseDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08ContentionScalingSliceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08FixedCostClientScalingEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08MultiRowInsertControlEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08TransactionStatusCrossEngineEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.freshRealisticTransactionFitnessEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2A1ThroughputSentinelEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2BThroughputSentinelEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2C3UpdateThroughputSentinelEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.insertTableShape;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mutationSchemaAttributionEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccGen2A1ServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccGen2BServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccGen2C1ServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccGen2C3ReadServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccGen2C3UpdateServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccGen2ProjectedCurrentReadEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccGen2SinglePassCurrentScanEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccGen2TransactionStatusVisibilityServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccPhysicalRowLocationCostEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccPhysicalScanCostEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccRefreshMultiJoinStatisticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.postModernizationMutationCheckpointEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.rawStoreDurabilityTestNoSyncServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.rawStoreLogBufferSizeServerOverride;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.refreshSimpleJoinStatisticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.targetIds;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.CSV_HEADER;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DRDA_PROTOCOL_CSV_HEADER;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ORACLE_CSV_HEADER;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.csvSafe;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadRunner.ordered;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadRunner.specsPerRun;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.expectedFitnessRows;

/** Campaign validation, aggregation and report publication. */
final class BenchmarkReports {
    private BenchmarkReports() {
    }

    static void writeContainerManifest(
            BenchmarkConfiguration options, String dockerVersion, Map<String, String> images) throws IOException {
        java.nio.file.FileStore fileStore = Files.getFileStore(options.projectDirectory());
        StringBuilder out = new StringBuilder()
                .append("DelosDB server-container concurrency comparison manifest\n")
                .append("Captured: ").append(Instant.now()).append('\n')
                .append(drdaServerPhaseEvidenceEnabled()
                        && options.targetValues().equals(DRDA_SERVER_PHASE_EVIDENCE_TARGETS)
                        ? "Question: decompose Delos Heap/MVCC DRDA server command phases for focused evidence.\n"
                        : "Question: compare DelosDB DRDA heap/MVCC, upstream Derby Network Server, and H2 TCP Server "
                                + "with PostgreSQL and MariaDB through equivalent TCP/JDBC boundaries.\n")
                .append("Targets: ").append(targetIds(options.targetValues())).append('\n')
                .append("Project version: ").append(options.projectVersion()).append('\n')
                .append("Client JDK: ").append(System.getProperty("java.runtime.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Docker server: ").append(dockerVersion).append('\n')
                .append("Client processors: ").append(Runtime.getRuntime().availableProcessors()).append('\n')
                .append("Client max heap bytes: ").append(Runtime.getRuntime().maxMemory()).append('\n')
                .append("Project filesystem: ").append(fileStore.name()).append(" type=").append(fileStore.type())
                .append(" totalBytes=").append(fileStore.getTotalSpace())
                .append(" usableBytes=").append(fileStore.getUsableSpace()).append('\n')
                .append("Isolation: TRANSACTION_READ_COMMITTED\n")
                .append("Durability: engine defaults retained; no fsync/synchronous-commit weakening\n")
                .append("Client topology: host JDK process outside each database container, "
                        + "JDBC over localhost TCP publishing\n")
                .append("Container lifecycle: fresh database container per target/run; fresh table per matrix cell\n")
                .append("Rows: ").append(options.rows()).append('\n')
                .append("Clients: ").append(options.clients()).append('\n')
                .append("Operations per transaction: ").append(options.widths()).append('\n')
                .append("Transactions per client: ").append(options.transactionsPerClient()).append('\n')
                .append("Payload bytes: ").append(options.payload()).append('\n')
                .append("Fixture batch: ").append(options.fixtureBatch()).append('\n')
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Minimum warmup seconds per run: ").append(options.minimumWarmupSeconds()).append('\n')
                .append("Maximum warmup intervals per run: ").append(options.maximumWarmupIterations()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append('\n')
                .append("Case timeout seconds: ").append(options.caseTimeoutSeconds()).append('\n')
                .append("Worker timeout seconds: ").append(options.workerTimeoutSeconds()).append('\n')
                .append("Upstream Derby version: ").append(options.upstreamDerbyVersion()).append('\n')
                .append("PostgreSQL JDBC: ").append(options.postgresqlDriverVersion()).append('\n')
                .append("MariaDB Connector/J: ").append(options.mariadbDriverVersion()).append('\n')
                .append("H2 JDBC/TCP: ").append(options.h2DriverVersion()).append('\n')
                .append("H2 range fetch size override: ").append(options.h2RangeFetchSize())
                .append(" (0=driver default)\n")
                .append("MVCC base-fetch directory-page prefetch experiment: ")
                .append(Boolean.getBoolean(PREFIX + "mvccBaseFetchPagePrefetch")).append('\n')
                .append("MVCC Gen2 projected current read experiment: ")
                .append(mvccGen2ProjectedCurrentReadEnabled()).append('\n')
                .append("MVCC physical scan cost experiment: ")
                .append(mvccPhysicalScanCostEnabled()).append('\n')
                .append("MVCC physical RowLocation cost experiment: ")
                .append(mvccPhysicalRowLocationCostEnabled()).append('\n')
                .append("MVCC single-pass CURRENT scan experiment: ")
                .append(mvccGen2SinglePassCurrentScanEnabled()).append('\n')
                .append("MVCC multi-join statistics refresh enabled: ")
                .append(mvccRefreshMultiJoinStatisticsEnabled()).append('\n')
                .append("Simple-join statistics refresh enabled: ")
                .append(refreshSimpleJoinStatisticsEnabled()).append('\n')
                .append("Derby keep disposable index stats enabled: ")
                .append(Boolean.getBoolean("derby.storage.indexStats.debug.keepDisposableStats")).append('\n')
                .append("Analysis schema: cross-engine-concurrency-v1\n")
                .append("Expected invariant: identical final-state semantic fingerprint for every target/run/cell\n")
                .append("Known limitation: contextual comparison; Docker virtualization, engine defaults, and ")
                .append("different server architectures remain part of the measured product models.\n")
                .append("Raw results: cross-engine-concurrency-results.csv; ratios/scaling/dispersion CSV; summary\n");
        for (Map.Entry<String, String> image : images.entrySet()) {
            out.append("Image: ").append(image.getKey()).append(" -> ").append(image.getValue()).append('\n');
        }
        for (String jar : List.of("derby.jar", "derbynet.jar", "derbyclient.jar", "derbyshared.jar")) {
            Path file = options.delosRuntimeDirectory().resolve(jar);
            if (Files.isRegularFile(file)) {
                out.append("Delos artifact: ").append(jar).append(" sha256=").append(sha256(file)).append('\n');
            }
        }
        if (options.targetValues().contains(Target.UPSTREAM_DERBY_DRDA)) {
            try (var stream = Files.list(options.upstreamDerbyServerRuntimeDirectory())) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    out.append("Upstream Derby server artifact: ")
                            .append(file.getFileName()).append(" sha256=").append(sha256(file)).append('\n');
                }
            }
        }
        if (options.targetValues().contains(Target.H2_SERVER) && Files.isRegularFile(options.h2ServerJar())) {
            out.append("H2 server artifact: ").append(options.h2ServerJar().getFileName())
                    .append(" sha256=").append(sha256(options.h2ServerJar())).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("server-container-manifest.txt"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static void writeContainerSemanticEvidence(BenchmarkConfiguration options, List<Row> rows) throws IOException {
        Map<ShapeKey, Long> fingerprints = new java.util.LinkedHashMap<>();
        for (Row row : rows) {
            fingerprints.putIfAbsent(row.shape(), row.semanticFingerprint());
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder evidence = new StringBuilder();
            for (Map.Entry<ShapeKey, Long> entry : fingerprints.entrySet()) {
                String token = entry.getKey().csv() + '=' + entry.getValue() + '\n';
                digest.update(token.getBytes(StandardCharsets.UTF_8));
                evidence.append(token);
            }
            evidence.append("semanticShapes=").append(fingerprints.size()).append('\n')
                    .append("sha256=").append(java.util.HexFormat.of().formatHex(digest.digest())).append('\n');
            Files.writeString(options.reportDirectory().resolve("server-container-semantic-checksum.txt"),
                    evidence.toString(), StandardCharsets.UTF_8);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    digest.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static List<OracleEvidence> loadOracleEvidence(BenchmarkConfiguration options) throws IOException {
        List<OracleEvidence> evidence = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (Target target : options.targetValues()) {
                Path file = options.reportDirectory().resolve("workers")
                        .resolve("sql-semantic-oracle-" + target.id() + "-run-" + run + ".csv");
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty() || !ORACLE_CSV_HEADER.equals(lines.getFirst())) {
                    throw new IllegalStateException(
                            "Unexpected Phase 0A SQL oracle CSV header: " + file);
                }
                for (int index = 1; index < lines.size(); index++) {
                    if (!lines.get(index).isBlank()) {
                        evidence.add(OracleEvidence.parse(lines.get(index)));
                    }
                }
            }
        }
        evidence.sort(Comparator.comparingInt(OracleEvidence::rowCount)
                .thenComparing(value -> value.workload().name())
                .thenComparingInt(OracleEvidence::operationsPerTransaction)
                .thenComparingInt(OracleEvidence::clients)
                .thenComparing(OracleEvidence::target)
                .thenComparingInt(OracleEvidence::run));
        return List.copyOf(evidence);
    }

    static void validateOracleEvidence(
            BenchmarkConfiguration options,
            List<OracleEvidence> evidence) {
        int expected = options.targetValues().size() * options.runs() * options.rowCounts().size()
                * specsPerRun(options);
        if (evidence.size() != expected) {
            throw new IllegalStateException(
                    "Phase 0A SQL oracle evidence count mismatch: expected="
                            + expected + ", actual=" + evidence.size());
        }
        Map<ShapeKey, String> fingerprints = new HashMap<>();
        for (OracleEvidence value : evidence) {
            ShapeKey key = value.shape();
            String prior = fingerprints.putIfAbsent(key, value.fingerprint());
            if (prior != null && !prior.equals(value.fingerprint())) {
                throw new IllegalStateException(
                        "Phase 0A SQL oracle mismatch for " + key
                                + ": expected=" + prior
                                + ", actual=" + value.fingerprint()
                                + ", target=" + value.target()
                                + ", run=" + value.run());
            }
        }
    }

    static void writeOracleEvidence(
            BenchmarkConfiguration options,
            List<OracleEvidence> evidence) throws IOException {
        StringBuilder csv = new StringBuilder(ORACLE_CSV_HEADER).append('\n');
        for (OracleEvidence value : evidence) {
            csv.append(value.csv()).append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("sql-semantic-oracle.csv"),
                csv.toString(),
                StandardCharsets.UTF_8);

        Map<ShapeKey, OracleEvidence> shapes = new LinkedHashMap<>();
        for (OracleEvidence value : evidence) {
            shapes.putIfAbsent(value.shape(), value);
        }
        StringBuilder summary = new StringBuilder();
        summary.append("DelosDB Phase 0A SQL-authoritative oracle evidence\n")
                .append("================================================\n\n")
                .append("Authority: canonical JDBC SQL-visible values; storage-engine internals are diagnostic only.\n")
                .append("Cross-target/run equality: PASS\n")
                .append("Shapes: ").append(shapes.size()).append('\n')
                .append("Evidence rows: ").append(evidence.size()).append("\n\n");
        for (Map.Entry<ShapeKey, OracleEvidence> entry : shapes.entrySet()) {
            OracleEvidence value = entry.getValue();
            summary.append("- ").append(entry.getKey().csv())
                    .append(" kind=").append(value.kind())
                    .append(" count=").append(value.count())
                    .append(" fingerprint=").append(value.fingerprint())
                    .append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("sql-semantic-oracle.txt"),
                summary.toString(),
                StandardCharsets.UTF_8);
    }

    static List<DrdaProtocolEvidence> loadDrdaProtocolEvidence(BenchmarkConfiguration options) throws IOException {
        List<DrdaProtocolEvidence> evidence = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (Target target : options.targetValues()) {
                Path file = options.reportDirectory().resolve("workers")
                        .resolve("drda-protocol-evidence-" + target.id() + "-run-" + run + ".csv");
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty() || !DRDA_PROTOCOL_CSV_HEADER.equals(lines.getFirst())) {
                    throw new IllegalStateException("Unexpected DRDA protocol evidence CSV header: " + file);
                }
                for (int index = 1; index < lines.size(); index++) {
                    if (!lines.get(index).isBlank()) {
                        evidence.add(DrdaProtocolEvidence.parse(lines.get(index)));
                    }
                }
            }
        }
        evidence.sort(Comparator.comparingInt(DrdaProtocolEvidence::rowCount)
                .thenComparing(value -> value.workload().name())
                .thenComparingInt(DrdaProtocolEvidence::operationsPerTransaction)
                .thenComparingInt(DrdaProtocolEvidence::clients)
                .thenComparing(DrdaProtocolEvidence::target)
                .thenComparingInt(DrdaProtocolEvidence::run));
        return List.copyOf(evidence);
    }

    static List<DrdaServerPhaseEvidence> loadDrdaServerPhaseEvidence(BenchmarkConfiguration options)
            throws IOException {
        Path directory = options.reportDirectory().resolve("server-phase-logs");
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Missing DRDA server phase log directory: " + directory);
        }
        List<DrdaServerPhaseEvidence> evidence = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            for (Path log : stream.filter(Files::isRegularFile).sorted().toList()) {
                String file = log.getFileName().toString();
                int dash = file.indexOf('-');
                int dot = file.lastIndexOf('.');
                if (dash < 0 || dot <= dash) {
                    continue;
                }
                int run = Integer.parseInt(file.substring(0, dash));
                String target = file.substring(dash + 1, dot);
                for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                    int marker = line.indexOf("DELOS_DRDA_SERVER_PHASE_EVIDENCE|");
                    if (marker < 0) {
                        continue;
                    }
                    Map<String, String> values = parseServerPhaseEvidenceLine(line.substring(marker));
                    long openQueries = parseServerEvidenceLong(values, "openQueries");
                    long continueQueries = parseServerEvidenceLong(values, "continueQueries");
                    int resultColumns = Math.toIntExact(parseServerEvidenceLong(values, "resultColumns"));
                    Workload workload;
                    if (f04DrdaServerPhaseDiagnosticEnabled()) {
                        workload = switch (resultColumns) {
                            case 1 -> Workload.JOIN_INDEXED_1TO1;
                            case 2 -> Workload.JOIN_INDEXED_FANOUT;
                            default -> throw new IllegalStateException(
                                    "Unexpected F04 DRDA server-phase result-column count: "
                                            + resultColumns + " in " + values);
                        };
                    } else if (f07DrdaServerPhaseDiagnosticEnabled()) {
                        workload = Workload.SORT_FULL;
                    } else if (continueQueries > 0L) {
                        workload = Workload.RANGE_SCAN_FULL;
                    } else if (resultColumns == 1) {
                        workload = Workload.RANGE_SCAN_INDEX_ONLY_1000;
                    } else {
                        workload = Workload.RANGE_SCAN_1000;
                    }
                    evidence.add(new DrdaServerPhaseEvidence(
                            target, workload, run,
                            parseServerEvidenceLong(values, "connection"),
                            parseServerEvidenceLong(values, "captureFirst"),
                            parseServerEvidenceLong(values, "captureLast"),
                            openQueries, continueQueries, resultColumns,
                            parseServerEvidenceLong(values, "sqlHash"),
                            parseServerEvidenceLong(values, "openRows"),
                            parseServerEvidenceLong(values, "continueRows"),
                            parseServerEvidenceLong(values, "openParseNanos"),
                            parseServerEvidenceLong(values, "openExecuteNanos"),
                            parseServerEvidenceLong(values, "openMetadataNanos"),
                            parseServerEvidenceLong(values, "openQueryDataNanos"),
                            parseServerEvidenceLong(values, "openRowAdvanceNanos"),
                            parseServerEvidenceLong(values, "openSendNanos"),
                            parseServerEvidenceLong(values, "openTotalNanos"),
                            parseServerEvidenceLong(values, "continueParseNanos"),
                            parseServerEvidenceLong(values, "continueMetadataNanos"),
                            parseServerEvidenceLong(values, "continueQueryDataNanos"),
                            parseServerEvidenceLong(values, "continueRowAdvanceNanos"),
                            parseServerEvidenceLong(values, "continueSendNanos"),
                            parseServerEvidenceLong(values, "continueTotalNanos")));
                }
            }
        }
        return evidence;
    }

    static Map<String, String> parseServerPhaseEvidenceLine(String line) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : line.split("\\|")) {
            int equals = token.indexOf('=');
            if (equals > 0) {
                values.put(token.substring(0, equals), token.substring(equals + 1));
            }
        }
        return values;
    }

    static long parseServerEvidenceLong(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("Missing DRDA server phase field: " + name + " in " + values);
        }
        return Long.parseLong(value);
    }

    static void validateDrdaServerPhaseEvidence(
            BenchmarkConfiguration options, List<DrdaServerPhaseEvidence> evidence) {
        if (f04DrdaServerPhaseDiagnosticEnabled()) {
            validateF04DrdaServerPhaseEvidence(options, evidence);
            return;
        }
        if (f07DrdaServerPhaseDiagnosticEnabled()) {
            validateF07DrdaServerPhaseEvidence(options, evidence);
            return;
        }
        List<Target> expectedTargets = DRDA_SERVER_PHASE_EVIDENCE_TARGETS;
        if (!options.targetValues().equals(expectedTargets)) {
            throw new IllegalStateException(
                    "DRDA server phase evidence requires Delos Heap/MVCC DRDA targets: "
                            + options.targetValues());
        }
        int expected = expectedTargets.size() * options.runs() * 3;
        if (evidence.size() != expected) {
            throw new IllegalStateException(
                    "DRDA server phase evidence count mismatch: expected=" + expected
                            + ", actual=" + evidence.size() + ", evidence=" + evidence);
        }
        Map<String, DrdaServerPhaseEvidence> unique = new LinkedHashMap<>();
        for (DrdaServerPhaseEvidence value : evidence) {
            if (value.captureFirst() != 21L || value.captureLast() != 40L
                    || value.openQueries() != 20L) {
                throw new IllegalStateException("Unexpected measured query window: " + value);
            }
            long expectedContinue = value.workload() == Workload.RANGE_SCAN_FULL ? 60L : 0L;
            if (value.continueQueries() != expectedContinue) {
                throw new IllegalStateException("Unexpected CNTQRY count: " + value);
            }
            int expectedColumns = value.workload() == Workload.RANGE_SCAN_INDEX_ONLY_1000 ? 1 : 2;
            if (value.resultColumns() != expectedColumns) {
                throw new IllegalStateException("Unexpected result-column shape: " + value);
            }
            if (value.openRows() <= 0L || value.openTotalNanos() <= 0L
                    || value.openQueryDataNanos() <= 0L || value.openSendNanos() <= 0L) {
                throw new IllegalStateException("Missing OPNQRY server phase evidence: " + value);
            }
            if (value.workload() == Workload.RANGE_SCAN_FULL
                    && (value.continueRows() <= 0L || value.continueTotalNanos() <= 0L
                            || value.continueQueryDataNanos() <= 0L || value.continueSendNanos() <= 0L)) {
                throw new IllegalStateException("Missing CNTQRY server phase evidence: " + value);
            }
            if (value.openAccountedNanos() > value.openTotalNanos()) {
                throw new IllegalStateException("OPNQRY phase accounting exceeds total: " + value);
            }
            if (value.continueAccountedNanos() > value.continueTotalNanos()) {
                throw new IllegalStateException("CNTQRY phase accounting exceeds total: " + value);
            }
            String key = value.target() + '|' + value.workload() + '|' + value.run();
            if (unique.put(key, value) != null) {
                throw new IllegalStateException("Duplicate DRDA server phase evidence: " + key);
            }
        }
    }

    static void validateF04DrdaServerPhaseEvidence(
            BenchmarkConfiguration options, List<DrdaServerPhaseEvidence> evidence) {
        List<Target> expectedTargets = DRDA_SERVER_PHASE_EVIDENCE_TARGETS;
        if (!options.targetValues().equals(expectedTargets)) {
            throw new IllegalStateException(
                    "F04 DRDA server-phase evidence requires Delos Heap/MVCC DRDA targets: "
                            + options.targetValues());
        }
        int clients = options.clientValues().getFirst();
        List<Workload> workloads = List.of(
                Workload.JOIN_INDEXED_1TO1,
                Workload.JOIN_INDEXED_FANOUT);
        int expected = expectedTargets.size() * options.runs() * clients * workloads.size();
        if (evidence.size() != expected) {
            throw new IllegalStateException(
                    "F04 DRDA server-phase evidence count mismatch: expected=" + expected
                            + ", actual=" + evidence.size() + ", evidence=" + evidence);
        }

        Map<String, Integer> connectionCounts = new LinkedHashMap<>();
        Map<String, DrdaServerPhaseEvidence> unique = new LinkedHashMap<>();
        int rowCount = options.rowCounts().getFirst();
        for (DrdaServerPhaseEvidence value : evidence) {
            if (!workloads.contains(value.workload())) {
                throw new IllegalStateException("Unexpected F04 server-phase workload: " + value);
            }
            long expectedCaptureFirst = drdaServerPhaseSkipOpenQueries() + 1L;
            long expectedCaptureLast = drdaServerPhaseSkipOpenQueries()
                    + (long) drdaServerPhaseCaptureOpenQueries();
            if (value.captureFirst() != expectedCaptureFirst
                    || value.captureLast() != expectedCaptureLast
                    || value.openQueries() != drdaServerPhaseCaptureOpenQueries()) {
                throw new IllegalStateException("Unexpected F04 measured query window: " + value);
            }
            int expectedColumns = value.workload() == Workload.JOIN_INDEXED_1TO1 ? 1 : 2;
            if (value.resultColumns() != expectedColumns || value.sqlHash() == 0L) {
                throw new IllegalStateException("Unexpected F04 statement shape: " + value);
            }
            long expectedRows = (long) drdaServerPhaseCaptureOpenQueries()
                    * expectedFitnessRows(value.workload(), rowCount);
            if (value.openRows() + value.continueRows() != expectedRows) {
                throw new IllegalStateException(
                        "Unexpected F04 result-row count: expected=" + expectedRows
                                + ", evidence=" + value);
            }
            if (value.openRows() <= 0L || value.openTotalNanos() <= 0L
                    || value.openExecuteNanos() <= 0L
                    || value.openQueryDataNanos() <= 0L || value.openRowAdvanceNanos() <= 0L
                    || value.openSendNanos() <= 0L) {
                throw new IllegalStateException("Missing F04 OPNQRY phase evidence: " + value);
            }
            if (value.openRowAdvanceNanos() > value.openQueryDataNanos()
                    || value.continueRowAdvanceNanos() > value.continueQueryDataNanos()) {
                throw new IllegalStateException("F04 row-advance timing exceeds QRYDTA timing: " + value);
            }
            if (value.continueQueries() > 0L
                    && (value.continueRows() <= 0L || value.continueTotalNanos() <= 0L
                            || value.continueQueryDataNanos() <= 0L
                            || value.continueSendNanos() <= 0L)) {
                throw new IllegalStateException("Missing F04 CNTQRY phase evidence: " + value);
            }
            if (value.openAccountedNanos() > value.openTotalNanos()
                    || value.continueAccountedNanos() > value.continueTotalNanos()) {
                throw new IllegalStateException("F04 phase accounting exceeds total: " + value);
            }

            String targetWorkloadRun = value.target() + '|' + value.workload() + '|' + value.run();
            connectionCounts.merge(targetWorkloadRun, 1, Integer::sum);
            String uniqueKey = targetWorkloadRun + '|' + value.connection();
            if (unique.put(uniqueKey, value) != null) {
                throw new IllegalStateException(
                        "Duplicate F04 DRDA server-phase evidence: " + uniqueKey);
            }
        }
        for (Target target : expectedTargets) {
            for (Workload workload : workloads) {
                for (int run = 1; run <= options.runs(); run++) {
                    String key = target.id() + '|' + workload + '|' + run;
                    int actual = connectionCounts.getOrDefault(key, 0);
                    if (actual != clients) {
                        throw new IllegalStateException(
                                "F04 server-phase connection count mismatch for " + key
                                        + ": expected=" + clients + ", actual=" + actual);
                    }
                }
            }
        }
    }

    static void validateF07DrdaServerPhaseEvidence(
            BenchmarkConfiguration options, List<DrdaServerPhaseEvidence> evidence) {
        List<Target> expectedTargets = DRDA_SERVER_PHASE_EVIDENCE_TARGETS;
        if (!options.targetValues().equals(expectedTargets)) {
            throw new IllegalStateException(
                    "F07 DRDA server-phase evidence requires Delos Heap/MVCC DRDA targets: "
                            + options.targetValues());
        }
        int expectedConnectionsPerTargetRun = options.clientValues().get(0);
        int expected = expectedTargets.size() * options.runs() * expectedConnectionsPerTargetRun;
        if (evidence.size() != expected) {
            throw new IllegalStateException(
                    "F07 DRDA server-phase evidence count mismatch: expected=" + expected
                            + ", actual=" + evidence.size() + ", evidence=" + evidence);
        }
        Map<String, Integer> connectionCounts = new LinkedHashMap<>();
        Map<String, DrdaServerPhaseEvidence> unique = new LinkedHashMap<>();
        long expectedRows = (long) drdaServerPhaseCaptureOpenQueries()
                * options.rowCounts().get(0);
        for (DrdaServerPhaseEvidence value : evidence) {
            if (value.workload() != Workload.SORT_FULL) {
                throw new IllegalStateException("Unexpected F07 server-phase workload: " + value);
            }
            long expectedCaptureFirst = drdaServerPhaseSkipOpenQueries() + 1L;
            long expectedCaptureLast = drdaServerPhaseSkipOpenQueries()
                    + (long) drdaServerPhaseCaptureOpenQueries();
            if (value.captureFirst() != expectedCaptureFirst
                    || value.captureLast() != expectedCaptureLast
                    || value.openQueries() != drdaServerPhaseCaptureOpenQueries()) {
                throw new IllegalStateException("Unexpected F07 measured query window: " + value);
            }
            if (value.resultColumns() != 2 || value.sqlHash() == 0L) {
                throw new IllegalStateException("Unexpected F07 statement shape: " + value);
            }
            if (value.openRows() + value.continueRows() != expectedRows) {
                throw new IllegalStateException(
                        "Unexpected F07 result-row count: expected=" + expectedRows + ", evidence=" + value);
            }
            if (value.openRows() <= 0L || value.openTotalNanos() <= 0L
                    || value.openExecuteNanos() <= 0L
                    || value.openQueryDataNanos() <= 0L || value.openSendNanos() <= 0L) {
                throw new IllegalStateException("Missing F07 OPNQRY phase evidence: " + value);
            }
            if (value.continueQueries() <= 0L || value.continueRows() <= 0L
                    || value.continueTotalNanos() <= 0L
                    || value.continueQueryDataNanos() <= 0L || value.continueSendNanos() <= 0L) {
                throw new IllegalStateException("Missing F07 CNTQRY phase evidence: " + value);
            }
            if (value.openAccountedNanos() > value.openTotalNanos()
                    || value.continueAccountedNanos() > value.continueTotalNanos()) {
                throw new IllegalStateException("F07 phase accounting exceeds total: " + value);
            }
            String targetRun = value.target() + '|' + value.run();
            connectionCounts.merge(targetRun, 1, Integer::sum);
            String uniqueKey = targetRun + '|' + value.connection();
            if (unique.put(uniqueKey, value) != null) {
                throw new IllegalStateException("Duplicate F07 DRDA server phase evidence: " + uniqueKey);
            }
        }
        for (Target target : expectedTargets) {
            for (int run = 1; run <= options.runs(); run++) {
                String key = target.id() + '|' + run;
                int actual = connectionCounts.getOrDefault(key, 0);
                if (actual != expectedConnectionsPerTargetRun) {
                    throw new IllegalStateException(
                            "F07 server-phase connection count mismatch for " + key
                                    + ": expected=" + expectedConnectionsPerTargetRun + ", actual=" + actual);
                }
            }
        }
    }

    static void writeDrdaServerPhaseEvidence(
            BenchmarkConfiguration options, List<DrdaServerPhaseEvidence> evidence) throws IOException {
        List<DrdaServerPhaseEvidence> sorted = new ArrayList<>(evidence);
        sorted.sort(Comparator.comparing(DrdaServerPhaseEvidence::workload)
                .thenComparing(DrdaServerPhaseEvidence::target)
                .thenComparingInt(DrdaServerPhaseEvidence::run));
        String header = "target,workload,run,connection,openQueries,continueQueries,resultColumns,"
                + "openRows,continueRows,openParseNanos,openExecuteNanos,openMetadataNanos,"
                + "openQueryDataNanos,openRowAdvanceNanos,openSendNanos,openResidualNanos,openTotalNanos,"
                + "continueParseNanos,continueMetadataNanos,continueQueryDataNanos,continueRowAdvanceNanos,"
                + "continueSendNanos,continueResidualNanos,continueTotalNanos,averageOpenTotalMicros,"
                + "averageOpenExecuteMicros,averageOpenQueryDataMicros,averageOpenRowAdvanceMicros,"
                + "averageOpenQueryDataResidualMicros,averageOpenSendMicros,"
                + "openExecuteShare,openQueryDataShare,openSendShare,averageContinueTotalMicros,"
                + "averageContinueQueryDataMicros,averageContinueRowAdvanceMicros,"
                + "averageContinueQueryDataResidualMicros,averageContinueSendMicros,continueQueryDataShare,"
                + "continueSendShare";
        StringBuilder csv = new StringBuilder(header).append('\n');
        StringBuilder text = new StringBuilder()
                .append("DelosDB Phase-1 DRDA server phase evidence\n")
                .append("=========================================\n\n")
                .append("Authority: Delos Network Server command processing for measured query ordinals 21-40.\n")
                .append("Granularity: command phases plus aggregate ResultSet row-advance timing; no per-column timers.\n")
                .append("Evidence rows: ").append(sorted.size()).append("\n\n");
        for (DrdaServerPhaseEvidence value : sorted) {
            csv.append(value.toCsv()).append('\n');
            text.append(value.target()).append(' ')
                    .append(value.workload()).append(" run=").append(value.run())
                    .append(" openTotalUs=").append(format(value.averageOpenTotalMicros()))
                    .append(" executeUs=").append(format(value.averageOpenExecuteMicros()))
                    .append(" qrydtaUs=").append(format(value.averageOpenQueryDataMicros()))
                    .append(" rowAdvanceUs=").append(format(value.averageOpenRowAdvanceMicros()))
                    .append(" qrydtaResidualUs=").append(format(value.averageOpenQueryDataResidualMicros()))
                    .append(" sendUs=").append(format(value.averageOpenSendMicros()))
                    .append(" qrydtaShare=").append(format(value.openQueryDataShare()))
                    .append(" continueTotalUs=").append(format(value.averageContinueTotalMicros()))
                    .append(" continueQrydtaUs=").append(format(value.averageContinueQueryDataMicros()))
                    .append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("drda-server-phase-evidence.csv"),
                csv, StandardCharsets.UTF_8);
        Files.writeString(options.reportDirectory().resolve("drda-server-phase-evidence.txt"),
                text, StandardCharsets.UTF_8);
        if (f04DrdaServerPhaseDiagnosticEnabled()) {
            writeF04DrdaServerPhaseAggregate(options, sorted);
        }
        if (f07DrdaServerPhaseDiagnosticEnabled()) {
            writeF07DrdaServerPhaseAggregate(options, sorted);
        }
    }

    static void writeF04DrdaServerPhaseAggregate(
            BenchmarkConfiguration options, List<DrdaServerPhaseEvidence> evidence) throws IOException {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (DrdaServerPhaseEvidence value : evidence) {
            String key = value.target() + '|' + value.workload() + '|' + value.run();
            long[] total = totals.computeIfAbsent(key, ignored -> new long[17]);
            total[0]++;
            total[1] += value.openQueries();
            total[2] += value.continueQueries();
            total[3] += value.openRows() + value.continueRows();
            total[4] += value.openParseNanos();
            total[5] += value.openExecuteNanos();
            total[6] += value.openMetadataNanos();
            total[7] += value.openQueryDataNanos();
            total[8] += value.openRowAdvanceNanos();
            total[9] += value.openSendNanos();
            total[10] += value.openTotalNanos();
            total[11] += value.continueParseNanos();
            total[12] += value.continueMetadataNanos();
            total[13] += value.continueQueryDataNanos();
            total[14] += value.continueRowAdvanceNanos();
            total[15] += value.continueSendNanos();
            total[16] += value.continueTotalNanos();
        }

        String header = "target,workload,run,connections,openQueries,continueQueries,resultRows,"
                + "averageOpenTotalMicros,averageOpenParseMicros,averageOpenExecuteMicros,"
                + "averageOpenMetadataMicros,averageOpenQueryDataMicros,averageOpenRowAdvanceMicros,"
                + "averageOpenQueryDataResidualMicros,averageOpenSendMicros,"
                + "openParseShare,openExecuteShare,openMetadataShare,openQueryDataShare,openSendShare,"
                + "averageContinueTotalMicros,averageContinueParseMicros,averageContinueMetadataMicros,"
                + "averageContinueQueryDataMicros,averageContinueRowAdvanceMicros,"
                + "averageContinueQueryDataResidualMicros,averageContinueSendMicros,"
                + "continueParseShare,continueMetadataShare,continueQueryDataShare,continueSendShare";
        StringBuilder csv = new StringBuilder(header).append('\n');
        StringBuilder text = new StringBuilder()
                .append("DelosDB Phase-2X F04 QRYDTA row-advance decomposition\n")
                .append("=====================================================\n\n")
                .append("diagnosticOnly=true\n")
                .append("workloads=JOIN_INDEXED_1TO1,JOIN_INDEXED_FANOUT\n")
                .append("clients=").append(options.clients()).append('\n')
                .append("captureOpenQueries=").append(drdaServerPhaseCaptureOpenQueries()).append('\n')
                .append("physicalScanCost=").append(mvccPhysicalScanCostEnabled()).append('\n')
                .append("physicalRowLocationCost=").append(mvccPhysicalRowLocationCostEnabled()).append('\n')
                .append("projectedCurrentRead=").append(mvccGen2ProjectedCurrentReadEnabled()).append('\n')
                .append("baseFetchPagePrefetch=")
                .append(Boolean.getBoolean(PREFIX + "mvccBaseFetchPagePrefetch")).append("\n\n");

        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            long[] total = entry.getValue();
            long openQueries = total[1];
            long continueQueries = total[2];
            long openQueryResidual = Math.max(0L, total[7] - total[8]);
            long continueQueryResidual = Math.max(0L, total[13] - total[14]);
            csv.append(parts[0]).append(',').append(parts[1]).append(',').append(parts[2]).append(',')
                    .append(total[0]).append(',').append(openQueries).append(',')
                    .append(continueQueries).append(',').append(total[3]).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[10], openQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[4], openQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[5], openQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[6], openQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[7], openQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[8], openQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(openQueryResidual, openQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[9], openQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[4], total[10]))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[5], total[10]))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[6], total[10]))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[7], total[10]))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[9], total[10]))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[16], continueQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[11], continueQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[12], continueQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[13], continueQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[14], continueQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(continueQueryResidual, continueQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.micros(total[15], continueQueries))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[11], total[16]))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[12], total[16]))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[13], total[16]))).append(',')
                    .append(format(DrdaServerPhaseEvidence.share(total[15], total[16]))).append('\n');

            text.append(parts[0]).append(' ').append(parts[1]).append(" run=").append(parts[2])
                    .append(" connections=").append(total[0])
                    .append(" openTotalUs=").append(format(DrdaServerPhaseEvidence.micros(total[10], openQueries)))
                    .append(" executeUs=").append(format(DrdaServerPhaseEvidence.micros(total[5], openQueries)))
                    .append(" qrydtaUs=").append(format(DrdaServerPhaseEvidence.micros(total[7], openQueries)))
                    .append(" rowAdvanceUs=").append(format(DrdaServerPhaseEvidence.micros(total[8], openQueries)))
                    .append(" qrydtaResidualUs=")
                    .append(format(DrdaServerPhaseEvidence.micros(openQueryResidual, openQueries)))
                    .append(" sendUs=").append(format(DrdaServerPhaseEvidence.micros(total[9], openQueries)))
                    .append('\n');
        }

        Files.writeString(options.reportDirectory().resolve("f04-drda-server-phase-aggregate.csv"),
                csv, StandardCharsets.UTF_8);
        Files.writeString(options.reportDirectory().resolve("f04-drda-server-phase-aggregate.txt"),
                text, StandardCharsets.UTF_8);
    }

    static void writeF07DrdaServerPhaseAggregate(
            BenchmarkConfiguration options, List<DrdaServerPhaseEvidence> evidence) throws IOException {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (DrdaServerPhaseEvidence value : evidence) {
            String key = value.target() + '|' + value.run();
            long[] total = totals.computeIfAbsent(key, ignored -> new long[16]);
            total[0]++;
            total[1] += value.openQueries();
            total[2] += value.continueQueries();
            total[3] += value.openRows() + value.continueRows();
            total[4] += value.openParseNanos();
            total[5] += value.openExecuteNanos();
            total[6] += value.openMetadataNanos();
            total[7] += value.openQueryDataNanos();
            total[8] += value.openSendNanos();
            total[9] += value.openTotalNanos();
            total[10] += value.continueParseNanos();
            total[11] += value.continueMetadataNanos();
            total[12] += value.continueQueryDataNanos();
            total[13] += value.continueSendNanos();
            total[14] += value.continueTotalNanos();
        }

        String header = "target,run,connections,openQueries,continueQueries,resultRows,"
                + "averageOpenTotalMicros,averageOpenParseMicros,averageOpenExecuteMicros,"
                + "averageOpenMetadataMicros,averageOpenQueryDataMicros,averageOpenSendMicros,"
                + "openParseShare,openExecuteShare,openMetadataShare,openQueryDataShare,openSendShare,"
                + "averageContinueTotalMicros,averageContinueParseMicros,averageContinueMetadataMicros,"
                + "averageContinueQueryDataMicros,averageContinueSendMicros,"
                + "continueParseShare,continueMetadataShare,continueQueryDataShare,continueSendShare";
        StringBuilder csv = new StringBuilder(header).append('\n');
        StringBuilder text = new StringBuilder()
                .append("DelosDB F07 DRDA server-phase aggregate\n")
                .append("======================================\n\n")
                .append("Workload: SORT_FULL\n")
                .append("Clients: ").append(options.clients()).append('\n')
                .append("Rows: ").append(options.rows()).append('\n')
                .append("Capture: query ordinals ")
                .append(drdaServerPhaseSkipOpenQueries() + 1)
                .append('-')
                .append(drdaServerPhaseSkipOpenQueries() + drdaServerPhaseCaptureOpenQueries())
                .append(" per client connection\n\n");

        for (Target target : DRDA_SERVER_PHASE_EVIDENCE_TARGETS) {
            for (int run = 1; run <= options.runs(); run++) {
                String key = target.id() + '|' + run;
                long[] total = totals.get(key);
                if (total == null) {
                    continue;
                }
                double openQueries = total[1];
                double continueQueries = total[2];
                double openTotal = total[9];
                double continueTotal = total[14];
                csv.append(target.id()).append(',').append(run).append(',')
                        .append(total[0]).append(',').append(total[1]).append(',')
                        .append(total[2]).append(',').append(total[3]).append(',')
                        .append(format(total[9] / (openQueries * 1_000.0))).append(',')
                        .append(format(total[4] / (openQueries * 1_000.0))).append(',')
                        .append(format(total[5] / (openQueries * 1_000.0))).append(',')
                        .append(format(total[6] / (openQueries * 1_000.0))).append(',')
                        .append(format(total[7] / (openQueries * 1_000.0))).append(',')
                        .append(format(total[8] / (openQueries * 1_000.0))).append(',')
                        .append(format((double) total[4] / openTotal)).append(',')
                        .append(format((double) total[5] / openTotal)).append(',')
                        .append(format((double) total[6] / openTotal)).append(',')
                        .append(format((double) total[7] / openTotal)).append(',')
                        .append(format((double) total[8] / openTotal)).append(',')
                        .append(format(total[14] / (continueQueries * 1_000.0))).append(',')
                        .append(format(total[10] / (continueQueries * 1_000.0))).append(',')
                        .append(format(total[11] / (continueQueries * 1_000.0))).append(',')
                        .append(format(total[12] / (continueQueries * 1_000.0))).append(',')
                        .append(format(total[13] / (continueQueries * 1_000.0))).append(',')
                        .append(format((double) total[10] / continueTotal)).append(',')
                        .append(format((double) total[11] / continueTotal)).append(',')
                        .append(format((double) total[12] / continueTotal)).append(',')
                        .append(format((double) total[13] / continueTotal)).append('\n');
                text.append(target.id()).append(" run=").append(run)
                        .append(" openTotalUs=").append(format(total[9] / (openQueries * 1_000.0)))
                        .append(" executeUs=").append(format(total[5] / (openQueries * 1_000.0)))
                        .append(" qrydtaUs=").append(format(total[7] / (openQueries * 1_000.0)))
                        .append(" sendUs=").append(format(total[8] / (openQueries * 1_000.0)))
                        .append(" continueTotalUs=").append(format(total[14] / (continueQueries * 1_000.0)))
                        .append(" continueQrydtaUs=").append(format(total[12] / (continueQueries * 1_000.0)))
                        .append(" continueSendUs=").append(format(total[13] / (continueQueries * 1_000.0)))
                        .append('\n');
            }
        }
        Files.writeString(options.reportDirectory().resolve("f07-drda-server-phase-aggregate.csv"),
                csv, StandardCharsets.UTF_8);
        Files.writeString(options.reportDirectory().resolve("f07-drda-server-phase-aggregate.txt"),
                text, StandardCharsets.UTF_8);
    }

    static void validateDrdaProtocolEvidence(
            BenchmarkConfiguration options,
            List<DrdaProtocolEvidence> evidence) {
        if (!options.targetValues().equals(DRDA_PROTOCOL_EVIDENCE_TARGETS)) {
            throw new IllegalStateException(
                    "DRDA protocol evidence requires the shared-client DRDA target set: "
                            + options.targetValues());
        }
        int expected = options.targetValues().size() * options.runs() * options.rowCounts().size()
                * specsPerRun(options);
        if (evidence.size() != expected) {
            throw new IllegalStateException(
                    "DRDA protocol evidence count mismatch: expected=" + expected
                            + ", actual=" + evidence.size());
        }
        for (DrdaProtocolEvidence value : evidence) {
            if (value.measuredPrepareCommands() != 0L) {
                throw new IllegalStateException(
                        "Prepared statement was re-prepared inside measured DRDA interval: " + value);
            }
            if (value.setupPrepareCommands() < value.clients()) {
                throw new IllegalStateException(
                        "Expected at least one setup prepare per DRDA benchmark client: " + value);
            }
            if (value.requestFlushes() <= 0L || value.requestBytes() <= 0L
                    || value.replySocketReads() <= 0L || value.replyBytes() <= 0L) {
                throw new IllegalStateException("Missing authoritative DRDA wire evidence: " + value);
            }
            if (value.commitCommands() != value.measuredTransactions()) {
                throw new IllegalStateException(
                        "DRDA RDBCMM count does not match measured transactions: " + value);
            }
            if (value.measuredElapsedNanos() <= 0L || value.totalTimedFlowNanos() <= 0L) {
                throw new IllegalStateException("Missing DRDA latency evidence: " + value);
            }
            if (value.commitCommands() > 0L && value.commitFlowNanos() <= 0L) {
                throw new IllegalStateException("Missing DRDA commit-flow latency evidence: " + value);
            }
            if (value.clients() == 1 && value.totalTimedFlowNanos() > value.measuredElapsedNanos()) {
                throw new IllegalStateException(
                        "Single-client DRDA timed flows exceed measured interval: " + value);
            }
            if (value.workload().isRangeScan() || value.workload().isPrimaryKeyRead() || value.workload().isValues()) {
                if (value.openQueryCommands() != value.measuredOperations()) {
                    throw new IllegalStateException(
                            "DRDA OPNQRY count does not match measured query operations: " + value);
                }
                if (value.openQueryFlowNanos() <= 0L) {
                    throw new IllegalStateException("Missing DRDA open-query latency evidence: " + value);
                }
                if (value.continueQueryCommands() > 0L && value.continueQueryFlowNanos() <= 0L) {
                    throw new IllegalStateException("Missing DRDA continue-query latency evidence: " + value);
                }
                if (value.fetchRequests() < value.openQueryCommands() || value.queryDataBlocks() <= 0L) {
                    throw new IllegalStateException("DRDA fetch/query-data count is inconsistent: " + value);
                }
            } else if (value.workload().isUpdate()) {
                if (value.executeCommands() != value.measuredOperations()) {
                    throw new IllegalStateException(
                            "DRDA EXCSQLSTT count does not match measured update operations: " + value);
                }
                if (value.executeFlowNanos() <= 0L) {
                    throw new IllegalStateException("Missing DRDA execute latency evidence: " + value);
                }
            }
        }
    }

    static void writeDrdaProtocolEvidence(
            BenchmarkConfiguration options,
            List<DrdaProtocolEvidence> evidence) throws IOException {
        StringBuilder csv = new StringBuilder(DRDA_PROTOCOL_CSV_HEADER).append('\n');
        for (DrdaProtocolEvidence value : evidence) {
            csv.append(value.csv()).append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("drda-protocol-evidence.csv"),
                csv.toString(), StandardCharsets.UTF_8);

        StringBuilder summary = new StringBuilder();
        summary.append("DelosDB Phase-1 DRDA protocol evidence\n")
                .append("======================================\n\n")
                .append("Authority: Delos network-client DRDA command builders and socket boundaries.\n")
                .append("Client: one Delos network client implementation is used for all three DRDA servers.\n")
                .append("Targets: delos_heap_drda, delos_mvcc_drda, upstream_derby_drda.\n")
                .append("Measured interval excludes Phase-0 SQL-oracle and fixture restore traffic.\n")
                .append("Evidence rows: ").append(evidence.size()).append("\n\n");
        for (DrdaProtocolEvidence value : evidence) {
            summary.append(value.target()).append(' ')
                    .append(value.workload()).append(" c=").append(value.clients())
                    .append(" w=").append(value.operationsPerTransaction())
                    .append(" run=").append(value.run())
                    .append(" flows/op=").append(format(value.requestFlushesPerOperation()))
                    .append(" flows/tx=").append(format(value.requestFlushesPerTransaction()))
                    .append(" fetches=").append(value.fetchRequests())
                    .append(" rows/fetch=").append(format(value.rowsPerFetchRequest()))
                    .append(" replyBytes/row=").append(format(value.replyBytesPerResultRow()))
                    .append(" openUs=").append(format(value.averageOpenQueryFlowMicros()))
                    .append(" continueUs=").append(format(value.averageContinueQueryFlowMicros()))
                    .append(" executeUs=").append(format(value.averageExecuteFlowMicros()))
                    .append(" commitUs=").append(format(value.averageCommitFlowMicros()))
                    .append(" timedFlowShare=").append(format(value.timedFlowShareOfMeasuredElapsed()))
                    .append(" commits=").append(value.commitCommands())
                    .append(" setupPrepares=").append(value.setupPrepareCommands())
                    .append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("drda-protocol-evidence.txt"),
                summary.toString(), StandardCharsets.UTF_8);
    }

    static List<Row> loadRows(BenchmarkConfiguration options) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (Target target : options.targetValues()) {
                Path file = options.reportDirectory().resolve("workers")
                        .resolve(target.id() + "-run-" + run + ".csv");
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty() || !CSV_HEADER.equals(lines.getFirst())) {
                    throw new IllegalStateException("Unexpected concurrency CSV header: " + file);
                }
                for (int index = 1; index < lines.size(); index++) {
                    if (!lines.get(index).isBlank()) {
                        rows.add(Row.parse(lines.get(index)));
                    }
                }
            }
        }
        rows.sort(Comparator.comparingInt(Row::rowCount)
                .thenComparing(Row::workload)
                .thenComparingInt(Row::operationsPerTransaction)
                .thenComparingInt(Row::clients)
                .thenComparing(Row::target)
                .thenComparingInt(Row::run));
        return List.copyOf(rows);
    }

    static void validateRows(BenchmarkConfiguration options, List<Row> rows) {
        int expected = options.targetValues().size() * options.runs() * options.rowCounts().size()
                * specsPerRun(options);
        if (rows.size() != expected) {
            throw new IllegalStateException(
                    "Concurrency measurement count mismatch: expected=" + expected + ", actual=" + rows.size());
        }
        Map<ShapeKey, Long> semantics = new HashMap<>();
        for (Row row : rows) {
            ShapeKey key = row.shape();
            Long prior = semantics.putIfAbsent(key, row.semanticFingerprint());
            if (prior != null && prior.longValue() != row.semanticFingerprint()) {
                throw new IllegalStateException("Cross-engine concurrency semantic mismatch for " + key
                        + ": expected=" + prior + ", actual=" + row.semanticFingerprint()
                        + ", target=" + row.target() + ", run=" + row.run());
            }
        }
    }

    static void writeMergedCsv(BenchmarkConfiguration options, List<Row> rows) throws IOException {
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Row row : rows) {
            out.append(row.csv()).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-results.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static void writeRatioCsv(BenchmarkConfiguration options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(options, rows);
        StringBuilder out;
        if (mutationSchemaAttributionEnabled()) {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosHeapDrdaMedianTps,"
                            + "delosMvccDrdaMedianTps,upstreamDerbyDrdaMedianTps,"
                            + "delosMvccToHeap,delosMvccToDerby,delosHeapToDerby\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                EnumMap<Target, Double> values = entry.getValue();
                double heap = require(values, Target.DELOS_HEAP_DRDA, key);
                double mvcc = require(values, Target.DELOS_MVCC_DRDA, key);
                double derby = require(values, Target.UPSTREAM_DERBY_DRDA, key);
                out.append(key.csv()).append(',')
                        .append(format(heap)).append(',').append(format(mvcc)).append(',')
                        .append(format(derby)).append(',')
                        .append(format(mvcc / heap)).append(',')
                        .append(format(mvcc / derby)).append(',')
                        .append(format(heap / derby)).append('\n');
            }
        } else if (options.targetValues().equals(EMBEDDED_REFERENCE_CANARY_TARGETS)
                || options.targetValues().equals(SERVER_REFERENCE_CANARY_TARGETS)
                || options.targetValues().equals(HOST_RECOVERY_DIAGNOSTIC_TARGETS)
                || options.targetValues().equals(DRDA_PROTOCOL_EVIDENCE_TARGETS)
                || options.targetValues().equals(DRDA_SERVER_PHASE_EVIDENCE_TARGETS)
                || options.targetValues().equals(CURRENT_BASELINE_EMBEDDED_TARGETS)
                || options.targetValues().equals(CURRENT_BASELINE_SERVER_TARGETS)
                || options.targetValues().equals(GEN2_C3_POSTGRESQL_UPDATE_TARGETS)) {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,target,medianOperationsPerSecond\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                for (Target target : options.targetValues()) {
                    out.append(key.csv()).append(',')
                            .append(target.id()).append(',')
                            .append(format(require(entry.getValue(), target, key))).append('\n');
                }
            }
        } else if (options.containerMode()) {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosHeapDrdaMedianTps,"
                            + "delosMvccDrdaMedianTps,upstreamDerbyDrdaMedianTps,h2ServerMedianTps,"
                            + "postgresqlMedianTps,mariadbMedianTps,"
                            + "delosHeapToDerby,delosMvccToDerby,"
                            + "delosHeapToH2Server,delosMvccToH2Server,"
                            + "delosHeapToPostgresql,delosMvccToPostgresql,"
                            + "delosHeapToMariadb,delosMvccToMariadb\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                EnumMap<Target, Double> values = entry.getValue();
                double heap = require(values, Target.DELOS_HEAP_DRDA, key);
                double mvcc = require(values, Target.DELOS_MVCC_DRDA, key);
                double derby = require(values, Target.UPSTREAM_DERBY_DRDA, key);
                double h2Server = require(values, Target.H2_SERVER, key);
                double postgres = require(values, Target.POSTGRESQL, key);
                double mariadb = require(values, Target.MARIADB, key);
                out.append(key.csv()).append(',')
                        .append(format(heap)).append(',').append(format(mvcc)).append(',')
                        .append(format(derby)).append(',').append(format(h2Server)).append(',')
                        .append(format(postgres)).append(',').append(format(mariadb)).append(',')
                        .append(format(heap / derby)).append(',').append(format(mvcc / derby)).append(',')
                        .append(format(heap / h2Server)).append(',').append(format(mvcc / h2Server)).append(',')
                        .append(format(heap / postgres)).append(',').append(format(mvcc / postgres)).append(',')
                        .append(format(heap / mariadb)).append(',').append(format(mvcc / mariadb)).append('\n');
            }
        } else if (options.targetValues().equals(MVCC_ONLY_DIAGNOSTIC_TARGETS)) {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosMvccMedianTps\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                double mvcc = require(entry.getValue(), Target.DELOS_MVCC, key);
                out.append(key.csv()).append(',').append(format(mvcc)).append('\n');
            }
        } else if (options.targetValues().equals(READ_DECOMPOSITION_TARGETS)) {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosHeapMedianTps,"
                            + "upstreamDerbyMedianTps,h2MedianTps,delosHeapToDerby,delosHeapToH2\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                EnumMap<Target, Double> values = entry.getValue();
                double heap = require(values, Target.DELOS_HEAP, key);
                double derby = require(values, Target.UPSTREAM_DERBY, key);
                double h2 = require(values, Target.H2, key);
                out.append(key.csv()).append(',')
                        .append(format(heap)).append(',').append(format(derby)).append(',')
                        .append(format(h2)).append(',')
                        .append(format(heap / derby)).append(',').append(format(heap / h2)).append('\n');
            }
        } else if (options.targetValues().equals(RANGE_SCAN_JFR_TARGETS)) {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosHeapMedianTps,"
                            + "delosMvccMedianTps,upstreamDerbyMedianTps,"
                            + "delosMvccToHeap,delosHeapToDerby,delosMvccToDerby\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                EnumMap<Target, Double> values = entry.getValue();
                double heap = require(values, Target.DELOS_HEAP, key);
                double mvcc = require(values, Target.DELOS_MVCC, key);
                double derby = require(values, Target.UPSTREAM_DERBY, key);
                out.append(key.csv()).append(',')
                        .append(format(heap)).append(',').append(format(mvcc)).append(',')
                        .append(format(derby)).append(',')
                        .append(format(mvcc / heap)).append(',')
                        .append(format(heap / derby)).append(',')
                        .append(format(mvcc / derby)).append('\n');
            }
        } else if (options.targetValues().equals(RANGE_BULK_FETCH_TARGETS)) {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosHeapMedianTps,"
                            + "upstreamDerbyMedianTps,delosHeapToDerby\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                EnumMap<Target, Double> values = entry.getValue();
                double heap = require(values, Target.DELOS_HEAP, key);
                double derby = require(values, Target.UPSTREAM_DERBY, key);
                out.append(key.csv()).append(',')
                        .append(format(heap)).append(',').append(format(derby)).append(',')
                        .append(format(heap / derby)).append('\n');
            }
        } else {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosHeapMedianTps,delosMvccMedianTps,"
                            + "upstreamDerbyMedianTps,h2MedianTps,nativeSqliteJdbcMedianTps,"
                            + "delosHeapToDerby,delosMvccToDerby,delosHeapToH2,delosMvccToH2,"
                            + "delosHeapToNativeSqliteJdbc,delosMvccToNativeSqliteJdbc\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                EnumMap<Target, Double> values = entry.getValue();
                double heap = require(values, Target.DELOS_HEAP, key);
                double mvcc = require(values, Target.DELOS_MVCC, key);
                double derby = require(values, Target.UPSTREAM_DERBY, key);
                double h2 = require(values, Target.H2, key);
                double sqlite = require(values, Target.SQLITE, key);
                out.append(key.csv()).append(',')
                        .append(format(heap)).append(',').append(format(mvcc)).append(',')
                        .append(format(derby)).append(',').append(format(h2)).append(',')
                        .append(format(sqlite)).append(',')
                        .append(format(heap / derby)).append(',').append(format(mvcc / derby)).append(',')
                        .append(format(heap / h2)).append(',').append(format(mvcc / h2)).append(',')
                        .append(format(heap / sqlite)).append(',').append(format(mvcc / sqlite)).append('\n');
            }
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-ratios.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static void writeScalingCsv(BenchmarkConfiguration options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(options, rows);
        if (!options.clientValues().contains(1)) {
            Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-scaling.csv"),
                    "rowCount,workload,clients,operationsPerTransaction,target,medianTransactionsPerSecond,"
                            + "medianOperationsPerSecond,speedupFromOneClient,parallelEfficiency\n",
                    StandardCharsets.UTF_8);
            return;
        }
        Map<BaselineKey, EnumMap<Target, Double>> baselines = new HashMap<>();
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            if (entry.getKey().clients() == 1) {
                baselines.put(entry.getKey().baselineKey(), entry.getValue());
            }
        }
        StringBuilder out = new StringBuilder(
                "rowCount,workload,clients,operationsPerTransaction,target,medianTransactionsPerSecond,"
                        + "medianOperationsPerSecond,speedupFromOneClient,parallelEfficiency\n");
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            ShapeKey key = entry.getKey();
            EnumMap<Target, Double> baseline = baselines.get(key.baselineKey());
            if (baseline == null) {
                throw new IllegalStateException("Missing one-client concurrency baseline for " + key);
            }
            for (Target target : options.targetValues()) {
                double tps = require(entry.getValue(), target, key);
                double one = require(baseline, target, key);
                double speedup = tps / one;
                out.append(key.csv()).append(',').append(target.id()).append(',')
                        .append(format(tps)).append(',')
                        .append(format(tps * key.operationsPerTransaction())).append(',')
                        .append(format(speedup)).append(',')
                        .append(format(speedup / key.clients())).append('\n');
            }
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-scaling.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static void writeDispersionCsv(BenchmarkConfiguration options, List<Row> rows) throws IOException {
        Map<ShapeTargetKey, List<Double>> values = new HashMap<>();
        for (Row row : rows) {
            values.computeIfAbsent(new ShapeTargetKey(row.shape(), Target.parse(row.target())),
                    ignored -> new ArrayList<>()).add(row.transactionsPerSecond());
        }
        List<ShapeTargetKey> keys = new ArrayList<>(values.keySet());
        keys.sort(Comparator.comparing((ShapeTargetKey key) -> key.shape().rowCount())
                .thenComparing(key -> key.shape().workload())
                .thenComparingInt(key -> key.shape().operationsPerTransaction())
                .thenComparingInt(key -> key.shape().clients())
                .thenComparing(ShapeTargetKey::target));
        StringBuilder out = new StringBuilder(
                "rowCount,workload,clients,operationsPerTransaction,target,runs,medianTps,q1Tps,q3Tps,"
                        + "iqrTps,madTps,minTps,maxTps,medianOpsPerSecond,q1OpsPerSecond,q3OpsPerSecond,"
                        + "iqrOpsPerSecond,madOpsPerSecond,minOpsPerSecond,maxOpsPerSecond,"
                        + "iqrToMedian,madToMedian,maxToMin\n");
        for (ShapeTargetKey key : keys) {
            Distribution distribution = distribution(values.get(key));
            int width = key.shape().operationsPerTransaction();
            out.append(key.shape().csv()).append(',').append(key.target().id()).append(',')
                    .append(distribution.count()).append(',')
                    .append(format(distribution.median())).append(',')
                    .append(format(distribution.q1())).append(',').append(format(distribution.q3())).append(',')
                    .append(format(distribution.iqr())).append(',').append(format(distribution.mad())).append(',')
                    .append(format(distribution.min())).append(',').append(format(distribution.max())).append(',')
                    .append(format(distribution.median() * width)).append(',')
                    .append(format(distribution.q1() * width)).append(',')
                    .append(format(distribution.q3() * width)).append(',')
                    .append(format(distribution.iqr() * width)).append(',')
                    .append(format(distribution.mad() * width)).append(',')
                    .append(format(distribution.min() * width)).append(',')
                    .append(format(distribution.max() * width)).append(',')
                    .append(format(distribution.iqr() / distribution.median())).append(',')
                    .append(format(distribution.mad() / distribution.median())).append(',')
                    .append(format(distribution.max() / distribution.min())).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-dispersion.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static void writeRangeScanCsv(BenchmarkConfiguration options, List<Row> rows) throws IOException {
        boolean requested = options.workloadValues().stream().anyMatch(Workload::isRangeScan);
        if (!requested) {
            return;
        }
        Map<ShapeTargetKey, List<Double>> values = new HashMap<>();
        for (Row row : rows) {
            if (!row.workload().isRangeScan()) {
                continue;
            }
            values.computeIfAbsent(
                    new ShapeTargetKey(row.shape(), Target.parse(row.target())),
                    ignored -> new ArrayList<>()).add(row.operationsPerSecond());
        }
        List<ShapeTargetKey> keys = new ArrayList<>(values.keySet());
        keys.sort(Comparator.comparing((ShapeTargetKey key) -> key.shape().rowCount())
                .thenComparing(key -> key.shape().workload())
                .thenComparingInt(key -> key.shape().clients())
                .thenComparing(ShapeTargetKey::target));
        StringBuilder out = new StringBuilder(
                "rowCount,workload,rangeRows,clients,operationsPerTransaction,target,runs,"
                        + "medianQueriesPerSecond,medianRowsPerSecond,q1RowsPerSecond,q3RowsPerSecond,"
                        + "iqrRowsPerSecond,madRowsPerSecond,minRowsPerSecond,maxRowsPerSecond,"
                        + "iqrToMedian,madToMedian\n");
        for (ShapeTargetKey key : keys) {
            Distribution distribution = distribution(values.get(key));
            int rangeRows = key.shape().workload().rangeRows(key.shape().rowCount());
            out.append(key.shape().rowCount()).append(',')
                    .append(key.shape().workload()).append(',')
                    .append(rangeRows).append(',')
                    .append(key.shape().clients()).append(',')
                    .append(key.shape().operationsPerTransaction()).append(',')
                    .append(key.target().id()).append(',')
                    .append(distribution.count()).append(',')
                    .append(format(distribution.median())).append(',')
                    .append(format(distribution.median() * rangeRows)).append(',')
                    .append(format(distribution.q1() * rangeRows)).append(',')
                    .append(format(distribution.q3() * rangeRows)).append(',')
                    .append(format(distribution.iqr() * rangeRows)).append(',')
                    .append(format(distribution.mad() * rangeRows)).append(',')
                    .append(format(distribution.min() * rangeRows)).append(',')
                    .append(format(distribution.max() * rangeRows)).append(',')
                    .append(format(distribution.iqr() / distribution.median())).append(',')
                    .append(format(distribution.mad() / distribution.median())).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("range-scan-throughput.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static void writeCapabilityCsv(BenchmarkConfiguration options) throws IOException {
        StringBuilder out = new StringBuilder("target,workload,supported,executionModel,notes\n");
        for (Target target : options.targetValues()) {
            for (Workload workload : options.workloadValues()) {
                String executionModel;
                String notes;
                if (target == Target.SQLITE) {
                    executionModel = "native SQLite through JDBC";
                    notes = workload.isReadOnly()
                            ? "WAL mode; native/JDBC boundary retained as part of product result"
                            : "WAL mode; single-writer architecture; SQLITE_BUSY/SQLITE_LOCKED retries counted";
                } else if (target.isContainer()) {
                    executionModel = "client/server JDBC";
                    notes = "TCP/process boundary retained as part of product result";
                } else {
                    executionModel = "embedded JVM";
                    notes = "in-process JDBC engine";
                }
                out.append(target.id()).append(',').append(workload).append(",true,")
                        .append(csvSafe(executionModel)).append(',').append(csvSafe(notes)).append('\n');
            }
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-capabilities.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static void writeSummary(BenchmarkConfiguration options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(options, rows);
        StringBuilder out = new StringBuilder();
        out.append(options.containerMode()
                        ? "DelosDB JDBC server-container concurrency comparison\n"
                        : "DelosDB JDBC embedded concurrency comparison\n")
                .append("Targets: ").append(targetIds(options.targetValues())).append('\n')
                .append("Rows: ").append(options.rows()).append('\n')
                .append("Clients: ").append(options.clients()).append('\n')
                .append("Generic operations-per-transaction widths: ").append(options.widths()).append('\n')
                .append("Fixed diagnostic workloads carry their operation count in the workload definition.\n")
                .append("Default transactions per client/interval: ").append(options.transactionsPerClient())
                .append('\n')
                .append("Fixed-workload operation budget per client/interval: ")
                .append(options.fixedWorkloadOperationBudgetPerClient()).append('\n')
                .append("Range-scan target rows per client/interval: ")
                .append(options.rangeScanTargetRowsPerClient()).append('\n')
                .append("Range-scan query bounds per client/interval: ")
                .append(options.rangeScanMinQueriesPerClient()).append("..").append(
                        options.rangeScanMaxQueriesPerClient()).append('\n')
                .append("H2 range fetch size override: ").append(options.h2RangeFetchSize())
                .append(" (0=driver default)\n")
                .append("Minimum warmup seconds per run: ").append(options.minimumWarmupSeconds()).append('\n')
                .append("Maximum warmup intervals per run: ").append(options.maximumWarmupIterations()).append('\n')
                .append("Minimum measured seconds per run: ").append(options.minimumMeasuredSeconds()).append('\n')
                .append("Maximum measured intervals per run: ").append(options.maximumMeasuredIterations()).append('\n')
                .append("Workloads: ").append(options.workloadValues()).append('\n')
                .append("INSERT table shape: ").append(insertTableShape()).append('\n')
                .append("Gen2-A1 throughput sentinel: ")
                .append(gen2A1ThroughputSentinelEnabled()).append('\n')
                .append("MVCC Gen2-A1 server enabled: ")
                .append(mvccGen2A1ServerEnabled()).append('\n')
                .append("Gen2-B throughput sentinel: ")
                .append(gen2BThroughputSentinelEnabled()).append('\n')
                .append("MVCC Gen2-B server enabled: ")
                .append(mvccGen2BServerEnabled()).append('\n')
                .append("MVCC Gen2 transaction-status visibility server enabled: ")
                .append(mvccGen2TransactionStatusVisibilityServerEnabled()).append('\n')
                .append("Benchmark contract: ")
                .append(benchmarkContractId().isEmpty() ? "none" : benchmarkContractId()).append('\n')
                .append("F08 transaction-status cross-engine diagnostic: ")
                .append(f08TransactionStatusCrossEngineEnabled()).append('\n')
                .append("F08 fixed-cost client-scaling diagnostic: ")
                .append(f08FixedCostClientScalingEnabled()).append('\n')
                .append("F08 contention-scaling slice diagnostic: ")
                .append(f08ContentionScalingSliceEnabled()).append('\n')
                .append("F08 multi-row INSERT control: ")
                .append(f08MultiRowInsertControlEnabled()).append('\n')
                .append("RawStore log buffer size server override: ")
                .append(rawStoreLogBufferSizeServerOverride().isEmpty()
                        ? "default" : rawStoreLogBufferSizeServerOverride()).append('\n')
                .append("RawStore durability test no-sync server enabled: ")
                .append(rawStoreDurabilityTestNoSyncServerEnabled()).append('\n')
                .append("B-tree INSERT root-routing snapshot server enabled: ")
                .append(btreeInsertRootRoutingSnapshotServerEnabled()).append('\n')
                .append("B-tree INSERT branch-routing snapshot server enabled: ")
                .append(btreeInsertBranchRoutingSnapshotServerEnabled()).append('\n')
                .append("MVCC Gen2-C1 server enabled: ")
                .append(mvccGen2C1ServerEnabled()).append('\n')
                .append("Gen2-C3 UPDATE throughput sentinel: ")
                .append(gen2C3UpdateThroughputSentinelEnabled()).append('\n')
                .append("MVCC Gen2-C3 UPDATE server enabled: ")
                .append(mvccGen2C3UpdateServerEnabled()).append('\n')
                .append("MVCC Gen2-C3 read server enabled: ")
                .append(mvccGen2C3ReadServerEnabled()).append('\n')
                .append("Post-modernization mutation checkpoint enabled: ")
                .append(postModernizationMutationCheckpointEnabled()).append('\n')
                .append("F02 scale-surface diagnostic: ")
                .append(f02ScaleSurfaceDiagnosticEnabled()).append('\n')
                .append("F02 embedded concurrency diagnostic: ")
                .append(f02EmbeddedConcurrencyDiagnosticEnabled()).append('\n')
                .append("F04 DRDA server-phase diagnostic: ")
                .append(f04DrdaServerPhaseDiagnosticEnabled()).append('\n')
                .append("F07 DRDA server-phase diagnostic: ")
                .append(f07DrdaServerPhaseDiagnosticEnabled()).append('\n')
                .append("MVCC Gen2 projected current read enabled: ")
                .append(mvccGen2ProjectedCurrentReadEnabled()).append('\n')
                .append("MVCC physical scan cost enabled: ")
                .append(mvccPhysicalScanCostEnabled()).append('\n')
                .append("MVCC physical RowLocation cost enabled: ")
                .append(mvccPhysicalRowLocationCostEnabled()).append('\n')
                .append("MVCC single-pass CURRENT scan enabled: ")
                .append(mvccGen2SinglePassCurrentScanEnabled()).append('\n')
                .append("MVCC multi-join statistics refresh enabled: ")
                .append(mvccRefreshMultiJoinStatisticsEnabled()).append('\n')
                .append("Simple-join statistics refresh enabled: ")
                .append(refreshSimpleJoinStatisticsEnabled()).append('\n')
                .append("F04 server plan cost matrix enabled: ")
                .append(f04ServerPlanCostMatrixEnabled()).append('\n')
                .append("Fresh realistic transaction fitness: ")
                .append(freshRealisticTransactionFitnessEnabled()).append('\n')
                .append("Each client owns one JDBC connection and reuses prepared statements where applicable.\n");
        List<Workload> requestedWorkloads = options.workloadValues();
        if (requestedWorkloads.contains(Workload.PRIMARY_KEY_READ_HOT)) {
            out.append("PRIMARY_KEY_READ_HOT: every client repeatedly reads id=1.\n");
        }
        if (requestedWorkloads.contains(Workload.PRIMARY_KEY_READ_DISJOINT)) {
            out.append("PRIMARY_KEY_READ_DISJOINT: each client repeatedly reads one evenly spaced private id.\n");
        }
        if (requestedWorkloads.contains(Workload.PRIMARY_KEY_READ_RANDOM)) {
            out.append("PRIMARY_KEY_READ_RANDOM: each client replays a deterministic precomputed key stream "
                    + "across the fixture; random generation is outside the timed interval.\n");
        }
        if (requestedWorkloads.stream().anyMatch(Workload::isRangeScan)) {
            out.append("RANGE_SCAN_*: each client repeatedly consumes an ordered primary-key range; "
                    + "range checks and result consumption are inside the timed interval.\n")
                    .append("Range-scan query counts adapt to a returned-row budget with configured min/max bounds.\n");
        }
        if (requestedWorkloads.stream().anyMatch(workload -> workload == Workload.EMPTY_TRANSACTION
                || workload == Workload.VALUES_1 || workload == Workload.VALUES_10
                || workload == Workload.PK_READ_1_RC || workload == Workload.PK_READ_10_RC
                || workload == Workload.PK_READ_100_RC || workload == Workload.PK_READ_10_RU)) {
            out.append("Read-stack decomposition workloads: EMPTY_TRANSACTION, VALUES_1, VALUES_10, "
                    + "PK_READ_1_RC, PK_READ_10_RC, PK_READ_100_RC, PK_READ_10_RU.\n")
                    .append("Decomposition PK reads are DISJOINT: each client repeatedly reads one evenly spaced "
                            + "private id.\n")
                    .append("PK_READ_10_RU changes only isolation from READ_COMMITTED to READ_UNCOMMITTED; "
                            + "its causal interpretation is Derby/Delos-specific.\n");
        }
        if (requestedWorkloads.contains(Workload.SAME_TABLE_DISJOINT)
                || requestedWorkloads.contains(Workload.PRIVATE_TABLE_DISJOINT)) {
            out.append("Locality workloads: SAME_TABLE_DISJOINT and PRIVATE_TABLE_DISJOINT, "
                    + "both READ_COMMITTED with 100 PK reads per transaction.\n")
                    .append("Locality comparison prepares one full fixture table per client for BOTH shapes so "
                            + "database size, fixture count, and cache population pressure are matched.\n")
                    .append("SAME_TABLE_DISJOINT routes every client to the first table; "
                            + "PRIVATE_TABLE_DISJOINT routes each client to its own heap conglomerate, PK B-tree, "
                            + "root/internal pages, and data pages.\n");
        }
        if (requestedWorkloads.contains(Workload.DISJOINT_INDEXED_UPDATE)) {
            out.append("Disjoint-update client rows are evenly spread across the fixture.\n");
        }
        out.append("Timed interval: synchronized client execution through final commit.\n")
                .append("operationsPerSecond is measuredOperations / shared wall-clock interval.\n")
                .append("inverseThroughputNanosPerTransaction is elapsedNanos / aggregate completed transactions; "
                        + "it is NOT observed client transaction latency.\n")
                .append(freshRealisticTransactionFitnessEnabled()
                        ? "Semantic verification outside timed interval: true\n"
                                + "Semantic restoration outside timed interval: false (fresh disposable matrix cell)\n"
                        : "Semantic verification/restoration outside timed interval: true\n")
                .append(options.containerMode()
                        ? "Fresh database container per target/run; fresh table per matrix cell: true\n"
                        : "Fresh database per target/run/matrix cell: true\n")
                .append("Target and matrix order orthogonalized across four-run blocks: true\n")
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Minimum warmup seconds per run: ").append(options.minimumWarmupSeconds()).append('\n')
                .append("Maximum warmup intervals per run: ").append(options.maximumWarmupIterations()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append('\n')
                .append("Capability matrix: cross-engine-concurrency-capabilities.csv\n")
                .append(options.targetValues().contains(Target.SQLITE)
                        ? "SQLite: native SQLite through Xerial JDBC; product/workload reference only, "
                                + "not a JVM architectural-equivalence threshold.\n"
                        : "")
                .append(options.targetValues().contains(Target.SQLITE)
                        ? "SQLite mode: persistent file, WAL, synchronous=FULL, busy_timeout=3000 ms, "
                                + "JDBC isolation requested as READ_COMMITTED; BUSY/LOCKED retries are "
                                + "reported as retryable conflict retries.\n"
                        : "")
                .append('\n');
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            ShapeKey key = entry.getKey();
            out.append(String.format(Locale.ROOT, "%7d %-25s clients=%-2d ops/tx=%-2d",
                    key.rowCount(), key.workload(), key.clients(), key.operationsPerTransaction()));
            for (Target target : options.targetValues()) {
                double tps = require(entry.getValue(), target, key);
                out.append(String.format(Locale.ROOT, " %s=%11.2f tx/s (%11.2f op/s)",
                        target.id(), tps, tps * key.operationsPerTransaction()));
            }
            out.append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-summary.txt"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static Map<ShapeKey, EnumMap<Target, Double>> medianThroughput(BenchmarkConfiguration options, List<Row> rows) {
        Map<ShapeTargetKey, List<Double>> grouped = new HashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(new ShapeTargetKey(row.shape(), Target.parse(row.target())),
                    ignored -> new ArrayList<>()).add(row.transactionsPerSecond());
        }
        List<ShapeKey> shapes = grouped.keySet().stream().map(ShapeTargetKey::shape).distinct().sorted(
                Comparator.comparingInt(ShapeKey::rowCount).thenComparing(ShapeKey::workload)
                        .thenComparingInt(ShapeKey::operationsPerTransaction)
                        .thenComparingInt(ShapeKey::clients)).toList();
        Map<ShapeKey, EnumMap<Target, Double>> result = new java.util.LinkedHashMap<>();
        for (ShapeKey shape : shapes) {
            EnumMap<Target, Double> values = new EnumMap<>(Target.class);
            for (Target target : options.targetValues()) {
                List<Double> samples = grouped.get(new ShapeTargetKey(shape, target));
                if (samples != null) {
                    values.put(target, median(samples));
                }
            }
            result.put(shape, values);
        }
        return result;
    }

    static double require(EnumMap<Target, Double> values, Target target, Object key) {
        Double value = values.get(target);
        if (value == null) {
            throw new IllegalStateException("Missing " + target.id() + " result for " + key);
        }
        return value;
    }

    static Distribution distribution(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double median = medianSorted(sorted);
        double q1 = percentile(sorted, 0.25);
        double q3 = percentile(sorted, 0.75);
        List<Double> deviations = sorted.stream().map(value -> Math.abs(value - median)).sorted().toList();
        return new Distribution(sorted.size(), median, q1, q3, q3 - q1,
                medianSorted(deviations), sorted.getFirst(), sorted.getLast());
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        return medianSorted(sorted);
    }

    static double medianSorted(List<Double> sorted) {
        int size = sorted.size();
        return (size & 1) == 0
                ? (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0
                : sorted.get(size / 2);
    }

    static double percentile(List<Double> sorted, double percentile) {
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double index = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = index - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }
}
