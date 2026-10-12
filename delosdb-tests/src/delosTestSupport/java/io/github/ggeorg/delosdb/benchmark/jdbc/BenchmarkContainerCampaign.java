/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ServerEndpoint;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkParentCommand.CommandResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseMeasurement.connect;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.btreeInsertBranchRoutingSnapshotServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.btreeInsertRootRoutingSnapshotServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaServerPhaseCaptureOpenQueries;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaServerPhaseEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaServerPhaseSkipOpenQueries;
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
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.rawStoreDurabilityTestNoSyncServerEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.rawStoreLogBufferSizeServerOverride;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkParentCommand.runCommand;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeContainerManifest;

/** Parent container lifecycle, readiness and server profiling. */
final class BenchmarkContainerCampaign {
    private BenchmarkContainerCampaign() {
    }

    static boolean shouldProfileServer(Target target) {
        String configuredTargets = System.getProperty(PREFIX + "profileServerTargets", "").trim();
        if (configuredTargets.isEmpty()) {
            return false;
        }
        for (String configured : configuredTargets.split(",")) {
            if (target.id().equalsIgnoreCase(configured.trim())) {
                return true;
            }
        }
        return false;
    }

    static final String SERVER_PROFILE_MONITOR_SETTINGS =
            "server-profile-monitor.jfc";

    static void addServerProfileMount(
            BenchmarkConfiguration options, Target target, List<String> command) throws Exception {
        if (!shouldProfileServer(target)) {
            return;
        }
        Path profileDirectory = options.reportDirectory().resolve("workers").resolve("server-jfr")
                .toAbsolutePath().normalize();
        Files.createDirectories(profileDirectory);
        prepareServerProfileMonitorSettings(options, profileDirectory);
        command.add("--mount");
        command.add("type=bind,src=" + profileDirectory + ",dst=/opt/delos-jfr");
    }

    static void prepareServerProfileMonitorSettings(
            BenchmarkConfiguration options, Path profileDirectory) throws Exception {
        String threshold = serverProfileJavaMonitorEnterThreshold();
        if (threshold.isEmpty()) {
            return;
        }
        Path settings = profileDirectory.resolve(SERVER_PROFILE_MONITOR_SETTINGS);
        if (Files.isRegularFile(settings)) {
            return;
        }
        Path jfrTool = options.javaExecutable().getParent().resolve("jfr");
        if (!Files.isRegularFile(jfrTool)) {
            throw new IllegalStateException("JFR tool does not exist: " + jfrTool);
        }
        CommandResult configured = runCommand(30, List.of(
                jfrTool.toString(), "configure", "--input", "profile.jfc",
                "jdk.JavaMonitorEnter#threshold=" + threshold,
                "--output", settings.toString()));
        if (configured.exitCode() != 0 || !Files.isRegularFile(settings)) {
            throw new IllegalStateException(
                    "Could not generate server JFR monitor settings:\n" + configured.output());
        }
    }

    static String serverProfileJavaMonitorEnterThreshold() {
        return System.getProperty(
                PREFIX + "profileServerJavaMonitorEnterThreshold", "").trim();
    }

    static void addServerProfileJvmArgs(
            Target target, int run, List<String> javaCommand) {
        if (!shouldProfileServer(target)) {
            return;
        }
        String recording = String.format(
                Locale.ROOT, "/opt/delos-jfr/%02d-%s-server.jfr", run, target.id());
        String threshold = serverProfileJavaMonitorEnterThreshold();
        String settings = threshold.isEmpty()
                ? "profile"
                : "/opt/delos-jfr/" + SERVER_PROFILE_MONITOR_SETTINGS;
        javaCommand.add("-XX:FlightRecorderOptions=stackdepth=256");
        javaCommand.add("-XX:StartFlightRecording=filename=" + recording
                + ",settings=" + settings + ",dumponexit=true,maxsize=512m");
    }

    static void addRawStoreLogControlJvmArgs(List<String> javaCommand) {
        String logBufferSize = rawStoreLogBufferSizeServerOverride();
        if (!logBufferSize.isEmpty()) {
            javaCommand.add("-Dderby.storage.logBufferSize=" + logBufferSize);
        }
        if (rawStoreDurabilityTestNoSyncServerEnabled()) {
            javaCommand.add("-Dderby.system.durability=test");
        }
    }

