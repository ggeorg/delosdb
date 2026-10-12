/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkParentCommand.CommandResult;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostProcessSampleSeconds;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateCooldownAfterRun;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateCooldownMaximumCpuLoad;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateCooldownMaximumLoadPerProcessor;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateCooldownMaximumSeconds;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateCooldownMinimumSeconds;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateCooldownQuietSamples;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateCooldownSampleSeconds;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkParentCommand.runCommand;

/** Parent host/process observation and cooldown policy. */
final class BenchmarkHostObservation {
    private BenchmarkHostObservation() {
    }

    static void writeHostStateDiagnosticHeader(BenchmarkConfiguration options) throws IOException {
        Files.writeString(
                options.reportDirectory().resolve("host-state-diagnostics.tsv"),
                "capturedAtUtc\tstage\trun\ttarget\tsystemLoadAverage\tsystemCpuLoad"
                        + "\tavailableProcessors\tfreeMemoryBytes\ttotalMemoryBytes"
                        + "\tpmsetTherm\tpmsetSysload\tpmsetBattery\n",
                StandardCharsets.UTF_8);
    }

    static HostState captureHostState(
            BenchmarkConfiguration options, String stage, int run, String target) {
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

    static HostState currentHostState() {
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

    static Number operatingSystemNumber(String... attributeNames) {
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

    static void writeHostProcessDiagnosticHeader(BenchmarkConfiguration options) throws IOException {
        Files.writeString(
                options.reportDirectory().resolve("host-process-diagnostics.tsv"),
                "capturedAtUtc\tstage\trun\ttarget\tworkerPid\tworkerAlive\tworkerCpuSeconds"
                        + "\tsystemLoadAverage\tsystemCpuLoad\tavailableProcessors"
                        + "\tfreeMemoryBytes\ttotalMemoryBytes\ttopProcesses\tvmStat\tswapUsage\n",
                StandardCharsets.UTF_8);
    }

    static Thread startHostProcessSampler(
            BenchmarkConfiguration options, Target target, int run, Process process) {
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

    static void stopHostProcessSampler(Thread sampler) {
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

    static void captureHostProcessSample(
            BenchmarkConfiguration options, String stage, int run, String target, Process worker) {
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

    static String topProcessSnapshot() {
        try {
            boolean mac = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("mac");
            String executable = RawStoreStabilityDiagnostics.enabled() ? "comm=" : "command=";
            List<String> command = mac
                    ? List.of("ps", "-A", "-r", "-o",
                            "pid=,ppid=,%cpu=,%mem=,state=,etime=," + executable)
                    : List.of("ps", "-eo",
                            "pid=,ppid=,%cpu=,%mem=,stat=,etime=," + executable, "--sort=-%cpu");
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

    static String vmStat() {
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

    static String swapUsage() {
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

    static void writeHostRecoveryConfiguration(BenchmarkConfiguration options) throws IOException {
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

    static void coolDownHost(BenchmarkConfiguration options, int run) {
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

    static String pmset(String argument) {
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

    static String tsvField(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    record HostState(
            double systemLoadAverage,
            double systemCpuLoad,
            int availableProcessors,
            long freeMemoryBytes,
            long totalMemoryBytes) {
    }
}
