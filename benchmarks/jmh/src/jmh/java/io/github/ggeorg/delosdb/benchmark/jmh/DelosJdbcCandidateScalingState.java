/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jmh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Adds candidate-count scaling and one unmeasured runtime-statistics snapshot per trial. */
@State(Scope.Thread)
public class DelosJdbcCandidateScalingState extends DelosJdbcJmhState {
    private static final String DIAGNOSTICS_DIRECTORY_PROPERTY =
            "delosdb.jmh.candidateDiagnosticsDirectory";
    private static final String[] MVCC_METRICS = {
        "mvccOrderedCandidates",
        "mvccCoveringCandidates",
        "mvccCoveredCandidates",
        "mvccFallbackCandidates",
        "mvccDirectoryPageAcquisitions",
        "mvccDirectoryLogicalFallbacks",
        "mvccDirectoryHeadSummaryChecks",
        "mvccDirectoryHeadSummaryHits",
        "mvccDirectoryHeadSummaryFallbacks",
        "mvccVersionPageAcquisitions",
        "mvccVersionSlotFetches",
        "mvccVisibilityChecks",
        "mvccVersionChainSteps",
        "mvccVersionLogicalFallbacks"
    };

    @Param({"1", "4", "16", "64", "256"})
    public int candidateCount;

    @Override
    @Setup(Level.Trial)
    public void setup() throws Exception {
        super.setup();
        try {
            captureCandidateDiagnostics();
        } catch (Exception | Error failure) {
            try {
                super.tearDown();
            } catch (Exception | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void captureCandidateDiagnostics() throws SQLException, IOException {
        if (candidateCount < 1 || candidateCount > rows) {
            throw new IllegalArgumentException(
                    "candidateCount must be between 1 and rows: " + candidateCount);
        }
        String statistics = candidateRangeRuntimeStatistics(candidateCount);
        Map<String, Long> metrics = parseMetrics(statistics);
        if ("mvcc".equals(provider)) {
            requireMetric(metrics, "mvccOrderedCandidates", candidateCount);
            requireMetric(metrics, "mvccCoveringCandidates", candidateCount);
            requireMetric(metrics, "mvccCoveredCandidates", candidateCount);
            requireMetric(metrics, "mvccFallbackCandidates", 0L);
            requireMetric(metrics, "mvccDirectoryPageAcquisitions", candidateCount);
            requireMetric(metrics, "mvccDirectoryLogicalFallbacks", 0L);
            requireMetric(metrics, "mvccDirectoryHeadSummaryChecks", candidateCount);
            requireMetric(metrics, "mvccDirectoryHeadSummaryHits", candidateCount);
            requireMetric(metrics, "mvccDirectoryHeadSummaryFallbacks", 0L);
            requireMetric(metrics, "mvccVersionPageAcquisitions", 0L);
            requireMetric(metrics, "mvccVersionSlotFetches", 0L);
            requireMetric(metrics, "mvccVisibilityChecks", candidateCount);
            requireMetric(metrics, "mvccVersionChainSteps", 0L);
            requireMetric(metrics, "mvccVersionLogicalFallbacks", 0L);
        }
        writeDiagnostics(statistics, metrics);
    }

    long coveredCountByCandidateCount() throws SQLException {
        return candidateRangeCoveredCount(candidateCount);
    }

    private static Map<String, Long> parseMetrics(String statistics) {
        Map<String, Long> metrics = new LinkedHashMap<>();
        for (String name : MVCC_METRICS) {
            Matcher matcher = Pattern.compile(
                    "(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*(\\d+)\\s*$")
                    .matcher(statistics);
            if (matcher.find()) {
                metrics.put(name, Long.parseLong(matcher.group(1)));
            }
        }
        return metrics;
    }

    private static void requireMetric(Map<String, Long> metrics, String name, long expected) {
        Long actual = metrics.get(name);
        if (actual == null || actual.longValue() != expected) {
            throw new IllegalStateException(
                    "Candidate diagnostic " + name + " expected " + expected + " but found " + actual);
        }
    }

    private void writeDiagnostics(String statistics, Map<String, Long> metrics) throws IOException {
        String configuredDirectory = System.getProperty(DIAGNOSTICS_DIRECTORY_PROPERTY);
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            throw new IllegalStateException(
                    "Missing system property " + DIAGNOSTICS_DIRECTORY_PROPERTY);
        }
        Path directory = Path.of(configuredDirectory);
        Files.createDirectories(directory);
        Path report = directory.resolve(String.format(
                java.util.Locale.ROOT,
                "%s-rows-%d-payload-%d-batch-%d-candidates-%d.txt",
                provider,
                rows,
                payloadSize,
                commitBatchSize,
                candidateCount));
        StringBuilder output = new StringBuilder();
        output.append("provider=").append(provider).append('\n');
        output.append("rows=").append(rows).append('\n');
        output.append("payloadSize=").append(payloadSize).append('\n');
        output.append("commitBatchSize=").append(commitBatchSize).append('\n');
        output.append("candidateCount=").append(candidateCount).append('\n');
        for (String name : MVCC_METRICS) {
            output.append(name).append('=').append(metrics.getOrDefault(name, -1L)).append('\n');
        }
        output.append("runtimeStatisticsBegin\n");
        output.append(statistics);
        if (!statistics.endsWith("\n")) {
            output.append('\n');
        }
        output.append("runtimeStatisticsEnd\n");
        Files.writeString(report, output, StandardCharsets.UTF_8);
    }
}
