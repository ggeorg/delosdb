/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final List<Target> MVCC_ONLY_DIAGNOSTIC_TARGETS = List.of(Target.DELOS_MVCC);
    private static final String CSV_HEADER =
            "target,product,productVersion,driverVersion,workload,clients,operationsPerTransaction,"
                    + "transactionsPerClient,rowCount,payloadSize,fixtureCommitBatchSize,warmups,iterations,"
                    + "measuredTransactions,measuredOperations,retryableConflictRetries,elapsedNanos,"
                    + "transactionsPerSecond,operationsPerSecond,inverseThroughputNanosPerTransaction,"
                    + "semanticFingerprint,run";

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

        for (int run = 1; run <= options.runs(); run++) {
            List<Target> targets = new ArrayList<>(options.targetValues());
            if (((run - 1) & 2) != 0) {
                Collections.reverse(targets);
            }
            for (Target target : targets) {
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
            }
        }

        List<Row> rows = loadRows(options);
        validateRows(options, rows);
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
                && Boolean.getBoolean("delosdb.experimental.fastRecordReadLock")) {
            command.add("-Ddelosdb.experimental.fastRecordReadLock=true");
        }
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean("delosdb.experimental.heapPageLocalIndexBaseFetch")) {
            command.add("-Ddelosdb.experimental.heapPageLocalIndexBaseFetch=true");
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
        command.add("-cp");
        command.add(options.benchmarkClasses() + java.io.File.pathSeparator + options.classpath(target));
        addProperty(command, "targets", options.targets());
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
        addProperty(command, "payload", options.payload());
        addProperty(command, "fixtureBatch", options.fixtureBatch());
        addProperty(command, "warmups", options.warmups());
        addProperty(command, "iterations", options.iterations());
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
        boolean completed = options.workerTimeoutSeconds() == 0
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
                command.addAll(List.of(
                        "java", "-Xms" + options.childHeap(), "-Xmx" + options.childHeap(),
                        "-XX:+AlwaysPreTouch", "-cp", "/opt/delos/lib/*",
                        "org.apache.derby.drda.NetworkServerControl", "start",
                        "-h", "0.0.0.0", "-p", Integer.toString(target.containerPort())));
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
            default -> throw new IllegalArgumentException("Not a container target: " + target);
        }

        CommandResult started = runCommand(Math.max(30, options.containerStartupTimeoutSeconds()), command);
        if (started.exitCode() != 0) {
            throw new IllegalStateException("Could not start " + target.id() + " container:\n" + started.output());
        }
        int port = publishedPort(name, target.containerPort());
        ServerEndpoint endpoint = target.endpoint(port);
        ContainerServer server = new ContainerServer(name, endpoint);
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
                .append("Question: compare DelosDB DRDA heap/MVCC with PostgreSQL and MariaDB through "
                        + "equivalent TCP/JDBC boundaries.\n")
                .append("Targets: ").append(options.targets()).append('\n')
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
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append('\n')
                .append("Case timeout seconds: ").append(options.caseTimeoutSeconds()).append('\n')
                .append("Worker timeout seconds: ").append(options.workerTimeoutSeconds()).append('\n')
                .append("PostgreSQL JDBC: ").append(options.postgresqlDriverVersion()).append('\n')
                .append("MariaDB Connector/J: ").append(options.mariadbDriverVersion()).append('\n')
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
                    measurements.add(measureSpec(options, config, spec));
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
    }

    private static Measurement measureSpec(Options options, DelosBenchmarkConfig config, Spec spec)
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
                for (int warmup = 0; warmup < options.warmups(); warmup++) {
                    Interval interval = concurrentCase.runInterval();
                    expectedSemantic = sameSemantic(expectedSemantic, interval.semanticFingerprint(), spec,
                            "warmup " + warmup);
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
                for (int iteration = 0; iteration < options.iterations(); iteration++) {
                    Interval interval = concurrentCase.runInterval();
                    elapsed = Math.addExact(elapsed, interval.elapsedNanos());
                    retryableRollbacks = Math.addExact(retryableRollbacks, interval.retryableRollbacks());
                    measuredSemantic = sameSemantic(measuredSemantic, interval.semanticFingerprint(), spec,
                            "measured iteration " + iteration);
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
                        options.iterations());
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
                measurement = new Measurement(
                        options.target().id(), product, productVersion, driverVersion,
                        spec.workload(), spec.clients(), spec.operationsPerTransaction(),
                        transactionsPerClient, config.rowCount(), config.payloadSize(),
                        config.commitBatchSize(), options.warmups(), options.iterations(),
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
        return measurement;
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
                .append("benchmarkDdl=").append(sqliteBenchmarkDdl(tables)).append('\n')
                .append("compileOptions=").append(sqliteCompileOptions(connection)).append('\n')
                .append('\n');
        Path output = options.reportDirectory().resolve(
                "sqlite-runtime-metadata-run-" + options.run() + ".txt");
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static String sqliteBenchmarkDdl(List<String> tables) {
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
        private final int transactionsPerClient;
        private final int[] mutationIds;
        private final int[] mutationBaseline;
        private final List<Client> clients;
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
            this.transactionsPerClient = options.transactionsPerClient(spec, rowCount);
            this.mutationIds = mutationIds(spec, rowCount);
            this.mutationBaseline = new int[mutationIds.length];
            for (int index = 0; index < mutationIds.length; index++) {
                mutationBaseline[index] = quantity(verifier, this.table, mutationIds[index]);
            }
            int[] fixtureQuantities = spec.workload().usesFixtureQuantities() ? fixtureQuantities(rowCount) : null;
            verifier.rollback();
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
                    } else if (spec.workload().isUpdate()) {
                        int targetIndex = spec.workload() == Workload.DISJOINT_INDEXED_UPDATE ? client : 0;
                        updateId = mutationIds[targetIndex];
                    }
                    String clientTable = spec.workload().usesPrivateTablePerClient()
                            ? tables.get(client)
                            : this.table;
                    clients.add(new Client(
                            connection, clientTable, spec, updateId, readIds, expectedReadQuantities,
                            rangeStart, rangeEndExclusive, expectedRangeRows, expectedRangeFingerprint,
                            options.target()));
                }
            } catch (SQLException failure) {
                closeClients(failure);
                throw failure;
            }
            this.executor = Executors.newFixedThreadPool(
                    spec.clients(), Thread.ofPlatform().daemon().name("delos-bench-client-", 0).factory());
        }

        private Interval runInterval() throws Exception {
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
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(options.caseTimeoutSeconds());
            start.countDown();
            long executionFingerprint = 1L;
            long retryableRollbacks = 0L;
            Throwable failure = null;
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
            long elapsed = System.nanoTime() - started;
            if (failure != null) {
                cancelFutures(futures);
                throwFailure(failure);
            }
            long stateFingerprint = verifyAndRestore();
            return new Interval(elapsed, mix(executionFingerprint, stateFingerprint), retryableRollbacks);
        }

        private static void cancelFutures(List<? extends Future<?>> futures) {
            for (Future<?> future : futures) {
                future.cancel(true);
            }
        }

        private long verifyAndRestore() throws SQLException {
            try {
                long fingerprint = mix(spec.clients(), spec.operationsPerTransaction());
                if (spec.workload().isReadOnly()) {
                    verifier.rollback();
                    return fingerprint;
                }
                int increment = Math.multiplyExact(transactionsPerClient, spec.operationsPerTransaction());
                for (int index = 0; index < mutationIds.length; index++) {
                    int expected = mutationBaseline[index];
                    if (spec.workload() == Workload.DISJOINT_INDEXED_UPDATE) {
                        expected += increment;
                    } else if (spec.workload() == Workload.CONTENDED_INDEXED_UPDATE) {
                        expected += Math.multiplyExact(spec.clients(), increment);
                    }
                    int actual = quantity(verifier, table, mutationIds[index]);
                    if (actual != expected) {
                        throw new IllegalStateException("Concurrent semantic drift for " + spec
                                + ", id=" + mutationIds[index] + ": expected=" + expected + ", actual=" + actual);
                    }
                    fingerprint = mix(mix(fingerprint, mutationIds[index]), actual);
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
                return fingerprint;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    verifier.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            executor.shutdownNow();
            if (executor.awaitTermination(10, TimeUnit.SECONDS)) {
                closeClients(null);
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

    private static final class Client implements AutoCloseable {
        private final Connection connection;
        private final Workload workload;
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
        private final PreparedStatement update;

        private Client(
                Connection connection,
                String table,
                Spec spec,
                int updateId,
                int[] readIds,
                int[] expectedReadQuantities,
                int rangeStart,
                int rangeEndExclusive,
                int expectedRangeRows,
                long expectedRangeFingerprint,
                Target target)
                throws SQLException {
            this.connection = connection;
            this.workload = spec.workload();
            this.updateId = updateId;
            this.readIds = readIds;
            this.expectedReadQuantities = expectedReadQuantities;
            this.rangeStart = rangeStart;
            this.rangeEndExclusive = rangeEndExclusive;
            this.expectedRangeRows = expectedRangeRows;
            this.expectedRangeFingerprint = expectedRangeFingerprint;
            this.target = target;
            PreparedStatement localRead = null;
            PreparedStatement localRangeRead = null;
            PreparedStatement localValues = null;
            PreparedStatement localUpdate = null;
            try {
                if (workload.isPrimaryKeyRead()) {
                    localRead = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                } else if (workload.isRangeScan()) {
                    localRangeRead = connection.prepareStatement(
                            workload.isIndexOnlyRangeScan()
                                    ? "select id from " + table
                                            + " where id >= ? and id < ? order by id"
                                    : "select id, quantity from " + table
                                            + " where id >= ? and id < ? order by id");
                } else if (workload.isValues()) {
                    localValues = connection.prepareStatement("values (1)");
                } else if (workload.isUpdate()) {
                    localUpdate = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + 1 where id = ?");
                }
                this.read = localRead;
                this.rangeRead = localRangeRead;
                this.values = localValues;
                this.update = localUpdate;
            } catch (SQLException failure) {
                closeStatement(localUpdate, failure);
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
                        for (int operation = 0; operation < operationsPerTransaction; operation++) {
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
                            } else if (workload.isUpdate()) {
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

    private static int[] mutationIds(Spec spec, int rowCount) {
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
        if (options.containerMode()) {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosHeapDrdaMedianTps,"
                            + "delosMvccDrdaMedianTps,postgresqlMedianTps,mariadbMedianTps,"
                            + "delosHeapToPostgresql,delosMvccToPostgresql,delosHeapToMariadb,delosMvccToMariadb\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                EnumMap<Target, Double> values = entry.getValue();
                double heap = require(values, Target.DELOS_HEAP_DRDA, key);
                double mvcc = require(values, Target.DELOS_MVCC_DRDA, key);
                double postgres = require(values, Target.POSTGRESQL, key);
                double mariadb = require(values, Target.MARIADB, key);
                out.append(key.csv()).append(',')
                        .append(format(heap)).append(',').append(format(mvcc)).append(',')
                        .append(format(postgres)).append(',').append(format(mariadb)).append(',')
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
                .append("Targets: ").append(options.targets()).append('\n')
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
        DISJOINT_INDEXED_UPDATE(false, false, false, -1, Connection.TRANSACTION_READ_COMMITTED),
        CONTENDED_INDEXED_UPDATE(false, false, false, -1, Connection.TRANSACTION_READ_COMMITTED);

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
                    || this == RANGE_SCAN_INDEX_ONLY_1000;
        }

        boolean isIndexOnlyRangeScan() {
            return this == RANGE_SCAN_INDEX_ONLY_100
                    || this == RANGE_SCAN_INDEX_ONLY_1000;
        }

        boolean usesFixtureQuantities() {
            return isPrimaryKeyRead() || isRangeScan();
        }

        int rangeRows(int rowCount) {
            return switch (this) {
                case RANGE_SCAN_1 -> 1;
                case RANGE_SCAN_10 -> Math.min(10, rowCount);
                case RANGE_SCAN_100, RANGE_SCAN_INDEX_ONLY_100 -> Math.min(100, rowCount);
                case RANGE_SCAN_1000, RANGE_SCAN_INDEX_ONLY_1000 -> Math.min(1000, rowCount);
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

        boolean isReadOnly() {
            return readOnly;
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
        POSTGRESQL("postgresql", ""),
        MARIADB("mariadb", "");

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
                    || this == POSTGRESQL || this == MARIADB;
        }

        int containerPort() {
            return switch (this) {
                case DELOS_HEAP_DRDA, DELOS_MVCC_DRDA -> 1527;
                case POSTGRESQL -> 5432;
                case MARIADB -> 3306;
                default -> throw new IllegalStateException("Not a container target: " + this);
            };
        }

        String containerImage(Options options) {
            return switch (this) {
                case DELOS_HEAP_DRDA, DELOS_MVCC_DRDA -> options.delosServerImage();
                case POSTGRESQL -> options.postgresqlImage();
                case MARIADB -> options.mariadbImage();
                default -> throw new IllegalStateException("Not a container target: " + this);
            };
        }

        ServerEndpoint endpoint(int hostPort) {
            return switch (this) {
                case DELOS_HEAP_DRDA, DELOS_MVCC_DRDA -> new ServerEndpoint(
                        "jdbc:derby://127.0.0.1:" + hostPort + "/delosbench;create=true", "", "");
                case POSTGRESQL -> new ServerEndpoint(
                        "jdbc:postgresql://127.0.0.1:" + hostPort + "/delosbench", "delosbench", "delosbench");
                case MARIADB -> new ServerEndpoint(
                        "jdbc:mariadb://127.0.0.1:" + hostPort + "/delosbench", "delosbench", "delosbench");
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

    private record ServerEndpoint(String jdbcUrl, String user, String password) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private static final class ContainerServer implements AutoCloseable {
        private final String name;
        private final ServerEndpoint endpoint;
        private boolean closed;

        private ContainerServer(String name, ServerEndpoint endpoint) {
            this.name = name;
            this.endpoint = endpoint;
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
            try {
                runCommand(30, List.of("docker", "rm", "-f", name));
            } catch (Exception ignored) {
            }
        }
    }

    private record Spec(Workload workload, int clients, int operationsPerTransaction) {
    }

    private record ClientRun(long fingerprint, long retryableRollbacks) {
    }

    private record Interval(long elapsedNanos, long semanticFingerprint, long retryableRollbacks) {
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
                    Integer.toString(warmups), Integer.toString(iterations),
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
            if (fields.length != 22) {
                throw new IllegalArgumentException(
                        "Expected 22 concurrency CSV fields, found " + fields.length + ": " + line);
            }
            return new Row(fields[0], fields[1], fields[2], fields[3], Workload.valueOf(fields[4]),
                    Integer.parseInt(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
                    Integer.parseInt(fields[8]), Integer.parseInt(fields[9]), Integer.parseInt(fields[10]),
                    Integer.parseInt(fields[11]), Integer.parseInt(fields[12]), Long.parseLong(fields[13]),
                    Long.parseLong(fields[14]), Long.parseLong(fields[15]), Long.parseLong(fields[16]),
                    Double.parseDouble(fields[17]), Double.parseDouble(fields[18]), Double.parseDouble(fields[19]),
                    Long.parseLong(fields[20]), Integer.parseInt(fields[21]));
        }

        ShapeKey shape() {
            return new ShapeKey(rowCount, workload, clients, operationsPerTransaction);
        }

        String csv() {
            return new Measurement(target, product, productVersion, driverVersion, workload, clients,
                    operationsPerTransaction, transactionsPerClient, rowCount, payloadSize,
                    fixtureCommitBatchSize, warmups, iterations, measuredTransactions, measuredOperations,
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

    private record Options(
            Path projectDirectory,
            Path javaExecutable,
            String benchmarkClasses,
            String delosClasspath,
            String upstreamDerbyClasspath,
            String h2Classpath,
            String sqliteClasspath,
            String delosClientClasspath,
            String postgresqlClasspath,
            String mariadbClasspath,
            String targets,
            Path delosRuntimeDirectory,
            String delosServerImage,
            String postgresqlImage,
            String mariadbImage,
            String postgresqlDriverVersion,
            String mariadbDriverVersion,
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
            int payload,
            int fixtureBatch,
            int warmups,
            int iterations,
            int runs,
            int caseTimeoutSeconds,
            int workerTimeoutSeconds,
            int containerStartupTimeoutSeconds,
            String childHeap,
            Target target,
            int run) {
        static Options fromSystemProperties() {
            String targetValue = System.getProperty(PREFIX + "target");
            return new Options(
                    path(PREFIX + "projectDirectory", "."),
                    path(PREFIX + "javaExecutable", Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                    System.getProperty(PREFIX + "benchmarkClasses", "."),
                    System.getProperty(PREFIX + "delosClasspath", "."),
                    System.getProperty(PREFIX + "upstreamDerbyClasspath", "."),
                    System.getProperty(PREFIX + "h2Classpath", "."),
                    System.getProperty(PREFIX + "sqliteClasspath", "."),
                    System.getProperty(PREFIX + "delosClientClasspath", "."),
                    System.getProperty(PREFIX + "postgresqlClasspath", "."),
                    System.getProperty(PREFIX + "mariadbClasspath", "."),
                    System.getProperty(PREFIX + "targets", "delos_heap,delos_mvcc,upstream_derby,h2,sqlite"),
                    path(PREFIX + "delosRuntimeDirectory", "build/libs"),
                    System.getProperty(PREFIX + "delosServerImage", "eclipse-temurin:25.0.3_9-jre-noble"),
                    System.getProperty(PREFIX + "postgresqlImage", "postgres:18.4"),
                    System.getProperty(PREFIX + "mariadbImage", "mariadb:12.3.2"),
                    System.getProperty(PREFIX + "postgresqlDriverVersion", "42.7.13"),
                    System.getProperty(PREFIX + "mariadbDriverVersion", "3.5.10"),
                    System.getProperty(PREFIX + "projectVersion", "unknown"),
                    System.getProperty(PREFIX + "remoteJdbcUrl", ""),
                    System.getProperty(PREFIX + "remoteUser", ""),
                    System.getProperty(PREFIX + "remotePassword", ""),
                    path(PREFIX + "databaseRoot", "build/tmp/delos-jdbc-cross-engine-concurrency"),
                    path(PREFIX + "reportDirectory", "build/reports/delosdb/benchmarks/cross-engine-concurrency"),
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
                    Integer.parseInt(System.getProperty(PREFIX + "payload", "128")),
                    Integer.parseInt(System.getProperty(PREFIX + "fixtureBatch", "100")),
                    Integer.parseInt(System.getProperty(PREFIX + "warmups", "2")),
                    Integer.parseInt(System.getProperty(PREFIX + "iterations", "3")),
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
            List<Target> container = List.of(
                    Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA, Target.POSTGRESQL, Target.MARIADB);
            boolean mvccOnlyDiagnostic = configuredTargets.equals(MVCC_ONLY_DIAGNOSTIC_TARGETS);
            if (target == null
                    && !configuredTargets.equals(embedded)
                    && !configuredTargets.equals(container)
                    && !configuredTargets.equals(READ_DECOMPOSITION_TARGETS)
                    && !configuredTargets.equals(RANGE_SCAN_JFR_TARGETS)
                    && !configuredTargets.equals(RANGE_BULK_FETCH_TARGETS)
                    && !mvccOnlyDiagnostic) {
                throw new IllegalArgumentException("coordinator targets must be exactly " + embedded + ", "
                        + container + ", diagnostic " + READ_DECOMPOSITION_TARGETS
                        + ", range/JFR diagnostic " + RANGE_SCAN_JFR_TARGETS
                        + ", range bulk-fetch diagnostic " + RANGE_BULK_FETCH_TARGETS
                        + ", or MVCC diagnostic " + MVCC_ONLY_DIAGNOSTIC_TARGETS + ": " + configuredTargets);
            }
            if (target != null && !configuredTargets.contains(target)) {
                throw new IllegalArgumentException(
                        "worker target " + target.id() + " is not present in configured targets " + configuredTargets);
            }
            parsePositive(rows, "rows", 100);
            parsePositive(clients, "clients", 1);
            parsePositive(widths, "widths", 1);
            workloadValues();
            int maxClients = clientValues().stream().mapToInt(Integer::intValue).max().orElseThrow();
            int minRows = rowCounts().stream().mapToInt(Integer::intValue).min().orElseThrow();
            if (maxClients > minRows) {
                throw new IllegalArgumentException("clients cannot exceed rows");
            }
            if (target == null && !mvccOnlyDiagnostic && !clientValues().contains(1)) {
                throw new IllegalArgumentException("clients must include 1 for scaling ratios");
            }
            if (transactionsPerClient < 1 || fixedWorkloadOperationBudgetPerClient < 0
                    || rangeScanTargetRowsPerClient < 1L || rangeScanMinQueriesPerClient < 1
                    || rangeScanMaxQueriesPerClient < rangeScanMinQueriesPerClient
                    || payload < 16 || fixtureBatch < 1 || warmups < 0
                    || iterations < 1 || caseTimeoutSeconds < 1 || workerTimeoutSeconds < 0
                    || containerStartupTimeoutSeconds < 1) {
                throw new IllegalArgumentException("Invalid concurrency benchmark numeric option");
            }
            if (target == null && !mvccOnlyDiagnostic && (runs < 4 || (runs & 3) != 0)) {
                throw new IllegalArgumentException("runs must be a multiple of 4 for orthogonal order");
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
                if (!Files.isDirectory(delosRuntimeDirectory)) {
                    throw new IllegalArgumentException("Delos runtime directory does not exist: "
                            + delosRuntimeDirectory);
                }
                if (delosClientClasspath.isBlank() || postgresqlClasspath.isBlank() || mariadbClasspath.isBlank()
                        || delosServerImage.isBlank() || postgresqlImage.isBlank() || mariadbImage.isBlank()) {
                    throw new IllegalArgumentException("Container benchmark classpaths and images are required");
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
            return List.copyOf(values);
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
                case POSTGRESQL -> postgresqlClasspath;
                case MARIADB -> mariadbClasspath;
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
