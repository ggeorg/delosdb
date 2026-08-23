/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** JDBC concurrency comparison with deterministic semantic verification. */
public final class DelosJdbcCrossEngineConcurrency {
    private static final String PREFIX = "delosdb.benchmark.crossEngineConcurrency.";
    private static final long SEED = 0x5DE10DBL;
    private static final List<Target> READ_DECOMPOSITION_TARGETS = List.of(
            Target.DELOS_HEAP, Target.UPSTREAM_DERBY, Target.H2);
    private static final List<Target> RANGE_SCAN_JFR_TARGETS = List.of(
            Target.DELOS_HEAP, Target.DELOS_MVCC, Target.UPSTREAM_DERBY);
    private static final List<Target> RANGE_BULK_FETCH_TARGETS = List.of(
            Target.DELOS_HEAP, Target.UPSTREAM_DERBY);
    private static final List<Target> SERVER_PRODUCT_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA,
            Target.DELOS_MVCC_DRDA,
            Target.UPSTREAM_DERBY_DRDA,
            Target.H2_SERVER,
            Target.POSTGRESQL,
            Target.MARIADB);
    private static final List<Target> EMBEDDED_REFERENCE_CANARY_TARGETS = List.of(
            Target.UPSTREAM_DERBY, Target.H2, Target.SQLITE);
    private static final List<Target> SERVER_REFERENCE_CANARY_TARGETS = List.of(
            Target.UPSTREAM_DERBY_DRDA, Target.H2_SERVER, Target.POSTGRESQL, Target.MARIADB);
    private static final List<Target> MVCC_ONLY_DIAGNOSTIC_TARGETS = List.of(Target.DELOS_MVCC);
    private static final List<Target> HOST_RECOVERY_DIAGNOSTIC_TARGETS = List.of(Target.H2, Target.SQLITE);
    private static final List<Target> DRDA_PROTOCOL_EVIDENCE_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA, Target.UPSTREAM_DERBY_DRDA);
    private static final List<Target> DRDA_SERVER_PHASE_EVIDENCE_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA);
    private static final List<Target> CURRENT_BASELINE_EMBEDDED_TARGETS = List.of(
            Target.DELOS_HEAP, Target.DELOS_MVCC);
    private static final List<Target> CURRENT_BASELINE_SERVER_TARGETS = List.of(
            Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA);
    private static final String CURRENT_BASELINE_ENV = "DELOSDB_CURRENT_BASELINE";
    private static final String CURRENT_BASELINE_REPORT_ROOT_ENV = "DELOSDB_CURRENT_BASELINE_REPORT_ROOT";
    private static final String CURRENT_BASELINE_DATABASE_ROOT_ENV = "DELOSDB_CURRENT_BASELINE_DATABASE_ROOT";
    private static final int DRDA_PROTOCOL_COUNTER_COUNT = 16;
    private static final int DRDA_PREPARE_COMMANDS = 4;
    private static final int DRDA_OPEN_QUERY_FLOW_NANOS = 12;
    private static final int DRDA_CONTINUE_QUERY_FLOW_NANOS = 13;
    private static final int DRDA_EXECUTE_FLOW_NANOS = 14;
    private static final int DRDA_COMMIT_FLOW_NANOS = 15;
    private static final String CSV_HEADER =
            "target,product,productVersion,driverVersion,workload,clients,operationsPerTransaction,"
                    + "transactionsPerClient,rowCount,payloadSize,fixtureCommitBatchSize,warmups,warmupElapsedNanos,iterations,"
                    + "measuredTransactions,measuredOperations,retryableConflictRetries,elapsedNanos,"
                    + "transactionsPerSecond,operationsPerSecond,inverseThroughputNanosPerTransaction,"
                    + "semanticFingerprint,run";
    private static final String ORACLE_CSV_HEADER =
            "target,workload,clients,operationsPerTransaction,rowCount,kind,count,fingerprint,run";
    private static final String DRDA_PROTOCOL_CSV_HEADER =
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

    private DelosJdbcCrossEngineConcurrency() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.fromSystemProperties();
        options.validate();
        if (args.length == 1 && "worker".equals(args[0])) {
            runWorker(options);
        } else if (args.length == 0) {
            runCoordinator(options);
        } else {
            throw new IllegalArgumentException("Expected no argument or exactly 'worker'");
        }
    }

    private static void runCoordinator(Options options) throws Exception {
        if (!"false".equals(System.getProperty(PREFIX + "sane"))) {
            throw new IllegalStateException(
                    "Cross-engine concurrency comparison requires -Pdelosdb.sane=false");
        }
        deleteRecursively(options.reportDirectory());
        deleteRecursively(options.databaseRoot());
        Files.createDirectories(options.reportDirectory().resolve("workers"));
        Files.createDirectories(options.reportDirectory().resolve("logs"));
        Files.createDirectories(options.databaseRoot());

        if (options.containerMode()) {
            prepareContainerEnvironment(options);
        }

        if (hostStateDiagnosticsEnabled()) {
            writeHostStateDiagnosticHeader(options);
            captureHostState(options, "SUITE_START", 0, "-");
        }
        if (hostProcessDiagnosticsEnabled()) {
            writeHostProcessDiagnosticHeader(options);
            captureHostProcessSample(options, "SUITE_START", 0, "-", null);
        }
        if (hostStateRecoveryEnabled()) {
            writeHostRecoveryConfiguration(options);
        }

        try {
            for (int run = 1; run <= options.runs(); run++) {
                List<Target> targets = new ArrayList<>(options.targetValues());
                if (((run - 1) & 2) != 0) {
                    Collections.reverse(targets);
                }
                for (Target target : targets) {
                    if (hostStateDiagnosticsEnabled()) {
                        captureHostState(options, "BEFORE_WORKER", run, target.id());
                    }
                    try {
                        if (target.isContainer()) {
                            try (ContainerServer server = startContainer(options, target, run)) {
                                try {
                                    launchWorker(options, target, run, server.endpoint());
                                } catch (Throwable failure) {
                                    failure.addSuppressed(new IllegalStateException(
                                            "Container log for " + target.id() + ":\n" + server.logs()));
                                    throw failure;
                                }
                            }
                        } else {
                            launchWorker(options, target, run, null);
                        }
                    } finally {
                        if (hostStateDiagnosticsEnabled()) {
                            captureHostState(options, "AFTER_WORKER", run, target.id());
                        }
                    }
                }
                if (hostStateRecoveryEnabled() && run == hostStateCooldownAfterRun()) {
                    coolDownHost(options, run);
                }
            }
        } finally {
            if (hostStateDiagnosticsEnabled()) {
                captureHostState(options, "SUITE_END", options.runs(), "-");
            }
            if (hostProcessDiagnosticsEnabled()) {
                captureHostProcessSample(options, "SUITE_END", options.runs(), "-", null);
            }
        }

        List<Row> rows = loadRows(options);
        validateRows(options, rows);
        if (sqlSemanticOracleEnabled()) {
            List<OracleEvidence> oracleEvidence = loadOracleEvidence(options);
            validateOracleEvidence(options, oracleEvidence);
            writeOracleEvidence(options, oracleEvidence);
        }
        if (drdaProtocolEvidenceEnabled()) {
            List<DrdaProtocolEvidence> protocolEvidence = loadDrdaProtocolEvidence(options);
            validateDrdaProtocolEvidence(options, protocolEvidence);
            writeDrdaProtocolEvidence(options, protocolEvidence);
        }
        if (drdaServerPhaseEvidenceEnabled()) {
            List<DrdaServerPhaseEvidence> serverEvidence = loadDrdaServerPhaseEvidence(options);
            validateDrdaServerPhaseEvidence(options, serverEvidence);
            writeDrdaServerPhaseEvidence(options, serverEvidence);
        }
        writeMergedCsv(options, rows);
        writeRatioCsv(options, rows);
        writeScalingCsv(options, rows);
        writeDispersionCsv(options, rows);
        writeRangeScanCsv(options, rows);
        writeCapabilityCsv(options);
        writeSummary(options, rows);
        if (options.containerMode()) {
            writeContainerSemanticEvidence(options, rows);
        }
    }

    private static void launchWorker(
            Options options, Target target, int run, ServerEndpoint endpoint) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(options.javaExecutable().toString());
        command.add("-Xms" + options.childHeap());
        command.add("-Xmx" + options.childHeap());
        command.add("-XX:+AlwaysPreTouch");
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean("delosdb.experimental.heapPageReadImage")) {
            command.add("-Ddelosdb.experimental.heapPageReadImage=true");
        }
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean("delosdb.diagnostic.heapPageReadImage")) {
            command.add("-Ddelosdb.diagnostic.heapPageReadImage=true");
        }
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean(PREFIX + "heapPageReadImageDiagnostics")) {
            addProperty(command, "heapPageReadImageDiagnostics", true);
        }
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean("delosdb.experimental.fastRecordReadLock")) {
            command.add("-Ddelosdb.experimental.fastRecordReadLock=true");
        }
        if (target == Target.DELOS_MVCC) {
            String slots = System.getProperty(
                    PREFIX + "mvccCurrentRowReadCacheSlots", "").trim();
            if (!slots.isEmpty()) {
                command.add("-Ddelosdb.mvcc.currentRowReadCache.slots=" + slots);
            }
            if (Boolean.getBoolean(PREFIX + "mvccSnapshotLeaseDiagnostics")) {
                command.add("-Ddelosdb.diagnostic.mvccSnapshotLease=true");
                addProperty(command, "mvccSnapshotLeaseDiagnostics", true);
            }
            if (Boolean.getBoolean(PREFIX + "mvccSnapshotLeaseRegistry")) {
                command.add("-Ddelosdb.experimental.mvccSnapshotLeaseRegistry=true");
                String leaseSlots = System.getProperty(
                        PREFIX + "mvccSnapshotLeaseRegistrySlots", "").trim();
                if (!leaseSlots.isEmpty()) {
                    command.add("-Ddelosdb.experimental.mvccSnapshotLeaseRegistry.slots="
                            + leaseSlots);
                }
            }
        }
        String rangeBulkFetchDefault = System.getProperty(
                PREFIX + "rangeBulkFetchDefault", "").trim();
        if (!rangeBulkFetchDefault.isEmpty()) {
            command.add("-D" + PREFIX + "rangeBulkFetchDefault=" + rangeBulkFetchDefault);
        }
        if (shouldProfileWorker(target)) {
            Path profileDirectory = options.reportDirectory().resolve("profiles");
            Files.createDirectories(profileDirectory);
            Path recording = profileDirectory.resolve(String.format(
                    Locale.ROOT, "%02d-%s.jfr", run, target.id()));
            Path compilationLog = profileDirectory.resolve(String.format(
                    Locale.ROOT, "%02d-%s-hotspot.log", run, target.id()));
            if (!System.getProperty(PREFIX + "profileTargets", "").isBlank()) {
                command.add("-XX:FlightRecorderOptions=stackdepth=256");
            }
            command.add("-XX:+UnlockDiagnosticVMOptions");
            command.add("-XX:+LogCompilation");
            command.add("-XX:LogFile=" + compilationLog);
            command.add("-XX:StartFlightRecording=filename=" + recording
                    + ",settings=profile,dumponexit=true,maxsize=512m");
        }
        if (target == Target.SQLITE) {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
        if (sqlSemanticOracleEnabled()) {
            addProperty(command, "sqlSemanticOracle", true);
        }
        if (drdaProtocolEvidenceEnabled() && target.isDrda()) {
            command.add("-Ddelosdb.diagnostic.drdaProtocolEvidence=true");
            addProperty(command, "drdaProtocolEvidence", true);
        }
        if (drdaServerPhaseEvidenceEnabled() && target.isDrda()) {
            addProperty(command, "drdaServerPhaseEvidence", true);
        }
        command.add("-cp");
        command.add(options.benchmarkClasses() + java.io.File.pathSeparator + options.classpath(target));
        addProperty(command, "targets", targetIds(options.targetValues()));
        addProperty(command, "target", target.id());
        addProperty(command, "run", run);
        addProperty(command, "databaseRoot", options.databaseRoot());
        addProperty(command, "reportDirectory", options.reportDirectory().resolve("workers"));
        addProperty(command, "rows", options.rows());
        addProperty(command, "sqliteSharedCache", options.sqliteSharedCache());
        addProperty(command, "clients", options.clients());
        addProperty(command, "widths", options.widths());
        addProperty(command, "workloads", options.workloads());
        addProperty(command, "transactionsPerClient", options.transactionsPerClient());
        addProperty(command, "fixedWorkloadOperationBudgetPerClient",
                options.fixedWorkloadOperationBudgetPerClient());
        addProperty(command, "rangeScanTargetRowsPerClient", options.rangeScanTargetRowsPerClient());
        addProperty(command, "rangeScanMinQueriesPerClient", options.rangeScanMinQueriesPerClient());
        addProperty(command, "rangeScanMaxQueriesPerClient", options.rangeScanMaxQueriesPerClient());
        addProperty(command, "h2RangeFetchSize", options.h2RangeFetchSize());
        addProperty(command, "payload", options.payload());
        addProperty(command, "fixtureBatch", options.fixtureBatch());
        addProperty(command, "warmups", options.warmups());
        addProperty(command, "iterations", options.iterations());
        addProperty(command, "minimumWarmupSeconds", options.minimumWarmupSeconds());
        addProperty(command, "maximumWarmupIterations", options.maximumWarmupIterations());
        addProperty(command, "minimumMeasuredSeconds", options.minimumMeasuredSeconds());
        addProperty(command, "maximumMeasuredIterations", options.maximumMeasuredIterations());
        addProperty(command, "caseTimeoutSeconds", options.caseTimeoutSeconds());
        addProperty(command, "closeCursorsAtCommit",
                Boolean.getBoolean(PREFIX + "closeCursorsAtCommit"));
        if (endpoint != null) {
            addProperty(command, "remoteJdbcUrl", endpoint.jdbcUrl());
            addProperty(command, "remoteUser", endpoint.user());
            addProperty(command, "remotePassword", endpoint.password());
        }
        command.add(DelosJdbcCrossEngineConcurrency.class.getName());
        command.add("worker");

        Path log = options.reportDirectory().resolve("logs")
                .resolve(String.format(Locale.ROOT, "%02d-%s.log", run, target.id()));
        Process process = new ProcessBuilder(command)
                .directory(options.projectDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        Thread hostSampler = hostProcessDiagnosticsEnabled()
                ? startHostProcessSampler(options, target, run, process)
                : null;
        boolean completed;
        try {
            completed = options.workerTimeoutSeconds() == 0
                    ? waitForUnbounded(process)
                    : process.waitFor(options.workerTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
                throw new IllegalStateException("Concurrency worker timed out: target=" + target.id()
                        + ", run=" + run + ", workerTimeoutSeconds=" + options.workerTimeoutSeconds()
                        + ", log=" + log);
            }
        } finally {
            stopHostProcessSampler(hostSampler);
        }
        int status = process.exitValue();
        if (status != 0) {
            List<String> lines = Files.exists(log) ? Files.readAllLines(log) : List.of();
            int from = Math.max(0, lines.size() - 40);
            throw new IllegalStateException("Concurrency worker failed: target=" + target.id()
                    + ", run=" + run + ", exit=" + status + ", log=" + log
                    + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines.subList(from, lines.size()))));
        }
    }

    private static boolean waitForUnbounded(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    private static boolean shouldProfileWorker(Target target) {
        String configuredTargets = System.getProperty(PREFIX + "profileTargets", "").trim();
        if (!configuredTargets.isEmpty()) {
            for (String configured : configuredTargets.split(",")) {
                if (target.id().equalsIgnoreCase(configured.trim())) {
                    return true;
                }
            }
        }
        return target == Target.DELOS_MVCC
                && Boolean.getBoolean(PREFIX + "profileDelosMvccWorkers");
    }

    private static void addProperty(List<String> command, String name, Object value) {
        command.add("-D" + PREFIX + name + '=' + value);
    }

    private static void prepareContainerEnvironment(Options options) throws Exception {
        CommandResult docker = runCommand(options.containerStartupTimeoutSeconds(),
                List.of("docker", "version", "--format", "{{.Server.Version}}"));
        if (docker.exitCode() != 0) {
            throw new IllegalStateException("Docker is required for server-container benchmarks:\n"
                    + docker.output());
        }
        Map<String, String> images = new LinkedHashMap<>();
        for (Target target : options.targetValues()) {
            if (target.isContainer()) {
                String image = target.containerImage(options);
                if (!images.containsKey(image)) {
                    images.put(image, ensureImage(options, image));
                }
            }
        }
        writeContainerManifest(options, docker.output().trim(), images);
    }

    private static String ensureImage(Options options, String image) throws Exception {
        CommandResult id = runCommand(20,
                List.of("docker", "image", "inspect", "--format", "{{.Id}}", image));
        if (id.exitCode() != 0) {
            CommandResult pull = runCommand(Math.max(120, options.containerStartupTimeoutSeconds()),
                    List.of("docker", "pull", image));
            if (pull.exitCode() != 0) {
                throw new IllegalStateException("Could not pull benchmark image " + image + ":\n" + pull.output());
            }
            id = runCommand(20,
                    List.of("docker", "image", "inspect", "--format", "{{.Id}}", image));
        }
        if (id.exitCode() != 0) {
            throw new IllegalStateException("Could not inspect benchmark image " + image + ":\n" + id.output());
        }
        CommandResult digests = runCommand(20,
                List.of("docker", "image", "inspect", "--format", "{{json .RepoDigests}}", image));
        String digestEvidence = digests.exitCode() == 0 ? digests.output().trim() : "unavailable";
        return "id=" + id.output().trim() + " repoDigests=" + digestEvidence;
    }

    private static ContainerServer startContainer(Options options, Target target, int run) throws Exception {
        String name = "delos-bench-" + target.id().replace('_', '-') + '-'
                + ProcessHandle.current().pid() + '-' + run;
        runCommand(20, List.of("docker", "rm", "-f", name));
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "-d", "--rm", "--name", name,
                "-p", "127.0.0.1::" + target.containerPort()));
        switch (target) {
            case DELOS_HEAP_DRDA, DELOS_MVCC_DRDA -> {
                command.add("--mount");
                command.add("type=bind,src=" + options.delosRuntimeDirectory().toAbsolutePath().normalize()
                        + ",dst=/opt/delos/lib,readonly");
                command.add("--workdir");
                command.add("/var/lib/delosdb");
                command.add(target.containerImage(options));
                List<String> javaCommand = new ArrayList<>(List.of(
                        "java", "-Xms" + options.childHeap(), "-Xmx" + options.childHeap(),
                        "-XX:+AlwaysPreTouch"));
                if (target == Target.DELOS_HEAP_DRDA) {
                    javaCommand.add("-Ddelosdb.experimental.heapPageReadImage=true");
                    javaCommand.add("-Ddelosdb.experimental.fastRecordReadLock=true");
                }
                if (drdaServerPhaseEvidenceEnabled()) {
                    javaCommand.add("-Ddelosdb.diagnostic.drdaServerPhaseEvidence=true");
                    javaCommand.add("-Ddelosdb.diagnostic.drdaServerPhaseEvidence.skipOpenQueries=20");
                    javaCommand.add("-Ddelosdb.diagnostic.drdaServerPhaseEvidence.captureOpenQueries=20");
                }
                javaCommand.addAll(List.of(
                        "-cp", "/opt/delos/lib/*",
                        "org.apache.derby.drda.NetworkServerControl", "start",
                        "-h", "0.0.0.0", "-p", Integer.toString(target.containerPort())));
                command.addAll(javaCommand);
            }
            case UPSTREAM_DERBY_DRDA -> {
                command.add("--mount");
                command.add("type=bind,src="
                        + options.upstreamDerbyServerRuntimeDirectory().toAbsolutePath().normalize()
                        + ",dst=/opt/derby/lib,readonly");
                command.add("--workdir");
                command.add("/var/lib/derby");
                command.add(target.containerImage(options));
                command.addAll(List.of(
                        "java", "-Xms" + options.childHeap(), "-Xmx" + options.childHeap(),
                        "-XX:+AlwaysPreTouch", "-cp", "/opt/derby/lib/*",
                        "org.apache.derby.drda.NetworkServerControl", "start",
                        "-h", "0.0.0.0", "-p", Integer.toString(target.containerPort())));
            }
            case H2_SERVER -> {
                command.add("--mount");
                command.add("type=bind,src=" + options.h2ServerJar().toAbsolutePath().normalize()
                        + ",dst=/opt/h2/h2.jar,readonly");
                command.add("--workdir");
                command.add("/var/lib/h2");
                command.add(target.containerImage(options));
                command.addAll(List.of(
                        "java", "-Xms" + options.childHeap(), "-Xmx" + options.childHeap(),
                        "-XX:+AlwaysPreTouch", "-cp", "/opt/h2/h2.jar",
                        "org.h2.tools.Server", "-tcp",
                        "-tcpPort", Integer.toString(target.containerPort()),
                        "-tcpAllowOthers", "-baseDir", "/var/lib/h2", "-ifNotExists"));
            }
            case POSTGRESQL -> {
                command.addAll(List.of(
                        "-e", "POSTGRES_USER=delosbench",
                        "-e", "POSTGRES_PASSWORD=delosbench",
                        "-e", "POSTGRES_DB=delosbench",
                        target.containerImage(options)));
            }
            case MARIADB -> {
                command.addAll(List.of(
                        "-e", "MARIADB_ROOT_PASSWORD=delosbench-root",
                        "-e", "MARIADB_USER=delosbench",
                        "-e", "MARIADB_PASSWORD=delosbench",
                        "-e", "MARIADB_DATABASE=delosbench",
                        target.containerImage(options)));
            }
            case FIREBIRD -> {
                command.addAll(List.of(
                        "-e", "FIREBIRD_ROOT_PASSWORD=delosbench",
                        "-e", "FIREBIRD_DATABASE=delosbench.fdb",
                        "-e", "FIREBIRD_DATABASE_DEFAULT_CHARSET=UTF8",
                        target.containerImage(options)));
            }
            default -> throw new IllegalArgumentException("Not a container target: " + target);
        }

        CommandResult started = runCommand(Math.max(30, options.containerStartupTimeoutSeconds()), command);
        if (started.exitCode() != 0) {
            throw new IllegalStateException("Could not start " + target.id() + " container:\n" + started.output());
        }
        int port = publishedPort(name, target.containerPort());
        ServerEndpoint endpoint = target.endpoint(port);
        Path serverEvidenceLog = drdaServerPhaseEvidenceEnabled()
                && (target == Target.DELOS_HEAP_DRDA || target == Target.DELOS_MVCC_DRDA)
                ? options.reportDirectory().resolve("server-phase-logs").resolve(
                        String.format(Locale.ROOT, "%02d-%s.log", run, target.id()))
                : null;
        ContainerServer server = new ContainerServer(name, endpoint, serverEvidenceLog);
        try {
            awaitReady(options, target, endpoint);
            return server;
        } catch (Throwable failure) {
            failure.addSuppressed(new IllegalStateException("Container log:\n" + server.logs()));
            server.close();
            throw failure;
        }
    }

    private static void awaitReady(Options options, Target target, ServerEndpoint endpoint) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(options.containerStartupTimeoutSeconds());
        SQLException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (Connection connection = connect(endpoint, target)) {
                if (connection.isValid(2)) {
                    return;
                }
            } catch (SQLException failure) {
                lastFailure = failure;
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Timed out waiting for " + target.id() + " at " + endpoint.jdbcUrl(),
                lastFailure);
    }

    private static Connection connect(ServerEndpoint endpoint, Target target) throws SQLException {
        Connection connection = endpoint.user().isBlank()
                ? DriverManager.getConnection(endpoint.jdbcUrl())
                : DriverManager.getConnection(endpoint.jdbcUrl(), endpoint.user(), endpoint.password());
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }

    private static Connection connect(Options options, Path database) throws SQLException {
        String url = options.target().jdbcUrl(database, options);
        Connection connection = options.remoteUser().isBlank()
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, options.remoteUser(), options.remotePassword());
        if (options.target().isContainer() || options.target() == Target.SQLITE) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        return connection;
    }

    private static int publishedPort(String name, int containerPort) throws Exception {
        CommandResult mapping = runCommand(20,
                List.of("docker", "port", name, containerPort + "/tcp"));
        if (mapping.exitCode() != 0) {
            throw new IllegalStateException("Could not resolve published port for " + name + ":\n"
                    + mapping.output());
        }
        String output = mapping.output().trim();
        int separator = output.lastIndexOf(':');
        if (separator < 0 || separator == output.length() - 1) {
            throw new IllegalStateException("Unexpected docker port mapping for " + name + ": " + output);
        }
        return Integer.parseInt(output.substring(separator + 1).trim());
    }

    private static CommandResult runCommand(int timeoutSeconds, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException ignored) {
            }
        });
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(10_000L);
        String text = output.toString(StandardCharsets.UTF_8);
        if (!completed) {
            return new CommandResult(124, text + "\nTimed out: " + String.join(" ", command));
        }
        return new CommandResult(process.exitValue(), text);
    }

    private static void writeContainerManifest(
            Options options, String dockerVersion, Map<String, String> images) throws IOException {
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

    private static void writeContainerSemanticEvidence(Options options, List<Row> rows) throws IOException {
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

    private static String targetIds(List<Target> targets) {
        StringBuilder value = new StringBuilder();
        for (Target target : targets) {
            if (!value.isEmpty()) {
                value.append(',');
            }
            value.append(target.id());
        }
        return value.toString();
    }

    private static String sha256(Path file) throws IOException {
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

    private static void runWorker(Options options) throws Exception {
        Files.createDirectories(options.reportDirectory());
        List<Measurement> measurements = new ArrayList<>();
        List<OracleEvidence> oracleEvidence = new ArrayList<>();
        List<DrdaProtocolEvidence> protocolEvidence = new ArrayList<>();
        List<Spec> specs = specsForRun(options);
        for (int rows : ordered(options.rowCounts(), options.run())) {
            DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                    rows, options.payload(), SEED, Math.min(options.fixtureBatch(), rows));
            for (Spec spec : specs) {
                long started = System.nanoTime();
                System.out.printf(Locale.ROOT,
                        "START run=%d target=%s rows=%d workload=%s clients=%d width=%d%n",
                        options.run(), options.target().id(), config.rowCount(), spec.workload().name(),
                        spec.clients(), spec.operationsPerTransaction());
                System.out.flush();
                try {
                    MeasuredSpec measured = measureSpec(options, config, spec);
                    measurements.add(measured.measurement());
                    if (measured.oracleEvidence() != null) {
                        oracleEvidence.add(measured.oracleEvidence());
                    }
                    if (measured.protocolEvidence() != null) {
                        protocolEvidence.add(measured.protocolEvidence());
                    }
                    System.out.printf(Locale.ROOT,
                            "DONE run=%d target=%s rows=%d workload=%s clients=%d width=%d elapsedSeconds=%.3f%n",
                            options.run(), options.target().id(), config.rowCount(), spec.workload().name(),
                            spec.clients(), spec.operationsPerTransaction(),
                            (System.nanoTime() - started) / 1_000_000_000.0);
                    System.out.flush();
                } catch (Throwable failure) {
                    System.out.printf(Locale.ROOT,
                            "FAIL run=%d target=%s rows=%d workload=%s clients=%d width=%d elapsedSeconds=%.3f error=%s%n",
                            options.run(), options.target().id(), config.rowCount(), spec.workload().name(),
                            spec.clients(), spec.operationsPerTransaction(),
                            (System.nanoTime() - started) / 1_000_000_000.0, failure);
                    System.out.flush();
                    throwFailure(failure);
                }
            }
        }
        writeWorkerCsv(options, measurements);
        if (sqlSemanticOracleEnabled()) {
            writeWorkerOracleEvidence(options, oracleEvidence);
        }
        if (drdaProtocolEvidenceEnabled()) {
            writeWorkerDrdaProtocolEvidence(options, protocolEvidence);
        }
    }

    private static MeasuredSpec measureSpec(Options options, DelosBenchmarkConfig config, Spec spec)
            throws Exception {
        String specId = spec.workload().name().toLowerCase(Locale.ROOT)
                + "-c" + spec.clients() + "-w" + spec.operationsPerTransaction();
        Path database = options.databaseRoot().resolve(options.target().id())
                .resolve("run-" + options.run()).resolve("rows-" + config.rowCount()).resolve(specId);

        if (!options.target().isContainer()) {
            deleteRecursively(database);
            Files.createDirectories(database.getParent());
            if (options.target() == Target.H2 || options.target() == Target.SQLITE) {
                Files.createDirectories(database);
            }
        }

        Throwable failure = null;
        Measurement measurement = null;
        DrdaProtocolEvidence protocolEvidence = null;
        DelosSqlSemanticOracle.Result sqlOracleResult = null;
        try (Connection verifier = connect(options, database)) {
            DatabaseMetaData metadata = verifier.getMetaData();
            String product = csvSafe(metadata.getDatabaseProductName());
            String productVersion = csvSafe(metadata.getDatabaseProductVersion());
            String driverVersion = csvSafe(metadata.getDriverVersion());
            List<String> tables = prepareTables(verifier, options, spec, config);
            configureRangeBulkFetchDefault(verifier, options, spec);
            if (options.target() == Target.SQLITE) {
                writeSqliteRuntimeMetadata(verifier, options, spec, config, tables);
            }
            if (heapAuthorityDiagnosticsEnabled() && options.target() == Target.DELOS_HEAP) {
                writeDerbyConglomerateMap(verifier, options, spec, config, tables);
            }
            if (mvccPhysicalLayoutDiagnosticsEnabled() && options.target() == Target.DELOS_MVCC) {
                writeMvccPhysicalLayout(verifier, options, spec, config, tables);
            }
            try (ConcurrentCase concurrentCase = new ConcurrentCase(
                    options, spec, database, verifier, tables, config.rowCount())) {
                Long expectedSemantic = null;
                long warmupElapsed = 0L;
                int warmupIterations = 0;
                long minimumWarmupNanos = options.minimumWarmupSeconds() <= 0.0
                        ? 0L
                        : Math.max(1L, (long) Math.ceil(
                                options.minimumWarmupSeconds() * 1_000_000_000.0));
                if (minimumWarmupNanos == 0L) {
                    for (int warmup = 0; warmup < options.warmups(); warmup++) {
                        Interval interval = concurrentCase.runInterval(false);
                        expectedSemantic = sameSemantic(expectedSemantic, interval.semanticFingerprint(), spec,
                                "warmup " + warmup);
                        warmupElapsed = Math.addExact(warmupElapsed, interval.elapsedNanos());
                        warmupIterations++;
                    }
                } else {
                    while (warmupIterations < options.warmups() || warmupElapsed < minimumWarmupNanos) {
                        if (warmupIterations >= options.maximumWarmupIterations()) {
                            throw new IllegalStateException(
                                    "Unable to reach minimum warmup duration "
                                            + options.minimumWarmupSeconds() + "s within "
                                            + options.maximumWarmupIterations() + " intervals: " + spec);
                        }
                        Interval interval = concurrentCase.runInterval(false);
                        expectedSemantic = sameSemantic(expectedSemantic, interval.semanticFingerprint(), spec,
                                "warmup " + warmupIterations);
                        warmupElapsed = Math.addExact(warmupElapsed, interval.elapsedNanos());
                        warmupIterations++;
                    }
                }
                if (pageLatchDiagnosticsEnabled()) {
                    resetPageLatchDiagnostics();
                }
                if (heapPageReadImageDiagnosticsEnabled()) {
                    resetHeapPageReadImageDiagnostics();
                }
                if (btreePointReadPathDiagnosticsEnabled()) {
                    resetBTreePointReadPathDiagnostics();
                }
                if (lockEntryDiagnosticsEnabled()) {
                    resetLockEntryDiagnostics();
                }
                if (lockWaitDiagnosticsEnabled()) {
                    resetLockWaitDiagnostics();
                }
                if (cacheEntryDiagnosticsEnabled()) {
                    resetCacheEntryDiagnostics();
                }
                if (hotStateDiagnosticsEnabled()) {
                    resetHotStateDiagnostics();
                }
                if (mvccSnapshotLeaseDiagnosticsEnabled()) {
                    resetMvccSnapshotLeaseDiagnostics();
                }
                long elapsed = 0L;
                long retryableRollbacks = 0L;
                Long measuredSemantic = expectedSemantic;
                int measuredIterations = 0;
                long[] measuredProtocolEvidence = drdaProtocolEvidenceEnabled()
                        ? new long[DRDA_PROTOCOL_COUNTER_COUNT]
                        : null;
                long minimumMeasuredNanos = options.minimumMeasuredSeconds() <= 0.0
                        ? 0L
                        : Math.max(1L, (long) Math.ceil(
                                options.minimumMeasuredSeconds() * 1_000_000_000.0));
                if (minimumMeasuredNanos == 0L) {
                    for (int iteration = 0; iteration < options.iterations(); iteration++) {
                        boolean captureOracle = sqlSemanticOracleEnabled()
                                && iteration == options.iterations() - 1;
                        Interval interval = concurrentCase.runInterval(captureOracle);
                        addDrdaProtocolEvidence(measuredProtocolEvidence, interval.drdaProtocolEvidence());
                        elapsed = Math.addExact(elapsed, interval.elapsedNanos());
                        retryableRollbacks = Math.addExact(retryableRollbacks, interval.retryableRollbacks());
                        measuredSemantic = sameSemantic(measuredSemantic, interval.semanticFingerprint(), spec,
                                "measured iteration " + iteration);
                        measuredIterations++;
                        if (captureOracle) {
                            sqlOracleResult = Objects.requireNonNull(
                                    interval.sqlOracleResult(),
                                    "Phase 0A SQL oracle result");
                        }
                    }
                } else {
                    while (measuredIterations < options.iterations() || elapsed < minimumMeasuredNanos) {
                        if (measuredIterations >= options.maximumMeasuredIterations()) {
                            throw new IllegalStateException(
                                    "Unable to reach minimum measured duration "
                                            + options.minimumMeasuredSeconds() + "s within "
                                            + options.maximumMeasuredIterations() + " intervals: " + spec);
                        }
                        Interval interval = concurrentCase.runInterval(false);
                        addDrdaProtocolEvidence(measuredProtocolEvidence, interval.drdaProtocolEvidence());
                        elapsed = Math.addExact(elapsed, interval.elapsedNanos());
                        retryableRollbacks = Math.addExact(retryableRollbacks, interval.retryableRollbacks());
                        measuredSemantic = sameSemantic(measuredSemantic, interval.semanticFingerprint(), spec,
                                "measured iteration " + measuredIterations);
                        measuredIterations++;
                    }
                    if (sqlSemanticOracleEnabled()) {
                        if (measuredIterations >= options.maximumMeasuredIterations()) {
                            throw new IllegalStateException(
                                    "No interval remains for Phase 0A SQL oracle capture after adaptive measurement: "
                                            + spec);
                        }
                        Interval interval = concurrentCase.runInterval(true);
                        addDrdaProtocolEvidence(measuredProtocolEvidence, interval.drdaProtocolEvidence());
                        elapsed = Math.addExact(elapsed, interval.elapsedNanos());
                        retryableRollbacks = Math.addExact(retryableRollbacks, interval.retryableRollbacks());
                        measuredSemantic = sameSemantic(measuredSemantic, interval.semanticFingerprint(), spec,
                                "oracle-bearing measured iteration " + measuredIterations);
                        measuredIterations++;
                        sqlOracleResult = Objects.requireNonNull(
                                interval.sqlOracleResult(),
                                "Phase 0A SQL oracle result");
                    }
                }
                long[] pageLatchDiagnostics = pageLatchDiagnosticsEnabled()
                        ? snapshotPageLatchDiagnostics()
                        : null;
                long[] heapPageReadImageDiagnostics = heapPageReadImageDiagnosticsEnabled()
                        ? snapshotHeapPageReadImageDiagnostics()
                        : null;
                String[] pageLatchContentionByPage = pageLatchDiagnosticsEnabled()
                        ? snapshotPageLatchContentionByPage()
                        : null;
                String[] detailedPageLatchContentionByPage = heapAuthorityDiagnosticsEnabled()
                        ? snapshotDetailedPageLatchContentionByPage()
                        : null;
                long[] btreePointReadPathDiagnostics = btreePointReadPathDiagnosticsEnabled()
                        ? snapshotBTreePointReadPathDiagnostics()
                        : null;
                String[] lockEntryDiagnostics = lockEntryDiagnosticsEnabled()
                        ? snapshotLockEntryDiagnostics()
                        : null;
                String[] lockWaitDiagnostics = lockWaitDiagnosticsEnabled()
                        ? snapshotLockWaitDiagnostics()
                        : null;
                String[] cacheEntryDiagnostics = cacheEntryDiagnosticsEnabled()
                        ? snapshotCacheEntryDiagnostics()
                        : null;
                String[] hotStateDiagnostics = hotStateDiagnosticsEnabled()
                        ? snapshotHotStateDiagnostics()
                        : null;
                long[] mvccSnapshotLeaseDiagnostics = mvccSnapshotLeaseDiagnosticsEnabled()
                        ? snapshotMvccSnapshotLeaseDiagnostics()
                        : null;
                int transactionsPerClient = options.transactionsPerClient(spec, config.rowCount());
                long measuredTransactions = Math.multiplyExact(
                        Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                        measuredIterations);
                long measuredOperations = Math.multiplyExact(
                        measuredTransactions, spec.operationsPerTransaction());
                if (pageLatchDiagnostics != null) {
                    writePageLatchDiagnostics(
                            options, spec, config, measuredOperations, pageLatchDiagnostics);
                    writePageLatchContentionByPage(
                            options, spec, config, pageLatchContentionByPage);
                    if (detailedPageLatchContentionByPage != null) {
                        writeDetailedPageLatchContentionByPage(
                                options, spec, config, measuredOperations,
                                detailedPageLatchContentionByPage);
                    }
                }
                if (heapPageReadImageDiagnostics != null) {
                    writeHeapPageReadImageDiagnostics(
                            options, spec, config, measuredOperations,
                            heapPageReadImageDiagnostics);
                }
                if (btreePointReadPathDiagnostics != null) {
                    writeBTreePointReadPathDiagnostics(
                            options, spec, config, measuredOperations, btreePointReadPathDiagnostics);
                }
                if (lockEntryDiagnostics != null) {
                    writeLockEntryDiagnostics(
                            options, spec, config, measuredOperations, lockEntryDiagnostics);
                }
                if (lockWaitDiagnostics != null) {
                    writeLockWaitDiagnostics(
                            options, spec, config, measuredOperations, lockWaitDiagnostics);
                }
                if (cacheEntryDiagnostics != null) {
                    writeCacheEntryDiagnostics(
                            options, spec, config, measuredOperations, cacheEntryDiagnostics);
                }
                if (hotStateDiagnostics != null) {
                    writeHotStateDiagnostics(
                            options, spec, config, measuredOperations, hotStateDiagnostics);
                }
                if (mvccSnapshotLeaseDiagnostics != null) {
                    writeMvccSnapshotLeaseDiagnostics(
                            options, spec, config, measuredTransactions,
                            mvccSnapshotLeaseDiagnostics);
                }
                protocolEvidence = drdaProtocolEvidenceEnabled()
                        ? DrdaProtocolEvidence.from(
                                options, spec, config, product, productVersion, driverVersion,
                                measuredTransactions, measuredOperations, elapsed,
                                concurrentCase.setupDrdaProtocolEvidence(), measuredProtocolEvidence)
                        : null;
                measurement = new Measurement(
                        options.target().id(), product, productVersion, driverVersion,
                        spec.workload(), spec.clients(), spec.operationsPerTransaction(),
                        transactionsPerClient, config.rowCount(), config.payloadSize(),
                        config.commitBatchSize(), warmupIterations, warmupElapsed, measuredIterations,
                        measuredTransactions, measuredOperations, retryableRollbacks, elapsed,
                        measuredTransactions * 1_000_000_000.0 / elapsed,
                        measuredOperations * 1_000_000_000.0 / elapsed,
                        (double) elapsed / measuredTransactions,
                        Objects.requireNonNull(measuredSemantic), options.run());
            }
        } catch (Throwable operationFailure) {
            failure = operationFailure;
        }

        if (options.target().isEmbeddedDerby() && Files.exists(database)) {
            failure = shutdownDerby(database, failure);
        }
        if (!options.target().isContainer()) {
            try {
                deleteRecursively(database);
            } catch (Throwable cleanupFailure) {
                failure = preserve(failure, cleanupFailure);
            }
        }
        if (failure != null) {
            throwFailure(failure);
        }
        OracleEvidence evidence = sqlOracleResult == null ? null : new OracleEvidence(
                options.target().id(),
                spec.workload(),
                spec.clients(),
                spec.operationsPerTransaction(),
                config.rowCount(),
                sqlOracleResult.kind(),
                sqlOracleResult.count(),
                sqlOracleResult.fingerprint(),
                options.run());
        return new MeasuredSpec(measurement, evidence, protocolEvidence);
    }

    private static boolean sqlSemanticOracleEnabled() {
        return Boolean.getBoolean(PREFIX + "sqlSemanticOracle");
    }

    private static boolean drdaProtocolEvidenceEnabled() {
        return Boolean.getBoolean(PREFIX + "drdaProtocolEvidence");
    }

    private static boolean drdaServerPhaseEvidenceEnabled() {
        return Boolean.getBoolean(PREFIX + "drdaServerPhaseEvidence");
    }

    private static void resetDrdaProtocolEvidence() {
        if (!drdaProtocolEvidenceEnabled()) {
            return;
        }
        invokeDrdaProtocolEvidence("reset");
    }

    private static void beginDrdaProtocolTimingWindow() {
        if (!drdaProtocolEvidenceEnabled()) {
            return;
        }
        invokeDrdaProtocolEvidence("beginTimingWindow");
    }

    private static void endDrdaProtocolTimingWindow() {
        if (!drdaProtocolEvidenceEnabled()) {
            return;
        }
        invokeDrdaProtocolEvidence("endTimingWindow");
    }

    private static long[] snapshotDrdaProtocolEvidence() {
        if (!drdaProtocolEvidenceEnabled()) {
            return null;
        }
        Object value = invokeDrdaProtocolEvidence("snapshot");
        if (!(value instanceof long[] snapshot) || snapshot.length != DRDA_PROTOCOL_COUNTER_COUNT) {
            throw new IllegalStateException("Unexpected DRDA protocol evidence snapshot");
        }
        return snapshot;
    }

    private static Object invokeDrdaProtocolEvidence(String method) {
        try {
            Class<?> evidence = Class.forName("org.apache.derby.client.net.NetProtocolEvidence");
            return evidence.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("DRDA protocol evidence unavailable: " + method, failure);
        }
    }

    private static void addDrdaProtocolEvidence(long[] total, long[] delta) {
        if (total == null || delta == null) {
            return;
        }
        if (total.length != delta.length) {
            throw new IllegalStateException("DRDA protocol evidence shape mismatch");
        }
        for (int index = 0; index < total.length; index++) {
            total[index] = Math.addExact(total[index], delta[index]);
        }
    }

    private static boolean hostStateDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "hostStateDiagnostics");
    }

    private static boolean hostProcessDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "hostProcessDiagnostics");
    }

    private static boolean hostStateRecoveryEnabled() {
        return Boolean.getBoolean(PREFIX + "hostStateRecovery");
    }

    private static int hostProcessSampleSeconds() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostProcessSampleSeconds", "5"));
    }

    private static int hostStateCooldownAfterRun() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownAfterRun", "0"));
    }

    private static int hostStateCooldownMinimumSeconds() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownMinimumSeconds", "60"));
    }

    private static int hostStateCooldownMaximumSeconds() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownMaximumSeconds", "300"));
    }

    private static int hostStateCooldownSampleSeconds() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownSampleSeconds", "10"));
    }

    private static int hostStateCooldownQuietSamples() {
        return Integer.parseInt(System.getProperty(PREFIX + "hostStateCooldownQuietSamples", "2"));
    }

    private static double hostStateCooldownMaximumCpuLoad() {
        return Double.parseDouble(System.getProperty(PREFIX + "hostStateCooldownMaximumCpuLoad", "0.35"));
    }

    private static double hostStateCooldownMaximumLoadPerProcessor() {
        return Double.parseDouble(System.getProperty(
                PREFIX + "hostStateCooldownMaximumLoadPerProcessor", "1.25"));
    }

    private static void writeHostStateDiagnosticHeader(Options options) throws IOException {
        Files.writeString(
                options.reportDirectory().resolve("host-state-diagnostics.tsv"),
                "capturedAtUtc\tstage\trun\ttarget\tsystemLoadAverage\tsystemCpuLoad"
                        + "\tavailableProcessors\tfreeMemoryBytes\ttotalMemoryBytes"
                        + "\tpmsetTherm\tpmsetSysload\tpmsetBattery\n",
                StandardCharsets.UTF_8);
    }

    private static HostState captureHostState(
            Options options, String stage, int run, String target) {
        HostState state = currentHostState();
        try {
            String row = Instant.now() + "\t"
                    + stage + "\t"
                    + run + "\t"
                    + target + "\t"
                    + format(state.systemLoadAverage()) + "\t"
                    + format(state.systemCpuLoad()) + "\t"
                    + state.availableProcessors() + "\t"
                    + state.freeMemoryBytes() + "\t"
                    + state.totalMemoryBytes() + "\t"
                    + tsvField(pmset("therm")) + "\t"
                    + tsvField(pmset("sysload")) + "\t"
                    + tsvField(pmset("batt")) + "\n";
            Files.writeString(
                    options.reportDirectory().resolve("host-state-diagnostics.tsv"),
                    row,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable failure) {
            try {
                Files.writeString(
                        options.reportDirectory().resolve("host-state-diagnostics.tsv"),
                        Instant.now() + "\t" + stage + "\t" + run + "\t" + target
                                + "\tNaN\tNaN\t0\t-1\t-1\t"
                                + tsvField("capture-failed: " + failure) + "\t\t\n",
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Host diagnostics must never alter benchmark execution semantics.
            }
        }
        return state;
    }

    private static HostState currentHostState() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        Number cpuLoad = operatingSystemNumber("CpuLoad", "SystemCpuLoad");
        Number freeMemory = operatingSystemNumber("FreeMemorySize", "FreePhysicalMemorySize");
        Number totalMemory = operatingSystemNumber("TotalMemorySize", "TotalPhysicalMemorySize");
        return new HostState(
                bean.getSystemLoadAverage(),
                cpuLoad == null ? Double.NaN : cpuLoad.doubleValue(),
                bean.getAvailableProcessors(),
                freeMemory == null ? -1L : freeMemory.longValue(),
                totalMemory == null ? -1L : totalMemory.longValue());
    }

    private static Number operatingSystemNumber(String... attributeNames) {
        try {
            var server = ManagementFactory.getPlatformMBeanServer();
            var operatingSystem = new javax.management.ObjectName(
                    ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
            for (String attributeName : attributeNames) {
                try {
                    Object value = server.getAttribute(operatingSystem, attributeName);
                    if (value instanceof Number number) {
                        return number;
                    }
                } catch (javax.management.JMException ignored) {
                    // Try the compatibility attribute name, if one was supplied.
                }
            }
        } catch (javax.management.JMException ignored) {
            // Host diagnostics are optional; callers retain NaN/-1 fallbacks.
        }
        return null;
    }

    private static void writeHostProcessDiagnosticHeader(Options options) throws IOException {
        Files.writeString(
                options.reportDirectory().resolve("host-process-diagnostics.tsv"),
                "capturedAtUtc\tstage\trun\ttarget\tworkerPid\tworkerAlive\tworkerCpuSeconds"
                        + "\tsystemLoadAverage\tsystemCpuLoad\tavailableProcessors"
                        + "\tfreeMemoryBytes\ttotalMemoryBytes\ttopProcesses\tvmStat\tswapUsage\n",
                StandardCharsets.UTF_8);
    }

    private static Thread startHostProcessSampler(
            Options options, Target target, int run, Process process) {
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && process.isAlive()) {
                captureHostProcessSample(options, "WORKER_SAMPLE", run, target.id(), process);
                try {
                    TimeUnit.SECONDS.sleep(hostProcessSampleSeconds());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "delos-host-process-sampler-" + run + '-' + target.id());
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    private static void stopHostProcessSampler(Thread sampler) {
        if (sampler == null) {
            return;
        }
        sampler.interrupt();
        try {
            sampler.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void captureHostProcessSample(
            Options options, String stage, int run, String target, Process worker) {
        try {
            HostState state = currentHostState();
            long workerPid = worker == null ? 0L : worker.pid();
            boolean workerAlive = worker != null && worker.isAlive();
            double workerCpuSeconds = worker == null
                    ? Double.NaN
                    : worker.info().totalCpuDuration()
                            .map(duration -> duration.toNanos() / 1_000_000_000.0)
                            .orElse(Double.NaN);
            String row = Instant.now() + "\t"
                    + stage + "\t"
                    + run + "\t"
                    + target + "\t"
                    + workerPid + "\t"
                    + workerAlive + "\t"
                    + format(workerCpuSeconds) + "\t"
                    + format(state.systemLoadAverage()) + "\t"
                    + format(state.systemCpuLoad()) + "\t"
                    + state.availableProcessors() + "\t"
                    + state.freeMemoryBytes() + "\t"
                    + state.totalMemoryBytes() + "\t"
                    + tsvField(topProcessSnapshot()) + "\t"
                    + tsvField(vmStat()) + "\t"
                    + tsvField(swapUsage()) + "\n";
            Files.writeString(
                    options.reportDirectory().resolve("host-process-diagnostics.tsv"),
                    row,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // External host sampling is diagnostic only and must not fail the benchmark.
        }
    }

    private static String topProcessSnapshot() {
        try {
            boolean mac = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("mac");
            List<String> command = mac
                    ? List.of("ps", "-A", "-r", "-o",
                            "pid=,ppid=,%cpu=,%mem=,state=,etime=,command=")
                    : List.of("ps", "-eo",
                            "pid=,ppid=,%cpu=,%mem=,stat=,etime=,command=", "--sort=-%cpu");
            CommandResult result = runCommand(5, command);
            if (result.exitCode() != 0) {
                return "exit=" + result.exitCode() + " " + result.output().trim();
            }
            String[] lines = result.output().split("\\R");
            int limit = Math.min(lines.length, 20);
            return String.join("\n", Arrays.asList(lines).subList(0, limit));
        } catch (Throwable failure) {
            return "UNAVAILABLE " + failure;
        }
    }

    private static String vmStat() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return "UNAVAILABLE_NON_MACOS";
        }
        try {
            CommandResult result = runCommand(5, List.of("vm_stat"));
            return "exit=" + result.exitCode() + " " + result.output().trim();
        } catch (Throwable failure) {
            return "UNAVAILABLE " + failure;
        }
    }

    private static String swapUsage() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return "UNAVAILABLE_NON_MACOS";
        }
        try {
            CommandResult result = runCommand(5, List.of("sysctl", "vm.swapusage"));
            return "exit=" + result.exitCode() + " " + result.output().trim();
        } catch (Throwable failure) {
            return "UNAVAILABLE " + failure;
        }
    }

    private static void writeHostRecoveryConfiguration(Options options) throws IOException {
        Files.writeString(
                options.reportDirectory().resolve("host-recovery.txt"),
                "status=PENDING\n"
                        + "cooldownAfterRun=" + hostStateCooldownAfterRun() + "\n"
                        + "minimumCooldownSeconds=" + hostStateCooldownMinimumSeconds() + "\n"
                        + "maximumCooldownSeconds=" + hostStateCooldownMaximumSeconds() + "\n"
                        + "sampleSeconds=" + hostStateCooldownSampleSeconds() + "\n"
                        + "requiredQuietSamples=" + hostStateCooldownQuietSamples() + "\n"
                        + "maximumCpuLoad=" + hostStateCooldownMaximumCpuLoad() + "\n"
                        + "maximumLoadPerProcessor=" + hostStateCooldownMaximumLoadPerProcessor() + "\n",
                StandardCharsets.UTF_8);
    }

    private static void coolDownHost(Options options, int run) {
        long started = System.nanoTime();
        int quietSamples = 0;
        int samples = 0;
        String status = "TIMEOUT";
        captureHostState(options, "COOLDOWN_BEGIN", run, "-");
        captureHostProcessSample(options, "COOLDOWN_BEGIN", run, "-", null);
        while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                < hostStateCooldownMaximumSeconds()) {
            try {
                TimeUnit.SECONDS.sleep(hostStateCooldownSampleSeconds());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                status = "INTERRUPTED";
                break;
            }
            samples++;
            HostState state = captureHostState(options, "COOLDOWN_SAMPLE", run, "-");
            captureHostProcessSample(options, "COOLDOWN_SAMPLE", run, "-", null);
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
            boolean minimumElapsed = elapsedSeconds >= hostStateCooldownMinimumSeconds();
            boolean cpuQuiet = Double.isFinite(state.systemCpuLoad())
                    && state.systemCpuLoad() >= 0.0
                    && state.systemCpuLoad() <= hostStateCooldownMaximumCpuLoad();
            boolean loadQuiet = Double.isFinite(state.systemLoadAverage())
                    && state.systemLoadAverage() >= 0.0
                    && state.systemLoadAverage()
                            <= state.availableProcessors() * hostStateCooldownMaximumLoadPerProcessor();
            if (minimumElapsed && cpuQuiet && loadQuiet) {
                quietSamples++;
                if (quietSamples >= hostStateCooldownQuietSamples()) {
                    status = "QUIET";
                    break;
                }
            } else {
                quietSamples = 0;
            }
        }
        HostState end = captureHostState(options, "COOLDOWN_END_" + status, run, "-");
        captureHostProcessSample(options, "COOLDOWN_END_" + status, run, "-", null);
        try {
            Files.writeString(
                    options.reportDirectory().resolve("host-recovery.txt"),
                    "status=" + status + "\n"
                            + "samples=" + samples + "\n"
                            + "elapsedSeconds="
                            + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) + "\n"
                            + "finalSystemLoadAverage=" + format(end.systemLoadAverage()) + "\n"
                            + "finalSystemCpuLoad=" + format(end.systemCpuLoad()) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Diagnostic summary must not alter benchmark execution semantics.
        }
    }

    private static String pmset(String argument) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return "UNAVAILABLE_NON_MACOS";
        }
        try {
            CommandResult result = runCommand(5, List.of("pmset", "-g", argument));
            return "exit=" + result.exitCode() + " " + result.output().trim();
        } catch (Throwable failure) {
            return "UNAVAILABLE " + failure;
        }
    }

    private static String tsvField(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private record HostState(
            double systemLoadAverage,
            double systemCpuLoad,
            int availableProcessors,
            long freeMemoryBytes,
            long totalMemoryBytes) {
    }

    private static boolean mvccSnapshotLeaseDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccSnapshotLeaseDiagnostics");
    }

    private static void resetMvccSnapshotLeaseDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.access.mvcc.MvccSnapshotLeaseDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    private static long[] snapshotMvccSnapshotLeaseDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.access.mvcc.MvccSnapshotLeaseDiagnosticTestSupport");
        return (long[]) support.getMethod("snapshot").invoke(null);
    }

    private static void writeMvccSnapshotLeaseDiagnostics(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredTransactions,
            long[] values) throws IOException {
        if (values.length != 8) {
            throw new IllegalStateException(
                    "Unexpected MVCC snapshot-lease diagnostic width: " + values.length);
        }
        Path output = options.reportDirectory().resolve(
                "mvcc-snapshot-lease-diagnostics-" + options.target().id()
                        + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredTransactions,"
                + "lockedCurrentOpens,lockedRetainedOpens,lockedCloses,"
                + "slottedCurrentOpens,slottedRetainedOpens,slottedCloses,"
                + "currentSlotClaimFailures,retainedSlotClaimFailures\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        StringBuilder row = new StringBuilder();
        row.append(options.target().id()).append(',')
                .append(spec.workload().name()).append(',')
                .append(spec.clients()).append(',')
                .append(spec.operationsPerTransaction()).append(',')
                .append(config.rowCount()).append(',')
                .append(measuredTransactions);
        for (long value : values) {
            row.append(',').append(value);
        }
        row.append('\n');
        Files.writeString(
                output, row.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static boolean lockEntryDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "lockEntryDiagnostics");
    }

    private static void resetLockEntryDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.locks.LockEntryDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    private static String[] snapshotLockEntryDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.locks.LockEntryDiagnosticTestSupport");
        return (String[]) support.getMethod("snapshot").invoke(null);
    }

    private static void writeLockEntryDiagnostics(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredOperations,
            String[] rows) throws IOException {
        Path output = options.reportDirectory().resolve(
                "lock-entry-diagnostics-" + options.target().id() + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredOperations,"
                + "lockableClass,entryAcquisitions,contendedAcquisitions,entryMutexWaitNanos,"
                + "contendedPercent,waitNanosPerOperation\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            String[] fields = row.split(",", -1);
            if (fields.length != 4) {
                throw new IllegalStateException("Unexpected lock-entry diagnostic row: " + row);
            }
            long acquisitions = Long.parseLong(fields[1]);
            long contended = Long.parseLong(fields[2]);
            long waitNanos = Long.parseLong(fields[3]);
            double contendedPercent = acquisitions == 0L ? 0.0 : contended * 100.0 / acquisitions;
            double waitNanosPerOperation = measuredOperations == 0L
                    ? 0.0 : (double) waitNanos / measuredOperations;
            out.append(options.target().id()).append(',')
                    .append(spec.workload().name()).append(',')
                    .append(spec.clients()).append(',')
                    .append(spec.operationsPerTransaction()).append(',')
                    .append(config.rowCount()).append(',')
                    .append(measuredOperations).append(',')
                    .append(fields[0]).append(',')
                    .append(acquisitions).append(',')
                    .append(contended).append(',')
                    .append(waitNanos).append(',')
                    .append(format(contendedPercent)).append(',')
                    .append(format(waitNanosPerOperation)).append('\n');
        }
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static boolean lockWaitDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "lockWaitDiagnostics");
    }

    private static void resetLockWaitDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.locks.LockWaitDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    private static String[] snapshotLockWaitDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.locks.LockWaitDiagnosticTestSupport");
        return (String[]) support.getMethod("snapshot").invoke(null);
    }

    private static void writeLockWaitDiagnostics(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredOperations,
            String[] rows) throws IOException {
        Path output = options.reportDirectory().resolve(
                "logical-lock-wait-diagnostics-" + options.target().id()
                        + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredOperations,"
                + "lockableClass,logicalWaits,totalWaitNanos,maxWaitNanos,"
                + "waitsPerOperation,waitNanosPerOperation\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            String[] fields = row.split(",", -1);
            if (fields.length != 4) {
                throw new IllegalStateException("Unexpected logical lock-wait diagnostic row: " + row);
            }
            long waits = Long.parseLong(fields[1]);
            long waitNanos = Long.parseLong(fields[2]);
            double waitsPerOperation = measuredOperations == 0L
                    ? 0.0 : waits / (double) measuredOperations;
            double waitNanosPerOperation = measuredOperations == 0L
                    ? 0.0 : waitNanos / (double) measuredOperations;
            out.append(options.target().id()).append(',')
                    .append(spec.workload().name()).append(',')
                    .append(spec.clients()).append(',')
                    .append(spec.operationsPerTransaction()).append(',')
                    .append(config.rowCount()).append(',')
                    .append(measuredOperations).append(',')
                    .append(fields[0]).append(',')
                    .append(waits).append(',')
                    .append(waitNanos).append(',')
                    .append(fields[3]).append(',')
                    .append(format(waitsPerOperation)).append(',')
                    .append(format(waitNanosPerOperation)).append('\n');
        }
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static boolean cacheEntryDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "cacheEntryDiagnostics");
    }

    private static void resetCacheEntryDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.cache.CacheEntryDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    private static String[] snapshotCacheEntryDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.cache.CacheEntryDiagnosticTestSupport");
        return (String[]) support.getMethod("snapshot").invoke(null);
    }

    private static void writeCacheEntryDiagnostics(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredOperations,
            String[] rows) throws IOException {
        Path output = options.reportDirectory().resolve(
                "cache-entry-diagnostics-" + options.target().id() + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredOperations,"
                + "cacheName,entryLockAcquisitions,contendedAcquisitions,entryMutexWaitNanos,"
                + "contendedPercent,waitNanosPerOperation\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            String[] fields = row.split(",", -1);
            if (fields.length != 4) {
                throw new IllegalStateException("Unexpected cache-entry diagnostic row: " + row);
            }
            long acquisitions = Long.parseLong(fields[1]);
            long contended = Long.parseLong(fields[2]);
            long waitNanos = Long.parseLong(fields[3]);
            double contendedPercent = acquisitions == 0L ? 0.0 : contended * 100.0 / acquisitions;
            double waitNanosPerOperation = measuredOperations == 0L
                    ? 0.0 : (double) waitNanos / measuredOperations;
            out.append(options.target().id()).append(',')
                    .append(spec.workload().name()).append(',')
                    .append(spec.clients()).append(',')
                    .append(spec.operationsPerTransaction()).append(',')
                    .append(config.rowCount()).append(',')
                    .append(measuredOperations).append(',')
                    .append(fields[0]).append(',')
                    .append(acquisitions).append(',')
                    .append(contended).append(',')
                    .append(waitNanos).append(',')
                    .append(format(contendedPercent)).append(',')
                    .append(format(waitNanosPerOperation)).append('\n');
        }
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static boolean hotStateDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "hotStateDiagnostics");
    }

    private static void resetHotStateDiagnostics() throws ReflectiveOperationException {
        Class<?> cacheSupport = Class.forName(
                "org.apache.derby.impl.services.cache.CacheEntryDiagnosticTestSupport");
        cacheSupport.getMethod("resetHotState").invoke(null);
        Class<?> lockSupport = Class.forName(
                "org.apache.derby.impl.services.locks.LockEntryDiagnosticTestSupport");
        lockSupport.getMethod("resetHotState").invoke(null);
    }

    private static String[] snapshotHotStateDiagnostics()
            throws ReflectiveOperationException {
        Class<?> cacheSupport = Class.forName(
                "org.apache.derby.impl.services.cache.CacheEntryDiagnosticTestSupport");
        String[] cacheRows = (String[]) cacheSupport.getMethod("snapshotHotState").invoke(null);
        Class<?> lockSupport = Class.forName(
                "org.apache.derby.impl.services.locks.LockEntryDiagnosticTestSupport");
        String[] lockRows = (String[]) lockSupport.getMethod("snapshotHotState").invoke(null);
        String[] rows = Arrays.copyOf(cacheRows, cacheRows.length + lockRows.length);
        System.arraycopy(lockRows, 0, rows, cacheRows.length, lockRows.length);
        return rows;
    }

    private static void writeHotStateDiagnostics(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredOperations,
            String[] rows) throws IOException {
        Path output = options.reportDirectory().resolve(
                "hot-state-diagnostics-" + options.target().id() + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredOperations,"
                + "component,metric,value,valuePerOperation\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            String[] fields = row.split(",", -1);
            if (fields.length != 3) {
                throw new IllegalStateException("Unexpected hot-state diagnostic row: " + row);
            }
            long value = Long.parseLong(fields[2]);
            double valuePerOperation = measuredOperations == 0L
                    ? 0.0 : (double) value / measuredOperations;
            out.append(options.target().id()).append(',')
                    .append(spec.workload().name()).append(',')
                    .append(spec.clients()).append(',')
                    .append(spec.operationsPerTransaction()).append(',')
                    .append(config.rowCount()).append(',')
                    .append(measuredOperations).append(',')
                    .append(fields[0]).append(',')
                    .append(fields[1]).append(',')
                    .append(value).append(',')
                    .append(format(valuePerOperation)).append('\n');
        }
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void writeDetailedPageLatchContentionByPage(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredOperations,
            String[] rows) throws IOException {
        Path output = options.reportDirectory().resolve(
                "page-latch-authority-by-page-" + options.target().id()
                        + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredOperations,pageKey,"
                + "latchRequests,contendedLatchRequests,ownerWaitCalls,ownerWaitNanos,maxOwnerWaitNanos,"
                + "latchesPerOperation,contendedPercent,waitNanosPerOperation\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            String[] values = row.split("\t", -1);
            if (values.length != 6) {
                throw new IllegalStateException("Unexpected detailed page-latch row: " + row);
            }
            long requests = Long.parseLong(values[1]);
            long contended = Long.parseLong(values[2]);
            long waitNanos = Long.parseLong(values[4]);
            double latchesPerOperation = measuredOperations == 0L
                    ? 0.0 : requests / (double) measuredOperations;
            double contendedPercent = requests == 0L ? 0.0 : contended * 100.0 / requests;
            double waitNanosPerOperation = measuredOperations == 0L
                    ? 0.0 : waitNanos / (double) measuredOperations;
            out.append(options.target().id()).append(',')
                    .append(spec.workload().name()).append(',')
                    .append(spec.clients()).append(',')
                    .append(spec.operationsPerTransaction()).append(',')
                    .append(config.rowCount()).append(',')
                    .append(measuredOperations).append(',')
                    .append(csvSafe(values[0])).append(',')
                    .append(requests).append(',')
                    .append(contended).append(',')
                    .append(values[3]).append(',')
                    .append(waitNanos).append(',')
                    .append(values[5]).append(',')
                    .append(format(latchesPerOperation)).append(',')
                    .append(format(contendedPercent)).append(',')
                    .append(format(waitNanosPerOperation)).append('\n');
        }
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static boolean btreePointReadPathDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "btreePointReadPathDiagnostics");
    }

    private static void resetBTreePointReadPathDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.access.btree.BTreePointReadDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    private static long[] snapshotBTreePointReadPathDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.access.btree.BTreePointReadDiagnosticTestSupport");
        return (long[]) support.getMethod("snapshot").invoke(null);
    }

    private static void writeBTreePointReadPathDiagnostics(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredOperations,
            long[] values) throws IOException {
        if (values.length != 31) {
            throw new IllegalStateException(
                    "Unexpected B-tree point-read diagnostic width: " + values.length);
        }
        Path output = options.reportDirectory().resolve(
                "btree-point-read-path-diagnostics-" + options.target().id()
                        + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredOperations,"
                + "fetchRowsCalls,scanInitSingleRowCalls,rejectForUpdate,rejectHeld,rejectQualifier,"
                + "rejectMissingStart,rejectMissingStop,rejectStartOperator,rejectStopOperator,"
                + "rejectNonUnique,rejectStartKeyLength,rejectStopKeyLength,rejectUnequalBounds,"
                + "eligibleExactPointShapes,rootSnapshotAttempts,rootSnapshotHits,rootSnapshotFallbacks,"
                + "rootSnapshotHeightTwoHits,rootSnapshotOtherHeightHits,exactStartMatches,"
                + "previousKeyLockSkipped,previousKeyLockRequested,indexLeafRowFetches,"
                + "snapshotPointAttempts,snapshotPointHits,snapshotPointSnapshotMisses,"
                + "snapshotPointLockFallbacks,snapshotPointRevalidationFallbacks,"
                + "snapshotPointHeldExhaustions,leafSnapshotObservations,leafSnapshotInvalidations,"
                + "scanInitSingleRowCallsPerOperation,rejectHeldPerScanInit,"
                + "eligibleExactPointShapesPerScanInit,rootSnapshotHitsPerOperation,"
                + "indexLeafRowFetchesPerOperation,snapshotPointAttemptsPerOperation,"
                + "snapshotPointHitsPerOperation,snapshotPointHitRatio\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        double scanInitPerOperation = measuredOperations == 0L
                ? 0.0 : (double) values[1] / measuredOperations;
        double heldPerScanInit = values[1] == 0L ? 0.0 : (double) values[3] / values[1];
        double eligiblePerScanInit = values[1] == 0L ? 0.0 : (double) values[13] / values[1];
        double rootHitsPerOperation = measuredOperations == 0L
                ? 0.0 : (double) values[15] / measuredOperations;
        double leafFetchesPerOperation = measuredOperations == 0L
                ? 0.0 : (double) values[22] / measuredOperations;
        double snapshotAttemptsPerOperation = measuredOperations == 0L
                ? 0.0 : (double) values[23] / measuredOperations;
        double snapshotHitsPerOperation = measuredOperations == 0L
                ? 0.0 : (double) values[24] / measuredOperations;
        double snapshotHitRatio = values[23] == 0L
                ? 0.0 : (double) values[24] / values[23];
        StringBuilder row = new StringBuilder();
        row.append(options.target().id()).append(',')
                .append(spec.workload().name()).append(',')
                .append(spec.clients()).append(',')
                .append(spec.operationsPerTransaction()).append(',')
                .append(config.rowCount()).append(',')
                .append(measuredOperations);
        for (long value : values) {
            row.append(',').append(value);
        }
        row.append(',').append(format(scanInitPerOperation))
                .append(',').append(format(heldPerScanInit))
                .append(',').append(format(eligiblePerScanInit))
                .append(',').append(format(rootHitsPerOperation))
                .append(',').append(format(leafFetchesPerOperation))
                .append(',').append(format(snapshotAttemptsPerOperation))
                .append(',').append(format(snapshotHitsPerOperation))
                .append(',').append(format(snapshotHitRatio))
                .append('\n');
        Files.writeString(
                output, row.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static boolean heapPageReadImageDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "heapPageReadImageDiagnostics");
    }

    private static void resetHeapPageReadImageDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.HeapPageReadImageDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    private static long[] snapshotHeapPageReadImageDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.HeapPageReadImageDiagnosticTestSupport");
        return (long[]) support.getMethod("snapshot").invoke(null);
    }

    private static void writeHeapPageReadImageDiagnostics(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredOperations,
            long[] values) throws IOException {
        if (values.length != 12) {
            throw new IllegalStateException(
                    "Unexpected heap-page read-image diagnostic width: " + values.length);
        }
        Path output = options.reportDirectory().resolve(
                "heap-page-read-image-diagnostics-" + options.target().id()
                        + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredOperations,"
                + "attempts,hits,misses,generationFailures,recordFailures,unsupported,fallbacks,"
                + "imagesPublished,imagesInvalidated,bytesCopied,currentImageBytes,peakImageBytes,"
                + "attemptsPerOperation,hitsPerOperation,hitRatio,fallbackRatio,bytesCopiedPerOperation\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        double attemptsPerOperation = measuredOperations == 0L
                ? 0.0 : (double) values[0] / measuredOperations;
        double hitsPerOperation = measuredOperations == 0L
                ? 0.0 : (double) values[1] / measuredOperations;
        double hitRatio = values[0] == 0L ? 0.0 : (double) values[1] / values[0];
        double fallbackRatio = values[0] == 0L ? 0.0 : (double) values[6] / values[0];
        double bytesPerOperation = measuredOperations == 0L
                ? 0.0 : (double) values[9] / measuredOperations;
        StringBuilder row = new StringBuilder();
        row.append(options.target().id()).append(',')
                .append(spec.workload().name()).append(',')
                .append(spec.clients()).append(',')
                .append(spec.operationsPerTransaction()).append(',')
                .append(config.rowCount()).append(',')
                .append(measuredOperations);
        for (long value : values) {
            row.append(',').append(value);
        }
        row.append(',').append(format(attemptsPerOperation))
                .append(',').append(format(hitsPerOperation))
                .append(',').append(format(hitRatio))
                .append(',').append(format(fallbackRatio))
                .append(',').append(format(bytesPerOperation))
                .append('\n');
        Files.writeString(
                output, row.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static boolean pageLatchDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "pageLatchDiagnostics");
    }

    private static void resetPageLatchDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.PageLatchDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    private static long[] snapshotPageLatchDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.PageLatchDiagnosticTestSupport");
        return (long[]) support.getMethod("snapshot").invoke(null);
    }

    private static String[] snapshotPageLatchContentionByPage()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.PageLatchDiagnosticTestSupport");
        return (String[]) support.getMethod("contentionByPage").invoke(null);
    }

    private static String[] snapshotDetailedPageLatchContentionByPage()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.PageLatchDiagnosticTestSupport");
        return (String[]) support.getMethod("detailedContentionByPage").invoke(null);
    }

    private static boolean heapAuthorityDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "heapAuthorityDiagnostics");
    }

    private static boolean mvccPhysicalLayoutDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccPhysicalLayoutDiagnostics");
    }

    private static void writePageLatchDiagnostics(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            long measuredOperations,
            long[] values) throws IOException {
        if (values.length != 8) {
            throw new IllegalStateException(
                    "Unexpected page-latch diagnostic width: " + values.length);
        }
        Path output = options.reportDirectory().resolve(
                "page-latch-diagnostics-" + options.target().id() + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,measuredOperations,"
                + "latchRequests,contendedLatchRequests,ownerWaitCalls,ownerWaitNanos,"
                + "cleanerWaitCalls,cleanerWaitNanos,noWaitRequests,noWaitFailures,"
                + "contendedPercent,ownerWaitNanosPerOperation\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        double contendedPercent = values[0] == 0L ? 0.0 : values[1] * 100.0 / values[0];
        double waitNanosPerOperation = measuredOperations == 0L
                ? 0.0
                : (double) values[3] / measuredOperations;
        String row = options.target().id() + ',' + spec.workload().name() + ','
                + spec.clients() + ',' + spec.operationsPerTransaction() + ','
                + config.rowCount() + ',' + measuredOperations + ','
                + values[0] + ',' + values[1] + ',' + values[2] + ',' + values[3] + ','
                + values[4] + ',' + values[5] + ',' + values[6] + ',' + values[7] + ','
                + format(contendedPercent) + ',' + format(waitNanosPerOperation) + '\n';
        Files.writeString(
                output, row, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void writePageLatchContentionByPage(
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            String[] rows) throws IOException {
        if (rows == null) {
            return;
        }
        Path output = options.reportDirectory().resolve(
                "page-latch-contention-by-page-" + options.target().id()
                        + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,pageKey,"
                + "contendedLatchRequests,ownerWaitCalls,ownerWaitNanos\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            String[] values = row.split("\t", -1);
            if (values.length != 4) {
                throw new IllegalStateException("Unexpected page-latch contention row: " + row);
            }
            out.append(options.target().id()).append(',')
                    .append(spec.workload().name()).append(',')
                    .append(spec.clients()).append(',')
                    .append(spec.operationsPerTransaction()).append(',')
                    .append(config.rowCount()).append(',')
                    .append(csvSafe(values[0])).append(',')
                    .append(values[1]).append(',')
                    .append(values[2]).append(',')
                    .append(values[3]).append('\n');
        }
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void configureRangeBulkFetchDefault(
            Connection connection, Options options, Spec spec) throws SQLException {
        String value = System.getProperty(PREFIX + "rangeBulkFetchDefault", "").trim();
        if (value.isEmpty()) {
            return;
        }
        if (!spec.workload().isRangeScan()
                || (options.target() != Target.DELOS_HEAP
                        && options.target() != Target.UPSTREAM_DERBY)) {
            throw new IllegalStateException(
                    "rangeBulkFetchDefault is valid only for Delos heap/upstream Derby range scans");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid rangeBulkFetchDefault=" + value, failure);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException("rangeBulkFetchDefault must be >= 1");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "call syscs_util.syscs_set_database_property("
                        + "'derby.language.bulkFetchDefault','" + parsed + "')")) {
            statement.execute();
        }
        connection.commit();
    }

    private static List<String> prepareTables(
            Connection verifier, Options options, Spec spec, DelosBenchmarkConfig config) throws SQLException {
        int tableCount = spec.workload().isConglomerateLocalityDiagnostic() ? spec.clients() : 1;
        List<String> tables = new ArrayList<>(tableCount);
        for (int index = 0; index < tableCount; index++) {
            String targetId = options.target().id();
            if (tableCount > 1) {
                targetId += "_client_" + (index + 1);
            }
            DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(
                    verifier, targetId, options.target().createTableSuffix(),
                    options.target().isContainer(), config);
            scenario.prepare();
            if (spec.workload() == Workload.JOIN_INDEXED_1TO1) {
                prepareJoinDimensionFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
            } else if (spec.workload() == Workload.JOIN_INDEXED_FANOUT) {
                prepareJoinFanoutFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
            } else if (spec.workload() == Workload.JOIN_3WAY_SELECTIVE
                    || spec.workload() == Workload.JOIN_4WAY_FANOUT) {
                prepareMultiJoinFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
            } else if (spec.workload() == Workload.GROUP_HIGH_CARD) {
                prepareHighCardGroupFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
            } else if (spec.workload() == Workload.BANK_TRANSACTION) {
                prepareBankFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
            } else if (spec.workload() == Workload.ORDER_ENTRY_MIX) {
                prepareOrderEntryFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), spec.clients(), options.transactionsPerClient(spec, config.rowCount()),
                        config.commitBatchSize());
            }
            if (spec.workload().isCoveringRangeScan()
                    || (spec.workload().isRowBearingComparisonRangeScan()
                            && (options.target() == Target.DELOS_HEAP
                                    || options.target() == Target.UPSTREAM_DERBY))) {
                String coveringIndex = rangeCoveringIndexName(scenario.tableName());
                try (java.sql.Statement statement = verifier.createStatement()) {
                    statement.executeUpdate("create index " + coveringIndex + " on "
                            + scenario.tableName() + " (id, quantity)");
                }
                verifier.commit();
            }
            tables.add(scenario.tableName());
        }
        return List.copyOf(tables);
    }

    private static void writeMvccPhysicalLayout(
            Connection connection,
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            List<String> tables) throws Exception {
        Path output = options.reportDirectory().resolve(
                "mvcc-physical-layout-" + options.target().id()
                        + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,tableName,"
                + "role,containerId,rawContainerKey\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        Class<?> support = Class.forName(
                "org.apache.derbyTesting.functionTests.tests.delos.MvccPhysicalLayoutDiagnosticTestSupport");
        java.lang.reflect.Method describe = support.getMethod(
                "describe", Connection.class, String.class);
        StringBuilder rows = new StringBuilder();
        for (String table : tables) {
            String[] layoutRows = (String[]) describe.invoke(null, connection, table);
            for (String layoutRow : layoutRows) {
                String[] values = layoutRow.split("\t", -1);
                if (values.length != 2) {
                    throw new IllegalStateException(
                            "Unexpected MVCC physical-layout row: " + layoutRow);
                }
                long containerId = Long.parseLong(values[1]);
                rows.append(options.target().id()).append(',')
                        .append(spec.workload().name()).append(',')
                        .append(spec.clients()).append(',')
                        .append(spec.operationsPerTransaction()).append(',')
                        .append(config.rowCount()).append(',')
                        .append(table).append(',')
                        .append(values[0]).append(',')
                        .append(containerId).append(',')
                        .append("Container(0;").append(containerId).append(")")
                        .append('\n');
            }
        }
        Files.writeString(
                output, rows.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void writeDerbyConglomerateMap(
            Connection connection,
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            List<String> tables) throws SQLException, IOException {
        Path output = options.reportDirectory().resolve(
                "derby-conglomerate-map-" + options.target().id()
                        + "-run-" + options.run() + ".csv");
        String header = "target,workload,clients,operationsPerTransaction,rowCount,tableName,"
                + "conglomerateNumber,rawContainerKey,conglomerateName,isIndex\n";
        if (!Files.exists(output)) {
            Files.writeString(output, header, StandardCharsets.UTF_8);
        }
        String sql = "select c.conglomeratenumber, c.conglomeratename, c.isindex "
                + "from sys.sysconglomerates c join sys.systables t on c.tableid = t.tableid "
                + "where t.tablename = ? order by c.conglomeratenumber";
        StringBuilder rows = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String table : tables) {
                statement.setString(1, table.toUpperCase(Locale.ROOT));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        long conglomerateNumber = rs.getLong(1);
                        rows.append(options.target().id()).append(',')
                                .append(spec.workload().name()).append(',')
                                .append(spec.clients()).append(',')
                                .append(spec.operationsPerTransaction()).append(',')
                                .append(config.rowCount()).append(',')
                                .append(csvSafe(table)).append(',')
                                .append(conglomerateNumber).append(',')
                                .append(csvSafe("Container(0;" + conglomerateNumber + ")")).append(',')
                                .append(csvSafe(rs.getString(2))).append(',')
                                .append(rs.getBoolean(3)).append('\n');
                    }
                }
            }
        }
        Files.writeString(
                output, rows.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void writeSqliteRuntimeMetadata(
            Connection connection,
            Options options,
            Spec spec,
            DelosBenchmarkConfig config,
            List<String> tables) throws SQLException, IOException {
        DatabaseMetaData metadata = connection.getMetaData();
        StringBuilder out = new StringBuilder();
        out.append("workload=").append(spec.workload().name()).append('\n')
                .append("clients=").append(spec.clients()).append('\n')
                .append("operationsPerTransaction=").append(spec.operationsPerTransaction()).append('\n')
                .append("rowCount=").append(config.rowCount()).append('\n')
                .append("sqliteVersion=").append(singleValue(connection, "select sqlite_version()"))
                .append('\n')
                .append("driverVersion=").append(metadata.getDriverVersion()).append('\n')
                .append("sharedCacheRequested=").append(options.sqliteSharedCache()).append('\n')
                .append("omitSharedCacheCompileOption=")
                .append(singleValue(connection,
                        "select sqlite_compileoption_used('OMIT_SHARED_CACHE')"))
                .append('\n')
                .append("journalMode=").append(pragmaValue(connection, "journal_mode")).append('\n')
                .append("lockingMode=").append(pragmaValue(connection, "locking_mode")).append('\n')
                .append("synchronous=").append(pragmaValue(connection, "synchronous")).append('\n')
                .append("pageSize=").append(pragmaValue(connection, "page_size")).append('\n')
                .append("cacheSize=").append(pragmaValue(connection, "cache_size")).append('\n')
                .append("mmapSize=").append(pragmaValue(connection, "mmap_size")).append('\n')
                .append("benchmarkDdl=").append(sqliteBenchmarkDdl(tables, spec.workload())).append('\n')
                .append("compileOptions=").append(sqliteCompileOptions(connection)).append('\n')
                .append('\n');
        Path output = options.reportDirectory().resolve(
                "sqlite-runtime-metadata-run-" + options.run() + ".txt");
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static String sqliteBenchmarkDdl(List<String> tables, Workload workload) {
        StringBuilder ddl = new StringBuilder();
        for (String table : tables) {
            if (ddl.length() != 0) {
                ddl.append(" | ");
            }
            ddl.append("create table ").append(table)
                    .append(" (id int not null primary key, category int not null, bucket int not null, ")
                    .append("quantity int not null, payload varchar(4096) not null); ")
                    .append("create index ").append(table).append("_CATEGORY_IDX on ")
                    .append(table).append(" (category); ")
                    .append("create index ").append(table).append("_RANGE_IDX on ")
                    .append(table).append(" (bucket, quantity)");
            if (workload == Workload.JOIN_INDEXED_1TO1) {
                ddl.append("; create table ").append(joinDimensionTableName(table))
                        .append(" (id int not null primary key)");
            } else if (workload == Workload.JOIN_INDEXED_FANOUT) {
                ddl.append("; create table ").append(joinFanoutParentTableName(table))
                        .append(" (id int not null primary key)")
                        .append("; create table ").append(joinFanoutChildTableName(table))
                        .append(" (id int not null primary key, parent_id int not null)")
                        .append("; create index ").append(joinFanoutChildTableName(table)).append("_P_IDX on ")
                        .append(joinFanoutChildTableName(table)).append(" (parent_id)");
            } else if (workload == Workload.JOIN_3WAY_SELECTIVE
                    || workload == Workload.JOIN_4WAY_FANOUT) {
                ddl.append("; create table ").append(multiJoinCustomerTableName(table))
                        .append(" (id int not null primary key, bucket int not null)")
                        .append("; create index ").append(multiJoinCustomerTableName(table)).append("_B_IDX on ")
                        .append(multiJoinCustomerTableName(table)).append(" (bucket)")
                        .append("; create table ").append(multiJoinOrderTableName(table))
                        .append(" (id int not null primary key, customer_id int not null)")
                        .append("; create index ").append(multiJoinOrderTableName(table)).append("_C_IDX on ")
                        .append(multiJoinOrderTableName(table)).append(" (customer_id)")
                        .append("; create table ").append(multiJoinLineTableName(table))
                        .append(" (id int not null primary key, order_id int not null, line_no int not null, item_id int not null)")
                        .append("; create index ").append(multiJoinLineTableName(table)).append("_O_IDX on ")
                        .append(multiJoinLineTableName(table)).append(" (order_id)")
                        .append("; create table ").append(multiJoinItemTableName(table))
                        .append(" (id int not null primary key)");
            } else if (workload == Workload.GROUP_HIGH_CARD) {
                ddl.append("; create table ").append(highCardGroupTableName(table))
                        .append(" (id int not null primary key, group_key int not null, quantity int not null)");
            }
        }
        return ddl.toString();
    }

    private static String pragmaValue(Connection connection, String pragma) throws SQLException {
        return singleValue(connection, "pragma " + pragma);
    }

    private static String singleValue(Connection connection, String sql) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? Objects.toString(rs.getObject(1), "") : "";
        }
    }

    private static String sqliteCompileOptions(Connection connection) throws SQLException {
        List<String> options = new ArrayList<>();
        try (java.sql.Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("pragma compile_options")) {
            while (rs.next()) {
                options.add(rs.getString(1));
            }
        }
        Collections.sort(options);
        return String.join("|", options);
    }

    private static final class ConcurrentCase implements AutoCloseable {
        private final Options options;
        private final Spec spec;
        private final Connection verifier;
        private final String table;
        private final int rowCount;
        private final int transactionsPerClient;
        private final int[] mutationIds;
        private final int[] mutationBaseline;
        private final int[] deleteReinsertIds;
        private final DelosSqlSemanticOracle.Result deleteReinsertBaseline;
        private final int insertFirstId;
        private final int insertLastId;
        private final List<Client> clients;
        private final Connection longReaderConnection;
        private final PreparedStatement longReaderScan;
        private final int longReaderStart;
        private final int longReaderEndExclusive;
        private final DelosSqlSemanticOracle.Result longReaderBaseline;
        private final long[] setupDrdaProtocolEvidence;
        private final ExecutorService executor;

        private ConcurrentCase(
                Options options,
                Spec spec,
                Path database,
                Connection verifier,
                List<String> tables,
                int rowCount) throws SQLException {
            this.options = options;
            this.spec = spec;
            this.verifier = verifier;
            this.table = tables.get(0);
            this.rowCount = rowCount;
            this.transactionsPerClient = options.transactionsPerClient(spec, rowCount);
            this.mutationIds = mutationIds(spec, rowCount);
            this.mutationBaseline = new int[mutationIds.length];
            for (int index = 0; index < mutationIds.length; index++) {
                mutationBaseline[index] = quantity(verifier, this.table, mutationIds[index]);
            }
            this.deleteReinsertIds = spec.workload().isDeleteReinsert()
                    ? deleteReinsertIds(spec, rowCount)
                    : new int[0];
            this.deleteReinsertBaseline = spec.workload().isDeleteReinsert()
                    ? authoritativeDeleteReinsertState(verifier, this.table, deleteReinsertIds)
                    : null;
            long insertedRows = spec.workload().isInsert()
                    ? Math.multiplyExact(
                            Math.multiplyExact((long) spec.clients(), this.transactionsPerClient),
                            spec.operationsPerTransaction())
                    : 0L;
            this.insertFirstId = spec.workload().isInsert() ? Math.addExact(rowCount, 1) : 0;
            this.insertLastId = spec.workload().isInsert()
                    ? Math.toIntExact(Math.addExact((long) rowCount, insertedRows))
                    : 0;
            this.longReaderStart = spec.workload().isLongReaderWriter()
                    ? 1 + rowCount / 4
                    : 0;
            this.longReaderEndExclusive = spec.workload().isLongReaderWriter()
                    ? 1 + (3 * rowCount) / 4
                    : 0;
            this.longReaderBaseline = spec.workload().isLongReaderWriter()
                    ? authoritativeLongReaderState(
                            verifier, this.table, longReaderStart, longReaderEndExclusive)
                    : null;
            int[] fixtureQuantities = spec.workload().usesFixtureQuantities() ? fixtureQuantities(rowCount) : null;
            verifier.rollback();
            if (drdaProtocolEvidenceEnabled()) {
                resetDrdaProtocolEvidence();
            }
            this.clients = new ArrayList<>(spec.clients());
            try {
                for (int client = 0; client < spec.clients(); client++) {
                    Connection connection = connect(options, database);
                    connection.setAutoCommit(false);
                    connection.setTransactionIsolation(spec.workload().isolationLevel());
                    if (Boolean.getBoolean(PREFIX + "closeCursorsAtCommit")) {
                        connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
                    }
                    int updateId = 0;
                    int[] readIds = null;
                    int[] expectedReadQuantities = null;
                    int rangeStart = 0;
                    int rangeEndExclusive = 0;
                    int expectedRangeRows = 0;
                    long expectedRangeFingerprint = 0L;
                    DeleteRow[] deleteRows = null;
                    int expectedFitnessRows = 0;
                    int insertBaseId = 0;
                    if (spec.workload().isPrimaryKeyRead()) {
                        int operations = Math.multiplyExact(
                                transactionsPerClient, spec.operationsPerTransaction());
                        readIds = readOperationIds(spec, rowCount, client, operations);
                        expectedReadQuantities = new int[readIds.length];
                        for (int operation = 0; operation < readIds.length; operation++) {
                            expectedReadQuantities[operation] = fixtureQuantities[readIds[operation]];
                        }
                    } else if (spec.workload().isRangeScan()) {
                        expectedRangeRows = spec.workload().rangeRows(rowCount);
                        int startCount = rowCount - expectedRangeRows + 1;
                        rangeStart = 1 + (int) (((long) client * startCount) / spec.clients());
                        rangeEndExclusive = rangeStart + expectedRangeRows;
                        expectedRangeFingerprint = spec.workload().isIndexOnlyRangeScan()
                                ? rangeIdFingerprint(rangeStart, rangeEndExclusive)
                                : rangeFingerprint(fixtureQuantities, rangeStart, rangeEndExclusive);
                    } else if (spec.workload().isFitnessRead()) {
                        expectedFitnessRows = expectedFitnessRows(spec.workload(), rowCount);
                    } else if (spec.workload().isInsert()) {
                        int insertsPerClient = Math.multiplyExact(
                                transactionsPerClient, spec.operationsPerTransaction());
                        insertBaseId = Math.addExact(rowCount + 1, Math.multiplyExact(client, insertsPerClient));
                    } else if (spec.workload().isDeleteReinsert()) {
                        int width = spec.operationsPerTransaction();
                        deleteRows = new DeleteRow[width];
                        int offset = client * width;
                        for (int operation = 0; operation < width; operation++) {
                            deleteRows[operation] = readDeleteRow(
                                    connection, this.table, deleteReinsertIds[offset + operation]);
                        }
                    } else if (spec.workload().isMixedReaderWriter()) {
                        updateId = spec.workload() == Workload.MIXED_80R20W
                                ? mutationIds[client]
                                : mutationIds[client % mutationIds.length];
                    } else if (spec.workload().isIndexedUpdate()) {
                        boolean disjoint = spec.workload() == Workload.DISJOINT_INDEXED_UPDATE
                                || spec.workload() == Workload.LONG_READER_DISJOINT_WRITER;
                        int targetIndex = disjoint ? client : 0;
                        updateId = mutationIds[targetIndex];
                    }
                    String clientTable = spec.workload().usesPrivateTablePerClient()
                            ? tables.get(client)
                            : this.table;
                    clients.add(new Client(
                            connection, clientTable, spec, client, transactionsPerClient, rowCount,
                            updateId, readIds, expectedReadQuantities,
                            rangeStart, rangeEndExclusive, expectedRangeRows, expectedRangeFingerprint,
                            deleteRows, expectedFitnessRows, insertBaseId, options.payload(),
                            options.target(), options.h2RangeFetchSize()));
                }
            } catch (SQLException failure) {
                closeClients(failure);
                throw failure;
            }
            Connection localLongReaderConnection = null;
            PreparedStatement localLongReaderScan = null;
            try {
                if (spec.workload().isLongReaderWriter()) {
                    localLongReaderConnection = connect(options, database);
                    localLongReaderConnection.setAutoCommit(false);
                    localLongReaderConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    localLongReaderScan = localLongReaderConnection.prepareStatement(
                            "select id, quantity from " + this.table
                                    + " where id >= ? and id < ? order by id");
                }
            } catch (SQLException failure) {
                if (localLongReaderConnection != null) {
                    try {
                        localLongReaderConnection.close();
                    } catch (SQLException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                closeClients(failure);
                throw failure;
            }
            this.longReaderConnection = localLongReaderConnection;
            this.longReaderScan = localLongReaderScan;
            this.setupDrdaProtocolEvidence = snapshotDrdaProtocolEvidence();
            this.executor = Executors.newFixedThreadPool(
                    spec.clients(), Thread.ofPlatform().daemon().name("delos-bench-client-", 0).factory());
        }

        private long[] setupDrdaProtocolEvidence() {
            return setupDrdaProtocolEvidence;
        }

        private Interval runInterval(boolean captureSqlOracle) throws Exception {
            if (spec.workload().isLongReaderWriter()) {
                return runLongReaderWriterInterval(captureSqlOracle);
            }
            CountDownLatch ready = new CountDownLatch(spec.clients());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ClientRun>> futures = new ArrayList<>(spec.clients());
            for (Client client : clients) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency start barrier timed out");
                    }
                    return client.runTransactions(transactionsPerClient, spec.operationsPerTransaction());
                }));
            }
            if (!ready.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                start.countDown();
                cancelFutures(futures);
                throw new IllegalStateException("concurrency readiness barrier timed out");
            }
            if (drdaProtocolEvidenceEnabled()) {
                resetDrdaProtocolEvidence();
                beginDrdaProtocolTimingWindow();
            }
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(options.caseTimeoutSeconds());
            long executionFingerprint = 1L;
            long retryableRollbacks = 0L;
            Throwable failure = null;
            long elapsed;
            try {
                start.countDown();
                for (Future<ClientRun> future : futures) {
                    try {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0L) {
                            throw new TimeoutException("concurrency interval deadline exceeded");
                        }
                        ClientRun clientRun = future.get(remaining, TimeUnit.NANOSECONDS);
                        executionFingerprint = mix(executionFingerprint, clientRun.fingerprint());
                        retryableRollbacks = Math.addExact(retryableRollbacks, clientRun.retryableRollbacks());
                    } catch (TimeoutException timeout) {
                        cancelFutures(futures);
                        throw new IllegalStateException("concurrency interval exceeded "
                                + options.caseTimeoutSeconds() + " seconds: " + spec, timeout);
                    } catch (Throwable clientFailure) {
                        failure = preserve(failure, clientFailure);
                    }
                }
                elapsed = System.nanoTime() - started;
            } finally {
                if (drdaProtocolEvidenceEnabled()) {
                    endDrdaProtocolTimingWindow();
                }
            }
            if (failure != null) {
                cancelFutures(futures);
                throwFailure(failure);
            }
            long[] protocolEvidence = snapshotDrdaProtocolEvidence();
            Verification verification = verifyAndRestore(captureSqlOracle);
            return new Interval(
                    elapsed,
                    mix(executionFingerprint, verification.legacyFingerprint()),
                    retryableRollbacks,
                    verification.sqlOracleResult(),
                    protocolEvidence);
        }

        private Interval runLongReaderWriterInterval(boolean captureSqlOracle) throws Exception {
            if (longReaderConnection == null || longReaderScan == null || longReaderBaseline == null) {
                throw new IllegalStateException("Long-reader/writer case is not initialized: " + spec);
            }
            try {
                longReaderConnection.rollback();
                longReaderScan.setInt(1, longReaderStart);
                longReaderScan.setInt(2, longReaderEndExclusive);

                CountDownLatch ready = new CountDownLatch(spec.clients());
                CountDownLatch startWriters = new CountDownLatch(1);
                List<Future<ClientRun>> futures = new ArrayList<>(spec.clients());
                for (Client client : clients) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        if (!startWriters.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                            throw new IllegalStateException("long-reader writer start barrier timed out");
                        }
                        return client.runTransactions(transactionsPerClient, spec.operationsPerTransaction());
                    }));
                }
                if (!ready.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                    startWriters.countDown();
                    cancelFutures(futures);
                    throw new IllegalStateException("long-reader writer readiness barrier timed out");
                }

                if (drdaProtocolEvidenceEnabled()) {
                    resetDrdaProtocolEvidence();
                    beginDrdaProtocolTimingWindow();
                }
                long started = System.nanoTime();
                long deadline = started + TimeUnit.SECONDS.toNanos(options.caseTimeoutSeconds());
                DelosSqlSemanticOracle.Result readerSnapshot;
                long executionFingerprint = 1L;
                long retryableRollbacks = 0L;
                Throwable failure = null;
                int completedBeforeReaderRelease;
                long elapsed;
                try {
                    try (ResultSet resultSet = longReaderScan.executeQuery()) {
                        readerSnapshot = DelosSqlSemanticOracle.query(
                                resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
                    }
                    if (readerSnapshot.count() != longReaderBaseline.count()
                            || !readerSnapshot.fingerprint().equals(longReaderBaseline.fingerprint())) {
                        throw new IllegalStateException(
                                "Long-reader snapshot drift for " + spec
                                        + ": expected=" + longReaderBaseline
                                        + ", actual=" + readerSnapshot);
                    }

                    startWriters.countDown();
                    Thread.sleep(25L);
                    completedBeforeReaderRelease = 0;
                    for (Future<ClientRun> future : futures) {
                        if (future.isDone()) {
                            completedBeforeReaderRelease++;
                        }
                    }
                    longReaderConnection.rollback();

                    executionFingerprint = mix(executionFingerprint, readerSnapshot.fingerprint().hashCode());
                    for (Future<ClientRun> future : futures) {
                        try {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0L) {
                                throw new TimeoutException("long-reader/writer interval deadline exceeded");
                            }
                            ClientRun clientRun = future.get(remaining, TimeUnit.NANOSECONDS);
                            executionFingerprint = mix(executionFingerprint, clientRun.fingerprint());
                            retryableRollbacks = Math.addExact(
                                    retryableRollbacks, clientRun.retryableRollbacks());
                        } catch (TimeoutException timeout) {
                            cancelFutures(futures);
                            throw new IllegalStateException(
                                    "long-reader/writer interval exceeded "
                                            + options.caseTimeoutSeconds() + " seconds: " + spec,
                                    timeout);
                        } catch (Throwable clientFailure) {
                            failure = preserve(failure, clientFailure);
                        }
                    }
                    elapsed = System.nanoTime() - started;
                } finally {
                    try {
                        longReaderConnection.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure = preserve(failure, rollbackFailure);
                    }
                    if (drdaProtocolEvidenceEnabled()) {
                        endDrdaProtocolTimingWindow();
                    }
                }
                if (failure != null) {
                    cancelFutures(futures);
                    throwFailure(failure);
                }

                long[] protocolEvidence = snapshotDrdaProtocolEvidence();
                Verification verification = verifyAndRestore(captureSqlOracle);
                DelosSqlSemanticOracle.Result oracle = null;
                if (captureSqlOracle) {
                    Map<String, DelosSqlSemanticOracle.Result> components = new LinkedHashMap<>();
                    components.put("reader-snapshot", readerSnapshot);
                    components.put("writer-mutation", Objects.requireNonNull(
                            verification.sqlOracleResult(), "writer mutation oracle"));
                    oracle = DelosSqlSemanticOracle.composite(
                            "FITNESS_LONG_READER_WRITER", components);
                }
                System.out.printf(
                        Locale.ROOT,
                        "F12_PROGRESS run=%d target=%s workload=%s writersCompletedBeforeReaderRelease=%d/%d holdMillis=25%n",
                        options.run(), options.target().id(), spec.workload().name(),
                        completedBeforeReaderRelease, spec.clients());
                return new Interval(
                        elapsed,
                        mix(executionFingerprint, verification.legacyFingerprint()),
                        retryableRollbacks,
                        oracle,
                        protocolEvidence);
            } catch (Throwable failure) {
                try {
                    longReaderConnection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throwFailure(failure);
                throw new AssertionError("unreachable");
            }
        }

        private static void cancelFutures(List<? extends Future<?>> futures) {
            for (Future<?> future : futures) {
                future.cancel(true);
            }
        }

        private Verification verifyAndRestore(boolean captureSqlOracle) throws SQLException {
            try {
                long fingerprint = mix(spec.clients(), spec.operationsPerTransaction());
                if (spec.workload().isReadOnly()) {
                    DelosSqlSemanticOracle.Result oracle = captureSqlOracle
                            ? authoritativeReadOracle(verifier, table, spec, rowCount)
                            : null;
                    verifier.rollback();
                    return new Verification(fingerprint, oracle);
                }
                if (spec.workload().isRealisticTransaction()) {
                    return verifyAndRestoreRealisticTransaction(captureSqlOracle, fingerprint);
                }
                if (spec.workload().isInsert()) {
                    long expectedRows = Math.multiplyExact(
                            Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                            spec.operationsPerTransaction());
                    long insertedFingerprint = verifyInsertedState(
                            verifier, table, insertFirstId, insertLastId, rowCount, expectedRows,
                            options.payload());
                    DelosSqlSemanticOracle.Result oracle = captureSqlOracle
                            ? DelosSqlSemanticOracle.mutation(
                                    expectedRows,
                                    authoritativeInsertState(verifier, table, insertFirstId, insertLastId))
                            : null;
                    try (PreparedStatement cleanup = verifier.prepareStatement(
                            "delete from " + table + " where id >= ? and id <= ?")) {
                        cleanup.setInt(1, insertFirstId);
                        cleanup.setInt(2, insertLastId);
                        int deleted = cleanup.executeUpdate();
                        if (deleted != expectedRows) {
                            throw new SQLException(
                                    "INSERT fitness cleanup row-count drift for " + spec
                                            + ": expected=" + expectedRows + ", actual=" + deleted);
                        }
                    }
                    verifier.commit();
                    return new Verification(mix(fingerprint, insertedFingerprint), oracle);
                }
                if (spec.workload().isDeleteReinsert()) {
                    DelosSqlSemanticOracle.Result current =
                            authoritativeDeleteReinsertState(verifier, table, deleteReinsertIds);
                    if (!Objects.equals(deleteReinsertBaseline.fingerprint(), current.fingerprint())
                            || deleteReinsertBaseline.count() != current.count()) {
                        throw new IllegalStateException(
                                "Delete/reinsert state drift for " + spec
                                        + ": expected=" + deleteReinsertBaseline
                                        + ", actual=" + current);
                    }
                    DelosSqlSemanticOracle.Result oracle = null;
                    if (captureSqlOracle) {
                        long pairs = Math.multiplyExact(
                                Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                                spec.operationsPerTransaction());
                        oracle = DelosSqlSemanticOracle.mutation(Math.multiplyExact(2L, pairs), current);
                    }
                    verifier.rollback();
                    return new Verification(mix(fingerprint, current.fingerprint().hashCode()), oracle);
                }
                int increment = Math.multiplyExact(transactionsPerClient, spec.operationsPerTransaction());
                for (int index = 0; index < mutationIds.length; index++) {
                    int expected = mutationBaseline[index];
                    if (spec.workload() == Workload.DISJOINT_INDEXED_UPDATE
                            || spec.workload() == Workload.LONG_READER_DISJOINT_WRITER) {
                        expected += increment;
                    } else if (spec.workload() == Workload.CONTENDED_INDEXED_UPDATE
                            || spec.workload() == Workload.LONG_READER_HOT_WRITER) {
                        expected += Math.multiplyExact(spec.clients(), increment);
                    } else if (spec.workload() == Workload.MIXED_80R20W) {
                        expected += transactionsPerClient / 5;
                    } else if (spec.workload() == Workload.MIXED_50R50W_HOT) {
                        int writersPerKey = spec.clients() / mutationIds.length;
                        expected += Math.multiplyExact(writersPerKey, transactionsPerClient / 2);
                    }
                    int actual = quantity(verifier, table, mutationIds[index]);
                    if (actual != expected) {
                        throw new IllegalStateException("Concurrent semantic drift for " + spec
                                + ", id=" + mutationIds[index] + ": expected=" + expected + ", actual=" + actual);
                    }
                    fingerprint = mix(mix(fingerprint, mutationIds[index]), actual);
                }
                DelosSqlSemanticOracle.Result oracle = null;
                if (captureSqlOracle) {
                    DelosSqlSemanticOracle.Result finalState =
                            authoritativeMutationState(verifier, table, mutationIds);
                    long affectedRows;
                    if (spec.workload() == Workload.MIXED_80R20W) {
                        affectedRows = Math.multiplyExact((long) spec.clients(), transactionsPerClient / 5);
                    } else if (spec.workload() == Workload.MIXED_50R50W_HOT) {
                        affectedRows = Math.multiplyExact((long) spec.clients(), transactionsPerClient / 2);
                    } else {
                        affectedRows = Math.multiplyExact(
                                Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                                spec.operationsPerTransaction());
                    }
                    oracle = DelosSqlSemanticOracle.mutation(affectedRows, finalState);
                }
                try (PreparedStatement restore = verifier.prepareStatement(
                        "update " + table + " set quantity = ? where id = ?")) {
                    for (int index = 0; index < mutationIds.length; index++) {
                        restore.setInt(1, mutationBaseline[index]);
                        restore.setInt(2, mutationIds[index]);
                        if (restore.executeUpdate() != 1) {
                            throw new SQLException(
                                    "Concurrent restore did not affect one row: id=" + mutationIds[index]);
                        }
                    }
                }
                verifier.commit();
                return new Verification(fingerprint, oracle);
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    verifier.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }


        private Verification verifyAndRestoreRealisticTransaction(
                boolean captureSqlOracle, long fingerprint) throws SQLException {
            return spec.workload() == Workload.BANK_TRANSACTION
                    ? verifyAndRestoreBankTransaction(captureSqlOracle, fingerprint)
                    : verifyAndRestoreOrderEntry(captureSqlOracle, fingerprint);
        }

        private Verification verifyAndRestoreBankTransaction(
                boolean captureSqlOracle, long fingerprint) throws SQLException {
            int[] quantities = fixtureQuantities(rowCount);
            Map<Integer, Integer> accountDelta = new java.util.TreeMap<>();
            Map<Integer, Long> tellerDelta = new java.util.TreeMap<>();
            Map<Integer, Long> branchDelta = new java.util.TreeMap<>();
            long expectedTotal = 0L;
            int expectedTransactions = Math.multiplyExact(spec.clients(), transactionsPerClient);
            for (int client = 0; client < spec.clients(); client++) {
                for (int transaction = 0; transaction < transactionsPerClient; transaction++) {
                    int globalOrdinal = client * transactionsPerClient + transaction;
                    int accountId = bankAccountId(globalOrdinal, rowCount);
                    int branchId = bankBranchId(accountId, rowCount);
                    int tellerId = bankTellerId(branchId, globalOrdinal);
                    int delta = bankDelta(globalOrdinal);
                    accountDelta.merge(accountId, delta, Math::addExact);
                    tellerDelta.merge(tellerId, (long) delta, Math::addExact);
                    branchDelta.merge(branchId, (long) delta, Math::addExact);
                    expectedTotal = Math.addExact(expectedTotal, delta);
                }
            }

            long actualAccountTotal = 0L;
            for (Map.Entry<Integer, Integer> entry : accountDelta.entrySet()) {
                int actual = quantity(verifier, table, entry.getKey());
                int expected = Math.addExact(quantities[entry.getKey()], entry.getValue());
                if (actual != expected) {
                    throw new IllegalStateException("Bank account state drift: id=" + entry.getKey()
                            + ", expected=" + expected + ", actual=" + actual);
                }
                actualAccountTotal = Math.addExact(
                        actualAccountTotal, (long) actual - quantities[entry.getKey()]);
                fingerprint = mix(mix(fingerprint, entry.getKey()), actual);
            }
            long actualTellerTotal = verifyBalanceTable(
                    verifier, bankTellerTableName(table), tellerDelta, "bank teller");
            long actualBranchTotal = verifyBalanceTable(
                    verifier, bankBranchTableName(table), branchDelta, "bank branch");
            long actualHistoryTotal = 0L;
            int historyRows = 0;
            try (Statement statement = verifier.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "select tx_id, account_id, teller_id, branch_id, amount from "
                                    + bankHistoryTableName(table) + " order by tx_id")) {
                while (resultSet.next()) {
                    int txId = resultSet.getInt(1);
                    int globalOrdinal = txId - 1;
                    if (globalOrdinal < 0 || globalOrdinal >= expectedTransactions) {
                        throw new IllegalStateException("Unexpected bank history tx_id=" + txId);
                    }
                    int expectedAccount = bankAccountId(globalOrdinal, rowCount);
                    int expectedBranch = bankBranchId(expectedAccount, rowCount);
                    int expectedTeller = bankTellerId(expectedBranch, globalOrdinal);
                    int expectedDelta = bankDelta(globalOrdinal);
                    if (resultSet.getInt(2) != expectedAccount
                            || resultSet.getInt(3) != expectedTeller
                            || resultSet.getInt(4) != expectedBranch
                            || resultSet.getInt(5) != expectedDelta) {
                        throw new IllegalStateException("Bank history state drift for tx_id=" + txId);
                    }
                    actualHistoryTotal = Math.addExact(actualHistoryTotal, expectedDelta);
                    historyRows++;
                }
            }
            if (historyRows != expectedTransactions) {
                throw new IllegalStateException("Bank history row-count drift: expected="
                        + expectedTransactions + ", actual=" + historyRows);
            }
            if (actualAccountTotal != expectedTotal
                    || actualTellerTotal != expectedTotal
                    || actualBranchTotal != expectedTotal
                    || actualHistoryTotal != expectedTotal) {
                throw new IllegalStateException(
                        "Bank invariant drift: expectedTotal=" + expectedTotal
                                + ", account=" + actualAccountTotal
                                + ", teller=" + actualTellerTotal
                                + ", branch=" + actualBranchTotal
                                + ", history=" + actualHistoryTotal);
            }

            DelosSqlSemanticOracle.Result oracle = null;
            if (captureSqlOracle) {
                Map<String, DelosSqlSemanticOracle.Result> components = new LinkedHashMap<>();
                components.put("accounts", queryOracle(verifier,
                        "select id, quantity from " + table + " order by id"));
                components.put("tellers", queryOracle(verifier,
                        "select id, branch_id, balance from " + bankTellerTableName(table) + " order by id"));
                components.put("branches", queryOracle(verifier,
                        "select id, balance from " + bankBranchTableName(table) + " order by id"));
                components.put("history", queryOracle(verifier,
                        "select tx_id, account_id, teller_id, branch_id, amount from "
                                + bankHistoryTableName(table) + " order by tx_id"));
                oracle = DelosSqlSemanticOracle.composite("FITNESS_BANK_TRANSACTION", components);
            }

            try (PreparedStatement restore = verifier.prepareStatement(
                            "update " + table + " set quantity = ? where id = ?");
                    Statement cleanup = verifier.createStatement()) {
                for (int accountId : accountDelta.keySet()) {
                    restore.setInt(1, quantities[accountId]);
                    restore.setInt(2, accountId);
                    if (restore.executeUpdate() != 1) {
                        throw new SQLException("Bank account restore failed for id=" + accountId);
                    }
                }
                cleanup.executeUpdate("update " + bankTellerTableName(table) + " set balance = 0");
                cleanup.executeUpdate("update " + bankBranchTableName(table) + " set balance = 0");
                cleanup.executeUpdate("delete from " + bankHistoryTableName(table));
            }
            verifier.commit();
            return new Verification(mix(fingerprint, expectedTotal), oracle);
        }

        private Verification verifyAndRestoreOrderEntry(
                boolean captureSqlOracle, long fingerprint) throws SQLException {
            int customerCount = Math.min(ORDER_CUSTOMERS, rowCount);
            int[] quantities = fixtureQuantities(rowCount);
            Map<Integer, Integer> stockDelta = new java.util.TreeMap<>();
            Map<Integer, Long> customerBalance = new java.util.TreeMap<>();
            Map<Integer, Integer> customerLastOrder = new java.util.TreeMap<>();
            Map<Integer, Long> warehouseBalance = new java.util.TreeMap<>();
            Map<Integer, ExpectedOrder> expectedOrders = new java.util.TreeMap<>();
            Map<OrderLineKey, ExpectedOrderLine> expectedLines = new java.util.TreeMap<>();

            for (int client = 0; client < spec.clients(); client++) {
                for (int transaction = 0; transaction < transactionsPerClient; transaction++) {
                    int globalOrdinal = client * transactionsPerClient + transaction;
                    int customerId = orderCustomerId(client, transaction, spec.clients(), customerCount);
                    int warehouseId = orderWarehouseId(client);
                    switch (orderEntryType(transaction)) {
                        case NEW_ORDER -> {
                            int orderId = ORDER_NEW_BASE + globalOrdinal;
                            expectedOrders.put(orderId, new ExpectedOrder(customerId, 0, 33));
                            customerLastOrder.put(customerId, orderId);
                            for (int line = 1; line <= 3; line++) {
                                int stockId = orderStockId(globalOrdinal, line, rowCount);
                                int amount = 10 + line;
                                stockDelta.merge(stockId, -1, Math::addExact);
                                expectedLines.put(
                                        new OrderLineKey(orderId, line),
                                        new ExpectedOrderLine(stockId, 1, amount));
                            }
                        }
                        case PAYMENT -> {
                            int amount = orderPaymentAmount(globalOrdinal);
                            customerBalance.merge(customerId, (long) amount, Math::addExact);
                            warehouseBalance.merge(warehouseId, (long) amount, Math::addExact);
                        }
                        case ORDER_STATUS, STOCK_LEVEL -> {
                            // Read-only members of the deterministic mix.
                        }
                        case DELIVERY -> {
                            int orderId = ORDER_DELIVERY_BASE + globalOrdinal;
                            expectedOrders.put(orderId, new ExpectedOrder(customerId, 1, 50));
                            customerBalance.merge(customerId, 50L, Math::addExact);
                        }
                        case NEW_ORDER_ROLLBACK -> {
                            // Deliberate rollback: no SQL-visible state is allowed to remain.
                        }
                    }
                }
            }

            for (Map.Entry<Integer, Integer> entry : stockDelta.entrySet()) {
                int actual = quantity(verifier, table, entry.getKey());
                int expected = Math.addExact(quantities[entry.getKey()], entry.getValue());
                if (actual != expected) {
                    throw new IllegalStateException("Order Entry stock drift: id=" + entry.getKey()
                            + ", expected=" + expected + ", actual=" + actual);
                }
                fingerprint = mix(mix(fingerprint, entry.getKey()), actual);
            }

            try (Statement statement = verifier.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "select id, balance, last_order from " + orderCustomerTableName(table)
                                    + " order by id")) {
                int rows = 0;
                while (resultSet.next()) {
                    int id = resultSet.getInt(1);
                    long expectedBalance = customerBalance.getOrDefault(id, 0L);
                    int expectedLastOrder = customerLastOrder.getOrDefault(id, 0);
                    if (resultSet.getLong(2) != expectedBalance || resultSet.getInt(3) != expectedLastOrder) {
                        throw new IllegalStateException("Order Entry customer drift: id=" + id
                                + ", expectedBalance=" + expectedBalance
                                + ", actualBalance=" + resultSet.getLong(2)
                                + ", expectedLastOrder=" + expectedLastOrder
                                + ", actualLastOrder=" + resultSet.getInt(3));
                    }
                    rows++;
                }
                if (rows != customerCount) {
                    throw new IllegalStateException("Order Entry customer row-count drift: expected="
                            + customerCount + ", actual=" + rows);
                }
            }
            long verifiedWarehouseTotal = verifyBalanceTable(
                    verifier, orderWarehouseTableName(table), warehouseBalance, "order warehouse");
            long expectedWarehouseTotal = 0L;
            for (long amount : warehouseBalance.values()) {
                expectedWarehouseTotal = Math.addExact(expectedWarehouseTotal, amount);
            }
            if (verifiedWarehouseTotal != expectedWarehouseTotal) {
                throw new IllegalStateException("Order Entry warehouse invariant drift");
            }

            try (Statement statement = verifier.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "select id, customer_id, status, amount from " + orderTableName(table)
                                    + " order by id")) {
                int rows = 0;
                while (resultSet.next()) {
                    int id = resultSet.getInt(1);
                    ExpectedOrder expected = expectedOrders.get(id);
                    if (expected == null) {
                        throw new IllegalStateException("Unexpected Order Entry order row id=" + id);
                    }
                    if (resultSet.getInt(2) != expected.customerId()
                            || resultSet.getInt(3) != expected.status()
                            || resultSet.getInt(4) != expected.amount()) {
                        throw new IllegalStateException("Order Entry order drift: id=" + id);
                    }
                    rows++;
                }
                if (rows != expectedOrders.size()) {
                    throw new IllegalStateException("Order Entry order row-count drift: expected="
                            + expectedOrders.size() + ", actual=" + rows);
                }
            }

            try (Statement statement = verifier.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "select order_id, line_no, stock_id, quantity, amount from "
                                    + orderLineTableName(table) + " order by order_id, line_no")) {
                int rows = 0;
                while (resultSet.next()) {
                    OrderLineKey key = new OrderLineKey(resultSet.getInt(1), resultSet.getInt(2));
                    ExpectedOrderLine expected = expectedLines.get(key);
                    if (expected == null) {
                        throw new IllegalStateException("Unexpected Order Entry line " + key);
                    }
                    if (resultSet.getInt(3) != expected.stockId()
                            || resultSet.getInt(4) != expected.quantity()
                            || resultSet.getInt(5) != expected.amount()) {
                        throw new IllegalStateException("Order Entry line drift: " + key);
                    }
                    rows++;
                }
                if (rows != expectedLines.size()) {
                    throw new IllegalStateException("Order Entry line row-count drift: expected="
                            + expectedLines.size() + ", actual=" + rows);
                }
            }

            DelosSqlSemanticOracle.Result oracle = null;
            if (captureSqlOracle) {
                Map<String, DelosSqlSemanticOracle.Result> components = new LinkedHashMap<>();
                components.put("stock", queryOracle(verifier,
                        "select id, quantity from " + table + " order by id"));
                components.put("warehouses", queryOracle(verifier,
                        "select id, balance from " + orderWarehouseTableName(table) + " order by id"));
                components.put("customers", queryOracle(verifier,
                        "select id, balance, last_order from " + orderCustomerTableName(table) + " order by id"));
                components.put("orders", queryOracle(verifier,
                        "select id, customer_id, status, amount from " + orderTableName(table) + " order by id"));
                components.put("lines", queryOracle(verifier,
                        "select order_id, line_no, stock_id, quantity, amount from "
                                + orderLineTableName(table) + " order by order_id, line_no"));
                oracle = DelosSqlSemanticOracle.composite("FITNESS_ORDER_ENTRY_MIX", components);
            }

            try (PreparedStatement restore = verifier.prepareStatement(
                            "update " + table + " set quantity = ? where id = ?");
                    Statement cleanup = verifier.createStatement()) {
                for (int stockId : stockDelta.keySet()) {
                    restore.setInt(1, quantities[stockId]);
                    restore.setInt(2, stockId);
                    if (restore.executeUpdate() != 1) {
                        throw new SQLException("Order Entry stock restore failed for id=" + stockId);
                    }
                }
                cleanup.executeUpdate("update " + orderCustomerTableName(table)
                        + " set balance = 0, last_order = 0");
                cleanup.executeUpdate("update " + orderWarehouseTableName(table) + " set balance = 0");
                cleanup.executeUpdate("delete from " + orderLineTableName(table));
                cleanup.executeUpdate("delete from " + orderTableName(table) + " where id >= " + ORDER_NEW_BASE);
                cleanup.executeUpdate("update " + orderTableName(table)
                        + " set status = 0 where id >= " + ORDER_DELIVERY_BASE + " and id < " + ORDER_NEW_BASE);
            }
            verifier.commit();
            return new Verification(mix(fingerprint, expectedOrders.size()), oracle);
        }

        private static long verifyBalanceTable(
                Connection connection,
                String table,
                Map<Integer, Long> expectedBalances,
                String label) throws SQLException {
            long total = 0L;
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "select id, balance from " + table + " order by id")) {
                while (resultSet.next()) {
                    int id = resultSet.getInt(1);
                    long actual = resultSet.getLong(2);
                    long expected = expectedBalances.getOrDefault(id, 0L);
                    if (actual != expected) {
                        throw new IllegalStateException(label + " balance drift: id=" + id
                                + ", expected=" + expected + ", actual=" + actual);
                    }
                    total = Math.addExact(total, actual);
                }
            }
            return total;
        }

        private static DelosSqlSemanticOracle.Result queryOracle(
                Connection connection, String sql) throws SQLException {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                return DelosSqlSemanticOracle.query(
                        resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
            }
        }

        private static DelosSqlSemanticOracle.Result authoritativeReadOracle(
                Connection connection,
                String table,
                Spec spec,
                int rowCount) throws SQLException {
            if (spec.workload() == Workload.PRIMARY_KEY_READ_HOT) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "select quantity from " + table + " where id = ?")) {
                    statement.setInt(1, 1);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return DelosSqlSemanticOracle.query(
                                resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
                    }
                }
            }
            if (spec.workload() == Workload.PRIMARY_KEY_READ_DISJOINT) {
                Map<String, DelosSqlSemanticOracle.Result> clients = new LinkedHashMap<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "select quantity from " + table + " where id = ?")) {
                    for (int client = 0; client < spec.clients(); client++) {
                        int id = 1 + (int) (((long) client * rowCount) / spec.clients());
                        statement.setInt(1, id);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            clients.put(
                                    String.format(Locale.ROOT, "client-%03d", client),
                                    DelosSqlSemanticOracle.query(
                                            resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED));
                        }
                    }
                }
                return DelosSqlSemanticOracle.composite("FITNESS_POINT_READ", clients);
            }
            if (spec.workload().isRangeScan()
                    && !spec.workload().isCoveringRangeScan()
                    && !spec.workload().isRowBearingComparisonRangeScan()
                    && !spec.workload().isMvccNaturalOrderRangeScan()) {
                Map<String, DelosSqlSemanticOracle.Result> clients = new LinkedHashMap<>();
                int rangeRows = spec.workload().rangeRows(rowCount);
                int startCount = rowCount - rangeRows + 1;
                String projection = spec.workload().isIndexOnlyRangeScan() ? "id" : "id, quantity";
                String sql = "select " + projection + " from " + table
                        + " where id >= ? and id < ? order by id";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int client = 0; client < spec.clients(); client++) {
                        int start = 1 + (int) (((long) client * startCount) / spec.clients());
                        statement.setInt(1, start);
                        statement.setInt(2, start + rangeRows);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            clients.put(
                                    String.format(Locale.ROOT, "client-%03d", client),
                                    DelosSqlSemanticOracle.query(
                                            resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED));
                        }
                    }
                }
                return DelosSqlSemanticOracle.composite("FITNESS_RANGE_SCAN", clients);
            }
            if (spec.workload().isFitnessRead()) {
                String sql = fitnessReadSql(spec.workload(), table);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bindFitnessRead(statement, spec.workload());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        DelosSqlSemanticOracle.RowOrder order =
                                spec.workload() == Workload.PROJECTION_COVERED
                                        || spec.workload() == Workload.JOIN_INDEXED_1TO1
                                        || spec.workload() == Workload.JOIN_3WAY_SELECTIVE
                                        || spec.workload() == Workload.JOIN_4WAY_FANOUT
                                        ? DelosSqlSemanticOracle.RowOrder.UNORDERED
                                        : DelosSqlSemanticOracle.RowOrder.ORDERED;
                        return DelosSqlSemanticOracle.query(resultSet, order);
                    }
                }
            }
            throw new SQLException(
                    "Phase 0A SQL oracle is not wired for fitness workload " + spec.workload());
        }

        private static DelosSqlSemanticOracle.Result authoritativeDeleteReinsertState(
                Connection connection,
                String table,
                int[] ids) throws SQLException {
            if (ids.length == 0) {
                throw new SQLException("Delete/reinsert SQL oracle requires at least one row id");
            }
            StringBuilder sql = new StringBuilder(
                    "select id, category, bucket, quantity, payload from " + table + " where id in (");
            for (int index = 0; index < ids.length; index++) {
                if (index != 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(") order by id");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int index = 0; index < ids.length; index++) {
                    statement.setInt(index + 1, ids[index]);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    return DelosSqlSemanticOracle.query(
                            resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
                }
            }
        }

        private static DelosSqlSemanticOracle.Result authoritativeLongReaderState(
                Connection connection,
                String table,
                int startInclusive,
                int endExclusive) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, quantity from " + table
                            + " where id >= ? and id < ? order by id")) {
                statement.setInt(1, startInclusive);
                statement.setInt(2, endExclusive);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return DelosSqlSemanticOracle.query(
                            resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
                }
            }
        }

        private static DelosSqlSemanticOracle.Result authoritativeMutationState(
                Connection connection,
                String table,
                int[] mutationIds) throws SQLException {
            if (mutationIds.length == 0) {
                throw new SQLException("Mutation SQL oracle requires at least one mutation id");
            }
            StringBuilder sql = new StringBuilder(
                    "select id, quantity from " + table + " where id in (");
            for (int index = 0; index < mutationIds.length; index++) {
                if (index != 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(") order by id");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int index = 0; index < mutationIds.length; index++) {
                    statement.setInt(index + 1, mutationIds[index]);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    return DelosSqlSemanticOracle.query(
                            resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
                }
            }
        }

        @Override
        public void close() throws Exception {
            executor.shutdownNow();
            if (executor.awaitTermination(10, TimeUnit.SECONDS)) {
                SQLException failure = null;
                if (longReaderScan != null) {
                    try {
                        longReaderScan.close();
                    } catch (SQLException closeFailure) {
                        failure = closeFailure;
                    }
                }
                if (longReaderConnection != null) {
                    try {
                        if (!longReaderConnection.getAutoCommit()) {
                            longReaderConnection.rollback();
                        }
                        longReaderConnection.close();
                    } catch (SQLException closeFailure) {
                        if (failure == null) {
                            failure = closeFailure;
                        } else {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                }
                try {
                    closeClients(failure);
                } catch (SQLException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else if (failure != closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            } else {
                System.err.println("Benchmark client tasks did not terminate after cancellation; "
                        + "leaving JDBC connections to isolated worker-process teardown");
            }
        }

        private void closeClients(Throwable primary) throws SQLException {
            SQLException first = null;
            for (Client client : clients) {
                try {
                    client.close();
                } catch (SQLException failure) {
                    if (first == null) {
                        first = failure;
                    } else {
                        first.addSuppressed(failure);
                    }
                }
            }
            if (first != null) {
                if (primary != null) {
                    primary.addSuppressed(first);
                } else {
                    throw first;
                }
            }
        }
    }


    private record ExpectedOrder(int customerId, int status, int amount) {
    }

    private record OrderLineKey(int orderId, int lineNo) implements Comparable<OrderLineKey> {
        @Override
        public int compareTo(OrderLineKey other) {
            int byOrder = Integer.compare(orderId, other.orderId);
            return byOrder != 0 ? byOrder : Integer.compare(lineNo, other.lineNo);
        }
    }

    private record ExpectedOrderLine(int stockId, int quantity, int amount) {
    }

    private static final class RealisticTransactionClient implements AutoCloseable {
        private final Connection connection;
        private final String table;
        private final Workload workload;
        private final int clientIndex;
        private final int transactionsPerClient;
        private final int clients;
        private final int rowCount;
        private final int customerCount;
        private final PreparedStatement bankUpdateAccount;
        private final PreparedStatement bankInsertHistory;
        private final PreparedStatement bankUpdateTeller;
        private final PreparedStatement bankUpdateBranch;
        private final PreparedStatement bankReadAccount;
        private final PreparedStatement oeInsertOrder;
        private final PreparedStatement oeInsertLine;
        private final PreparedStatement oeUpdateStock;
        private final PreparedStatement oeUpdateLastOrder;
        private final PreparedStatement oePaymentCustomer;
        private final PreparedStatement oePaymentWarehouse;
        private final PreparedStatement oeReadCustomer;
        private final PreparedStatement oeReadOrder;
        private final PreparedStatement oeDeliverOrder;
        private final PreparedStatement oeDeliverCustomer;
        private final PreparedStatement oeStockLevel;

        private RealisticTransactionClient(
                Connection connection,
                String table,
                Workload workload,
                int clientIndex,
                int transactionsPerClient,
                int clients,
                int rowCount) throws SQLException {
            this.connection = connection;
            this.table = table;
            this.workload = workload;
            this.clientIndex = clientIndex;
            this.transactionsPerClient = transactionsPerClient;
            this.clients = clients;
            this.rowCount = rowCount;
            this.customerCount = Math.min(ORDER_CUSTOMERS, rowCount);

            PreparedStatement localBankUpdateAccount = null;
            PreparedStatement localBankInsertHistory = null;
            PreparedStatement localBankUpdateTeller = null;
            PreparedStatement localBankUpdateBranch = null;
            PreparedStatement localBankReadAccount = null;
            PreparedStatement localOeInsertOrder = null;
            PreparedStatement localOeInsertLine = null;
            PreparedStatement localOeUpdateStock = null;
            PreparedStatement localOeUpdateLastOrder = null;
            PreparedStatement localOePaymentCustomer = null;
            PreparedStatement localOePaymentWarehouse = null;
            PreparedStatement localOeReadCustomer = null;
            PreparedStatement localOeReadOrder = null;
            PreparedStatement localOeDeliverOrder = null;
            PreparedStatement localOeDeliverCustomer = null;
            PreparedStatement localOeStockLevel = null;
            try {
                if (workload == Workload.BANK_TRANSACTION) {
                    localBankUpdateAccount = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + ? where id = ?");
                    localBankInsertHistory = connection.prepareStatement(
                            "insert into " + bankHistoryTableName(table)
                                    + " (tx_id, account_id, teller_id, branch_id, amount) values (?, ?, ?, ?, ?)");
                    localBankUpdateTeller = connection.prepareStatement(
                            "update " + bankTellerTableName(table)
                                    + " set balance = balance + ? where id = ?");
                    localBankUpdateBranch = connection.prepareStatement(
                            "update " + bankBranchTableName(table)
                                    + " set balance = balance + ? where id = ?");
                    localBankReadAccount = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                } else if (workload == Workload.ORDER_ENTRY_MIX) {
                    localOeInsertOrder = connection.prepareStatement(
                            "insert into " + orderTableName(table)
                                    + " (id, customer_id, status, amount) values (?, ?, 0, ?)");
                    localOeInsertLine = connection.prepareStatement(
                            "insert into " + orderLineTableName(table)
                                    + " (order_id, line_no, stock_id, quantity, amount) values (?, ?, ?, ?, ?)");
                    localOeUpdateStock = connection.prepareStatement(
                            "update " + table + " set quantity = quantity - ? where id = ?");
                    localOeUpdateLastOrder = connection.prepareStatement(
                            "update " + orderCustomerTableName(table)
                                    + " set last_order = ? where id = ?");
                    localOePaymentCustomer = connection.prepareStatement(
                            "update " + orderCustomerTableName(table)
                                    + " set balance = balance + ? where id = ?");
                    localOePaymentWarehouse = connection.prepareStatement(
                            "update " + orderWarehouseTableName(table)
                                    + " set balance = balance + ? where id = ?");
                    localOeReadCustomer = connection.prepareStatement(
                            "select balance, last_order from " + orderCustomerTableName(table)
                                    + " where id = ?");
                    localOeReadOrder = connection.prepareStatement(
                            "select status, amount from " + orderTableName(table) + " where id = ?");
                    localOeDeliverOrder = connection.prepareStatement(
                            "update " + orderTableName(table) + " set status = 1 where id = ? and status = 0");
                    localOeDeliverCustomer = connection.prepareStatement(
                            "update " + orderCustomerTableName(table)
                                    + " set balance = balance + 50 where id = ?");
                    localOeStockLevel = connection.prepareStatement(
                            "select count(*) from " + table + " where quantity < ?");
                } else {
                    throw new SQLException("Not a realistic transaction workload: " + workload);
                }
            } catch (SQLException failure) {
                PreparedStatement[] statements = {
                        localBankUpdateAccount, localBankInsertHistory, localBankUpdateTeller,
                        localBankUpdateBranch, localBankReadAccount, localOeInsertOrder,
                        localOeInsertLine, localOeUpdateStock, localOeUpdateLastOrder,
                        localOePaymentCustomer, localOePaymentWarehouse, localOeReadCustomer,
                        localOeReadOrder, localOeDeliverOrder, localOeDeliverCustomer,
                        localOeStockLevel};
                for (PreparedStatement statement : statements) {
                    closeStatement(statement, failure);
                }
                throw failure;
            }
            this.bankUpdateAccount = localBankUpdateAccount;
            this.bankInsertHistory = localBankInsertHistory;
            this.bankUpdateTeller = localBankUpdateTeller;
            this.bankUpdateBranch = localBankUpdateBranch;
            this.bankReadAccount = localBankReadAccount;
            this.oeInsertOrder = localOeInsertOrder;
            this.oeInsertLine = localOeInsertLine;
            this.oeUpdateStock = localOeUpdateStock;
            this.oeUpdateLastOrder = localOeUpdateLastOrder;
            this.oePaymentCustomer = localOePaymentCustomer;
            this.oePaymentWarehouse = localOePaymentWarehouse;
            this.oeReadCustomer = localOeReadCustomer;
            this.oeReadOrder = localOeReadOrder;
            this.oeDeliverOrder = localOeDeliverOrder;
            this.oeDeliverCustomer = localOeDeliverCustomer;
            this.oeStockLevel = localOeStockLevel;
        }

        private long execute(int transaction) throws SQLException {
            return workload == Workload.BANK_TRANSACTION
                    ? executeBankTransaction(transaction)
                    : executeOrderEntryTransaction(transaction);
        }

        private long executeBankTransaction(int transaction) throws SQLException {
            int globalOrdinal = clientIndex * transactionsPerClient + transaction;
            int accountId = bankAccountId(globalOrdinal, rowCount);
            int branchId = bankBranchId(accountId, rowCount);
            int tellerId = bankTellerId(branchId, globalOrdinal);
            int delta = bankDelta(globalOrdinal);
            int txId = globalOrdinal + 1;

            bankUpdateAccount.setInt(1, delta);
            bankUpdateAccount.setInt(2, accountId);
            requireOne(bankUpdateAccount.executeUpdate(), "bank account update", accountId);

            bankInsertHistory.setInt(1, txId);
            bankInsertHistory.setInt(2, accountId);
            bankInsertHistory.setInt(3, tellerId);
            bankInsertHistory.setInt(4, branchId);
            bankInsertHistory.setInt(5, delta);
            requireOne(bankInsertHistory.executeUpdate(), "bank history insert", txId);

            bankUpdateTeller.setInt(1, delta);
            bankUpdateTeller.setInt(2, tellerId);
            requireOne(bankUpdateTeller.executeUpdate(), "bank teller update", tellerId);

            bankUpdateBranch.setInt(1, delta);
            bankUpdateBranch.setInt(2, branchId);
            requireOne(bankUpdateBranch.executeUpdate(), "bank branch update", branchId);

            bankReadAccount.setInt(1, accountId);
            try (ResultSet resultSet = bankReadAccount.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Bank account read returned no row: id=" + accountId);
                }
                resultSet.getInt(1);
                if (resultSet.next()) {
                    throw new SQLException("Bank account read returned duplicate row: id=" + accountId);
                }
            }
            connection.commit();
            long fingerprint = mix(txId, accountId);
            fingerprint = mix(fingerprint, tellerId);
            fingerprint = mix(fingerprint, branchId);
            return mix(fingerprint, delta);
        }

        private long executeOrderEntryTransaction(int transaction) throws SQLException {
            int globalOrdinal = clientIndex * transactionsPerClient + transaction;
            int customerId = orderCustomerId(
                    clientIndex, transaction, clients, customerCount);
            int warehouseId = orderWarehouseId(clientIndex);
            OrderEntryType type = orderEntryType(transaction);
            long fingerprint = mix(globalOrdinal + 1L, type.ordinal());
            fingerprint = mix(fingerprint, customerId);
            switch (type) {
                case NEW_ORDER -> {
                    int orderId = ORDER_NEW_BASE + globalOrdinal;
                    int totalAmount = 0;
                    oeInsertOrder.setInt(1, orderId);
                    oeInsertOrder.setInt(2, customerId);
                    oeInsertOrder.setInt(3, 33);
                    requireOne(oeInsertOrder.executeUpdate(), "new-order header insert", orderId);
                    for (int line = 1; line <= 3; line++) {
                        int stockId = orderStockId(globalOrdinal, line, rowCount);
                        int amount = 10 + line;
                        totalAmount += amount;
                        oeUpdateStock.setInt(1, 1);
                        oeUpdateStock.setInt(2, stockId);
                        requireOne(oeUpdateStock.executeUpdate(), "new-order stock update", stockId);
                        oeInsertLine.setInt(1, orderId);
                        oeInsertLine.setInt(2, line);
                        oeInsertLine.setInt(3, stockId);
                        oeInsertLine.setInt(4, 1);
                        oeInsertLine.setInt(5, amount);
                        requireOne(oeInsertLine.executeUpdate(), "new-order line insert", orderId);
                    }
                    if (totalAmount != 33) {
                        throw new SQLException("Order Entry amount construction drift: " + totalAmount);
                    }
                    oeUpdateLastOrder.setInt(1, orderId);
                    oeUpdateLastOrder.setInt(2, customerId);
                    requireOne(oeUpdateLastOrder.executeUpdate(), "new-order customer update", customerId);
                    connection.commit();
                    return mix(fingerprint, orderId);
                }
                case PAYMENT -> {
                    int amount = orderPaymentAmount(globalOrdinal);
                    oePaymentCustomer.setInt(1, amount);
                    oePaymentCustomer.setInt(2, customerId);
                    requireOne(oePaymentCustomer.executeUpdate(), "payment customer update", customerId);
                    oePaymentWarehouse.setInt(1, amount);
                    oePaymentWarehouse.setInt(2, warehouseId);
                    requireOne(oePaymentWarehouse.executeUpdate(), "payment warehouse update", warehouseId);
                    readCustomer(customerId);
                    connection.commit();
                    return mix(fingerprint, amount);
                }
                case ORDER_STATUS -> {
                    int lastOrder = readCustomer(customerId);
                    if (lastOrder != 0) {
                        oeReadOrder.setInt(1, lastOrder);
                        try (ResultSet resultSet = oeReadOrder.executeQuery()) {
                            if (!resultSet.next()) {
                                throw new SQLException("Order status could not find last order " + lastOrder);
                            }
                            resultSet.getInt(1);
                            resultSet.getInt(2);
                            if (resultSet.next()) {
                                throw new SQLException("Order status returned duplicate order " + lastOrder);
                            }
                        }
                    }
                    connection.commit();
                    return fingerprint;
                }
                case DELIVERY -> {
                    int orderId = ORDER_DELIVERY_BASE + globalOrdinal;
                    oeDeliverOrder.setInt(1, orderId);
                    requireOne(oeDeliverOrder.executeUpdate(), "delivery order update", orderId);
                    oeDeliverCustomer.setInt(1, customerId);
                    requireOne(oeDeliverCustomer.executeUpdate(), "delivery customer update", customerId);
                    connection.commit();
                    return mix(fingerprint, orderId);
                }
                case STOCK_LEVEL -> {
                    oeStockLevel.setInt(1, 20);
                    try (ResultSet resultSet = oeStockLevel.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new SQLException("Stock-level query returned no row");
                        }
                        resultSet.getLong(1);
                        if (resultSet.next()) {
                            throw new SQLException("Stock-level query returned multiple rows");
                        }
                    }
                    connection.commit();
                    return fingerprint;
                }
                case NEW_ORDER_ROLLBACK -> {
                    int orderId = ORDER_ROLLBACK_BASE + globalOrdinal;
                    int stockId = orderStockId(globalOrdinal, 1, rowCount);
                    oeInsertOrder.setInt(1, orderId);
                    oeInsertOrder.setInt(2, customerId);
                    oeInsertOrder.setInt(3, 11);
                    requireOne(oeInsertOrder.executeUpdate(), "rollback order insert", orderId);
                    oeUpdateStock.setInt(1, 1);
                    oeUpdateStock.setInt(2, stockId);
                    requireOne(oeUpdateStock.executeUpdate(), "rollback stock update", stockId);
                    connection.rollback();
                    return mix(fingerprint, orderId);
                }
            }
            throw new SQLException("Unhandled Order Entry type " + type);
        }

        private int readCustomer(int customerId) throws SQLException {
            oeReadCustomer.setInt(1, customerId);
            try (ResultSet resultSet = oeReadCustomer.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Order Entry customer read returned no row: id=" + customerId);
                }
                resultSet.getLong(1);
                int lastOrder = resultSet.getInt(2);
                if (resultSet.next()) {
                    throw new SQLException("Order Entry customer read returned duplicate row: id=" + customerId);
                }
                return lastOrder;
            }
        }

        private static void requireOne(int count, String operation, int id) throws SQLException {
            if (count != 1) {
                throw new SQLException(operation + " did not affect exactly one row: id=" + id
                        + ", count=" + count);
            }
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            PreparedStatement[] statements = {
                    bankUpdateAccount, bankInsertHistory, bankUpdateTeller, bankUpdateBranch,
                    bankReadAccount, oeInsertOrder, oeInsertLine, oeUpdateStock, oeUpdateLastOrder,
                    oePaymentCustomer, oePaymentWarehouse, oeReadCustomer, oeReadOrder,
                    oeDeliverOrder, oeDeliverCustomer, oeStockLevel};
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
    }

    private static final class Client implements AutoCloseable {
        private final Connection connection;
        private final Workload workload;
        private final int clientIndex;
        private final int clientCount;
        private final int rowCount;
        private final int transactionsPerClient;
        private final int[] mixedBaselineQuantities;
        private final int updateId;
        private final int[] readIds;
        private final int[] expectedReadQuantities;
        private final int rangeStart;
        private final int rangeEndExclusive;
        private final int expectedRangeRows;
        private final long expectedRangeFingerprint;
        private final Target target;
        private final PreparedStatement read;
        private final PreparedStatement rangeRead;
        private final PreparedStatement values;
        private final PreparedStatement fitnessRead;
        private final PreparedStatement update;
        private final PreparedStatement deleteRow;
        private final PreparedStatement insertRow;
        private final DeleteRow[] deleteRows;
        private final int expectedFitnessRows;
        private final int insertBaseId;
        private final int payloadSize;
        private final RealisticTransactionClient realisticTransaction;

        private Client(
                Connection connection,
                String table,
                Spec spec,
                int clientIndex,
                int transactionsPerClient,
                int rowCount,
                int updateId,
                int[] readIds,
                int[] expectedReadQuantities,
                int rangeStart,
                int rangeEndExclusive,
                int expectedRangeRows,
                long expectedRangeFingerprint,
                DeleteRow[] deleteRows,
                int expectedFitnessRows,
                int insertBaseId,
                int payloadSize,
                Target target,
                int h2RangeFetchSize)
                throws SQLException {
            this.connection = connection;
            this.workload = spec.workload();
            this.clientIndex = clientIndex;
            this.clientCount = spec.clients();
            this.rowCount = rowCount;
            this.transactionsPerClient = transactionsPerClient;
            this.mixedBaselineQuantities = spec.workload().isMixedReaderWriter()
                    ? fixtureQuantities(rowCount)
                    : null;
            this.updateId = updateId;
            this.readIds = readIds;
            this.expectedReadQuantities = expectedReadQuantities;
            this.rangeStart = rangeStart;
            this.rangeEndExclusive = rangeEndExclusive;
            this.expectedRangeRows = expectedRangeRows;
            this.expectedRangeFingerprint = expectedRangeFingerprint;
            this.deleteRows = deleteRows == null ? new DeleteRow[0] : deleteRows.clone();
            this.expectedFitnessRows = expectedFitnessRows;
            this.insertBaseId = insertBaseId;
            this.payloadSize = payloadSize;
            this.target = target;
            PreparedStatement localRead = null;
            PreparedStatement localRangeRead = null;
            PreparedStatement localValues = null;
            PreparedStatement localFitnessRead = null;
            PreparedStatement localUpdate = null;
            PreparedStatement localDeleteRow = null;
            PreparedStatement localInsertRow = null;
            RealisticTransactionClient localRealisticTransaction = null;
            try {
                if (workload.isPrimaryKeyRead()) {
                    localRead = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                } else if (workload.isRangeScan()) {
                    String rangeSql;
                    if (workload.isIndexOnlyRangeScan()) {
                        rangeSql = "select id from " + table
                                + " where id >= ? and id < ? order by id";
                    } else if (workload.isCoveringRangeScan()
                            || (workload.isRowBearingComparisonRangeScan()
                                    && (target == Target.DELOS_HEAP
                                            || target == Target.UPSTREAM_DERBY))) {
                        rangeSql = "select id, quantity from " + table
                                + " --DERBY-PROPERTIES index=" + rangeCoveringIndexName(table) + "\n"
                                + " where id >= ? and id < ? order by id";
                    } else if (workload.isMvccNaturalOrderRangeScan()) {
                        rangeSql = "select id, quantity from " + table
                                + " where id >= ? and id < ?";
                    } else {
                        rangeSql = "select id, quantity from " + table
                                + " where id >= ? and id < ? order by id";
                    }
                    localRangeRead = connection.prepareStatement(rangeSql);
                    if (target == Target.H2_SERVER && h2RangeFetchSize > 0) {
                        localRangeRead.setFetchSize(h2RangeFetchSize);
                    }
                } else if (workload.isValues()) {
                    localValues = connection.prepareStatement("values (1)");
                } else if (workload.isFitnessRead()) {
                    localFitnessRead = connection.prepareStatement(fitnessReadSql(workload, table));
                } else if (workload.isMixedReaderWriter()) {
                    localRead = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                    localUpdate = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + 1 where id = ?");
                } else if (workload.isRealisticTransaction()) {
                    localRealisticTransaction = new RealisticTransactionClient(
                            connection, table, workload, clientIndex, transactionsPerClient,
                            spec.clients(), rowCount);
                } else if (workload.isIndexedUpdate()) {
                    localUpdate = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + 1 where id = ?");
                } else if (workload.isInsert()) {
                    localInsertRow = connection.prepareStatement(
                            "insert into " + table
                                    + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)");
                } else if (workload.isDeleteReinsert()) {
                    localDeleteRow = connection.prepareStatement("delete from " + table + " where id = ?");
                    localInsertRow = connection.prepareStatement(
                            "insert into " + table
                                    + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)");
                }
                this.read = localRead;
                this.rangeRead = localRangeRead;
                this.values = localValues;
                this.fitnessRead = localFitnessRead;
                this.update = localUpdate;
                this.deleteRow = localDeleteRow;
                this.insertRow = localInsertRow;
                this.realisticTransaction = localRealisticTransaction;
            } catch (SQLException failure) {
                if (localRealisticTransaction != null) {
                    try {
                        localRealisticTransaction.close();
                    } catch (SQLException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                closeStatement(localInsertRow, failure);
                closeStatement(localDeleteRow, failure);
                closeStatement(localUpdate, failure);
                closeStatement(localFitnessRead, failure);
                closeStatement(localValues, failure);
                closeStatement(localRangeRead, failure);
                closeStatement(localRead, failure);
                throw failure;
            }
        }

        private ClientRun runTransactions(int transactions, int operationsPerTransaction) throws SQLException {
            long fingerprint = 1L;
            long retryableRollbacks = 0L;
            for (int transaction = 0; transaction < transactions; transaction++) {
                int attempts = 0;
                while (true) {
                    long transactionFingerprint = 1L;
                    try {
                        if (workload.isRealisticTransaction()) {
                            transactionFingerprint = Objects.requireNonNull(
                                    realisticTransaction, "realistic transaction client")
                                    .execute(transaction);
                            fingerprint = mix(fingerprint, transactionFingerprint);
                            break;
                        }
                        if (workload.isMixedReaderWriter()) {
                            transactionFingerprint = executeMixedTransaction(transaction);
                            connection.commit();
                            fingerprint = mix(fingerprint, transactionFingerprint);
                            break;
                        }
                        if (workload.isInsert()) {
                            transactionFingerprint = executeInsertTransaction(
                                    transaction, operationsPerTransaction);
                        }
                        for (int operation = 0;
                                !workload.isInsert() && operation < operationsPerTransaction;
                                operation++) {
                            int operationIndex = transaction * operationsPerTransaction + operation;
                            if (workload.isValues()) {
                                try (ResultSet resultSet = values.executeQuery()) {
                                    if (!resultSet.next() || resultSet.getInt(1) != 1 || resultSet.next()) {
                                        throw new SQLException("VALUES benchmark returned unexpected result");
                                    }
                                }
                                transactionFingerprint = mix(transactionFingerprint, 1);
                            } else if (workload.isPrimaryKeyRead()) {
                                int id = readIds[operationIndex];
                                int expectedReadQuantity = expectedReadQuantities[operationIndex];
                                read.setInt(1, id);
                                try (ResultSet resultSet = read.executeQuery()) {
                                    if (!resultSet.next()) {
                                        throw new SQLException("Concurrent read row missing: id=" + id);
                                    }
                                    int quantity = resultSet.getInt(1);
                                    if (quantity != expectedReadQuantity) {
                                        throw new SQLException("Concurrent read value changed: id=" + id
                                                + ", expected=" + expectedReadQuantity + ", actual=" + quantity);
                                    }
                                    transactionFingerprint = mix(transactionFingerprint, quantity);
                                    if (resultSet.next()) {
                                        throw new SQLException("Concurrent read returned duplicate id=" + id);
                                    }
                                }
                                transactionFingerprint = mix(transactionFingerprint, id);
                            } else if (workload.isRangeScan()) {
                                rangeRead.setInt(1, rangeStart);
                                rangeRead.setInt(2, rangeEndExclusive);
                                long rangeFingerprint = 1L;
                                int rows = 0;
                                try (ResultSet resultSet = rangeRead.executeQuery()) {
                                    while (resultSet.next()) {
                                        int id = resultSet.getInt(1);
                                        if (workload.isIndexOnlyRangeScan()) {
                                            rangeFingerprint = mix(rangeFingerprint, id);
                                        } else {
                                            int quantity = resultSet.getInt(2);
                                            rangeFingerprint = mix(mix(rangeFingerprint, id), quantity);
                                        }
                                        rows++;
                                    }
                                }
                                if (rows != expectedRangeRows
                                        || rangeFingerprint != expectedRangeFingerprint) {
                                    throw new SQLException("Concurrent range scan changed: expectedRows="
                                            + expectedRangeRows + ", actualRows=" + rows
                                            + ", expectedFingerprint=" + expectedRangeFingerprint
                                            + ", actualFingerprint=" + rangeFingerprint);
                                }
                                transactionFingerprint = mix(transactionFingerprint, rows);
                                transactionFingerprint = mix(transactionFingerprint, rangeFingerprint);
                            } else if (workload.isFitnessRead()) {
                                transactionFingerprint = mix(
                                        transactionFingerprint,
                                        executeFitnessRead(fitnessRead, workload, expectedFitnessRows));
                            } else if (workload.isDeleteReinsert()) {
                                DeleteRow row = deleteRows[operation % deleteRows.length];
                                deleteRow.setInt(1, row.id());
                                if (deleteRow.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Delete/reinsert delete did not affect one row: id=" + row.id());
                                }
                                insertRow.setInt(1, row.id());
                                insertRow.setInt(2, row.category());
                                insertRow.setInt(3, row.bucket());
                                insertRow.setInt(4, row.quantity());
                                insertRow.setString(5, row.payload());
                                if (insertRow.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Delete/reinsert insert did not affect one row: id=" + row.id());
                                }
                                transactionFingerprint = mix(transactionFingerprint, row.id());
                            } else if (workload.isIndexedUpdate()) {
                                update.setInt(1, updateId);
                                if (update.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Concurrent update did not affect one row: id=" + updateId);
                                }
                                transactionFingerprint = mix(transactionFingerprint, updateId);
                            }
                        }
                        connection.commit();
                        fingerprint = mix(fingerprint, transactionFingerprint);
                        break;
                    } catch (SQLException failure) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackFailure) {
                            failure.addSuppressed(rollbackFailure);
                            throw failure;
                        }
                        if (!isRetryableConflict(target, failure) || ++attempts >= 1000) {
                            throw failure;
                        }
                        retryableRollbacks++;
                    }
                }
            }
            return new ClientRun(fingerprint, retryableRollbacks);
        }

        private long executeMixedTransaction(int transaction) throws SQLException {
            if (workload == Workload.MIXED_80R20W) {
                if (transaction % 5 == 4) {
                    update.setInt(1, updateId);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException(
                                "F11 80R20W update did not affect one row: id=" + updateId);
                    }
                    return mix(1L, updateId);
                }
                int readId = rowCount - clientIndex;
                int expected = mixedBaselineQuantities[readId];
                read.setInt(1, readId);
                try (ResultSet resultSet = read.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("F11 80R20W read row missing: id=" + readId);
                    }
                    int actual = resultSet.getInt(1);
                    if (actual != expected || resultSet.next()) {
                        throw new SQLException(
                                "F11 80R20W stable read drift: id=" + readId
                                        + ", expected=" + expected + ", actual=" + actual);
                    }
                    return mix(mix(1L, readId), actual);
                }
            }

            if (workload != Workload.MIXED_50R50W_HOT) {
                throw new IllegalStateException("Not an F11 mixed workload: " + workload);
            }
            if ((transaction & 1) != 0) {
                update.setInt(1, updateId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException(
                            "F11 50R50W hot update did not affect one row: id=" + updateId);
                }
                return mix(1L, updateId);
            }

            int hotKeyCount = 4;
            int readId = 1 + Math.floorMod(clientIndex + transaction / 2, hotKeyCount);
            int baseline = mixedBaselineQuantities[readId];
            int writersPerKey = clientCount / hotKeyCount;
            int writesPerClient = transactionsPerClient / 2;
            int maximum = Math.addExact(baseline, Math.multiplyExact(writersPerKey, writesPerClient));
            read.setInt(1, readId);
            try (ResultSet resultSet = read.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("F11 50R50W hot read row missing: id=" + readId);
                }
                int actual = resultSet.getInt(1);
                if (actual < baseline || actual > maximum || resultSet.next()) {
                    throw new SQLException(
                            "F11 50R50W hot read outside legal committed bounds: id=" + readId
                                    + ", baseline=" + baseline + ", maximum=" + maximum
                                    + ", actual=" + actual);
                }
                // Do not mix the timing-dependent observed quantity into the semantic fingerprint.
                return mix(1L, readId);
            }
        }

        private long executeInsertTransaction(int transaction, int operationsPerTransaction)
                throws SQLException {
            long fingerprint = 1L;
            if (operationsPerTransaction == 1) {
                int id = insertBaseId + transaction;
                bindFitnessInsert(insertRow, id, payloadSize);
                if (insertRow.executeUpdate() != 1) {
                    throw new SQLException("INSERT-1 did not affect exactly one row: id=" + id);
                }
                return mix(fingerprint, id);
            }

            insertRow.clearBatch();
            for (int operation = 0; operation < operationsPerTransaction; operation++) {
                int id = insertBaseId + transaction * operationsPerTransaction + operation;
                bindFitnessInsert(insertRow, id, payloadSize);
                insertRow.addBatch();
                fingerprint = mix(fingerprint, id);
            }
            int[] counts = insertRow.executeBatch();
            if (counts.length != operationsPerTransaction) {
                throw new SQLException(
                        "INSERT batch count length drift: expected=" + operationsPerTransaction
                                + ", actual=" + counts.length);
            }
            for (int count : counts) {
                if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException("INSERT batch returned unexpected update count: " + count);
                }
            }
            return fingerprint;
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            try {
                if (!connection.isClosed() && !connection.getAutoCommit()) {
                    connection.rollback();
                }
            } catch (SQLException rollbackFailure) {
                failure = rollbackFailure;
            }
            if (realisticTransaction != null) {
                try {
                    realisticTransaction.close();
                } catch (SQLException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            try {
                if (read != null) {
                    read.close();
                }
            } catch (SQLException closeFailure) {
                failure = closeFailure;
            }
            try {
                if (rangeRead != null) {
                    rangeRead.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (values != null) {
                    values.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (fitnessRead != null) {
                    fitnessRead.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (update != null) {
                    update.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (deleteRow != null) {
                    deleteRow.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (insertRow != null) {
                    insertRow.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static String joinDimensionTableName(String table) {
        return table + "_JOIN_DIM";
    }

    private static String highCardGroupTableName(String table) {
        return table + "_GROUP";
    }

    private static void prepareJoinDimensionFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        String dimension = joinDimensionTableName(table);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table " + dimension + " (id int not null primary key)" + createTableSuffix);
        }
        connection.commit();
        int rows = Math.min(1000, rowCount);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + dimension + " (id) values (?)")) {
            for (int id = 1; id <= rows; id++) {
                insert.setInt(1, id);
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
    }

    private static String joinFanoutParentTableName(String table) {
        return table + "_JOIN_PARENT";
    }

    private static String joinFanoutChildTableName(String table) {
        return table + "_JOIN_CHILD";
    }

    private static String multiJoinCustomerTableName(String table) {
        return table + "_MJ_CUSTOMER";
    }

    private static String multiJoinOrderTableName(String table) {
        return table + "_MJ_ORDER";
    }

    private static String multiJoinLineTableName(String table) {
        return table + "_MJ_LINE";
    }

    private static String multiJoinItemTableName(String table) {
        return table + "_MJ_ITEM";
    }

    private static int joinParentRows(int rowCount) {
        return Math.min(1000, Math.max(1, rowCount / 10));
    }

    private static int joinSelectiveParents(int rowCount) {
        return Math.min(100, joinParentRows(rowCount));
    }

    private static int joinBucketParents(int rowCount, int bucket) {
        int parents = joinParentRows(rowCount);
        if (parents < bucket) {
            return 0;
        }
        return ((parents - bucket) / 10) + 1;
    }

    private static void prepareJoinFanoutFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        String parent = joinFanoutParentTableName(table);
        String child = joinFanoutChildTableName(table);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table " + parent + " (id int not null primary key)" + createTableSuffix);
            statement.executeUpdate(
                    "create table " + child
                            + " (id int not null primary key, parent_id int not null)" + createTableSuffix);
            statement.executeUpdate("create index " + child + "_P_IDX on " + child + " (parent_id)");
        }
        connection.commit();

        int parents = joinParentRows(rowCount);
        try (PreparedStatement insertParent = connection.prepareStatement(
                        "insert into " + parent + " (id) values (?)");
                PreparedStatement insertChild = connection.prepareStatement(
                        "insert into " + child + " (id, parent_id) values (?, ?)")) {
            int pending = 0;
            for (int parentId = 1; parentId <= parents; parentId++) {
                insertParent.setInt(1, parentId);
                insertParent.addBatch();
                for (int childNo = 1; childNo <= 10; childNo++) {
                    insertChild.setInt(1, (parentId - 1) * 10 + childNo);
                    insertChild.setInt(2, parentId);
                    insertChild.addBatch();
                    pending++;
                }
                if (pending >= commitBatchSize) {
                    insertParent.executeBatch();
                    insertChild.executeBatch();
                    connection.commit();
                    pending = 0;
                }
            }
            if (pending != 0) {
                insertParent.executeBatch();
                insertChild.executeBatch();
                connection.commit();
            }
        }
    }

    private static void prepareMultiJoinFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        String customer = multiJoinCustomerTableName(table);
        String order = multiJoinOrderTableName(table);
        String line = multiJoinLineTableName(table);
        String item = multiJoinItemTableName(table);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table " + customer
                            + " (id int not null primary key, bucket int not null)" + createTableSuffix);
            statement.executeUpdate("create index " + customer + "_B_IDX on " + customer + " (bucket)");
            statement.executeUpdate(
                    "create table " + order
                            + " (id int not null primary key, customer_id int not null)" + createTableSuffix);
            statement.executeUpdate("create index " + order + "_C_IDX on " + order + " (customer_id)");
            statement.executeUpdate(
                    "create table " + line
                            + " (id int not null primary key, order_id int not null,"
                            + " line_no int not null, item_id int not null)" + createTableSuffix);
            statement.executeUpdate("create index " + line + "_O_IDX on " + line + " (order_id)");
            statement.executeUpdate(
                    "create table " + item + " (id int not null primary key)" + createTableSuffix);
        }
        connection.commit();

        int customers = joinParentRows(rowCount);
        int items = Math.min(128, Math.max(1, customers));
        try (PreparedStatement insertItem = connection.prepareStatement(
                "insert into " + item + " (id) values (?)")) {
            for (int itemId = 1; itemId <= items; itemId++) {
                insertItem.setInt(1, itemId);
                insertItem.addBatch();
            }
            insertItem.executeBatch();
            connection.commit();
        }

        try (PreparedStatement insertCustomer = connection.prepareStatement(
                        "insert into " + customer + " (id, bucket) values (?, ?)");
                PreparedStatement insertOrder = connection.prepareStatement(
                        "insert into " + order + " (id, customer_id) values (?, ?)");
                PreparedStatement insertLine = connection.prepareStatement(
                        "insert into " + line + " (id, order_id, line_no, item_id) values (?, ?, ?, ?)")) {
            int pending = 0;
            for (int customerId = 1; customerId <= customers; customerId++) {
                insertCustomer.setInt(1, customerId);
                insertCustomer.setInt(2, customerId % 10);
                insertCustomer.addBatch();
                for (int orderNo = 1; orderNo <= 4; orderNo++) {
                    int orderId = (customerId - 1) * 4 + orderNo;
                    insertOrder.setInt(1, orderId);
                    insertOrder.setInt(2, customerId);
                    insertOrder.addBatch();
                    for (int lineNo = 1; lineNo <= 3; lineNo++) {
                        int lineId = (orderId - 1) * 3 + lineNo;
                        insertLine.setInt(1, lineId);
                        insertLine.setInt(2, orderId);
                        insertLine.setInt(3, lineNo);
                        insertLine.setInt(4, 1 + ((lineId - 1) % items));
                        insertLine.addBatch();
                        pending++;
                    }
                }
                if (pending >= commitBatchSize) {
                    insertCustomer.executeBatch();
                    insertOrder.executeBatch();
                    insertLine.executeBatch();
                    connection.commit();
                    pending = 0;
                }
            }
            if (pending != 0) {
                insertCustomer.executeBatch();
                insertOrder.executeBatch();
                insertLine.executeBatch();
                connection.commit();
            }
        }
    }

    private static void prepareHighCardGroupFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        String groupTable = highCardGroupTableName(table);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table " + groupTable
                            + " (id int not null primary key, group_key int not null, quantity int not null)"
                            + createTableSuffix);
        }
        connection.commit();
        int[] quantities = fixtureQuantities(rowCount);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + groupTable + " (id, group_key, quantity) values (?, ?, ?)")) {
            for (int id = 1; id <= rowCount; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % Math.min(1000, rowCount));
                insert.setInt(3, quantities[id]);
                insert.addBatch();
                if (id % commitBatchSize == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            if (rowCount % commitBatchSize != 0) {
                insert.executeBatch();
                connection.commit();
            }
        }
    }


    private static final int BANK_BRANCHES = 10;
    private static final int BANK_TELLERS_PER_BRANCH = 10;
    private static final int ORDER_CUSTOMERS = 1000;
    private static final int ORDER_WAREHOUSES = 4;
    private static final int ORDER_DELIVERY_BASE = 100_000;
    private static final int ORDER_NEW_BASE = 1_000_000;
    private static final int ORDER_ROLLBACK_BASE = 2_000_000;

    private static String bankBranchTableName(String table) {
        return table + "_BANK_BRANCH";
    }

    private static String bankTellerTableName(String table) {
        return table + "_BANK_TELLER";
    }

    private static String bankHistoryTableName(String table) {
        return table + "_BANK_HISTORY";
    }

    private static String orderWarehouseTableName(String table) {
        return table + "_OE_WAREHOUSE";
    }

    private static String orderCustomerTableName(String table) {
        return table + "_OE_CUSTOMER";
    }

    private static String orderTableName(String table) {
        return table + "_OE_ORDER";
    }

    private static String orderLineTableName(String table) {
        return table + "_OE_LINE";
    }

    private static void prepareBankFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        if (rowCount < 100) {
            throw new SQLException("Bank transaction fitness requires at least 100 account rows");
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + bankBranchTableName(table)
                    + " (id int not null primary key, balance bigint not null)" + createTableSuffix);
            statement.executeUpdate("create table " + bankTellerTableName(table)
                    + " (id int not null primary key, branch_id int not null, balance bigint not null)"
                    + createTableSuffix);
            statement.executeUpdate("create table " + bankHistoryTableName(table)
                    + " (tx_id int not null primary key, account_id int not null, teller_id int not null, "
                    + "branch_id int not null, amount int not null)" + createTableSuffix);
        }
        connection.commit();
        try (PreparedStatement branch = connection.prepareStatement(
                        "insert into " + bankBranchTableName(table) + " (id, balance) values (?, 0)");
                PreparedStatement teller = connection.prepareStatement(
                        "insert into " + bankTellerTableName(table)
                                + " (id, branch_id, balance) values (?, ?, 0)")) {
            for (int branchId = 1; branchId <= BANK_BRANCHES; branchId++) {
                branch.setInt(1, branchId);
                branch.addBatch();
            }
            branch.executeBatch();
            for (int tellerId = 1; tellerId <= BANK_BRANCHES * BANK_TELLERS_PER_BRANCH; tellerId++) {
                teller.setInt(1, tellerId);
                teller.setInt(2, 1 + (tellerId - 1) / BANK_TELLERS_PER_BRANCH);
                teller.addBatch();
                if (tellerId % commitBatchSize == 0) {
                    teller.executeBatch();
                    connection.commit();
                }
            }
            if ((BANK_BRANCHES * BANK_TELLERS_PER_BRANCH) % commitBatchSize != 0) {
                teller.executeBatch();
            }
            connection.commit();
        }
    }

    private static void prepareOrderEntryFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int clients,
            int transactionsPerClient,
            int commitBatchSize) throws SQLException {
        if (rowCount < 1000) {
            throw new SQLException("Order Entry fitness requires at least 1000 stock rows");
        }
        int customerCount = Math.min(ORDER_CUSTOMERS, rowCount);
        if (customerCount < clients) {
            throw new SQLException("Order Entry customer fixture is smaller than client count");
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + orderWarehouseTableName(table)
                    + " (id int not null primary key, balance bigint not null)" + createTableSuffix);
            statement.executeUpdate("create table " + orderCustomerTableName(table)
                    + " (id int not null primary key, balance bigint not null, last_order int not null)"
                    + createTableSuffix);
            statement.executeUpdate("create table " + orderTableName(table)
                    + " (id int not null primary key, customer_id int not null, status int not null, amount int not null)"
                    + createTableSuffix);
            statement.executeUpdate("create table " + orderLineTableName(table)
                    + " (order_id int not null, line_no int not null, stock_id int not null, quantity int not null, "
                    + "amount int not null, primary key (order_id, line_no))" + createTableSuffix);
        }
        connection.commit();
        try (PreparedStatement warehouse = connection.prepareStatement(
                        "insert into " + orderWarehouseTableName(table) + " (id, balance) values (?, 0)");
                PreparedStatement customer = connection.prepareStatement(
                        "insert into " + orderCustomerTableName(table)
                                + " (id, balance, last_order) values (?, 0, 0)");
                PreparedStatement seedOrder = connection.prepareStatement(
                        "insert into " + orderTableName(table)
                                + " (id, customer_id, status, amount) values (?, ?, 0, 50)")) {
            for (int warehouseId = 1; warehouseId <= ORDER_WAREHOUSES; warehouseId++) {
                warehouse.setInt(1, warehouseId);
                warehouse.addBatch();
            }
            warehouse.executeBatch();
            for (int customerId = 1; customerId <= customerCount; customerId++) {
                customer.setInt(1, customerId);
                customer.addBatch();
                if (customerId % commitBatchSize == 0) {
                    customer.executeBatch();
                    connection.commit();
                }
            }
            if (customerCount % commitBatchSize != 0) {
                customer.executeBatch();
            }
            connection.commit();
            for (int client = 0; client < clients; client++) {
                for (int transaction = 0; transaction < transactionsPerClient; transaction++) {
                    if (orderEntryType(transaction) != OrderEntryType.DELIVERY) {
                        continue;
                    }
                    int globalOrdinal = client * transactionsPerClient + transaction;
                    seedOrder.setInt(1, ORDER_DELIVERY_BASE + globalOrdinal);
                    seedOrder.setInt(2, orderCustomerId(client, transaction, clients, customerCount));
                    seedOrder.addBatch();
                }
            }
            seedOrder.executeBatch();
            connection.commit();
        }
    }

    private static int bankAccountId(int globalOrdinal, int rowCount) {
        return 1 + Math.floorMod(globalOrdinal * 7919, rowCount);
    }

    private static int bankBranchId(int accountId, int rowCount) {
        return 1 + Math.min(BANK_BRANCHES - 1,
                (int) (((long) (accountId - 1) * BANK_BRANCHES) / rowCount));
    }

    private static int bankTellerId(int branchId, int globalOrdinal) {
        return (branchId - 1) * BANK_TELLERS_PER_BRANCH
                + 1 + Math.floorMod(globalOrdinal * 7, BANK_TELLERS_PER_BRANCH);
    }

    private static int bankDelta(int globalOrdinal) {
        int delta = Math.floorMod(globalOrdinal * 37 + 17, 199) - 99;
        return delta == 0 ? 1 : delta;
    }

    private static int orderCustomerId(
            int clientIndex,
            int transaction,
            int clients,
            int customerCount) {
        int logicalTransaction = orderEntryType(transaction) == OrderEntryType.ORDER_STATUS
                ? (transaction / 20) * 20
                : transaction;
        int partition = customerCount / clients;
        if (partition == 0) {
            return 1 + Math.floorMod(clientIndex + logicalTransaction, customerCount);
        }
        int base = clientIndex * partition;
        int limit = clientIndex == clients - 1 ? customerCount - base : partition;
        return 1 + base + Math.floorMod(logicalTransaction * 13 + 3, limit);
    }

    private static int orderWarehouseId(int clientIndex) {
        return 1 + Math.floorMod(clientIndex, ORDER_WAREHOUSES);
    }

    private static int orderStockId(int globalOrdinal, int line, int rowCount) {
        return 1 + Math.floorMod(globalOrdinal * 3 + line * 97, rowCount);
    }

    private static int orderPaymentAmount(int globalOrdinal) {
        return 5 + Math.floorMod(globalOrdinal * 11, 20);
    }

    private static OrderEntryType orderEntryType(int transaction) {
        int slot = Math.floorMod(transaction, 20);
        if (slot < 8) {
            return OrderEntryType.NEW_ORDER;
        }
        if (slot < 16) {
            return OrderEntryType.PAYMENT;
        }
        return switch (slot) {
            case 16 -> OrderEntryType.ORDER_STATUS;
            case 17 -> OrderEntryType.DELIVERY;
            case 18 -> OrderEntryType.STOCK_LEVEL;
            case 19 -> OrderEntryType.NEW_ORDER_ROLLBACK;
            default -> throw new IllegalStateException("Unexpected Order Entry mix slot: " + slot);
        };
    }

    private enum OrderEntryType {
        NEW_ORDER,
        PAYMENT,
        ORDER_STATUS,
        DELIVERY,
        STOCK_LEVEL,
        NEW_ORDER_ROLLBACK
    }

    private static void bindFitnessInsert(PreparedStatement statement, int id, int payloadSize)
            throws SQLException {
        statement.setInt(1, id);
        statement.setInt(2, id % 17);
        statement.setInt(3, id % 11);
        statement.setInt(4, fitnessInsertQuantity(id));
        statement.setString(5, fitnessPayload(id, payloadSize));
    }

    private static int fitnessInsertQuantity(int id) {
        return Math.floorMod(id * 31, 10_000);
    }

    private static String fitnessPayload(int id, int length) {
        String prefix = "insert-" + id + '-';
        StringBuilder value = new StringBuilder(length);
        while (value.length() < length) {
            value.append(prefix);
        }
        return value.substring(0, length);
    }

    private static long verifyInsertedState(
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

    private static DelosSqlSemanticOracle.Result authoritativeInsertState(
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

    private static final int FITNESS_CATEGORY = 7;

    private static String fitnessReadSql(Workload workload, String table) {
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

    private static void bindFitnessRead(PreparedStatement statement, Workload workload) throws SQLException {
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

    private static int expectedFitnessRows(Workload workload, int rowCount) {
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

    private static long executeFitnessRead(
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

    private static int[] deleteReinsertIds(Spec spec, int rowCount) {
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

    private static DeleteRow readDeleteRow(Connection connection, String table, int id) throws SQLException {
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

    private static int[] mutationIds(Spec spec, int rowCount) {
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

    private static int[] readOperationIds(Spec spec, int rowCount, int client, int operations) {
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

    private static int[] fixtureQuantities(int rowCount) {
        int[] quantities = new int[rowCount + 1];
        // Matches DelosJdbcBenchmarkScenario.prepare(); avoids touching database pages before timed reads.
        Random random = new Random(SEED);
        for (int id = 1; id <= rowCount; id++) {
            quantities[id] = random.nextInt(10_000);
        }
        return quantities;
    }

    private static long rangeIdFingerprint(int startInclusive, int endExclusive) {
        long fingerprint = 1L;
        for (int id = startInclusive; id < endExclusive; id++) {
            fingerprint = mix(fingerprint, id);
        }
        return fingerprint;
    }

    private static long rangeFingerprint(
            int[] fixtureQuantities, int startInclusive, int endExclusive) {
        long fingerprint = 1L;
        for (int id = startInclusive; id < endExclusive; id++) {
            fingerprint = mix(mix(fingerprint, id), fixtureQuantities[id]);
        }
        return fingerprint;
    }

    private static int quantity(Connection connection, String table, int id) throws SQLException {
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

    private static List<Spec> specsForRun(Options options) {
        List<Spec> specs = new ArrayList<>();
        for (int clients : options.clientValues()) {
            for (Workload workload : options.workloadValues()) {
                if (workload.fixedOperationsPerTransaction() >= 0) {
                    specs.add(new Spec(workload, clients, workload.fixedOperationsPerTransaction()));
                } else {
                    for (int width : options.widthValues()) {
                        specs.add(new Spec(workload, clients, width));
                    }
                }
            }
        }
        int phase = (options.run() - 1) & 3;
        if (phase == 1 || phase == 2) {
            Collections.reverse(specs);
        }
        return List.copyOf(specs);
    }

    private static int specsPerRun(Options options) {
        int perClient = 0;
        for (Workload workload : options.workloadValues()) {
            perClient += workload.fixedOperationsPerTransaction() >= 0 ? 1 : options.widthValues().size();
        }
        return Math.multiplyExact(options.clientValues().size(), perClient);
    }

    private static List<Integer> ordered(List<Integer> values, int run) {
        List<Integer> ordered = new ArrayList<>(values);
        if (((run - 1) & 1) != 0) {
            Collections.reverse(ordered);
        }
        return ordered;
    }

    private static Long sameSemantic(Long expected, long actual, Spec spec, String stage) {
        if (expected != null && expected.longValue() != actual) {
            throw new IllegalStateException("Concurrent semantic drift for " + spec + " during " + stage
                    + ": expected=" + expected + ", actual=" + actual);
        }
        return actual;
    }

    private static void writeWorkerCsv(Options options, List<Measurement> values) throws IOException {
        Path output = options.reportDirectory().resolve(options.target().id() + "-run-" + options.run() + ".csv");
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Measurement value : values) {
            out.append(value.csv()).append('\n');
        }
        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeWorkerOracleEvidence(
            Options options,
            List<OracleEvidence> values) throws IOException {
        int expected = options.rowCounts().size() * specsPerRun(options);
        if (values.size() != expected) {
            throw new IllegalStateException(
                    "Phase 0A SQL oracle evidence count mismatch for worker "
                            + options.target().id() + " run=" + options.run()
                            + ": expected=" + expected + ", actual=" + values.size());
        }
        Path output = options.reportDirectory().resolve(
                "sql-semantic-oracle-" + options.target().id() + "-run-" + options.run() + ".csv");
        StringBuilder out = new StringBuilder(ORACLE_CSV_HEADER).append('\n');
        for (OracleEvidence value : values) {
            out.append(value.csv()).append('\n');
        }
        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
    }

    private static List<OracleEvidence> loadOracleEvidence(Options options) throws IOException {
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

    private static void validateOracleEvidence(
            Options options,
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

    private static void writeOracleEvidence(
            Options options,
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

    private static void writeWorkerDrdaProtocolEvidence(
            Options options,
            List<DrdaProtocolEvidence> values) throws IOException {
        int expected = options.rowCounts().size() * specsPerRun(options);
        if (values.size() != expected) {
            throw new IllegalStateException(
                    "DRDA protocol evidence count mismatch for worker "
                            + options.target().id() + " run=" + options.run()
                            + ": expected=" + expected + ", actual=" + values.size());
        }
        Path output = options.reportDirectory().resolve(
                "drda-protocol-evidence-" + options.target().id() + "-run-" + options.run() + ".csv");
        StringBuilder out = new StringBuilder(DRDA_PROTOCOL_CSV_HEADER).append('\n');
        for (DrdaProtocolEvidence value : values) {
            out.append(value.csv()).append('\n');
        }
        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
    }

    private static List<DrdaProtocolEvidence> loadDrdaProtocolEvidence(Options options) throws IOException {
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

    private static List<DrdaServerPhaseEvidence> loadDrdaServerPhaseEvidence(Options options)
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
                    if (continueQueries > 0L) {
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
                            parseServerEvidenceLong(values, "openSendNanos"),
                            parseServerEvidenceLong(values, "openTotalNanos"),
                            parseServerEvidenceLong(values, "continueParseNanos"),
                            parseServerEvidenceLong(values, "continueMetadataNanos"),
                            parseServerEvidenceLong(values, "continueQueryDataNanos"),
                            parseServerEvidenceLong(values, "continueSendNanos"),
                            parseServerEvidenceLong(values, "continueTotalNanos")));
                }
            }
        }
        return evidence;
    }

    private static Map<String, String> parseServerPhaseEvidenceLine(String line) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : line.split("\\|")) {
            int equals = token.indexOf('=');
            if (equals > 0) {
                values.put(token.substring(0, equals), token.substring(equals + 1));
            }
        }
        return values;
    }

    private static long parseServerEvidenceLong(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("Missing DRDA server phase field: " + name + " in " + values);
        }
        return Long.parseLong(value);
    }

    private static void validateDrdaServerPhaseEvidence(
            Options options, List<DrdaServerPhaseEvidence> evidence) {
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

    private static void writeDrdaServerPhaseEvidence(
            Options options, List<DrdaServerPhaseEvidence> evidence) throws IOException {
        List<DrdaServerPhaseEvidence> sorted = new ArrayList<>(evidence);
        sorted.sort(Comparator.comparing(DrdaServerPhaseEvidence::workload)
                .thenComparing(DrdaServerPhaseEvidence::target)
                .thenComparingInt(DrdaServerPhaseEvidence::run));
        String header = "target,workload,run,connection,openQueries,continueQueries,resultColumns,"
                + "openRows,continueRows,openParseNanos,openExecuteNanos,openMetadataNanos,"
                + "openQueryDataNanos,openSendNanos,openResidualNanos,openTotalNanos,"
                + "continueParseNanos,continueMetadataNanos,continueQueryDataNanos,continueSendNanos,"
                + "continueResidualNanos,continueTotalNanos,averageOpenTotalMicros,"
                + "averageOpenExecuteMicros,averageOpenQueryDataMicros,averageOpenSendMicros,"
                + "openExecuteShare,openQueryDataShare,openSendShare,averageContinueTotalMicros,"
                + "averageContinueQueryDataMicros,averageContinueSendMicros,continueQueryDataShare,"
                + "continueSendShare";
        StringBuilder csv = new StringBuilder(header).append('\n');
        StringBuilder text = new StringBuilder()
                .append("DelosDB Phase-1 DRDA server phase evidence\n")
                .append("=========================================\n\n")
                .append("Authority: Delos Network Server command processing for measured query ordinals 21-40.\n")
                .append("Granularity: command-sized phases only; no per-row or per-column timers.\n")
                .append("Evidence rows: ").append(sorted.size()).append("\n\n");
        for (DrdaServerPhaseEvidence value : sorted) {
            csv.append(value.toCsv()).append('\n');
            text.append(value.target()).append(' ')
                    .append(value.workload()).append(" run=").append(value.run())
                    .append(" openTotalUs=").append(format(value.averageOpenTotalMicros()))
                    .append(" executeUs=").append(format(value.averageOpenExecuteMicros()))
                    .append(" qrydtaUs=").append(format(value.averageOpenQueryDataMicros()))
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
    }

    private static void validateDrdaProtocolEvidence(
            Options options,
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

    private static void writeDrdaProtocolEvidence(
            Options options,
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

    private static List<Row> loadRows(Options options) throws IOException {
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

    private static void validateRows(Options options, List<Row> rows) {
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

    private static void writeMergedCsv(Options options, List<Row> rows) throws IOException {
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Row row : rows) {
            out.append(row.csv()).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-results.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeRatioCsv(Options options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(options, rows);
        StringBuilder out;
        if (options.targetValues().equals(EMBEDDED_REFERENCE_CANARY_TARGETS)
                || options.targetValues().equals(SERVER_REFERENCE_CANARY_TARGETS)
                || options.targetValues().equals(HOST_RECOVERY_DIAGNOSTIC_TARGETS)
                || options.targetValues().equals(DRDA_PROTOCOL_EVIDENCE_TARGETS)
                || options.targetValues().equals(DRDA_SERVER_PHASE_EVIDENCE_TARGETS)
                || options.targetValues().equals(CURRENT_BASELINE_EMBEDDED_TARGETS)
                || options.targetValues().equals(CURRENT_BASELINE_SERVER_TARGETS)) {
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

    private static void writeScalingCsv(Options options, List<Row> rows) throws IOException {
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

    private static void writeDispersionCsv(Options options, List<Row> rows) throws IOException {
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

    private static void writeRangeScanCsv(Options options, List<Row> rows) throws IOException {
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

    private static void writeCapabilityCsv(Options options) throws IOException {
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

    private static void writeSummary(Options options, List<Row> rows) throws IOException {
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
                .append("Semantic verification/restoration outside timed interval: true\n")
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

    private static Map<ShapeKey, EnumMap<Target, Double>> medianThroughput(Options options, List<Row> rows) {
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

    private static double require(EnumMap<Target, Double> values, Target target, Object key) {
        Double value = values.get(target);
        if (value == null) {
            throw new IllegalStateException("Missing " + target.id() + " result for " + key);
        }
        return value;
    }

    private static Distribution distribution(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double median = medianSorted(sorted);
        double q1 = percentile(sorted, 0.25);
        double q3 = percentile(sorted, 0.75);
        List<Double> deviations = sorted.stream().map(value -> Math.abs(value - median)).sorted().toList();
        return new Distribution(sorted.size(), median, q1, q3, q3 - q1,
                medianSorted(deviations), sorted.getFirst(), sorted.getLast());
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        return medianSorted(sorted);
    }

    private static double medianSorted(List<Double> sorted) {
        int size = sorted.size();
        return (size & 1) == 0
                ? (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0
                : sorted.get(size / 2);
    }

    private static double percentile(List<Double> sorted, double percentile) {
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

    private static Throwable shutdownDerby(Path database, Throwable primary) {
        try {
            DriverManager.getConnection("jdbc:derby:" + database.toAbsolutePath() + ";shutdown=true");
            return preserve(primary, new IllegalStateException(
                    "Embedded Derby shutdown completed without SQLState 08006: " + database));
        } catch (SQLException expected) {
            return "08006".equals(expected.getSQLState()) ? primary : preserve(primary, expected);
        } catch (Throwable failure) {
            return preserve(primary, failure);
        }
    }

    private static Throwable preserve(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected concurrency benchmark failure", failure);
    }

    private static boolean isRetryableConflict(Target target, SQLException failure) {
        if (target == Target.SQLITE) {
            int primaryResultCode = failure.getErrorCode() & 0xff;
            if (primaryResultCode == 5 || primaryResultCode == 6) {
                return true;
            }
        }
        String sqlState = failure.getSQLState();
        return sqlState != null && sqlState.startsWith("40");
    }

    private static void closeStatement(PreparedStatement statement, Throwable primary) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Collections.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static String csvSafe(String value) {
        return value == null ? "" : value.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static long mix(long fingerprint, long value) {
        return 31L * fingerprint + value;
    }

    private static String rangeCoveringIndexName(String table) {
        return table + "_PK_COVER_IDX";
    }

    private static List<Integer> integerList(String raw) {
        List<Integer> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            values.add(Integer.parseInt(token.trim()));
        }
        return List.copyOf(values);
    }

    private enum Workload {
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
            return isPrimaryKeyRead() || isRangeScan() || isMixedReaderWriter();
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

        boolean isIndexedUpdate() {
            return this == DISJOINT_INDEXED_UPDATE
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

    private enum Target {
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

        String containerImage(Options options) {
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

        String jdbcUrl(Path database, Options options) {
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

    private record DrdaServerPhaseEvidence(
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
            long openSendNanos,
            long openTotalNanos,
            long continueParseNanos,
            long continueMetadataNanos,
            long continueQueryDataNanos,
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

        double averageOpenSendMicros() {
            return micros(openSendNanos, openQueries);
        }

        double averageContinueTotalMicros() {
            return micros(continueTotalNanos, continueQueries);
        }

        double averageContinueQueryDataMicros() {
            return micros(continueQueryDataNanos, continueQueries);
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
                    Long.toString(openSendNanos), Long.toString(openResidualNanos()),
                    Long.toString(openTotalNanos), Long.toString(continueParseNanos),
                    Long.toString(continueMetadataNanos), Long.toString(continueQueryDataNanos),
                    Long.toString(continueSendNanos), Long.toString(continueResidualNanos()),
                    Long.toString(continueTotalNanos), format(averageOpenTotalMicros()),
                    format(averageOpenExecuteMicros()), format(averageOpenQueryDataMicros()),
                    format(averageOpenSendMicros()), format(openExecuteShare()),
                    format(openQueryDataShare()), format(openSendShare()),
                    format(averageContinueTotalMicros()),
                    format(averageContinueQueryDataMicros()),
                    format(averageContinueSendMicros()),
                    format(continueQueryDataShare()), format(continueSendShare()));
        }

        private static double micros(long nanos, long count) {
            return count == 0L ? 0.0 : nanos / (count * 1_000.0);
        }

        private static double share(long part, long total) {
            return total == 0L ? 0.0 : (double) part / total;
        }
    }

    private record ServerEndpoint(String jdbcUrl, String user, String password) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private static final class ContainerServer implements AutoCloseable {
        private final String name;
        private final ServerEndpoint endpoint;
        private final Path capturedLog;
        private boolean closed;

        private ContainerServer(String name, ServerEndpoint endpoint, Path capturedLog) {
            this.name = name;
            this.endpoint = endpoint;
            this.capturedLog = capturedLog;
        }

        ServerEndpoint endpoint() {
            return endpoint;
        }

        String logs() {
            try {
                return runCommand(20, List.of("docker", "logs", "--tail", "80", name)).output();
            } catch (Exception failure) {
                return "Could not read container logs: " + failure;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (capturedLog != null) {
                try {
                    Files.createDirectories(capturedLog.getParent());
                    CommandResult captured = runCommand(20, List.of("docker", "logs", name));
                    Files.writeString(capturedLog, captured.output(), StandardCharsets.UTF_8);
                } catch (Exception failure) {
                    try {
                        Files.writeString(capturedLog,
                                "Could not capture container log: " + failure + System.lineSeparator(),
                                StandardCharsets.UTF_8);
                    } catch (IOException ignored) {
                    }
                }
            }
            try {
                runCommand(30, List.of("docker", "rm", "-f", name));
            } catch (Exception ignored) {
            }
        }
    }

    private record Spec(Workload workload, int clients, int operationsPerTransaction) {
    }

    private record DeleteRow(int id, int category, int bucket, int quantity, String payload) {
        private DeleteRow {
            Objects.requireNonNull(payload, "payload");
        }
    }

    private record ClientRun(long fingerprint, long retryableRollbacks) {
    }

    private record Interval(
            long elapsedNanos,
            long semanticFingerprint,
            long retryableRollbacks,
            DelosSqlSemanticOracle.Result sqlOracleResult,
            long[] drdaProtocolEvidence) {
    }

    private record Verification(
            long legacyFingerprint,
            DelosSqlSemanticOracle.Result sqlOracleResult) {
    }

    private record MeasuredSpec(
            Measurement measurement,
            OracleEvidence oracleEvidence,
            DrdaProtocolEvidence protocolEvidence) {
    }

    private record DrdaProtocolEvidence(
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
                Options options,
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

    private record Measurement(
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

    private record Row(
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

    private record ShapeKey(int rowCount, Workload workload, int clients, int operationsPerTransaction) {
        String csv() {
            return rowCount + "," + workload + "," + clients + "," + operationsPerTransaction;
        }

        BaselineKey baselineKey() {
            return new BaselineKey(rowCount, workload, operationsPerTransaction);
        }
    }

    private record BaselineKey(int rowCount, Workload workload, int operationsPerTransaction) {
    }

    private record ShapeTargetKey(ShapeKey shape, Target target) {
    }

    private record Distribution(
            int count, double median, double q1, double q3, double iqr, double mad, double min, double max) {
    }

    private record OracleEvidence(
            String target,
            Workload workload,
            int clients,
            int operationsPerTransaction,
            int rowCount,
            String kind,
            long count,
            String fingerprint,
            int run) {

        private ShapeKey shape() {
            return new ShapeKey(rowCount, workload, clients, operationsPerTransaction);
        }

        private String csv() {
            return target + ',' + workload + ',' + clients + ',' + operationsPerTransaction + ','
                    + rowCount + ',' + kind + ',' + count + ',' + fingerprint + ',' + run;
        }

        private static OracleEvidence parse(String line) {
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

    private record Options(
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
        static Options fromSystemProperties() {
            String targetValue = System.getProperty(PREFIX + "target");
            Path databaseRoot = path(PREFIX + "databaseRoot", "build/tmp/delos-jdbc-cross-engine-concurrency");
            Path reportDirectory = path(PREFIX + "reportDirectory",
                    "build/reports/delosdb/benchmarks/cross-engine-concurrency");
            if (currentBaselineEnabled()) {
                Path suffix = currentBaselineSuffix(reportDirectory);
                reportDirectory = requiredEnvironmentPath(CURRENT_BASELINE_REPORT_ROOT_ENV).resolve(suffix);
                databaseRoot = requiredEnvironmentPath(CURRENT_BASELINE_DATABASE_ROOT_ENV).resolve(suffix);
            }
            return new Options(
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
            boolean drdaServerPhaseDiagnostic = drdaServerPhaseEvidenceEnabled()
                    && configuredTargets.equals(DRDA_SERVER_PHASE_EVIDENCE_TARGETS);
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
                    && !drdaServerPhaseDiagnostic
                    && !currentBaselineTargets) {
                throw new IllegalArgumentException("coordinator targets must be exactly " + embedded + ", "
                        + container + ", embedded reference canary " + EMBEDDED_REFERENCE_CANARY_TARGETS
                        + ", server reference canary " + SERVER_REFERENCE_CANARY_TARGETS
                        + ", diagnostic " + READ_DECOMPOSITION_TARGETS
                        + ", range/JFR diagnostic " + RANGE_SCAN_JFR_TARGETS
                        + ", range bulk-fetch diagnostic " + RANGE_BULK_FETCH_TARGETS
                        + ", MVCC diagnostic " + MVCC_ONLY_DIAGNOSTIC_TARGETS
                        + ", host recovery diagnostic " + HOST_RECOVERY_DIAGNOSTIC_TARGETS
                        + ", DRDA protocol diagnostic " + DRDA_PROTOCOL_EVIDENCE_TARGETS
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
                    && !mixedReaderWriterFitness && !hostStateDiagnosticsEnabled()
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
                    && !drdaServerPhaseDiagnostic && (runs < 4 || (runs & 3) != 0)) {
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
                if (drdaProtocolDiagnostic || drdaServerPhaseDiagnostic) {
                    if (delosClientClasspath.isBlank()) {
                        throw new IllegalArgumentException(
                                "Delos network client classpath is required for DRDA diagnostics");
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
                    } else if (drdaServerPhaseDiagnostic) {
                        if (delosServerImage.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Delos server image is required for DRDA server phase evidence");
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
    }

}
