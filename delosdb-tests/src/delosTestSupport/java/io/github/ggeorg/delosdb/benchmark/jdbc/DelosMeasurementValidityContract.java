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
import java.time.Instant;
import java.util.Locale;

/** Phase 0C benchmark-validity policy shared by DelosDB fitness measurements. */
public final class DelosMeasurementValidityContract {
    public static final double HEALTHY_DISPERSION_LIMIT = 0.05d;
    public static final double INVALID_DISPERSION_LIMIT = 0.15d;
    public static final double INVALID_CANARY_DRIFT_LIMIT = 0.20d;
    public static final int MINIMUM_BASELINE_CANARY_RUNS = 8;
    public static final long MINIMUM_BASELINE_CANARY_ELAPSED_NANOS = 1_000_000_000L;

    private static final String PREFIX = "delosdb.benchmark.measurementValidity.";

    public enum Status {
        VALID,
        NOISY,
        INVALID;

        static Status worst(Status left, Status right) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }

    public record DispersionDecision(
            Status status,
            double iqrToMedian,
            double madToMedian,
            double governingRatio) {
    }

    public record JmhDecision(Status status, double score, double scoreError, double scoreErrorToScore) {
    }

    public record CanaryDecision(Status status, double baseline, double current, double absoluteDriftRatio) {
    }

    public record CanarySampleDecision(Status status, int runs, long minElapsedNanos) {
    }

    private DelosMeasurementValidityContract() {
    }

    public static DispersionDecision classifyCustom(double iqrToMedian, double madToMedian) {
        requireFiniteNonNegative(iqrToMedian, "iqrToMedian");
        requireFiniteNonNegative(madToMedian, "madToMedian");
        double governing = Math.max(iqrToMedian, madToMedian);
        return new DispersionDecision(classifyRatio(governing), iqrToMedian, madToMedian, governing);
    }

    public static JmhDecision classifyJmh(double score, double scoreError) {
        if (!Double.isFinite(score) || score == 0.0d) {
            return new JmhDecision(Status.INVALID, score, scoreError, Double.POSITIVE_INFINITY);
        }
        requireFiniteNonNegative(scoreError, "scoreError");
        double ratio = scoreError / Math.abs(score);
        return new JmhDecision(classifyRatio(ratio), score, scoreError, ratio);
    }

    public static CanaryDecision classifyCanary(double baseline, double current) {
        if (!Double.isFinite(baseline) || baseline <= 0.0d
                || !Double.isFinite(current) || current <= 0.0d) {
            return new CanaryDecision(Status.INVALID, baseline, current, Double.POSITIVE_INFINITY);
        }
        double drift = Math.abs(current - baseline) / baseline;
        return new CanaryDecision(
                drift > INVALID_CANARY_DRIFT_LIMIT ? Status.INVALID : Status.VALID,
                baseline,
                current,
                drift);
    }

    public static CanarySampleDecision classifyCanarySample(int runs, long minElapsedNanos) {
        Status status = runs >= MINIMUM_BASELINE_CANARY_RUNS
                        && minElapsedNanos >= MINIMUM_BASELINE_CANARY_ELAPSED_NANOS
                ? Status.VALID
                : Status.INVALID;
        return new CanarySampleDecision(status, runs, minElapsedNanos);
    }

    public static Status combine(Status... values) {
        Status result = Status.VALID;
        for (Status value : values) {
            if (value == null) {
                return Status.INVALID;
            }
            result = Status.worst(result, value);
        }
        return result;
    }

