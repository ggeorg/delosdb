/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ExplainCapture;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaProtocolEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.highCardGroupTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinDimensionTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinFanoutChildTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinFanoutParentTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinCustomerTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinItemTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinLineTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinOrderTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DRDA_PROTOCOL_COUNTER_COUNT;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.csvSafe;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.capturePhase2NExplain;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.fitnessReadSql;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2BPrimaryKeyIndex;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2UPlanShape;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.writePhase2ACapture;

/** Per-case diagnostic counters, snapshots and evidence files. */
final class BenchmarkCaseDiagnostics {
    private BenchmarkCaseDiagnostics() {
    }

    static void resetDrdaProtocolEvidence() {
        if (!drdaProtocolEvidenceEnabled()) {
            return;
        }
        invokeDrdaProtocolEvidence("reset");
    }

    static void beginDrdaProtocolTimingWindow() {
        if (!drdaProtocolEvidenceEnabled()) {
            return;
        }
        invokeDrdaProtocolEvidence("beginTimingWindow");
    }

    static void endDrdaProtocolTimingWindow() {
        if (!drdaProtocolEvidenceEnabled()) {
            return;
        }
        invokeDrdaProtocolEvidence("endTimingWindow");
    }

    static long[] snapshotDrdaProtocolEvidence() {
        if (!drdaProtocolEvidenceEnabled()) {
            return null;
        }
        Object value = invokeDrdaProtocolEvidence("snapshot");
        if (!(value instanceof long[] snapshot) || snapshot.length != DRDA_PROTOCOL_COUNTER_COUNT) {
            throw new IllegalStateException("Unexpected DRDA protocol evidence snapshot");
        }
        return snapshot;
    }

    static Object invokeDrdaProtocolEvidence(String method) {
        try {
            Class<?> evidence = Class.forName("org.apache.derby.client.net.NetProtocolEvidence");
            return evidence.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("DRDA protocol evidence unavailable: " + method, failure);
        }
    }

    static void addDrdaProtocolEvidence(long[] total, long[] delta) {
        if (total == null || delta == null) {
            return;
        }
        if (total.length != delta.length) {
            throw new IllegalStateException("DRDA protocol evidence shape mismatch");
        }
        for (int index = 0; index < total.length; index++) {
            total[index] = Math.addExact(total[index], delta[index]);
        }
    }

    static boolean mvccSnapshotLeaseDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccSnapshotLeaseDiagnostics");
    }

    static void resetMvccSnapshotLeaseDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.access.mvcc.MvccSnapshotLeaseDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    static long[] snapshotMvccSnapshotLeaseDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.access.mvcc.MvccSnapshotLeaseDiagnosticTestSupport");
        return (long[]) support.getMethod("snapshot").invoke(null);
    }

    static void writeMvccSnapshotLeaseDiagnostics(
            BenchmarkConfiguration options,
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

    static boolean lockEntryDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "lockEntryDiagnostics");
    }

    static void resetLockEntryDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.locks.LockEntryDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    static String[] snapshotLockEntryDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.locks.LockEntryDiagnosticTestSupport");
        return (String[]) support.getMethod("snapshot").invoke(null);
    }

    static void writeLockEntryDiagnostics(
            BenchmarkConfiguration options,
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

    static boolean lockWaitDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "lockWaitDiagnostics");
    }

    static void resetLockWaitDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.locks.LockWaitDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    static String[] snapshotLockWaitDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.locks.LockWaitDiagnosticTestSupport");
        return (String[]) support.getMethod("snapshot").invoke(null);
    }

    static void writeLockWaitDiagnostics(
            BenchmarkConfiguration options,
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

    static boolean cacheEntryDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "cacheEntryDiagnostics");
    }

    static void resetCacheEntryDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.cache.CacheEntryDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    static String[] snapshotCacheEntryDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.services.cache.CacheEntryDiagnosticTestSupport");
        return (String[]) support.getMethod("snapshot").invoke(null);
    }

    static void writeCacheEntryDiagnostics(
            BenchmarkConfiguration options,
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

    static boolean hotStateDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "hotStateDiagnostics");
    }

    static void resetHotStateDiagnostics() throws ReflectiveOperationException {
        Class<?> cacheSupport = Class.forName(
                "org.apache.derby.impl.services.cache.CacheEntryDiagnosticTestSupport");
        cacheSupport.getMethod("resetHotState").invoke(null);
        Class<?> lockSupport = Class.forName(
                "org.apache.derby.impl.services.locks.LockEntryDiagnosticTestSupport");
        lockSupport.getMethod("resetHotState").invoke(null);
    }

    static String[] snapshotHotStateDiagnostics()
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

    static void writeHotStateDiagnostics(
            BenchmarkConfiguration options,
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

    static void writeDetailedPageLatchContentionByPage(
            BenchmarkConfiguration options,
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

    static boolean btreePointReadPathDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "btreePointReadPathDiagnostics");
    }

    static void resetBTreePointReadPathDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.access.btree.BTreePointReadDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    static long[] snapshotBTreePointReadPathDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.access.btree.BTreePointReadDiagnosticTestSupport");
        return (long[]) support.getMethod("snapshot").invoke(null);
    }

    static void writeBTreePointReadPathDiagnostics(
            BenchmarkConfiguration options,
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

    static boolean heapPageReadImageDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "heapPageReadImageDiagnostics");
    }

    static void resetHeapPageReadImageDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.HeapPageReadImageDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    static long[] snapshotHeapPageReadImageDiagnostics()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.HeapPageReadImageDiagnosticTestSupport");
        return (long[]) support.getMethod("snapshot").invoke(null);
    }

    static void writeHeapPageReadImageDiagnostics(
            BenchmarkConfiguration options,
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

    static boolean pageLatchDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "pageLatchDiagnostics");
    }

    static void resetPageLatchDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.PageLatchDiagnosticTestSupport");
        support.getMethod("reset").invoke(null);
    }

    static long[] snapshotPageLatchDiagnostics() throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.PageLatchDiagnosticTestSupport");
        return (long[]) support.getMethod("snapshot").invoke(null);
    }

    static String[] snapshotPageLatchContentionByPage()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.PageLatchDiagnosticTestSupport");
        return (String[]) support.getMethod("contentionByPage").invoke(null);
    }

    static String[] snapshotDetailedPageLatchContentionByPage()
            throws ReflectiveOperationException {
        Class<?> support = Class.forName(
                "org.apache.derby.impl.store.raw.data.PageLatchDiagnosticTestSupport");
        return (String[]) support.getMethod("detailedContentionByPage").invoke(null);
    }

    static boolean heapAuthorityDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "heapAuthorityDiagnostics");
    }

    static boolean mvccPhysicalLayoutDiagnosticsEnabled() {
        return Boolean.getBoolean(PREFIX + "mvccPhysicalLayoutDiagnostics");
    }

    static void writePageLatchDiagnostics(
            BenchmarkConfiguration options,
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

    static void writePageLatchContentionByPage(
            BenchmarkConfiguration options,
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

    static void captureF04ServerPlanCostMatrix(
            Connection connection, BenchmarkConfiguration options, Spec spec, String base)
            throws SQLException, IOException {
        if (spec.workload() != Workload.JOIN_INDEXED_1TO1
                || (options.target() != Target.DELOS_HEAP_DRDA
                        && options.target() != Target.DELOS_MVCC_DRDA)) {
            throw new IllegalStateException(
                    "F04 server plan-cost matrix is valid only for Delos Heap/MVCC JOIN_INDEXED_1TO1");
        }
        String dimension = joinDimensionTableName(base);
        String basePkIndex = phase2BPrimaryKeyIndex(connection, base, "ID");
        String dimensionPkIndex = phase2BPrimaryKeyIndex(connection, dimension, "ID");
        LinkedHashMap<String, String> variants = new LinkedHashMap<>();
        variants.put("natural", fitnessReadSql(Workload.JOIN_INDEXED_1TO1, base));
        variants.put("base-first-hash", phase2ZForcedJoinSql(
                base, "a", basePkIndex, dimension, "b", dimensionPkIndex, "HASH"));
        variants.put("base-first-nestedloop", phase2ZForcedJoinSql(
                base, "a", basePkIndex, dimension, "b", dimensionPkIndex, "NESTEDLOOP"));
        variants.put("dimension-first-hash", phase2ZForcedJoinSql(
                dimension, "b", dimensionPkIndex, base, "a", basePkIndex, "HASH"));
        variants.put("dimension-first-nestedloop", phase2ZForcedJoinSql(
                dimension, "b", dimensionPkIndex, base, "a", basePkIndex, "NESTEDLOOP"));

        String prefix = String.format(
                Locale.ROOT, "f04-plan-%02d-%s-", options.run(), options.target().id());
        StringBuilder summary = new StringBuilder("variant\tplanShape\ttopEstimatedCost\n");
        for (Map.Entry<String, String> entry : variants.entrySet()) {
            ExplainCapture explain = capturePhase2NExplain(connection, entry.getValue(), false);
            writePhase2ACapture(
                    options.reportDirectory(), prefix + entry.getKey() + "-explain", explain);
            summary.append(entry.getKey()).append('\t')
                    .append(phase2UPlanShape(explain.text())).append('\t')
                    .append(format(phase2ZTopEstimatedCost(explain.text()))).append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve(prefix + "cost-matrix.tsv"),
                summary.toString(), StandardCharsets.UTF_8);
    }

    static String phase2ZForcedJoinSql(
            String firstTable,
            String firstAlias,
            String firstIndex,
            String secondTable,
            String secondAlias,
            String secondIndex,
            String joinStrategy) {
        return "select a.id from --DERBY-PROPERTIES joinOrder=FIXED\n"
                + firstTable + " " + firstAlias
                + " --DERBY-PROPERTIES index='" + firstIndex + "'\n"
                + "join " + secondTable + " " + secondAlias
                + " --DERBY-PROPERTIES index='" + secondIndex
                + "', joinStrategy=" + joinStrategy + "\n"
                + "on a.id = b.id";
    }

    static double phase2ZTopEstimatedCost(String planText) {
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*n\\d+\\s+[^\\n]*?\\bcost=([0-9.Ee+\\-]+)")
                .matcher(planText);
        if (!matcher.find()) {
            throw new IllegalStateException("F04 plan-cost matrix missing estimated cost:\n" + planText);
        }
        return Double.parseDouble(matcher.group(1));
    }

    static void writeMvccPhysicalLayout(
            Connection connection,
            BenchmarkConfiguration options,
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

    static void writeDerbyConglomerateMap(
            Connection connection,
            BenchmarkConfiguration options,
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

    static void writeSqliteRuntimeMetadata(
            Connection connection,
            BenchmarkConfiguration options,
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
                .append("benchmarkDdl=").append(sqliteBenchmarkDdl(tables, spec.workload())).append('\n')
                .append("compileOptions=").append(sqliteCompileOptions(connection)).append('\n')
                .append('\n');
        Path output = options.reportDirectory().resolve(
                "sqlite-runtime-metadata-run-" + options.run() + ".txt");
        Files.writeString(
                output, out.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    static String sqliteBenchmarkDdl(List<String> tables, Workload workload) {
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
            if (workload == Workload.JOIN_INDEXED_1TO1) {
                ddl.append("; create table ").append(joinDimensionTableName(table))
                        .append(" (id int not null primary key)");
            } else if (workload == Workload.JOIN_INDEXED_FANOUT) {
                ddl.append("; create table ").append(joinFanoutParentTableName(table))
                        .append(" (id int not null primary key)")
                        .append("; create table ").append(joinFanoutChildTableName(table))
                        .append(" (id int not null primary key, parent_id int not null)")
                        .append("; create index ").append(joinFanoutChildTableName(table)).append("_P_IDX on ")
                        .append(joinFanoutChildTableName(table)).append(" (parent_id)");
            } else if (workload == Workload.JOIN_3WAY_SELECTIVE
                    || workload == Workload.JOIN_4WAY_FANOUT) {
                ddl.append("; create table ").append(multiJoinCustomerTableName(table))
                        .append(" (id int not null primary key, bucket int not null)")
                        .append("; create index ").append(multiJoinCustomerTableName(table)).append("_B_IDX on ")
                        .append(multiJoinCustomerTableName(table)).append(" (bucket)")
                        .append("; create table ").append(multiJoinOrderTableName(table))
                        .append(" (id int not null primary key, customer_id int not null)")
                        .append("; create index ").append(multiJoinOrderTableName(table)).append("_C_IDX on ")
                        .append(multiJoinOrderTableName(table)).append(" (customer_id)")
                        .append("; create table ").append(multiJoinLineTableName(table))
                        .append(" (id int not null primary key, order_id int not null, line_no int not null, item_id int not null)")
                        .append("; create index ").append(multiJoinLineTableName(table)).append("_O_IDX on ")
                        .append(multiJoinLineTableName(table)).append(" (order_id)")
                        .append("; create table ").append(multiJoinItemTableName(table))
                        .append(" (id int not null primary key)");
            } else if (workload == Workload.GROUP_HIGH_CARD) {
                ddl.append("; create table ").append(highCardGroupTableName(table))
                        .append(" (id int not null primary key, group_key int not null, quantity int not null)");
            }
        }
        return ddl.toString();
    }

    static String pragmaValue(Connection connection, String pragma) throws SQLException {
        return singleValue(connection, "pragma " + pragma);
    }

    static String singleValue(Connection connection, String sql) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? Objects.toString(rs.getObject(1), "") : "";
        }
    }

    static String sqliteCompileOptions(Connection connection) throws SQLException {
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
}
