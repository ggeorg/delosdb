/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** JDBC concurrency comparison with deterministic semantic verification. */
public final class DelosJdbcCrossEngineConcurrency {
    private static final String PREFIX = "delosdb.benchmark.crossEngineConcurrency.";
    private static final long SEED = 0x5DE10DBL;
    private static final String CSV_HEADER =
            "target,product,productVersion,driverVersion,workload,clients,operationsPerTransaction,"
                    + "transactionsPerClient,rowCount,payloadSize,fixtureCommitBatchSize,warmups,iterations,"
                    + "measuredTransactions,measuredOperations,retryableRollbacks,elapsedNanos,transactionsPerSecond,"
                    + "averageTransactionLatencyNanos,semanticFingerprint,run";

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
        command.add("-cp");
        command.add(options.benchmarkClasses() + java.io.File.pathSeparator + options.classpath(target));
        addProperty(command, "target", target.id());
        addProperty(command, "run", run);
        addProperty(command, "databaseRoot", options.databaseRoot());
        addProperty(command, "reportDirectory", options.reportDirectory().resolve("workers"));
        addProperty(command, "rows", options.rows());
        addProperty(command, "clients", options.clients());
        addProperty(command, "widths", options.widths());
        addProperty(command, "workloads", options.workloads());
        addProperty(command, "transactionsPerClient", options.transactionsPerClient());
        addProperty(command, "payload", options.payload());
        addProperty(command, "fixtureBatch", options.fixtureBatch());
        addProperty(command, "warmups", options.warmups());
        addProperty(command, "iterations", options.iterations());
        addProperty(command, "caseTimeoutSeconds", options.caseTimeoutSeconds());
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
        int status = process.waitFor();
        if (status != 0) {
            List<String> lines = Files.exists(log) ? Files.readAllLines(log) : List.of();
            int from = Math.max(0, lines.size() - 40);
            throw new IllegalStateException("Concurrency worker failed: target=" + target.id()
                    + ", run=" + run + ", exit=" + status + ", log=" + log
                    + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines.subList(from, lines.size()))));
        }
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
        int port = freePort();
        String name = "delos-bench-" + target.id().replace('_', '-') + '-'
                + ProcessHandle.current().pid() + '-' + run;
        runCommand(20, List.of("docker", "rm", "-f", name));
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "-d", "--rm", "--name", name,
                "-p", "127.0.0.1:" + port + ':' + target.containerPort()));
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
        if (options.target().isContainer()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        return connection;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
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
                measurements.add(measureSpec(options, config, spec));
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
            if (options.target() == Target.H2) {
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
            DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(
                    verifier, options.target().id(), options.target().createTableSuffix(),
                    options.target().isContainer(), config);
            scenario.prepare();
            try (ConcurrentCase concurrentCase = new ConcurrentCase(
                    options, spec, database, verifier, scenario.tableName(), config.rowCount())) {
                Long expectedSemantic = null;
                for (int warmup = 0; warmup < options.warmups(); warmup++) {
                    Interval interval = concurrentCase.runInterval();
                    expectedSemantic = sameSemantic(expectedSemantic, interval.semanticFingerprint(), spec,
                            "warmup " + warmup);
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
                long measuredTransactions = Math.multiplyExact(
                        Math.multiplyExact((long) spec.clients(), options.transactionsPerClient()),
                        options.iterations());
                long measuredOperations = Math.multiplyExact(
                        measuredTransactions, spec.operationsPerTransaction());
                measurement = new Measurement(
                        options.target().id(), product, productVersion, driverVersion,
                        spec.workload(), spec.clients(), spec.operationsPerTransaction(),
                        options.transactionsPerClient(), config.rowCount(), config.payloadSize(),
                        config.commitBatchSize(), options.warmups(), options.iterations(),
                        measuredTransactions, measuredOperations, retryableRollbacks, elapsed,
                        measuredTransactions * 1_000_000_000.0 / elapsed,
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

    private static final class ConcurrentCase implements AutoCloseable {
        private final Options options;
        private final Spec spec;
        private final Connection verifier;
        private final String table;
        private final int[] ids;
        private final int[] baseline;
        private final List<Client> clients;
        private final ExecutorService executor;

        private ConcurrentCase(
                Options options,
                Spec spec,
                Path database,
                Connection verifier,
                String table,
                int rowCount) throws SQLException {
            this.options = options;
            this.spec = spec;
            this.verifier = verifier;
            this.table = table;
            this.ids = targetIds(spec, rowCount);
            this.baseline = new int[ids.length];
            for (int index = 0; index < ids.length; index++) {
                baseline[index] = quantity(verifier, table, ids[index]);
            }
            verifier.rollback();
            this.clients = new ArrayList<>(spec.clients());
            try {
                for (int client = 0; client < spec.clients(); client++) {
                    Connection connection = connect(options, database);
                    connection.setAutoCommit(false);
                    int targetIndex = spec.workload() == Workload.DISJOINT_INDEXED_UPDATE ? client : 0;
                    clients.add(new Client(connection, table, spec, ids[targetIndex], baseline[targetIndex]));
                }
            } catch (SQLException failure) {
                closeClients(failure);
                throw failure;
            }
            this.executor = Executors.newFixedThreadPool(spec.clients());
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
                    return client.runTransactions(options.transactionsPerClient(), spec.operationsPerTransaction());
                }));
            }
            if (!ready.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                start.countDown();
                throw new IllegalStateException("concurrency readiness barrier timed out");
            }
            long started = System.nanoTime();
            start.countDown();
            long executionFingerprint = 1L;
            long retryableRollbacks = 0L;
            Throwable failure = null;
            for (Future<ClientRun> future : futures) {
                try {
                    ClientRun clientRun = future.get(options.caseTimeoutSeconds(), TimeUnit.SECONDS);
                    executionFingerprint = mix(executionFingerprint, clientRun.fingerprint());
                    retryableRollbacks = Math.addExact(retryableRollbacks, clientRun.retryableRollbacks());
                } catch (Throwable clientFailure) {
                    failure = preserve(failure, clientFailure);
                }
            }
            long elapsed = System.nanoTime() - started;
            if (failure != null) {
                throwFailure(failure);
            }
            long stateFingerprint = verifyAndRestore();
            return new Interval(elapsed, mix(executionFingerprint, stateFingerprint), retryableRollbacks);
        }

        private long verifyAndRestore() throws SQLException {
            try {
                long fingerprint = mix(spec.clients(), spec.operationsPerTransaction());
                int increment = Math.multiplyExact(options.transactionsPerClient(), spec.operationsPerTransaction());
                for (int index = 0; index < ids.length; index++) {
                    int expected = baseline[index];
                    if (spec.workload() == Workload.DISJOINT_INDEXED_UPDATE) {
                        expected += increment;
                    } else if (spec.workload() == Workload.CONTENDED_INDEXED_UPDATE) {
                        expected += Math.multiplyExact(spec.clients(), increment);
                    }
                    int actual = quantity(verifier, table, ids[index]);
                    if (actual != expected) {
                        throw new IllegalStateException("Concurrent semantic drift for " + spec
                                + ", id=" + ids[index] + ": expected=" + expected + ", actual=" + actual);
                    }
                    fingerprint = mix(mix(fingerprint, ids[index]), actual);
                }
                if (spec.workload() != Workload.PRIMARY_KEY_READ) {
                    try (PreparedStatement restore = verifier.prepareStatement(
                            "update " + table + " set quantity = ? where id = ?")) {
                        for (int index = 0; index < ids.length; index++) {
                            restore.setInt(1, baseline[index]);
                            restore.setInt(2, ids[index]);
                            if (restore.executeUpdate() != 1) {
                                throw new SQLException("Concurrent restore did not affect one row: id=" + ids[index]);
                            }
                        }
                    }
                    verifier.commit();
                } else {
                    verifier.rollback();
                }
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
            executor.awaitTermination(10, TimeUnit.SECONDS);
            closeClients(null);
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
        private final int id;
        private final int expectedReadQuantity;
        private final PreparedStatement read;
        private final PreparedStatement update;

        private Client(
                Connection connection, String table, Spec spec, int id, int expectedReadQuantity)
                throws SQLException {
            this.connection = connection;
            this.workload = spec.workload();
            this.id = id;
            this.expectedReadQuantity = expectedReadQuantity;
            PreparedStatement localRead = null;
            PreparedStatement localUpdate = null;
            try {
                if (workload == Workload.PRIMARY_KEY_READ) {
                    localRead = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                } else {
                    localUpdate = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + 1 where id = ?");
                }
                this.read = localRead;
                this.update = localUpdate;
            } catch (SQLException failure) {
                closeStatement(localUpdate, failure);
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
                            if (workload == Workload.PRIMARY_KEY_READ) {
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
                            } else {
                                update.setInt(1, id);
                                if (update.executeUpdate() != 1) {
                                    throw new SQLException("Concurrent update did not affect one row: id=" + id);
                                }
                            }
                            transactionFingerprint = mix(transactionFingerprint, id);
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
                        if (!isRetryableRollback(failure) || ++attempts >= 1000) {
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

    private static int[] targetIds(Spec spec, int rowCount) {
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
        return new int[]{1};
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
        for (int width : options.widthValues()) {
            for (int clients : options.clientValues()) {
                for (Workload workload : options.workloadValues()) {
                    specs.add(new Spec(workload, clients, width));
                }
            }
        }
        int phase = (options.run() - 1) & 3;
        if (phase == 1 || phase == 2) {
            Collections.reverse(specs);
        }
        return List.copyOf(specs);
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
                * options.clientValues().size() * options.widthValues().size() * options.workloadValues().size();
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
        } else {
            out = new StringBuilder(
                    "rowCount,workload,clients,operationsPerTransaction,delosHeapMedianTps,delosMvccMedianTps,"
                            + "upstreamDerbyMedianTps,h2MedianTps,delosHeapToDerby,delosMvccToDerby,"
                            + "delosHeapToH2,delosMvccToH2\n");
            for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
                ShapeKey key = entry.getKey();
                EnumMap<Target, Double> values = entry.getValue();
                double heap = require(values, Target.DELOS_HEAP, key);
                double mvcc = require(values, Target.DELOS_MVCC, key);
                double derby = require(values, Target.UPSTREAM_DERBY, key);
                double h2 = require(values, Target.H2, key);
                out.append(key.csv()).append(',')
                        .append(format(heap)).append(',').append(format(mvcc)).append(',')
                        .append(format(derby)).append(',').append(format(h2)).append(',')
                        .append(format(heap / derby)).append(',').append(format(mvcc / derby)).append(',')
                        .append(format(heap / h2)).append(',').append(format(mvcc / h2)).append('\n');
            }
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-ratios.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeScalingCsv(Options options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(options, rows);
        Map<BaselineKey, EnumMap<Target, Double>> baselines = new HashMap<>();
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            if (entry.getKey().clients() == 1) {
                baselines.put(entry.getKey().baselineKey(), entry.getValue());
            }
        }
        StringBuilder out = new StringBuilder(
                "rowCount,workload,clients,operationsPerTransaction,target,medianTransactionsPerSecond,"
                        + "speedupFromOneClient,parallelEfficiency\n");
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
                        .append(format(tps)).append(',').append(format(speedup)).append(',')
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
                        + "iqrTps,madTps,minTps,maxTps,iqrToMedian,madToMedian,maxToMin\n");
        for (ShapeTargetKey key : keys) {
            Distribution distribution = distribution(values.get(key));
            out.append(key.shape().csv()).append(',').append(key.target().id()).append(',')
                    .append(distribution.count()).append(',')
                    .append(format(distribution.median())).append(',')
                    .append(format(distribution.q1())).append(',').append(format(distribution.q3())).append(',')
                    .append(format(distribution.iqr())).append(',').append(format(distribution.mad())).append(',')
                    .append(format(distribution.min())).append(',').append(format(distribution.max())).append(',')
                    .append(format(distribution.iqr() / distribution.median())).append(',')
                    .append(format(distribution.mad() / distribution.median())).append(',')
                    .append(format(distribution.max() / distribution.min())).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-dispersion.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeSummary(Options options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(options, rows);
        StringBuilder out = new StringBuilder();
        out.append(options.containerMode()
                        ? "DelosDB JDBC server-container concurrency comparison\n"
                        : "DelosDB JDBC four-engine concurrency comparison\n")
                .append("Targets: ").append(options.targets()).append('\n')
                .append("Rows: ").append(options.rows()).append('\n')
                .append("Clients: ").append(options.clients()).append('\n')
                .append("Operations per transaction: ").append(options.widths()).append('\n')
                .append("Transactions per client/interval: ").append(options.transactionsPerClient()).append('\n')
                .append("Workloads: ").append(options.workloadValues()).append('\n')
                .append("Each client owns one JDBC connection and reused prepared statement.\n")
                .append("Disjoint-update client rows are evenly spread across the fixture.\n")
                .append("Timed interval: synchronized client execution through final commit.\n")
                .append("Semantic verification/restoration outside timed interval: true\n")
                .append(options.containerMode()
                        ? "Fresh database container per target/run; fresh table per matrix cell: true\n"
                        : "Fresh database per target/run/matrix cell: true\n")
                .append("Target and matrix order orthogonalized across four-run blocks: true\n")
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            ShapeKey key = entry.getKey();
            out.append(String.format(Locale.ROOT, "%7d %-25s clients=%-2d ops/tx=%-2d",
                    key.rowCount(), key.workload(), key.clients(), key.operationsPerTransaction()));
            for (Target target : options.targetValues()) {
                out.append(String.format(Locale.ROOT, " %s=%11.2f",
                        target.id(), require(entry.getValue(), target, key)));
            }
            out.append(" tx/s\n");
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

    private static boolean isRetryableRollback(SQLException failure) {
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
        PRIMARY_KEY_READ,
        DISJOINT_INDEXED_UPDATE,
        CONTENDED_INDEXED_UPDATE
    }

    private enum Target {
        DELOS_HEAP("delos_heap", ""),
        DELOS_MVCC("delos_mvcc", " using delos_mvcc"),
        UPSTREAM_DERBY("upstream_derby", ""),
        H2("h2", ""),
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
            double averageTransactionLatencyNanos,
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
                    format(averageTransactionLatencyNanos), Long.toString(semanticFingerprint),
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
            double averageTransactionLatencyNanos,
            long semanticFingerprint,
            int run) {
        static Row parse(String line) {
            String[] fields = line.split(",", -1);
            if (fields.length != 21) {
                throw new IllegalArgumentException(
                        "Expected 21 concurrency CSV fields, found " + fields.length + ": " + line);
            }
            return new Row(fields[0], fields[1], fields[2], fields[3], Workload.valueOf(fields[4]),
                    Integer.parseInt(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
                    Integer.parseInt(fields[8]), Integer.parseInt(fields[9]), Integer.parseInt(fields[10]),
                    Integer.parseInt(fields[11]), Integer.parseInt(fields[12]), Long.parseLong(fields[13]),
                    Long.parseLong(fields[14]), Long.parseLong(fields[15]), Long.parseLong(fields[16]),
                    Double.parseDouble(fields[17]), Double.parseDouble(fields[18]), Long.parseLong(fields[19]),
                    Integer.parseInt(fields[20]));
        }

        ShapeKey shape() {
            return new ShapeKey(rowCount, workload, clients, operationsPerTransaction);
        }

        String csv() {
            return new Measurement(target, product, productVersion, driverVersion, workload, clients,
                    operationsPerTransaction, transactionsPerClient, rowCount, payloadSize,
                    fixtureCommitBatchSize, warmups, iterations, measuredTransactions, measuredOperations,
                    retryableRollbacks, elapsedNanos, transactionsPerSecond, averageTransactionLatencyNanos,
                    semanticFingerprint, run).csv();
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
            int transactionsPerClient,
            int payload,
            int fixtureBatch,
            int warmups,
            int iterations,
            int runs,
            int caseTimeoutSeconds,
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
                    System.getProperty(PREFIX + "delosClientClasspath", "."),
                    System.getProperty(PREFIX + "postgresqlClasspath", "."),
                    System.getProperty(PREFIX + "mariadbClasspath", "."),
                    System.getProperty(PREFIX + "targets", "delos_heap,delos_mvcc,upstream_derby,h2"),
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
                            "PRIMARY_KEY_READ,DISJOINT_INDEXED_UPDATE,CONTENDED_INDEXED_UPDATE"),
                    Integer.parseInt(System.getProperty(PREFIX + "transactionsPerClient", "50")),
                    Integer.parseInt(System.getProperty(PREFIX + "payload", "128")),
                    Integer.parseInt(System.getProperty(PREFIX + "fixtureBatch", "100")),
                    Integer.parseInt(System.getProperty(PREFIX + "warmups", "2")),
                    Integer.parseInt(System.getProperty(PREFIX + "iterations", "3")),
                    Integer.parseInt(System.getProperty(PREFIX + "runs", "4")),
                    Integer.parseInt(System.getProperty(PREFIX + "caseTimeoutSeconds", "120")),
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
            List<Target> embedded = List.of(Target.DELOS_HEAP, Target.DELOS_MVCC, Target.UPSTREAM_DERBY, Target.H2);
            List<Target> container = List.of(
                    Target.DELOS_HEAP_DRDA, Target.DELOS_MVCC_DRDA, Target.POSTGRESQL, Target.MARIADB);
            if (!configuredTargets.equals(embedded) && !configuredTargets.equals(container)) {
                throw new IllegalArgumentException("targets must be exactly " + embedded + " or " + container
                        + ": " + configuredTargets);
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
            if (target == null && !clientValues().contains(1)) {
                throw new IllegalArgumentException("clients must include 1 for scaling ratios");
            }
            if (transactionsPerClient < 1 || payload < 16 || fixtureBatch < 1 || warmups < 0
                    || iterations < 1 || caseTimeoutSeconds < 1 || containerStartupTimeoutSeconds < 1) {
                throw new IllegalArgumentException("Invalid concurrency benchmark numeric option");
            }
            if (runs < 4 || (runs & 3) != 0) {
                throw new IllegalArgumentException("runs must be a multiple of 4 for orthogonal order");
            }
            if (childHeap.isBlank()) {
                throw new IllegalArgumentException("childHeap is required");
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