    private static Status classifyRatio(double ratio) {
        if (ratio > INVALID_DISPERSION_LIMIT) {
            return Status.INVALID;
        }
        if (ratio > HEALTHY_DISPERSION_LIMIT) {
            return Status.NOISY;
        }
        return Status.VALID;
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0d) {
            throw new IllegalArgumentException(name + " must be finite and non-negative: " + value);
        }
    }

    public static void main(String[] args) throws Exception {
        Path reportDirectory = Path.of(required(PREFIX + "reportDirectory"));
        Files.createDirectories(reportDirectory);
        selfValidate();
        writeReport(reportDirectory);
        System.out.println("DelosDB measurement validity contract passed: "
                + reportDirectory.resolve("measurement-validity-contract.txt"));
    }

    private static void selfValidate() {
        require(Status.VALID, classifyCustom(0.03d, 0.02d).status(), "healthy custom dispersion");
        require(Status.NOISY, classifyCustom(0.08d, 0.04d).status(), "noisy custom dispersion");
        require(Status.INVALID, classifyCustom(0.16d, 0.01d).status(), "invalid custom dispersion");

        require(Status.VALID, classifyJmh(100.0d, 4.0d).status(), "healthy JMH dispersion");
        require(Status.NOISY, classifyJmh(100.0d, 10.0d).status(), "noisy JMH dispersion");
        require(Status.INVALID, classifyJmh(100.0d, 16.0d).status(), "invalid JMH dispersion");
        require(Status.INVALID, classifyJmh(0.0d, 0.0d).status(), "zero-score JMH invalidation");

        require(Status.VALID, classifyCanary(100.0d, 119.0d).status(), "19 percent canary drift");
        require(Status.INVALID, classifyCanary(100.0d, 121.0d).status(), "21 percent canary drift");
        require(Status.INVALID, classifyCanary(0.0d, 100.0d).status(), "invalid canary baseline");

        require(Status.VALID, classifyCanarySample(8, 1_000_000_000L).status(), "adequate canary sample");
        require(Status.INVALID, classifyCanarySample(7, 1_000_000_000L).status(), "too few canary runs");
        require(Status.INVALID, classifyCanarySample(8, 999_999_999L).status(), "canary run too short");

        require(Status.INVALID, combine(Status.VALID, Status.NOISY, Status.INVALID), "status composition");
    }

    private static void require(Status expected, Status actual, String label) {
        if (expected != actual) {
            throw new IllegalStateException(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void writeReport(Path reportDirectory) throws IOException {
        String text = """
                DelosDB v1 measurement validity contract
                ========================================

                Phase 0C policy:
                - Frozen reference-engine canaries detect environmental drift.
                - A canary moving by more than 20%% from its accepted baseline invalidates the run.
                - Custom repeated-run benchmarks use max(IQR/median, MAD/median).
                - JMH benchmarks use scoreError/abs(score).
                - Dispersion <= 5%% is VALID.
                - Dispersion > 5%% and <= 15%% is NOISY.
                - Dispersion > 15%% is INVALID.
                - An architectural gain smaller than normal observed dispersion is not decision-quality evidence.
                - Any INVALID component invalidates the experiment.
                - NOISY results may describe only effects materially larger than the observed noise; they are not baseline-quality canaries.
                - Canary baselines are captured from unchanged external engines on the current accepted environment, then frozen explicitly.
                - Baseline canaries require at least 8 independent runs and every measured run must last at least 1 second.
                - Delos Heap/MVCC are never environmental canaries because they are the systems under active change.

                Canary policy:
                embedded/core: upstream Derby, H2, SQLite
                server/product: upstream Derby Network Server, H2 TCP Server, PostgreSQL, MariaDB

                Thresholds:
                healthyDispersionLimit=%.4f
                invalidDispersionLimit=%.4f
                invalidCanaryDriftLimit=%.4f
                minimumBaselineCanaryRuns=%d
                minimumBaselineCanaryElapsedNanos=%d

                Contract self-validation: PASS
                Generated: %s
                JDK: %s
                OS: %s %s %s
                Processors: %d
                """.formatted(
                HEALTHY_DISPERSION_LIMIT,
                INVALID_DISPERSION_LIMIT,
                INVALID_CANARY_DRIFT_LIMIT,
                MINIMUM_BASELINE_CANARY_RUNS,
                MINIMUM_BASELINE_CANARY_ELAPSED_NANOS,
                Instant.now(),
                System.getProperty("java.runtime.version"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        Files.writeString(reportDirectory.resolve("measurement-validity-contract.txt"), text, StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property " + name);
        }
        return value;
    }
}