    static void prepareContainerEnvironment(BenchmarkConfiguration options) throws Exception {
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

    static String ensureImage(BenchmarkConfiguration options, String image) throws Exception {
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

    static ContainerServer startContainer(BenchmarkConfiguration options, Target target, int run) throws Exception {
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
                addServerProfileMount(options, target, command);
                command.add(target.containerImage(options));
                List<String> javaCommand = new ArrayList<>(List.of(
                        "java", "-Xms" + options.childHeap(), "-Xmx" + options.childHeap(),
                        "-XX:+AlwaysPreTouch"));
                addServerProfileJvmArgs(target, run, javaCommand);
                addRawStoreLogControlJvmArgs(javaCommand);
                if (btreeInsertRootRoutingSnapshotServerEnabled()) {
                    javaCommand.add(
                            "-Ddelosdb.experimental.btreeInsertRootRoutingSnapshot.enabled=true");
                }
                if (btreeInsertBranchRoutingSnapshotServerEnabled()) {
                    javaCommand.add(
                            "-Ddelosdb.experimental.btreeInsertBranchRoutingSnapshot.enabled=true");
                }
                if (Boolean.getBoolean("derby.storage.indexStats.debug.keepDisposableStats")) {
                    javaCommand.add("-Dderby.storage.indexStats.debug.keepDisposableStats=true");
                }
                if (target == Target.DELOS_HEAP_DRDA) {
                    javaCommand.add("-Ddelosdb.experimental.heapPageReadImage=true");
                    javaCommand.add("-Ddelosdb.experimental.fastRecordReadLock=true");
                }
                if (target == Target.DELOS_MVCC_DRDA
                        && Boolean.getBoolean(PREFIX + "mvccBaseFetchPagePrefetch")) {
                    javaCommand.add("-Ddelosdb.experimental.mvccBaseFetchPagePrefetch=true");
                }
                if (target == Target.DELOS_MVCC_DRDA && mvccGen2A1ServerEnabled()) {
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2A1.enabled=true");
                }
                if (target == Target.DELOS_MVCC_DRDA && mvccGen2BServerEnabled()) {
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2B.pk.enabled=true");
                }
                if (target == Target.DELOS_MVCC_DRDA
                        && mvccGen2TransactionStatusVisibilityServerEnabled()) {
                    javaCommand.add(
                            "-Ddelosdb.experimental.mvccGen2TransactionStatusVisibility.enabled=true");
                }
                if (target == Target.DELOS_MVCC_DRDA && mvccGen2C1ServerEnabled()) {
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2C1.history.enabled=true");
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2C2.archivedUndo.enabled=true");
                }
                if (target == Target.DELOS_MVCC_DRDA
                        && (mvccGen2C3UpdateServerEnabled() || mvccGen2C3ReadServerEnabled())) {
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2B.pk.enabled=true");
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2C1.history.enabled=true");
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2C2.archivedUndo.enabled=true");
                }
                if (target == Target.DELOS_MVCC_DRDA && mvccGen2ProjectedCurrentReadEnabled()) {
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2ProjectedCurrentRead.enabled=true");
                }
                if (target == Target.DELOS_MVCC_DRDA) {
                    javaCommand.add("-Ddelosdb.experimental.mvccPhysicalScanCost.enabled="
                            + mvccPhysicalScanCostEnabled());
                    javaCommand.add("-Ddelosdb.experimental.mvccPhysicalRowLocationCost.enabled="
                            + mvccPhysicalRowLocationCostEnabled());
                    javaCommand.add("-Ddelosdb.experimental.mvccGen2SinglePassCurrentScan.enabled="
                            + mvccGen2SinglePassCurrentScanEnabled());
                }
                if (drdaServerPhaseEvidenceEnabled()) {
                    javaCommand.add("-Ddelosdb.diagnostic.drdaServerPhaseEvidence=true");
                    javaCommand.add("-Ddelosdb.diagnostic.drdaServerPhaseEvidence.skipOpenQueries="
                            + drdaServerPhaseSkipOpenQueries());
                    javaCommand.add("-Ddelosdb.diagnostic.drdaServerPhaseEvidence.captureOpenQueries="
                            + drdaServerPhaseCaptureOpenQueries());
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
                addServerProfileMount(options, target, command);
                command.add(target.containerImage(options));
                List<String> javaCommand = new ArrayList<>(List.of(
                        "java", "-Xms" + options.childHeap(), "-Xmx" + options.childHeap(),
                        "-XX:+AlwaysPreTouch"));
                addServerProfileJvmArgs(target, run, javaCommand);
                addRawStoreLogControlJvmArgs(javaCommand);
                javaCommand.addAll(List.of(
                        "-cp", "/opt/derby/lib/*",
                        "org.apache.derby.drda.NetworkServerControl", "start",
                        "-h", "0.0.0.0", "-p", Integer.toString(target.containerPort())));
                command.addAll(javaCommand);
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
        ContainerServer server = new ContainerServer(
                name, endpoint, serverEvidenceLog, shouldProfileServer(target));
        try {
            awaitReady(options, target, endpoint);
            return server;
        } catch (Throwable failure) {
            failure.addSuppressed(new IllegalStateException("Container log:\n" + server.logs()));
            server.close();
            throw failure;
        }
    }

    static void awaitReady(BenchmarkConfiguration options, Target target, ServerEndpoint endpoint) throws Exception {
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

    static Connection connect(ServerEndpoint endpoint, Target target) throws SQLException {
        Connection connection = endpoint.user().isBlank()
                ? DriverManager.getConnection(endpoint.jdbcUrl())
                : DriverManager.getConnection(endpoint.jdbcUrl(), endpoint.user(), endpoint.password());
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }

    static int publishedPort(String name, int containerPort) throws Exception {
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

    static final class ContainerServer implements AutoCloseable {
        private final String name;
        private final ServerEndpoint endpoint;
        private final Path capturedLog;
        private final boolean gracefulShutdown;
        private boolean closed;

        private ContainerServer(
                String name, ServerEndpoint endpoint, Path capturedLog, boolean gracefulShutdown) {
            this.name = name;
            this.endpoint = endpoint;
            this.capturedLog = capturedLog;
            this.gracefulShutdown = gracefulShutdown;
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
                if (gracefulShutdown) {
                    CommandResult stopped = runCommand(30,
                            List.of("docker", "stop", "--time", "15", name));
                    if (stopped.exitCode() == 0) {
                        return;
                    }
                }
                runCommand(30, List.of("docker", "rm", "-f", name));
            } catch (Exception ignored) {
            }
        }
    }
}
