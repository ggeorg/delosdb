/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.PREFIX;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;

/** Benchmark defaults, campaign constraints and target classpath selection. */
record BenchmarkConfiguration(
        Path projectDirectory,
        Path javaExecutable,
        String benchmarkClasses,
        String delosClasspath,
        String upstreamDerbyClasspath,
        String h2Classpath,
        String sqliteClasspath,
        String delosClientClasspath,
        String upstreamDerbyClientClasspath,
        String postgresqlClasspath,
        String mariadbClasspath,
        String firebirdClasspath,
        String targets,
        Path delosRuntimeDirectory,
        Path upstreamDerbyServerRuntimeDirectory,
        Path h2ServerJar,
        String delosServerImage,
        String upstreamDerbyServerImage,
        String postgresqlImage,
        String mariadbImage,
        String firebirdImage,
        String upstreamDerbyVersion,
        String postgresqlDriverVersion,
        String mariadbDriverVersion,
        String firebirdDriverVersion,
        String h2DriverVersion,
        String projectVersion,
        String remoteJdbcUrl,
        String remoteUser,
        String remotePassword,
        Path databaseRoot,
        Path reportDirectory,
        String rows,
        String clients,
        String widths,
        String workloads,
        boolean sqliteSharedCache,
        int transactionsPerClient,
        int fixedWorkloadOperationBudgetPerClient,
        long rangeScanTargetRowsPerClient,
        int rangeScanMinQueriesPerClient,
        int rangeScanMaxQueriesPerClient,
        int h2RangeFetchSize,
        int payload,
        int fixtureBatch,
        int warmups,
        int iterations,
        double minimumWarmupSeconds,
        int maximumWarmupIterations,
        double minimumMeasuredSeconds,
        int maximumMeasuredIterations,
        int runs,
        int caseTimeoutSeconds,
        int workerTimeoutSeconds,
        int containerStartupTimeoutSeconds,
        String childHeap,
        Target target,
        int run) {
    static final String PREFIX = "delosdb.benchmark.crossEngineConcurrency.";

    static final List<Target> READ_DECOMPOSITION_TARGETS = List.of(
            Target.DELOS_HEAP, Target.UPSTREAM_DERBY, Target.H2);

    static final List<Target> RANGE_SCAN_JFR_TARGETS = List.of(
            Target.DELOS_HEAP, Target.DELOS_MVCC, Target.UPSTREAM_DERBY);

    static final List<Target> RANGE_BULK_FETCH_TARGETS = List.of(
            Target.DELOS_HEAP, Target.UPSTREAM_DERBY);

    static final List<Target> SERVER_PRODUCT_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA,
            Target.DELOS_MVCC_DRDA,
            Target.UPSTREAM_DERBY_DRDA,
            Target.H2_SERVER,
            Target.POSTGRESQL,
            Target.MARIADB);

    static final List<Target> EMBEDDED_REFERENCE_CANARY_TARGETS = List.of(
            Target.UPSTREAM_DERBY, Target.H2, Target.SQLITE);

    static final List<Target> SERVER_REFERENCE_CANARY_TARGETS = List.of(
            Target.UPSTREAM_DERBY_DRDA, Target.H2_SERVER, Target.POSTGRESQL, Target.MARIADB);

    static final List<Target> MVCC_ONLY_DIAGNOSTIC_TARGETS = List.of(Target.DELOS_MVCC);

    static final List<Target> HOST_RECOVERY_DIAGNOSTIC_TARGETS = List.of(Target.H2, Target.SQLITE);

    static final List<Target> DRDA_PROTOCOL_EVIDENCE_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA, Target.UPSTREAM_DERBY_DRDA);

    static final List<Target> F08_MUTATION_SCHEMA_ATTRIBUTION_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA, Target.UPSTREAM_DERBY_DRDA);

    static final List<Target> F08_TRANSACTION_STATUS_ATTRIBUTION_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA, Target.UPSTREAM_DERBY_DRDA);

    static final List<Target> DRDA_SERVER_PHASE_EVIDENCE_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA);

    static final List<Target> CURRENT_BASELINE_EMBEDDED_TARGETS = List.of(
            Target.DELOS_HEAP, Target.DELOS_MVCC);

    static final List<Target> CURRENT_BASELINE_SERVER_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA);

    static final List<Target> GEN2_C3_POSTGRESQL_UPDATE_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.POSTGRESQL);

    static final String CURRENT_BASELINE_ENV = "DELOSDB_CURRENT_BASELINE";

    static final String CURRENT_BASELINE_REPORT_ROOT_ENV = "DELOSDB_CURRENT_BASELINE_REPORT_ROOT";

    static final String CURRENT_BASELINE_DATABASE_ROOT_ENV = "DELOSDB_CURRENT_BASELINE_DATABASE_ROOT";

    static BenchmarkConfiguration fromSystemProperties() {
        String targetValue = System.getProperty(PREFIX + "target");
        Path databaseRoot = path(PREFIX + "databaseRoot", "build/tmp/delos-jdbc-cross-engine-concurrency");
        Path reportDirectory = path(PREFIX + "reportDirectory",
                "build/reports/delosdb/benchmarks/cross-engine-concurrency");
        if (currentBaselineEnabled()) {
            Path suffix = currentBaselineSuffix(reportDirectory);
            reportDirectory = requiredEnvironmentPath(CURRENT_BASELINE_REPORT_ROOT_ENV).resolve(suffix);
            databaseRoot = requiredEnvironmentPath(CURRENT_BASELINE_DATABASE_ROOT_ENV).resolve(suffix);
        }
        return new BenchmarkConfiguration(
                path(PREFIX + "projectDirectory", "."),
                path(PREFIX + "javaExecutable", Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                System.getProperty(PREFIX + "benchmarkClasses", "."),
                System.getProperty(PREFIX + "delosClasspath", "."),
                System.getProperty(PREFIX + "upstreamDerbyClasspath", "."),
                System.getProperty(PREFIX + "h2Classpath", "."),
                System.getProperty(PREFIX + "sqliteClasspath", "."),
                System.getProperty(PREFIX + "delosClientClasspath", "."),
                System.getProperty(PREFIX + "upstreamDerbyClientClasspath", "."),
                System.getProperty(PREFIX + "postgresqlClasspath", "."),
                System.getProperty(PREFIX + "mariadbClasspath", "."),
                System.getProperty(PREFIX + "firebirdClasspath", "."),
                System.getProperty(PREFIX + "targets", "delos_heap,delos_mvcc,upstream_derby,h2,sqlite"),
                path(PREFIX + "delosRuntimeDirectory", "build/libs"),
                path(PREFIX + "upstreamDerbyServerRuntimeDirectory",
                        "build/tmp/upstream-derby-network-server-runtime"),
                path(PREFIX + "h2ServerJar", "build/tmp/h2-server/h2.jar"),
                System.getProperty(PREFIX + "delosServerImage", "eclipse-temurin:25.0.3_9-jre-noble"),
                System.getProperty(PREFIX + "upstreamDerbyServerImage",
                        "eclipse-temurin:25.0.3_9-jre-noble"),
                System.getProperty(PREFIX + "postgresqlImage", "postgres:18.4"),
                System.getProperty(PREFIX + "mariadbImage", "mariadb:12.3.2"),
                System.getProperty(PREFIX + "firebirdImage", "firebirdsql/firebird:5.0.4"),
                System.getProperty(PREFIX + "upstreamDerbyVersion", "10.17.1.0"),
                System.getProperty(PREFIX + "postgresqlDriverVersion", "42.7.13"),
                System.getProperty(PREFIX + "mariadbDriverVersion", "3.5.10"),
                System.getProperty(PREFIX + "firebirdDriverVersion", "6.0.5"),
                System.getProperty(PREFIX + "h2DriverVersion", "2.4.240"),
                System.getProperty(PREFIX + "projectVersion", "unknown"),
                System.getProperty(PREFIX + "remoteJdbcUrl", ""),
                System.getProperty(PREFIX + "remoteUser", ""),
                System.getProperty(PREFIX + "remotePassword", ""),
                databaseRoot,
                reportDirectory,
                System.getProperty(PREFIX + "rows", "10000"),
                System.getProperty(PREFIX + "clients", "1,2,4,8"),
                System.getProperty(PREFIX + "widths", "1,10"),
                System.getProperty(PREFIX + "workloads",
                        "PRIMARY_KEY_READ_HOT,PRIMARY_KEY_READ_DISJOINT,PRIMARY_KEY_READ_RANDOM,"
                                + "DISJOINT_INDEXED_UPDATE,CONTENDED_INDEXED_UPDATE"),
                Boolean.parseBoolean(System.getProperty(PREFIX + "sqliteSharedCache", "false")),
                Integer.parseInt(System.getProperty(PREFIX + "transactionsPerClient", "50")),
                Integer.parseInt(System.getProperty(PREFIX + "fixedWorkloadOperationBudgetPerClient", "0")),
                Long.parseLong(System.getProperty(PREFIX + "rangeScanTargetRowsPerClient", "1000000")),
                Integer.parseInt(System.getProperty(PREFIX + "rangeScanMinQueriesPerClient", "100")),
                Integer.parseInt(System.getProperty(PREFIX + "rangeScanMaxQueriesPerClient", "10000")),
                Integer.parseInt(System.getProperty(PREFIX + "h2RangeFetchSize", "0")),
                Integer.parseInt(System.getProperty(PREFIX + "payload", "128")),
                Integer.parseInt(System.getProperty(PREFIX + "fixtureBatch", "100")),
                Integer.parseInt(System.getProperty(PREFIX + "warmups", "2")),
                Integer.parseInt(System.getProperty(PREFIX + "iterations", "3")),
                Double.parseDouble(System.getProperty(PREFIX + "minimumWarmupSeconds", "0")),
                Integer.parseInt(System.getProperty(PREFIX + "maximumWarmupIterations", "10000")),
                Double.parseDouble(System.getProperty(PREFIX + "minimumMeasuredSeconds", "0")),
                Integer.parseInt(System.getProperty(PREFIX + "maximumMeasuredIterations", "10000")),
                Integer.parseInt(System.getProperty(PREFIX + "runs", "4")),
                Integer.parseInt(System.getProperty(PREFIX + "caseTimeoutSeconds", "120")),
                Integer.parseInt(System.getProperty(PREFIX + "workerTimeoutSeconds", "0")),
                Integer.parseInt(System.getProperty(PREFIX + "containerStartupTimeoutSeconds", "90")),
                System.getProperty(PREFIX + "childHeap", "1g"),
                targetValue == null ? null : Target.parse(targetValue),
                Integer.parseInt(System.getProperty(PREFIX + "run", "0")));
    }

    void validate() {
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IllegalArgumentException("Java executable does not exist: " + javaExecutable);
        }
        List<Target> configuredTargets = targetValues();
        List<Target> embedded = List.of(
                Target.DELOS_HEAP, Target.DELOS_MVCC, Target.UPSTREAM_DERBY, Target.H2, Target.SQLITE);
        List<Target> container = SERVER_PRODUCT_TARGETS;
        boolean referenceCanaryTargets = configuredTargets.equals(EMBEDDED_REFERENCE_CANARY_TARGETS)
                || configuredTargets.equals(SERVER_REFERENCE_CANARY_TARGETS);
        boolean mvccOnlyDiagnostic = configuredTargets.equals(MVCC_ONLY_DIAGNOSTIC_TARGETS);
        boolean hostRecoveryDiagnostic = hostStateRecoveryEnabled()
                && configuredTargets.equals(HOST_RECOVERY_DIAGNOSTIC_TARGETS);
        boolean drdaProtocolDiagnostic = drdaProtocolEvidenceEnabled()
                && configuredTargets.equals(DRDA_PROTOCOL_EVIDENCE_TARGETS);
        boolean mutationSchemaAttribution = mutationSchemaAttributionEnabled()
                && configuredTargets.equals(F08_MUTATION_SCHEMA_ATTRIBUTION_TARGETS);
        boolean gen2A1ThroughputSentinel = gen2A1ThroughputSentinelEnabled()
                && configuredTargets.equals(CURRENT_BASELINE_SERVER_TARGETS);
        boolean gen2BThroughputSentinel = gen2BThroughputSentinelEnabled()
                && configuredTargets.equals(CURRENT_BASELINE_SERVER_TARGETS);
        boolean gen2C3UpdateThroughputSentinel = gen2C3UpdateThroughputSentinelEnabled()
                && configuredTargets.equals(CURRENT_BASELINE_SERVER_TARGETS);
        boolean gen2C3PostgresqlUpdateComparison = gen2C3PostgresqlUpdateComparisonEnabled()
                && configuredTargets.equals(GEN2_C3_POSTGRESQL_UPDATE_TARGETS);
        boolean postModernizationMutationCheckpoint =
                postModernizationMutationCheckpointEnabled()
                && configuredTargets.equals(SERVER_PRODUCT_TARGETS);
        boolean f08TransactionStatusAttribution =
                f08TransactionStatusCrossEngineEnabled()
                && configuredTargets.equals(F08_TRANSACTION_STATUS_ATTRIBUTION_TARGETS);
        boolean f08TransactionStatusCrossEngine =
                f08TransactionStatusCrossEngineEnabled()
                && (configuredTargets.equals(SERVER_PRODUCT_TARGETS)
                        || f08TransactionStatusAttribution);
        boolean gen2C3ReadFitness = mvccGen2C3ReadServerEnabled()
                && configuredTargets.equals(SERVER_PRODUCT_TARGETS);
        boolean f02ScaleSurfaceDiagnostic = f02ScaleSurfaceDiagnosticEnabled()
                && mvccGen2C3ReadServerEnabled()
                && configuredTargets.equals(CURRENT_BASELINE_SERVER_TARGETS);
        boolean f02EmbeddedConcurrencyDiagnostic = f02EmbeddedConcurrencyDiagnosticEnabled()
                && mvccGen2C3ReadServerEnabled()
                && configuredTargets.equals(RANGE_SCAN_JFR_TARGETS);
        boolean freshRealisticTransactionFitness = freshRealisticTransactionFitnessEnabled()
                && mvccGen2C3ReadServerEnabled()
                && configuredTargets.equals(SERVER_PRODUCT_TARGETS);
        boolean gen2C3ReadJfr = !f02EmbeddedConcurrencyDiagnostic
                && mvccGen2C3ReadServerEnabled()
                && configuredTargets.equals(RANGE_SCAN_JFR_TARGETS);
        boolean gen2C3ProjectedCurrentRead = mvccGen2C3ReadServerEnabled()
                && mvccGen2ProjectedCurrentReadEnabled()
                && configuredTargets.equals(CURRENT_BASELINE_SERVER_TARGETS);
        boolean drdaServerPhaseDiagnostic = drdaServerPhaseEvidenceEnabled()
                && configuredTargets.equals(DRDA_SERVER_PHASE_EVIDENCE_TARGETS);
        boolean f04DrdaServerPhaseDiagnostic = drdaServerPhaseDiagnostic
                && f04DrdaServerPhaseDiagnosticEnabled();
        boolean f04DrdaRowAdvanceJfrDiagnostic = f04DrdaRowAdvanceJfrDiagnosticEnabled()
                && mvccGen2C3ReadServerEnabled()
                && configuredTargets.equals(DRDA_SERVER_PHASE_EVIDENCE_TARGETS);
        boolean f04FanoutJfrDiagnostic = f04FanoutJfrDiagnosticEnabled()
                && mvccGen2C3ReadServerEnabled()
                && configuredTargets.equals(DRDA_SERVER_PHASE_EVIDENCE_TARGETS);
        boolean f07DrdaServerPhaseDiagnostic = drdaServerPhaseDiagnostic
                && f07DrdaServerPhaseDiagnosticEnabled();
        boolean currentBaselineTargets = currentBaselineEnabled()
                && (configuredTargets.equals(CURRENT_BASELINE_EMBEDDED_TARGETS)
                        || configuredTargets.equals(CURRENT_BASELINE_SERVER_TARGETS));
        if (target == null
                && !configuredTargets.equals(embedded)
                && !configuredTargets.equals(container)
                && !referenceCanaryTargets
                && !configuredTargets.equals(READ_DECOMPOSITION_TARGETS)
                && !configuredTargets.equals(RANGE_SCAN_JFR_TARGETS)
                && !configuredTargets.equals(RANGE_BULK_FETCH_TARGETS)
                && !mvccOnlyDiagnostic
                && !hostRecoveryDiagnostic
                && !drdaProtocolDiagnostic
                && !mutationSchemaAttribution
                && !gen2A1ThroughputSentinel
                && !gen2BThroughputSentinel
                && !gen2C3UpdateThroughputSentinel
                && !gen2C3PostgresqlUpdateComparison
                && !f08TransactionStatusCrossEngine
                && !gen2C3ProjectedCurrentRead
                && !f02ScaleSurfaceDiagnostic
                && !drdaServerPhaseDiagnostic
                && !f04DrdaRowAdvanceJfrDiagnostic
                && !f04FanoutJfrDiagnostic
                && !currentBaselineTargets
                && !(RawStoreOccupancyBenchmark.campaign()
                        && configuredTargets.equals(List.of(Target.DELOS_HEAP, Target.UPSTREAM_DERBY)))) {
            throw new IllegalArgumentException("coordinator targets must be exactly " + embedded + ", "
                    + container + ", embedded reference canary " + EMBEDDED_REFERENCE_CANARY_TARGETS
                    + ", server reference canary " + SERVER_REFERENCE_CANARY_TARGETS
                    + ", diagnostic " + READ_DECOMPOSITION_TARGETS
                    + ", range/JFR diagnostic " + RANGE_SCAN_JFR_TARGETS
                    + ", range bulk-fetch diagnostic " + RANGE_BULK_FETCH_TARGETS
                    + ", MVCC diagnostic " + MVCC_ONLY_DIAGNOSTIC_TARGETS
                    + ", host recovery diagnostic " + HOST_RECOVERY_DIAGNOSTIC_TARGETS
                    + ", DRDA protocol diagnostic " + DRDA_PROTOCOL_EVIDENCE_TARGETS
                    + ", F08 mutation-schema attribution " + F08_MUTATION_SCHEMA_ATTRIBUTION_TARGETS
                    + ", F08 transaction-status attribution "
                    + F08_TRANSACTION_STATUS_ATTRIBUTION_TARGETS
                    + ", Gen2-A1 throughput sentinel " + CURRENT_BASELINE_SERVER_TARGETS
                    + ", Gen2-B throughput sentinel " + CURRENT_BASELINE_SERVER_TARGETS
                    + ", Gen2-C3 UPDATE throughput sentinel " + CURRENT_BASELINE_SERVER_TARGETS
                    + ", Gen2-C3 PostgreSQL UPDATE comparison " + GEN2_C3_POSTGRESQL_UPDATE_TARGETS
                    + ", DRDA server-phase diagnostic " + DRDA_SERVER_PHASE_EVIDENCE_TARGETS
                    + ", or Phase-1 current baseline " + CURRENT_BASELINE_EMBEDDED_TARGETS
                    + "/" + CURRENT_BASELINE_SERVER_TARGETS
                    + ": " + configuredTargets);
        }
        if (target != null && !configuredTargets.contains(target)) {
            throw new IllegalArgumentException(
                    "worker target " + target.id() + " is not present in configured targets " + configuredTargets);
        }
        parsePositive(rows, "rows", 100);
        parsePositive(clients, "clients", 1);
        parsePositive(widths, "widths", 1);
        List<Workload> configuredWorkloads = workloadValues();
        String configuredInsertTableShape = insertTableShape();
        if (RawStoreOccupancyBenchmark.campaign()) {
            RawStoreOccupancyBenchmark.validate(configuredTargets.stream()
                    .map(Target::id).toList(), clientValues(), configuredWorkloads.stream()
                    .map(Enum::name).toList(), configuredInsertTableShape, warmups, iterations);
        }
        if (!List.of("BARE", "PRIMARY_KEY_ONLY", "FULL_INDEXED")
                .contains(configuredInsertTableShape)) {
            throw new IllegalArgumentException(
                    "Unknown INSERT benchmark table shape: " + configuredInsertTableShape);
        }
        F08BenchmarkContract.validate(
                benchmarkContractId(),
                new F08BenchmarkContract.Context(
                        new F08BenchmarkContract.TargetScope(
                                configuredTargets.equals(SERVER_PRODUCT_TARGETS),
                                f08TransactionStatusAttribution),
                        new F08BenchmarkContract.RunShape(
                                configuredWorkloads.stream().map(Enum::name).toList(),
                                clientValues(), widthValues(), configuredInsertTableShape, payload),
                        new F08BenchmarkContract.Timing(
                                sqlSemanticOracleEnabled(), warmups, iterations,
                                minimumWarmupSeconds, maximumWarmupIterations,
                                minimumMeasuredSeconds, maximumMeasuredIterations),
                        new F08BenchmarkContract.Controls(
                                f08TransactionStatusCrossEngineEnabled(),
                                f08FixedCostClientScalingEnabled(),
                                f08ContentionScalingSliceEnabled(),
                                mvccGen2A1ServerEnabled(), mvccGen2BServerEnabled(),
                                mvccGen2TransactionStatusVisibilityServerEnabled(),
                                f08MultiRowInsertControlEnabled(),
                                btreeInsertRootRoutingSnapshotServerEnabled(),
                                btreeInsertBranchRoutingSnapshotServerEnabled()),
                        System.getProperty(PREFIX + "profileServerTargets", "").trim()));
        if (f04DrdaServerPhaseDiagnostic) {
            if (!configuredWorkloads.equals(List.of(
                    Workload.JOIN_INDEXED_1TO1,
                    Workload.JOIN_INDEXED_FANOUT))) {
                throw new IllegalArgumentException(
                        "F04 DRDA server-phase diagnostic requires the two F04 join workloads");
            }
            if (!clientValues().equals(List.of(8))) {
                throw new IllegalArgumentException(
                        "F04 DRDA server-phase diagnostic requires exactly 8 clients");
            }
            if (!rowCounts().equals(List.of(10000))) {
                throw new IllegalArgumentException(
                        "F04 DRDA server-phase diagnostic requires rows=10000");
            }
            if (!widthValues().equals(List.of(1))) {
                throw new IllegalArgumentException(
                        "F04 DRDA server-phase diagnostic requires width 1");
            }
            if (!"FULL_INDEXED".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "F04 DRDA server-phase diagnostic requires FULL_INDEXED table shape");
            }
            boolean phaseShape = transactionsPerClient == 20
                    && warmups == 1
                    && iterations == 1
                    && Double.compare(minimumWarmupSeconds, 0.0d) == 0
                    && maximumWarmupIterations == 1
                    && Double.compare(minimumMeasuredSeconds, 0.0d) == 0
                    && maximumMeasuredIterations == 1;
            if (!phaseShape
                    || drdaServerPhaseSkipOpenQueries() != 20
                    || drdaServerPhaseCaptureOpenQueries() != 20
                    || !sqlSemanticOracleEnabled()
                    || !mvccGen2C3ReadServerEnabled()
                    || !mvccPhysicalScanCostEnabled()
                    || !mvccPhysicalRowLocationCostEnabled()
                    || mvccGen2ProjectedCurrentReadEnabled()
                    || Boolean.getBoolean(PREFIX + "mvccBaseFetchPagePrefetch")) {
                throw new IllegalArgumentException(
                        "F04 DRDA server-phase diagnostic requires canonical Gen2 read settings, "
                                + "20 warmup/capture queries, transactionsPerClient=20, "
                                + "warmups=1, iterations=1, zero-duration single-interval phases, "
                                + "and SQL semantic oracle enabled");
            }
        } else if (f04DrdaRowAdvanceJfrDiagnostic) {
            if (!configuredWorkloads.equals(List.of(Workload.JOIN_INDEXED_1TO1))) {
                throw new IllegalArgumentException(
                        "F04 row-advance JFR diagnostic requires JOIN_INDEXED_1TO1 only");
            }
            if (!clientValues().equals(List.of(8))
                    || !rowCounts().equals(List.of(10000))
                    || !widthValues().equals(List.of(1))) {
                throw new IllegalArgumentException(
                        "F04 row-advance JFR diagnostic requires rows=10000, clients=8, width=1");
            }
            if (!"FULL_INDEXED".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "F04 row-advance JFR diagnostic requires FULL_INDEXED table shape");
            }
            boolean profileShape = transactionsPerClient == 100
                    && warmups == 1
                    && iterations == 1
                    && Double.compare(minimumWarmupSeconds, 0.0d) == 0
                    && maximumWarmupIterations == 1
                    && Double.compare(minimumMeasuredSeconds, 0.0d) == 0
                    && maximumMeasuredIterations == 1;
            if (!profileShape
                    || !sqlSemanticOracleEnabled()
                    || !mvccPhysicalScanCostEnabled()
                    || !mvccPhysicalRowLocationCostEnabled()
                    || mvccGen2ProjectedCurrentReadEnabled()
                    || Boolean.getBoolean(PREFIX + "mvccBaseFetchPagePrefetch")) {
                throw new IllegalArgumentException(
                        "F04 row-advance JFR diagnostic requires canonical Gen2 read settings, "
                                + "transactionsPerClient=100, warmups=1, iterations=1, "
                                + "zero-duration single-interval phases, and SQL semantic oracle enabled");
            }
            String expectedProfileTargets = "delos_heap_drda,delos_mvcc_drda";
            if (!expectedProfileTargets.equals(
                    System.getProperty(PREFIX + "profileServerTargets", "").trim())) {
                throw new IllegalArgumentException(
                        "F04 row-advance JFR diagnostic requires server profiling for "
                                + expectedProfileTargets);
            }
        } else if (f04FanoutJfrDiagnostic) {
            if (!configuredWorkloads.equals(List.of(Workload.JOIN_INDEXED_FANOUT))) {
                throw new IllegalArgumentException(
                        "F04 fanout JFR diagnostic requires JOIN_INDEXED_FANOUT only");
            }
            if (!clientValues().equals(List.of(8))
                    || !rowCounts().equals(List.of(10000))
                    || !widthValues().equals(List.of(1))) {
                throw new IllegalArgumentException(
                        "F04 fanout JFR diagnostic requires rows=10000, clients=8, width=1");
            }
            if (!"FULL_INDEXED".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "F04 fanout JFR diagnostic requires FULL_INDEXED table shape");
            }
            boolean profileShape = transactionsPerClient == 100
                    && warmups == 1
                    && iterations == 1
                    && Double.compare(minimumWarmupSeconds, 0.0d) == 0
                    && maximumWarmupIterations == 1
                    && Double.compare(minimumMeasuredSeconds, 0.0d) == 0
                    && maximumMeasuredIterations == 1;
            if (!profileShape
                    || !sqlSemanticOracleEnabled()
                    || !refreshSimpleJoinStatisticsEnabled()
                    || !mvccPhysicalScanCostEnabled()
                    || !mvccPhysicalRowLocationCostEnabled()
                    || mvccGen2ProjectedCurrentReadEnabled()
                    || Boolean.getBoolean(PREFIX + "mvccBaseFetchPagePrefetch")) {
                throw new IllegalArgumentException(
                        "F04 fanout JFR diagnostic requires canonical Gen2 read settings, "
                                + "simple-join statistics refresh, transactionsPerClient=100, "
                                + "warmups=1, iterations=1, zero-duration single-interval phases, "
                                + "and SQL semantic oracle enabled");
            }
            String expectedProfileTargets = "delos_heap_drda,delos_mvcc_drda";
            if (!expectedProfileTargets.equals(
                    System.getProperty(PREFIX + "profileServerTargets", "").trim())) {
                throw new IllegalArgumentException(
                        "F04 fanout JFR diagnostic requires server profiling for "
                                + expectedProfileTargets);
            }
        } else if (f07DrdaServerPhaseDiagnostic) {
            if (!configuredWorkloads.equals(List.of(Workload.SORT_FULL))) {
                throw new IllegalArgumentException(
                        "F07 DRDA server-phase diagnostic requires SORT_FULL only");
            }
            if (!clientValues().equals(List.of(8))) {
                throw new IllegalArgumentException(
                        "F07 DRDA server-phase diagnostic requires exactly 8 clients");
            }
            if (!rowCounts().equals(List.of(10000))) {
                throw new IllegalArgumentException(
                        "F07 DRDA server-phase diagnostic requires rows=10000");
            }
            if (!widthValues().equals(List.of(1))) {
                throw new IllegalArgumentException(
                        "F07 DRDA server-phase diagnostic requires width 1");
            }
            if (!"FULL_INDEXED".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "F07 DRDA server-phase diagnostic requires FULL_INDEXED table shape");
            }
            boolean shortPhaseShape = transactionsPerClient == 20
                    && warmups == 1
                    && iterations == 1
                    && Double.compare(minimumWarmupSeconds, 0.0d) == 0
                    && maximumWarmupIterations == 1
                    && Double.compare(minimumMeasuredSeconds, 0.0d) == 0
                    && maximumMeasuredIterations == 1;
            boolean sustainedPhaseShape = (transactionsPerClient == 3
                            || transactionsPerClient == 20)
                    && warmups == 1
                    && iterations == 1
                    && Double.compare(minimumWarmupSeconds, 2.0d) == 0
                    && maximumWarmupIterations == 5000
                    && Double.compare(minimumMeasuredSeconds, 8.0d) == 0
                    && maximumMeasuredIterations == 5000;
            if ((!shortPhaseShape && !sustainedPhaseShape)
                    || drdaServerPhaseSkipOpenQueries() < 0
                    || drdaServerPhaseCaptureOpenQueries() <= 0
                    || !sqlSemanticOracleEnabled()) {
                throw new IllegalArgumentException(
                        "F07 DRDA server-phase diagnostic requires transactionsPerClient=20 "
                                + "for the short phase shape, or transactionsPerClient=3|20 "
                                + "for the sustained phase shape, warmups=1, iterations=1, either "
                                + "2s warmup/8s measured sustained shape, a valid capture window, "
                                + "and SQL semantic oracle enabled");
            }
        } else if (f02ScaleSurfaceDiagnostic) {
            if (!configuredWorkloads.equals(List.of(Workload.RANGE_SCAN_1000))) {
                throw new IllegalArgumentException(
                        "F02 scale-surface diagnostic requires RANGE_SCAN_1000 only");
            }
            if (!clientValues().equals(List.of(1, 8))) {
                throw new IllegalArgumentException(
                        "F02 scale-surface diagnostic requires clients=1,8");
            }
            if (!rowCounts().equals(List.of(10000, 100000))) {
                throw new IllegalArgumentException(
                        "F02 scale-surface diagnostic requires rows=10000,100000");
            }
            if (!widthValues().equals(List.of(10))) {
                throw new IllegalArgumentException(
                        "F02 scale-surface diagnostic requires width 10");
            }
            if (!"PRIMARY_KEY_ONLY".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "F02 scale-surface diagnostic requires PRIMARY_KEY_ONLY table shape");
            }
        }
        if (f02EmbeddedConcurrencyDiagnostic) {
            if (!configuredWorkloads.equals(List.of(Workload.RANGE_SCAN_1000))) {
                throw new IllegalArgumentException(
                        "F02 embedded concurrency diagnostic requires RANGE_SCAN_1000 only");
            }
            List<Integer> embeddedConcurrencyClients = clientValues();
            if (!embeddedConcurrencyClients.equals(List.of(1, 8))
                    && !embeddedConcurrencyClients.equals(List.of(8))) {
                throw new IllegalArgumentException(
                        "F02 embedded concurrency diagnostic requires clients=1,8 or clients=8");
            }
            if (!rowCounts().equals(List.of(10000))) {
                throw new IllegalArgumentException(
                        "F02 embedded concurrency diagnostic requires rows=10000");
            }
            if (!widthValues().equals(List.of(10))) {
                throw new IllegalArgumentException(
                        "F02 embedded concurrency diagnostic requires width 10");
            }
            if (!"PRIMARY_KEY_ONLY".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "F02 embedded concurrency diagnostic requires PRIMARY_KEY_ONLY table shape");
            }
        } else if (gen2C3ReadFitness || gen2C3ProjectedCurrentRead) {
            boolean pointReadFitness = configuredWorkloads.equals(
                    List.of(Workload.PRIMARY_KEY_READ_RANDOM));
            boolean rangeScanFitness = configuredWorkloads.equals(List.of(
                    Workload.RANGE_SCAN_INDEX_ONLY_1000,
                    Workload.RANGE_SCAN_100,
                    Workload.RANGE_SCAN_1000,
                    Workload.RANGE_SCAN_FULL));
            boolean projectionFitness = configuredWorkloads.equals(List.of(
                    Workload.PROJECTION_COVERED,
                    Workload.PROJECTION_TWO_COLUMN,
                    Workload.PROJECTION_FULL_ROW));
            boolean simpleJoinFitness = configuredWorkloads.equals(List.of(
                    Workload.JOIN_INDEXED_1TO1,
                    Workload.JOIN_INDEXED_FANOUT));
            boolean multiWayJoinFitness = configuredWorkloads.equals(List.of(
                    Workload.JOIN_3WAY_SELECTIVE,
                    Workload.JOIN_4WAY_FANOUT));
            boolean groupByFitness = configuredWorkloads.equals(List.of(
                    Workload.GROUP_LOW_CARD,
                    Workload.GROUP_HIGH_CARD));
            boolean sortFitness = configuredWorkloads.equals(List.of(
                    Workload.SORT_FULL));
            boolean mixedReaderWriterGen2Fitness = configuredWorkloads.equals(List.of(
                    Workload.MIXED_80R20W,
                    Workload.MIXED_50R50W_HOT));
            boolean longReaderWriterGen2Fitness = configuredWorkloads.equals(List.of(
                    Workload.LONG_READER_DISJOINT_WRITER,
                    Workload.LONG_READER_HOT_WRITER));
            boolean realisticTransactionGen2Fitness = freshRealisticTransactionFitness
                    && configuredWorkloads.equals(List.of(
                            Workload.BANK_TRANSACTION,
                            Workload.ORDER_ENTRY_MIX));
            if (freshRealisticTransactionFitness && !realisticTransactionGen2Fitness) {
                throw new IllegalArgumentException(
                        "Fresh F13 realistic-transaction fitness requires exactly "
                                + "BANK_TRANSACTION,ORDER_ENTRY_MIX");
            }
            if ((!pointReadFitness && !rangeScanFitness && !projectionFitness
                            && !simpleJoinFitness && !multiWayJoinFitness && !groupByFitness
                            && !sortFitness && !mixedReaderWriterGen2Fitness && !longReaderWriterGen2Fitness
                            && !realisticTransactionGen2Fitness)
                    || (gen2C3ProjectedCurrentRead && !rangeScanFitness)) {
                throw new IllegalArgumentException(
                        gen2C3ProjectedCurrentRead
                                ? "Gen2-C3 projected-current read experiment requires the F02 range-scan workload set"
                                : "Gen2-C3 read fitness requires the F01 point-read workload, "
                                        + "the F02 range-scan workload set, the F03 projection workload set, "
                                        + "the F04 simple-join workload set, the F05 multi-way join workload set, "
                                        + "the F06 GROUP BY workload set, the F07 sort workload, "
                                        + "the F11 mixed reader/writer workload set, "
                                        + "the F12 long-reader/writer workload set, "
                                        + "or the fresh F13 realistic-transaction workload set");
            }
            List<Integer> expectedClients = longReaderWriterGen2Fitness
                    ? List.of(4)
                    : (rangeScanFitness || projectionFitness
                                    || simpleJoinFitness || multiWayJoinFitness || groupByFitness || sortFitness
                                    || mixedReaderWriterGen2Fitness || realisticTransactionGen2Fitness)
                            ? List.of(8) : List.of(1, 8);
            if (!clientValues().equals(expectedClients)) {
                throw new IllegalArgumentException(
                        "Gen2-C3 read fitness requires clients " + expectedClients);
            }
            List<Integer> expectedWidths = (projectionFitness
                            || simpleJoinFitness || multiWayJoinFitness || groupByFitness || sortFitness
                            || mixedReaderWriterGen2Fitness || longReaderWriterGen2Fitness
                            || realisticTransactionGen2Fitness)
                    ? List.of(1) : List.of(10);
            if (!widthValues().equals(expectedWidths)) {
                throw new IllegalArgumentException(
                        "Gen2-C3 read fitness requires widths " + expectedWidths);
            }
            String expectedTableShape = (projectionFitness
                            || simpleJoinFitness || multiWayJoinFitness || groupByFitness || sortFitness
                            || mixedReaderWriterGen2Fitness || longReaderWriterGen2Fitness
                            || realisticTransactionGen2Fitness)
                    ? "FULL_INDEXED" : "PRIMARY_KEY_ONLY";
            if (!expectedTableShape.equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "Gen2-C3 read fitness requires " + expectedTableShape + " table shape");
            }
            if (realisticTransactionGen2Fitness
                    && (warmups != 0
                            || iterations != 1
                            || Double.compare(minimumWarmupSeconds, 0.0d) != 0
                            || maximumWarmupIterations != 1
                            || Double.compare(minimumMeasuredSeconds, 0.0d) != 0
                            || maximumMeasuredIterations != 1
                            || !sqlSemanticOracleEnabled())) {
                throw new IllegalArgumentException(
                        "Fresh F13 realistic-transaction fitness requires one fresh measured interval, "
                                + "no warmup, adaptive durations disabled, and SQL semantic oracle enabled");
            }
        } else if (gen2C3ReadJfr) {
            boolean supportedJfrWorkload = configuredWorkloads.equals(List.of(Workload.RANGE_SCAN_100))
                    || configuredWorkloads.equals(List.of(Workload.RANGE_SCAN_1000));
            if (!supportedJfrWorkload) {
                throw new IllegalArgumentException(
                        "Gen2-C3 range-scan JFR requires RANGE_SCAN_100 or RANGE_SCAN_1000");
            }
            if (!clientValues().equals(List.of(1))) {
                throw new IllegalArgumentException(
                        "Gen2-C3 range-scan JFR requires exactly 1 client");
            }
            if (!widthValues().equals(List.of(10))) {
                throw new IllegalArgumentException(
                        "Gen2-C3 range-scan JFR requires exactly width 10");
            }
            if (!"PRIMARY_KEY_ONLY".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "Gen2-C3 range-scan JFR requires PRIMARY_KEY_ONLY table shape");
            }
        } else if (f08TransactionStatusCrossEngine) {
            // F08 configuration is owned by F08BenchmarkContract and validated above.
        } else if (postModernizationMutationCheckpoint) {
            boolean insertCheckpoint = configuredWorkloads.equals(List.of(Workload.INSERT_100));
            boolean freshUpdateCheckpoint = configuredWorkloads.equals(List.of(Workload.FRESH_INDEXED_UPDATE_100));
            boolean indexedUpdateCheckpoint = configuredWorkloads.equals(List.of(Workload.DISJOINT_INDEXED_UPDATE, Workload.CONTENDED_INDEXED_UPDATE));
            boolean deleteCheckpoint = configuredWorkloads.equals(List.of(Workload.DELETE_REINSERT));
            if (!insertCheckpoint && !freshUpdateCheckpoint && !indexedUpdateCheckpoint && !deleteCheckpoint) {
                throw new IllegalArgumentException("Unsupported post-modernization mutation checkpoint workload set");
            }
            if (!sqlSemanticOracleEnabled()) {
                throw new IllegalArgumentException("Post-modernization mutation checkpoint requires SQL semantic oracle");
            }
            if (insertCheckpoint) {
                boolean bare = "BARE".equals(configuredInsertTableShape);
                boolean primaryKey = "PRIMARY_KEY_ONLY".equals(configuredInsertTableShape);
                if (!bare && !primaryKey) {
                    throw new IllegalArgumentException("Post-modernization INSERT checkpoint requires BARE or PRIMARY_KEY_ONLY");
                }
                if ((bare && !mvccGen2A1ServerEnabled()) || (primaryKey && !mvccGen2BServerEnabled())) {
                    throw new IllegalArgumentException("Post-modernization INSERT checkpoint server mode does not match table shape");
                }
            } else if (freshUpdateCheckpoint || indexedUpdateCheckpoint) {
                if (!mvccGen2C3UpdateServerEnabled()) {
                    throw new IllegalArgumentException("Post-modernization UPDATE checkpoint requires the Gen2-C3 update server");
                }
            } else if (!"BARE".equals(configuredInsertTableShape) || !mvccGen2C1ServerEnabled()) {
                throw new IllegalArgumentException("Post-modernization DELETE_REINSERT checkpoint requires BARE and the Gen2-C1 history server");
            }
        } else if (gen2C3UpdateThroughputSentinel || gen2C3PostgresqlUpdateComparison) {
            String modeName = gen2C3UpdateThroughputSentinel
                    ? "Gen2-C3 UPDATE throughput sentinel"
                    : "Gen2-C3 PostgreSQL UPDATE comparison";
            if (!configuredWorkloads.equals(List.of(Workload.FRESH_INDEXED_UPDATE_100))) {
                throw new IllegalArgumentException(
                        modeName + " requires only FRESH_INDEXED_UPDATE_100");
            }
            if (!clientValues().equals(List.of(8))) {
                throw new IllegalArgumentException(modeName + " requires exactly 8 clients");
            }
            if (warmups != 0
                    || iterations != 1
                    || Double.compare(minimumWarmupSeconds, 0.0d) != 0
                    || maximumWarmupIterations != 1
                    || Double.compare(minimumMeasuredSeconds, 0.0d) != 0
                    || maximumMeasuredIterations != 1) {
                throw new IllegalArgumentException(
                        modeName + " requires one fresh measured UPDATE interval per worker "
                                + "(warmups=0, iterations=1, adaptive durations disabled)");
            }
            if (!"PRIMARY_KEY_ONLY".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        modeName + " requires PRIMARY_KEY_ONLY table shape");
            }
        } else if (mutationSchemaAttribution || gen2A1ThroughputSentinel || gen2BThroughputSentinel) {
            String modeName = gen2A1ThroughputSentinel
                    ? "Gen2-A1 throughput sentinel"
                    : gen2BThroughputSentinel
                            ? "Gen2-B throughput sentinel"
                            : "F08 mutation-schema attribution";
            if (configuredWorkloads.isEmpty()
                    || configuredWorkloads.stream().anyMatch(workload -> !workload.isInsert())) {
                throw new IllegalArgumentException(modeName + " requires INSERT workloads only");
            }
            if (!clientValues().equals(List.of(8))) {
                throw new IllegalArgumentException(modeName + " requires exactly 8 clients");
            }
            if (warmups != 0
                    || iterations != 1
                    || Double.compare(minimumWarmupSeconds, 0.0d) != 0
                    || maximumWarmupIterations != 1
                    || Double.compare(minimumMeasuredSeconds, 0.0d) != 0
                    || maximumMeasuredIterations != 1) {
                throw new IllegalArgumentException(
                        modeName + " requires one fresh measured INSERT interval per worker "
                                + "(warmups=0, iterations=1, adaptive durations disabled)");
            }
            if (gen2A1ThroughputSentinel && !"BARE".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "Gen2-A1 throughput sentinel requires BARE INSERT table shape");
            }
            if (gen2BThroughputSentinel && !"PRIMARY_KEY_ONLY".equals(configuredInsertTableShape)) {
                throw new IllegalArgumentException(
                        "Gen2-B throughput sentinel requires PRIMARY_KEY_ONLY INSERT table shape");
            }
        } else if (!f02ScaleSurfaceDiagnostic && !RawStoreOccupancyBenchmark.campaign()
                && !"FULL_INDEXED".equals(configuredInsertTableShape)) {
            throw new IllegalArgumentException(
                    "Non-default INSERT table shapes require mutationSchemaAttribution=true, "
                            + "gen2A1ThroughputSentinel=true, gen2BThroughputSentinel=true, "
                            + "gen2C3UpdateThroughputSentinel=true, Gen2-C3 read fitness, "
                            + "or the F02 scale-surface diagnostic");
        }
        boolean longReaderWriterFitness = !configuredWorkloads.isEmpty()
                && configuredWorkloads.stream().allMatch(Workload::isLongReaderWriter);
        if (configuredWorkloads.stream().anyMatch(Workload::isLongReaderWriter)
                && (!longReaderWriterFitness
                        || !clientValues().equals(List.of(4))
                        || !widthValues().equals(List.of(1)))) {
            throw new IllegalArgumentException(
                    "F12 long-reader/writer fitness requires only F12 workloads, exactly 4 writers, "
                            + "and width 1");
        }
        boolean realisticTransactionFitness = !configuredWorkloads.isEmpty()
                && configuredWorkloads.stream().allMatch(Workload::isRealisticTransaction);
        if (configuredWorkloads.stream().anyMatch(Workload::isRealisticTransaction)
                && (!realisticTransactionFitness || !widthValues().equals(List.of(1)))) {
            throw new IllegalArgumentException(
                    "F13 realistic transaction fitness requires only F13 workloads and width 1");
        }
        if (configuredWorkloads.contains(Workload.ORDER_ENTRY_MIX)
                && (transactionsPerClient < 20 || transactionsPerClient % 20 != 0)) {
            throw new IllegalArgumentException(
                    "F13 ORDER_ENTRY_MIX requires transactionsPerClient to be a positive multiple of 20");
        }
        boolean mixedReaderWriterFitness = !configuredWorkloads.isEmpty()
                && configuredWorkloads.stream().allMatch(Workload::isMixedReaderWriter);
        if (configuredWorkloads.stream().anyMatch(Workload::isMixedReaderWriter)
                && (!mixedReaderWriterFitness
                        || !clientValues().equals(List.of(8))
                        || !widthValues().equals(List.of(1))
                        || transactionsPerClient < 10
                        || transactionsPerClient % 10 != 0)) {
            throw new IllegalArgumentException(
                    "F11 mixed reader/writer fitness requires only F11 workloads, exactly 8 clients, "
                            + "width 1, and transactionsPerClient as a positive multiple of 10");
        }
        if (configuredWorkloads.stream().anyMatch(Workload::isCoveringRangeScan)
                && !configuredTargets.equals(RANGE_BULK_FETCH_TARGETS)) {
            throw new IllegalArgumentException(
                    "covering range proxy is valid only for Delos Heap and upstream Derby: "
                            + configuredTargets);
        }
        if (configuredWorkloads.stream().anyMatch(Workload::isRowBearingComparisonRangeScan)
                && !configuredTargets.equals(READ_DECOMPOSITION_TARGETS)) {
            throw new IllegalArgumentException(
                    "row-bearing H2 comparison requires Delos Heap, upstream Derby, and H2: "
                            + configuredTargets);
        }
        if (configuredWorkloads.stream().anyMatch(Workload::isMvccNaturalOrderRangeScan)
                && !configuredTargets.equals(MVCC_ONLY_DIAGNOSTIC_TARGETS)) {
            throw new IllegalArgumentException(
                    "MVCC natural-order range diagnostic requires Delos MVCC only: "
                            + configuredTargets);
        }
        int maxClients = clientValues().stream().mapToInt(Integer::intValue).max().orElseThrow();
        int minRows = rowCounts().stream().mapToInt(Integer::intValue).min().orElseThrow();
        if (configuredWorkloads.contains(Workload.ORDER_ENTRY_MIX) && minRows < 1000) {
            throw new IllegalArgumentException("F13 ORDER_ENTRY_MIX requires at least 1000 fixture rows");
        }
        if (configuredWorkloads.contains(Workload.BANK_TRANSACTION) && minRows < 100) {
            throw new IllegalArgumentException("F13 BANK_TRANSACTION requires at least 100 fixture rows");
        }
        if (configuredWorkloads.stream().anyMatch(Workload::isMixedReaderWriter) && minRows < 16) {
            throw new IllegalArgumentException("F11 mixed reader/writer fitness requires at least 16 fixture rows");
        }
        if (maxClients > minRows) {
            throw new IllegalArgumentException("clients cannot exceed rows");
        }
        if (target == null && !mvccOnlyDiagnostic && !longReaderWriterFitness
                && !mixedReaderWriterFitness && !mutationSchemaAttribution
                && !gen2A1ThroughputSentinel && !gen2BThroughputSentinel
                && !gen2C3UpdateThroughputSentinel && !gen2C3PostgresqlUpdateComparison
                && !gen2C3ReadFitness && !gen2C3ProjectedCurrentRead
                && !postModernizationMutationCheckpoint && !f08TransactionStatusCrossEngine
                && !f02EmbeddedConcurrencyDiagnostic && !f04DrdaServerPhaseDiagnostic
                && !f04DrdaRowAdvanceJfrDiagnostic && !f04FanoutJfrDiagnostic
                && !f07DrdaServerPhaseDiagnostic
                && !hostStateDiagnosticsEnabled() && !RawStoreOccupancyBenchmark.campaign()
                && !clientValues().contains(1)) {
            throw new IllegalArgumentException("clients must include 1 for scaling ratios");
        }
        if (transactionsPerClient < 1 || fixedWorkloadOperationBudgetPerClient < 0
                || rangeScanTargetRowsPerClient < 1L || rangeScanMinQueriesPerClient < 1
                || rangeScanMaxQueriesPerClient < rangeScanMinQueriesPerClient
                || h2RangeFetchSize < 0
                || payload < 16 || fixtureBatch < 1 || warmups < 0
                || iterations < 1
                || !Double.isFinite(minimumWarmupSeconds) || minimumWarmupSeconds < 0.0
                || maximumWarmupIterations < Math.max(1, warmups)
                || !Double.isFinite(minimumMeasuredSeconds) || minimumMeasuredSeconds < 0.0
                || maximumMeasuredIterations < iterations
                || caseTimeoutSeconds < 1 || workerTimeoutSeconds < 0
                || containerStartupTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid concurrency benchmark numeric option");
        }
        if (hostProcessDiagnosticsEnabled() && hostProcessSampleSeconds() < 1) {
            throw new IllegalArgumentException("hostProcessSampleSeconds must be positive");
        }
        if (hostStateRecoveryEnabled()) {
            if (hostStateCooldownAfterRun() < 1 || hostStateCooldownAfterRun() >= runs
                    || hostStateCooldownMinimumSeconds() < 0
                    || hostStateCooldownMaximumSeconds() < hostStateCooldownMinimumSeconds()
                    || hostStateCooldownSampleSeconds() < 1
                    || hostStateCooldownQuietSamples() < 1
                    || !Double.isFinite(hostStateCooldownMaximumCpuLoad())
                    || hostStateCooldownMaximumCpuLoad() < 0.0
                    || hostStateCooldownMaximumCpuLoad() > 1.0
                    || !Double.isFinite(hostStateCooldownMaximumLoadPerProcessor())
                    || hostStateCooldownMaximumLoadPerProcessor() <= 0.0) {
                throw new IllegalArgumentException("Invalid host-state recovery diagnostic option");
            }
        }
        if (target == null && !mvccOnlyDiagnostic && !drdaProtocolDiagnostic
                && !drdaServerPhaseDiagnostic && !f02EmbeddedConcurrencyDiagnostic
                && (runs < 4 || (runs & 3) != 0)) {
            throw new IllegalArgumentException("runs must be a multiple of 4 for orthogonal order");
        }
        if (target == null && drdaProtocolDiagnostic && runs < 2) {
            throw new IllegalArgumentException("DRDA protocol evidence requires at least two runs");
        }
        if (target == null && drdaServerPhaseDiagnostic && runs < 2) {
            throw new IllegalArgumentException("DRDA server phase evidence requires at least two runs");
        }
        if (childHeap.isBlank()) {
            throw new IllegalArgumentException("childHeap is required");
        }
        if (!containerMode() && (delosClasspath.isBlank() || upstreamDerbyClasspath.isBlank()
                || h2Classpath.isBlank()
                || (configuredTargets.contains(Target.SQLITE) && sqliteClasspath.isBlank()))) {
            throw new IllegalArgumentException("Embedded benchmark classpaths are required");
        }
        if (containerMode()) {
            if (drdaProtocolDiagnostic || drdaServerPhaseDiagnostic || f02ScaleSurfaceDiagnostic
                    || f04DrdaRowAdvanceJfrDiagnostic || f04FanoutJfrDiagnostic) {
                if (delosClientClasspath.isBlank()) {
                    String diagnosticName = f02ScaleSurfaceDiagnostic
                            ? "F02 scale-surface diagnostic"
                            : f04DrdaRowAdvanceJfrDiagnostic
                                    ? "F04 row-advance JFR diagnostic"
                                    : f04FanoutJfrDiagnostic
                                            ? "F04 fanout JFR diagnostic"
                                            : "DRDA diagnostic";
                    throw new IllegalArgumentException(
                            diagnosticName + " requires the Delos network client classpath");
                }
            } else if (mutationSchemaAttribution || f08TransactionStatusAttribution) {
                if (delosClientClasspath.isBlank() || upstreamDerbyClientClasspath.isBlank()) {
                    String diagnosticName = mutationSchemaAttribution
                            ? "F08 mutation-schema attribution"
                            : "F08 transaction-status attribution";
                    throw new IllegalArgumentException(
                            diagnosticName + " client classpaths are required");
                }
            } else if (gen2A1ThroughputSentinel || gen2BThroughputSentinel || gen2C3UpdateThroughputSentinel) {
                if (delosClientClasspath.isBlank()) {
                    String sentinelName = gen2A1ThroughputSentinel
                            ? "Gen2-A1"
                            : gen2BThroughputSentinel ? "Gen2-B" : "Gen2-C3 UPDATE";
                    throw new IllegalArgumentException(
                            sentinelName + " throughput sentinel requires the Delos network client classpath");
                }
            } else if (gen2C3PostgresqlUpdateComparison) {
                if (delosClientClasspath.isBlank() || postgresqlClasspath.isBlank()) {
                    throw new IllegalArgumentException(
                            "Gen2-C3 PostgreSQL UPDATE comparison requires Delos and PostgreSQL client classpaths");
                }
            } else if (gen2C3ProjectedCurrentRead) {
                if (delosClientClasspath.isBlank()) {
                    throw new IllegalArgumentException(
                            "Gen2-C3 projected-current read experiment requires the Delos network client classpath");
                }
            } else if (delosClientClasspath.isBlank() || upstreamDerbyClientClasspath.isBlank()
                    || h2Classpath.isBlank() || postgresqlClasspath.isBlank() || mariadbClasspath.isBlank()) {
                throw new IllegalArgumentException("Server benchmark client classpaths are required");
            }
            if (target == null) {
                if (!Files.isDirectory(delosRuntimeDirectory)) {
                    throw new IllegalArgumentException("Delos runtime directory does not exist: "
                            + delosRuntimeDirectory);
                }
                if (drdaProtocolDiagnostic) {
                    if (!Files.isDirectory(upstreamDerbyServerRuntimeDirectory)) {
                        throw new IllegalArgumentException(
                                "Upstream Derby server runtime directory does not exist: "
                                        + upstreamDerbyServerRuntimeDirectory);
                    }
                    if (delosServerImage.isBlank() || upstreamDerbyServerImage.isBlank()) {
                        throw new IllegalArgumentException("DRDA server benchmark images are required");
                    }
                } else if (drdaServerPhaseDiagnostic || f02ScaleSurfaceDiagnostic
                        || f04DrdaRowAdvanceJfrDiagnostic || f04FanoutJfrDiagnostic) {
                    if (delosServerImage.isBlank()) {
                        String diagnosticName = f02ScaleSurfaceDiagnostic
                                ? "F02 scale-surface diagnostic"
                                : f04DrdaRowAdvanceJfrDiagnostic
                                        ? "F04 row-advance JFR diagnostic"
                                        : f04FanoutJfrDiagnostic
                                                ? "F04 fanout JFR diagnostic"
                                                : "DRDA server phase evidence";
                        throw new IllegalArgumentException(
                                diagnosticName + " requires the Delos server image");
                    }
                } else if (mutationSchemaAttribution || f08TransactionStatusAttribution) {
                    if (!Files.isDirectory(upstreamDerbyServerRuntimeDirectory)) {
                        throw new IllegalArgumentException(
                                "Upstream Derby server runtime directory does not exist: "
                                        + upstreamDerbyServerRuntimeDirectory);
                    }
                    if (delosServerImage.isBlank() || upstreamDerbyServerImage.isBlank()) {
                        String diagnosticName = mutationSchemaAttribution
                                ? "F08 mutation-schema attribution"
                                : "F08 transaction-status attribution";
                        throw new IllegalArgumentException(
                                diagnosticName + " server images are required");
                    }
                } else if (gen2A1ThroughputSentinel || gen2BThroughputSentinel || gen2C3UpdateThroughputSentinel) {
                    if (delosServerImage.isBlank()) {
                        String sentinelName = gen2A1ThroughputSentinel
                                ? "Gen2-A1"
                                : gen2BThroughputSentinel ? "Gen2-B" : "Gen2-C3 UPDATE";
                        throw new IllegalArgumentException(
                                sentinelName + " throughput sentinel requires the Delos server image");
                    }
                } else if (gen2C3PostgresqlUpdateComparison) {
                    if (delosServerImage.isBlank() || postgresqlImage.isBlank()) {
                        throw new IllegalArgumentException(
                                "Gen2-C3 PostgreSQL UPDATE comparison requires Delos and PostgreSQL server images");
                    }
                } else if (gen2C3ProjectedCurrentRead) {
                    if (delosServerImage.isBlank()) {
                        throw new IllegalArgumentException(
                                "Gen2-C3 projected-current read experiment requires the Delos server image");
                    }
                } else {
                    if (!Files.isDirectory(upstreamDerbyServerRuntimeDirectory)) {
                        throw new IllegalArgumentException(
                                "Upstream Derby server runtime directory does not exist: "
                                        + upstreamDerbyServerRuntimeDirectory);
                    }
                    if (!Files.isRegularFile(h2ServerJar)) {
                        throw new IllegalArgumentException("H2 server jar does not exist: " + h2ServerJar);
                    }
                    if (delosServerImage.isBlank() || upstreamDerbyServerImage.isBlank()
                            || postgresqlImage.isBlank() || mariadbImage.isBlank()) {
                        throw new IllegalArgumentException("Server benchmark images are required");
                    }
                }
            }
        }
        if (target != null) {
            if (run < 1) {
                throw new IllegalArgumentException("worker run must be positive");
            }
            if (target.isContainer() && remoteJdbcUrl.isBlank()) {
                throw new IllegalArgumentException("remoteJdbcUrl is required for " + target.id());
            }
        }
    }

    int transactionsPerClient(Spec spec, int rowCount) {
        if (spec.workload().isRangeScan()) {
            int rangeRows = spec.workload().rangeRows(rowCount);
            long byRowBudget = rangeScanTargetRowsPerClient / rangeRows;
            long queries = Math.max(
                    rangeScanMinQueriesPerClient,
                    Math.min((long) rangeScanMaxQueriesPerClient, byRowBudget));
            long transactions = (queries + spec.operationsPerTransaction() - 1L)
                    / spec.operationsPerTransaction();
            return Math.toIntExact(Math.max(1L, transactions));
        }
        if (fixedWorkloadOperationBudgetPerClient == 0
                || spec.workload().fixedOperationsPerTransaction() < 0) {
            return transactionsPerClient;
        }
        if (spec.operationsPerTransaction() == 0) {
            return fixedWorkloadOperationBudgetPerClient;
        }
        return Math.max(1, fixedWorkloadOperationBudgetPerClient / spec.operationsPerTransaction());
    }

    List<Integer> rowCounts() {
        return integerList(rows);
    }

    List<Integer> clientValues() {
        return integerList(clients);
    }

    List<Integer> widthValues() {
        return integerList(widths);
    }

    List<Workload> workloadValues() {
        List<Workload> values = new ArrayList<>();
        for (String token : workloads.split(",")) {
            Workload value;
            try {
                value = Workload.valueOf(token.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Unknown concurrency workload: " + token, failure);
            }
            if (values.contains(value)) {
                throw new IllegalArgumentException("Duplicate concurrency workload: " + value);
            }
            values.add(value);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("At least one concurrency workload is required");
        }
        return List.copyOf(values);
    }

    List<Target> targetValues() {
        List<Target> values = new ArrayList<>();
        for (String token : targets.split(",")) {
            Target value = Target.parse(token.trim());
            if (values.contains(value)) {
                throw new IllegalArgumentException("Duplicate target: " + value.id());
            }
            values.add(value);
        }
        List<Target> configured = List.copyOf(values);
        if (!currentBaselineEnabled()) {
            return configured;
        }
        List<Target> embedded = List.of(
                Target.DELOS_HEAP, Target.DELOS_MVCC, Target.UPSTREAM_DERBY, Target.H2, Target.SQLITE);
        if (configured.equals(embedded) || configured.equals(CURRENT_BASELINE_EMBEDDED_TARGETS)) {
            return CURRENT_BASELINE_EMBEDDED_TARGETS;
        }
        if (configured.equals(SERVER_PRODUCT_TARGETS) || configured.equals(CURRENT_BASELINE_SERVER_TARGETS)) {
            return CURRENT_BASELINE_SERVER_TARGETS;
        }
        throw new IllegalArgumentException(
                "Phase-1 current baseline only supports the standard embedded/server fitness target sets: "
                        + configured);
    }

    private static boolean currentBaselineEnabled() {
        return Boolean.parseBoolean(System.getenv().getOrDefault(CURRENT_BASELINE_ENV, "false"));
    }

    private static Path requiredEnvironmentPath(String name) {
        String value = System.getenv().getOrDefault(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Phase-1 current baseline requires environment variable " + name);
        }
        return Path.of(value);
    }

    private static Path currentBaselineSuffix(Path reportDirectory) {
        Path baselineRoot = requiredEnvironmentPath(CURRENT_BASELINE_REPORT_ROOT_ENV)
                .toAbsolutePath().normalize();
        Path absoluteReport = reportDirectory.toAbsolutePath().normalize();
        if (absoluteReport.startsWith(baselineRoot)) {
            Path suffix = baselineRoot.relativize(absoluteReport);
            if (!suffix.toString().isEmpty()) {
                return suffix;
            }
        }
        for (int index = 0; index < reportDirectory.getNameCount(); index++) {
            if (!"architecture-fitness".equals(reportDirectory.getName(index).toString())) {
                continue;
            }
            int start = index + 1;
            if (start < reportDirectory.getNameCount()
                    && "current-baseline".equals(reportDirectory.getName(start).toString())) {
                start++;
            }
            Path suffix = Path.of("");
            for (int part = start; part < reportDirectory.getNameCount(); part++) {
                suffix = suffix.resolve(reportDirectory.getName(part).toString());
            }
            if (suffix.toString().isEmpty()) {
                throw new IllegalArgumentException(
                        "Unable to derive current-baseline report suffix from " + reportDirectory);
            }
            return suffix;
        }
        throw new IllegalArgumentException(
                "Phase-1 current baseline expects reportDirectory below an architecture-fitness root: "
                        + reportDirectory);
    }

    boolean containerMode() {
        return targetValues().stream().anyMatch(Target::isContainer);
    }

    String classpath(Target value) {
        return switch (value) {
            case DELOS_HEAP, DELOS_MVCC -> delosClasspath;
            case UPSTREAM_DERBY -> upstreamDerbyClasspath;
            case H2 -> h2Classpath;
            case SQLITE -> sqliteClasspath;
            case DELOS_HEAP_DRDA, DELOS_MVCC_DRDA -> delosClientClasspath;
            case UPSTREAM_DERBY_DRDA -> drdaProtocolEvidenceEnabled()
                    ? delosClientClasspath
                    : upstreamDerbyClientClasspath;
            case H2_SERVER -> h2Classpath;
            case POSTGRESQL -> postgresqlClasspath;
            case MARIADB -> mariadbClasspath;
            case FIREBIRD -> firebirdClasspath;
        };
    }

    private static Path path(String property, String defaultValue) {
        return Path.of(System.getProperty(property, defaultValue));
    }

    private static void parsePositive(String raw, String name, int minimum) {
        for (int value : integerList(raw)) {
            if (value < minimum) {
                throw new IllegalArgumentException(name + " values must be at least " + minimum + ": " + value);
            }
        }
    }
    static String benchmarkContractId() {
        return System.getProperty(PREFIX + "benchmarkContract", "").trim();
    }

    static boolean btreeInsertBranchRoutingSnapshotServerEnabled() {
        return Boolean.getBoolean(PREFIX + "btreeInsertBranchRoutingSnapshotServer");
    }

    static boolean btreeInsertRootRoutingSnapshotServerEnabled() {
        return Boolean.getBoolean(PREFIX + "btreeInsertRootRoutingSnapshotServer");
    }

    static boolean drdaProtocolEvidenceEnabled() {
        return Boolean.getBoolean(PREFIX + "drdaProtocolEvidence");
    }

    static int drdaServerPhaseCaptureOpenQueries() {
        return Integer.getInteger(PREFIX + "drdaServerPhaseCaptureOpenQueries", 20);
    }

    static boolean drdaServerPhaseEvidenceEnabled() {
        return Boolean.getBoolean(PREFIX + "drdaServerPhaseEvidence");
    }

    static int drdaServerPhaseSkipOpenQueries() {
        return Integer.getInteger(PREFIX + "drdaServerPhaseSkipOpenQueries", 20);
    }

    static boolean f02EmbeddedConcurrencyDiagnosticEnabled() {
        return Boolean.getBoolean(PREFIX + "f02EmbeddedConcurrencyDiagnostic");
    }

    static boolean f02ScaleSurfaceDiagnosticEnabled() {
        return Boolean.getBoolean(PREFIX + "f02ScaleSurfaceDiagnostic");
    }

    static boolean f04DrdaRowAdvanceJfrDiagnosticEnabled() {
        return Boolean.getBoolean(PREFIX + "f04DrdaRowAdvanceJfrDiagnostic");
    }

    static boolean f04DrdaServerPhaseDiagnosticEnabled() {
        return Boolean.getBoolean(PREFIX + "f04DrdaServerPhaseDiagnostic");
    }

    static boolean f04FanoutJfrDiagnosticEnabled() {
        return Boolean.getBoolean(PREFIX + "f04FanoutJfrDiagnostic");
    }

    static boolean f04ServerPlanCostMatrixEnabled() {
        return Boolean.getBoolean(PREFIX + "f04ServerPlanCostMatrix");
    }

    static boolean f07DrdaServerPhaseDiagnosticEnabled() {
        return Boolean.getBoolean(PREFIX + "f07DrdaServerPhaseDiagnostic");
    }

    static boolean f08ContentionScalingSliceEnabled() {
        return Boolean.getBoolean(PREFIX + "f08ContentionScalingSlice");
    }

    static boolean f08FixedCostClientScalingEnabled() {
        return Boolean.getBoolean(PREFIX + "f08FixedCostClientScaling");
    }

    static boolean f08MultiRowInsertControlEnabled() {
        return Boolean.getBoolean(PREFIX + "f08MultiRowInsertControl");
    }

    static boolean f08TransactionStatusCrossEngineEnabled() {
        return Boolean.getBoolean(PREFIX + "f08TransactionStatusCrossEngine");
    }

    static boolean freshRealisticTransactionFitnessEnabled() {
        return Boolean.getBoolean(PREFIX + "freshRealisticTransactionFitness");
    }

    static boolean gen2A1ThroughputSentinelEnabled() {
        return Boolean.getBoolean(PREFIX + "gen2A1ThroughputSentinel");
    }

    static boolean gen2BThroughputSentinelEnabled() {
        return Boolean.getBoolean(PREFIX + "gen2BThroughputSentinel");
    }

    static boolean gen2C3PostgresqlUpdateComparisonEnabled() {
        return Boolean.getBoolean(PREFIX + "gen2C3PostgresqlUpdateComparison");
    }

    static boolean gen2C3UpdateThroughputSentinelEnabled() {
        return Boolean.getBoolean(PREFIX + "gen2C3UpdateThroughputSentinel");
    }

    static boolean hostProcessDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "hostProcessDiagnostics");
    }

    static int hostProcessSampleSeconds() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostProcessSampleSeconds", "5"));
    }

    static int hostStateCooldownAfterRun() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownAfterRun", "0"));
    }

    static double hostStateCooldownMaximumCpuLoad() {
        return Double.parseDouble(System.getProperty(PREFIX + "hostStateCooldownMaximumCpuLoad", "0.35"));
    }

    static double hostStateCooldownMaximumLoadPerProcessor() {
        return Double.parseDouble(System.getProperty(
                PREFIX + "hostStateCooldownMaximumLoadPerProcessor", "1.25"));
    }

    static int hostStateCooldownMaximumSeconds() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownMaximumSeconds", "300"));
    }

    static int hostStateCooldownMinimumSeconds() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownMinimumSeconds", "60"));
    }

    static int hostStateCooldownQuietSamples() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownQuietSamples", "2"));
    }

    static int hostStateCooldownSampleSeconds() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownSampleSeconds", "10"));
    }

    static boolean hostStateDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "hostStateDiagnostics");
    }

    static boolean hostStateRecoveryEnabled() {
        return Boolean.getBoolean(PREFIX + "hostStateRecovery");
    }

    static String insertTableShape() {
        String value = System.getProperty(PREFIX + "insertTableShape", "FULL_INDEXED").trim();
        return value.isEmpty() ? "FULL_INDEXED" : value.toUpperCase(Locale.ROOT);
    }

    static List<Integer> integerList(String raw) {
        List<Integer> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            values.add(Integer.parseInt(token.trim()));
        }
        return List.copyOf(values);
    }

    static boolean mutationSchemaAttributionEnabled() {
        return Boolean.getBoolean(PREFIX + "mutationSchemaAttribution");
    }

    static boolean mvccGen2A1ServerEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccGen2A1Server");
    }

    static boolean mvccGen2BServerEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccGen2BServer");
    }

    static boolean mvccGen2C1ServerEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccGen2C1Server");
    }

    static boolean mvccGen2C3ReadServerEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccGen2C3ReadServer");
    }

    static boolean mvccGen2C3UpdateServerEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccGen2C3UpdateServer");
    }

    static boolean mvccGen2ProjectedCurrentReadEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccGen2ProjectedCurrentRead");
    }

    static boolean mvccGen2SinglePassCurrentScanEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                PREFIX + "mvccGen2SinglePassCurrentScan", "false"));
    }

    static boolean mvccGen2TransactionStatusVisibilityServerEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccGen2TransactionStatusVisibilityServer");
    }

    static boolean mvccPhysicalRowLocationCostEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                PREFIX + "mvccPhysicalRowLocationCost", "true"));
    }

    static boolean mvccPhysicalScanCostEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                PREFIX + "mvccPhysicalScanCost", "true"));
    }

    static boolean mvccRefreshMultiJoinStatisticsEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccRefreshMultiJoinStatistics");
    }

    static boolean postModernizationMutationCheckpointEnabled() {
        return Boolean.getBoolean(PREFIX + "postModernizationMutationCheckpoint");
    }

    static boolean refreshSimpleJoinStatisticsEnabled() {
        return Boolean.getBoolean(PREFIX + "refreshSimpleJoinStatistics");
    }

    static boolean sqlSemanticOracleEnabled() {
        return Boolean.getBoolean(PREFIX + "sqlSemanticOracle");
    }

    static String targetIds(List<Target> targets) {
        StringBuilder value = new StringBuilder();
        for (Target target : targets) {
            if (!value.isEmpty()) {
                value.append(',');
            }
            value.append(target.id());
        }
        return value.toString();
    }
    static String rawStoreLogBufferSizeServerOverride() {
        return System.getProperty(PREFIX + "rawStoreLogBufferSizeServer", "").trim();
    }

    static boolean rawStoreDurabilityTestNoSyncServerEnabled() {
        return Boolean.getBoolean(PREFIX + "rawStoreDurabilityTestNoSyncServer");
    }
}
