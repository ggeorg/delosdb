/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Window-level JVM diagnostics, outside timed transactions and store ownership. */
public final class RawStoreStabilityDiagnostics {
    private static final String PROPERTY =
            "delosdb.benchmark.crossEngineConcurrency.rawStoreStabilityDiagnostics";
    private static Snapshot before;
    private static long measurementStart;

    private RawStoreStabilityDiagnostics() {}

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static void configureWorker(List<String> command, Path reportDirectory,
            String target, int run) throws IOException {
        if (!enabled()) return;
        Path profiles = reportDirectory.resolve("profiles");
        Files.createDirectories(profiles);
        Path log = profiles.resolve("jvm-" + run + "-" + target + ".log").toAbsolutePath();
        command.add("-D" + PROPERTY + "=true");
        command.add("-Xlog:async");
        command.add("-Xlog:gc*=info,safepoint=info,jit+compilation=debug,logging=warning:file="
                + log + ":uptime,level,tags:filecount=0");
    }

    /** Entry guard only; it never changes or interrupts unrelated host work. */
    public static void awaitQuietHost(Path directory, String target, int run)
            throws IOException, InterruptedException {
        if (!Boolean.getBoolean(
                "delosdb.benchmark.crossEngineConcurrency.rawStoreQuietGuard")) return;
        Path output = directory.resolve("quiet-host-guard.csv");
        if (!Files.exists(output)) {
            Files.writeString(output, "captured_utc,target,run,cpu_load,load_average,free_memory,processors,quiet_samples\n");
        }
        long deadline = System.nanoTime() + 90_000_000_000L;
        int consecutive = 0;
        while (System.nanoTime() < deadline) {
            Number cpu = operatingSystemNumber("CpuLoad");
            double cpuLoad = cpu == null ? -1.0 : cpu.doubleValue();
            var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
            double load = operatingSystem.getSystemLoadAverage();
            int processors = operatingSystem.getAvailableProcessors();
            long memory = operatingSystemAttribute("FreeMemorySize");
            consecutive = quietEnough(cpuLoad, load, processors, memory) ? consecutive + 1 : 0;
            Files.writeString(output, Instant.now() + "," + target + "," + run + "," + cpuLoad
                    + "," + load + "," + memory + "," + processors + "," + consecutive + "\n",
                    java.nio.file.StandardOpenOption.APPEND);
            if (consecutive >= 3) return;
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Host did not meet quiet-entry requirements within 90 seconds for "
                + target + " run " + run + "; no worker launched for this attempt. See " + output);
    }

    public static boolean quietEnough(double cpuLoad, double load, int processors, long freeMemory) {
        return cpuLoad >= 0.0 && cpuLoad <= 0.20 && processors > 0
                && load >= 0.0 && load <= processors * 0.5
                && freeMemory >= 2L * 1024 * 1024 * 1024;
    }

    public static void initialize() {
        if (enabled()) Snapshot.capture();
    }

    public static void beforeMeasurement() {
        if (!enabled()) return;
        before = Snapshot.capture();
        measurementStart = 0L;
    }

    public static void markStart(long started) {
        if (enabled()) measurementStart = started;
    }

    public static void writeWindow(Path directory, String stem, long elapsed, long rows)
            throws IOException {
        if (!enabled()) return;
        if (before == null || measurementStart == 0L) {
            throw new IllegalStateException("Missing JVM diagnostic measurement boundary");
        }
        Snapshot after = Snapshot.capture();
        double startUptime = before.uptimeMillis()
                + (measurementStart - before.nanoTime()) / 1_000_000.0;
        double endUptime = startUptime + elapsed / 1_000_000.0;
        String header = "inserted_rows,elapsed_ns,start_uptime_ms,end_uptime_ms,"
                + "boundary_alignment_tolerance_ms,process_cpu_ns,compilation_ms,"
                + "gc_collections,gc_collection_ms,free_memory_before,free_memory_after,"
                + "free_swap_before,free_swap_after,available_processors,captured_before_utc,captured_after_utc\n";
        String data = rows + "," + elapsed + "," + startUptime + "," + endUptime + ",2,"
                + delta(before.processCpu(), after.processCpu()) + ","
                + delta(before.compilationMillis(), after.compilationMillis()) + ","
                + delta(before.collections(), after.collections()) + ","
                + delta(before.collectionMillis(), after.collectionMillis()) + ","
                + before.freeMemory() + "," + after.freeMemory() + ","
                + before.freeSwap() + "," + after.freeSwap() + ","
                + ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors() + ","
                + before.utc() + "," + after.utc() + "\n";
        Files.writeString(directory.resolve(stem + "-jvm.csv"), header + data,
                StandardCharsets.UTF_8);
        before = null;
        measurementStart = 0L;
    }

    private static long delta(long begin, long end) {
        return begin < 0L || end < begin ? -1L : end - begin;
    }

    private static long operatingSystemAttribute(String name) {
        Number number = operatingSystemNumber(name);
        return number == null ? -1L : number.longValue();
    }

    private static Number operatingSystemNumber(String name) {
        try {
            var server = ManagementFactory.getPlatformMBeanServer();
            var object = new javax.management.ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
            Object value = server.getAttribute(object, name);
            return value instanceof Number number ? number : null;
        } catch (javax.management.JMException unavailable) {
            return null;
        }
    }

    private record Snapshot(long nanoTime, long uptimeMillis, Instant utc,
            long processCpu, long compilationMillis, long collections, long collectionMillis,
            long freeMemory, long freeSwap) {
        static Snapshot capture() {
            var compilation = ManagementFactory.getCompilationMXBean();
            long compileMillis = compilation != null && compilation.isCompilationTimeMonitoringSupported()
                    ? compilation.getTotalCompilationTime() : -1L;
            long collections = 0L;
            long collectionMillis = 0L;
            for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (collector.getCollectionCount() >= 0) collections += collector.getCollectionCount();
                if (collector.getCollectionTime() >= 0) collectionMillis += collector.getCollectionTime();
            }
            long processCpu = operatingSystemAttribute("ProcessCpuTime");
            long freeMemory = operatingSystemAttribute("FreeMemorySize");
            long freeSwap = operatingSystemAttribute("FreeSwapSpaceSize");
            long nanoTime = System.nanoTime();
            long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
            return new Snapshot(nanoTime, uptime, Instant.now(), processCpu, compileMillis,
                    collections, collectionMillis, freeMemory, freeSwap);
        }
    }
}
