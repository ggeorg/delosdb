/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Measurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.OracleEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DrdaProtocolEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.MeasuredSpec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.SEED;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.CSV_HEADER;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ORACLE_CSV_HEADER;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DRDA_PROTOCOL_CSV_HEADER;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.sqlSemanticOracleEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaProtocolEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseMeasurement.measureSpec;

/** Child worker scheduling, progress, result collection and complete report publication. */
final class BenchmarkWorkloadRunner {
    private BenchmarkWorkloadRunner() {
    }

    static void run(BenchmarkConfiguration options) throws Exception {
        RawStoreStabilityDiagnostics.initialize();
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

    static List<Spec> specsForRun(BenchmarkConfiguration options) {
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

    static int specsPerRun(BenchmarkConfiguration options) {
        int perClient = 0;
        for (Workload workload : options.workloadValues()) {
            perClient += workload.fixedOperationsPerTransaction() >= 0 ? 1 : options.widthValues().size();
        }
        return Math.multiplyExact(options.clientValues().size(), perClient);
    }

    static List<Integer> ordered(List<Integer> values, int run) {
        List<Integer> ordered = new ArrayList<>(values);
        if (((run - 1) & 1) != 0) {
            Collections.reverse(ordered);
        }
        return ordered;
    }

    static void writeWorkerCsv(BenchmarkConfiguration options, List<Measurement> values) throws IOException {
        Path output = options.reportDirectory().resolve(options.target().id() + "-run-" + options.run() + ".csv");
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Measurement value : values) {
            out.append(value.csv()).append('\n');
        }
        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
    }

    static void writeWorkerOracleEvidence(
            BenchmarkConfiguration options,
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

    static void writeWorkerDrdaProtocolEvidence(
            BenchmarkConfiguration options,
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

    static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected concurrency benchmark failure", failure);
    }

}
