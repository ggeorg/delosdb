/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Forked benchmark JVM execution, timeout termination and failure evidence. */
record BenchmarkWorkerProcess(List<String> command, Path directory, Path log,
                              int timeoutSeconds, String target, int run) {
    BenchmarkWorkerProcess {
        command = List.copyOf(command);
    }

    /**
     * Start optional observation after launch and stop it when waiting ends.
     * The observer supplies its cleanup action; workload and host-sampling
     * policy stay with their respective owners. Interruption propagates.
     */
    void execute(Function<Process, Runnable> observe) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        Runnable stopObservation = observe.apply(process);
        boolean completed;
        try {
            completed = timeoutSeconds == 0
                    ? waitForUnbounded(process)
                    : process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
                throw new IllegalStateException("Concurrency worker timed out: target=" + target
                        + ", run=" + run + ", workerTimeoutSeconds=" + timeoutSeconds
                        + ", log=" + log);
            }
        } finally {
            stopObservation.run();
        }
        int status = process.exitValue();
        if (status != 0) {
            List<String> lines = Files.exists(log) ? Files.readAllLines(log) : List.of();
            int from = Math.max(0, lines.size() - 40);
            throw new IllegalStateException("Concurrency worker failed: target=" + target
                    + ", run=" + run + ", exit=" + status + ", log=" + log
                    + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines.subList(from, lines.size()))));
        }
    }

    private static boolean waitForUnbounded(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }
}
