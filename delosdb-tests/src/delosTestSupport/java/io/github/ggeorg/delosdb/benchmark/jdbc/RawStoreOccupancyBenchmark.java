/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Bounded F08 occupancy evidence adapter; upstream Derby remains uninstrumented. */
final class RawStoreOccupancyBenchmark {
    private static final String PREFIX = "delosdb.benchmark.crossEngineConcurrency.";
    private RawStoreOccupancyBenchmark() {}

    static boolean campaign() {
        return Boolean.getBoolean(PREFIX + "rawStoreOccupancyCampaign");
    }

    static boolean enabledForRun(int run, String mode) {
        return "paired".equals(mode) ? ((run - 1) & 3) == 1 || ((run - 1) & 3) == 2
                : Boolean.parseBoolean(mode);
    }

    private static boolean enabled() {
        return campaign() && Boolean.getBoolean("delosdb.diagnostic.rawStoreOccupancy.enabled");
    }

    static void validate(List<String> targets, List<Integer> clients, List<String> workloads,
            String shape, int warmups, int iterations) {
        if (!targets.equals(List.of("delos_heap", "upstream_derby"))
                || !(clients.equals(List.of(4, 8)) || clients.equals(List.of(4)))
                || !workloads.equals(List.of("INSERT_100"))
                || !List.of("BARE", "PRIMARY_KEY_ONLY").contains(shape)
                || warmups != 0 || iterations != 1) {
            throw new IllegalArgumentException("Occupancy campaign requires Heap/Derby, clients=4,8, "
                    + "INSERT_100, BARE or PRIMARY_KEY_ONLY and one fresh interval");
        }
    }

    static void reset() throws ReflectiveOperationException {
        RawStoreStabilityDiagnostics.beforeMeasurement();
        if (enabled()) support().getMethod("reset").invoke(null);
    }

    static void write(Path directory, String target, int clients, int run,
            int transactionsPerClient, int operationsPerTransaction, long elapsed) throws Exception {
        if (!campaign()) return;
        String stem = "occupancy-" + target + "-" + clients + "-run-" + run;
        long rows = (long) clients * transactionsPerClient * operationsPerTransaction;
        RawStoreStabilityDiagnostics.writeWindow(directory, stem, elapsed, rows);
        Files.writeString(directory.resolve(stem + "-window.csv"),
                "target,clients,inserted_rows,elapsed_ns,ledger_enabled,calibration_mode\n"
                        + target + "," + clients + "," + rows + "," + elapsed + "," + enabled() + ","
                        + System.getProperty(PREFIX + "rawStoreOccupancyMode", "false") + "\n",
                StandardCharsets.UTF_8);
        if (enabled()) {
            String[] report = (String[]) support().getMethod("snapshot").invoke(null);
            Files.write(directory.resolve(stem + ".csv"), List.of(report), StandardCharsets.UTF_8);
        }
    }

    private static Class<?> support() throws ClassNotFoundException {
        return Class.forName("org.apache.derby.impl.store.raw.data.RawStoreOccupancy");
    }
}
