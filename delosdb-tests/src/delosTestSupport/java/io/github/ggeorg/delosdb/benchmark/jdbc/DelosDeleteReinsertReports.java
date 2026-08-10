/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** CSV, JSON, and human-readable reports for delete/reinsert attribution. */
final class DelosDeleteReinsertReports {
    private static final String IDENTITY_RESERVATION_BLOCK_SIZE_PROPERTY =
            "delosdb.mvcc.rawStoreIdentityReservationBlockSize";
    private DelosDeleteReinsertReports() {
    }

    static void write(
            Path reportDirectory,
            List<Integer> rowCounts,
            int cyclesPerIteration,
            int warmups,
            int iterations,
            int runs,
            List<DelosDeleteReinsertAttributionMeasurement> measurements) throws IOException {
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("delete-reinsert-results.csv"),
                csv(measurements));
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("delete-reinsert-results.json"),
                json(measurements));
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("delete-reinsert-summary.txt"),
                summary(rowCounts, cyclesPerIteration, warmups, iterations, runs, measurements));
    }

    private static String csv(List<DelosDeleteReinsertAttributionMeasurement> values) {
        StringBuilder out = new StringBuilder(
                "provider,keyMode,transactionBoundary,outcome,cyclesPerIteration,rowCount,payloadSize,"
                        + "fixtureCommitBatchSize,warmups,iterations,measuredCycles,transactionsPerCycle,"
                        + "sourceReadNanos,deleteExecuteNanos,deleteTransactionEndNanos,insertExecuteNanos,"
                        + "finalTransactionEndNanos,totalTimedNanos,averageCycleNanos,pageReadOperations,"
                        + "pageReadBytes,pageWriteOperations,pageWriteBytes,contentOnlyForceOperations,"
                        + "metadataForceOperations,semanticFingerprint,run\n");
        for (DelosDeleteReinsertAttributionMeasurement value : values) {
            out.append(value.provider().id()).append(',')
                    .append(value.keyMode()).append(',')
                    .append(value.transactionBoundary()).append(',')
                    .append(value.outcome()).append(',')
                    .append(value.cyclesPerIteration()).append(',')
                    .append(value.rowCount()).append(',')
                    .append(value.payloadSize()).append(',')
                    .append(value.fixtureCommitBatchSize()).append(',')
                    .append(value.warmups()).append(',')
                    .append(value.iterations()).append(',')
                    .append(value.measuredCycles()).append(',')
                    .append(value.transactionsPerCycle()).append(',')
                    .append(value.sourceReadNanos()).append(',')
                    .append(value.deleteExecuteNanos()).append(',')
                    .append(value.deleteTransactionEndNanos()).append(',')
                    .append(value.insertExecuteNanos()).append(',')
                    .append(value.finalTransactionEndNanos()).append(',')
                    .append(value.totalTimedNanos()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.averageCycleNanos())).append(',')
                    .append(value.pageReadOperations()).append(',')
                    .append(value.pageReadBytes()).append(',')
                    .append(value.pageWriteOperations()).append(',')
                    .append(value.pageWriteBytes()).append(',')
                    .append(value.contentOnlyForceOperations()).append(',')
                    .append(value.metadataForceOperations()).append(',')
                    .append(value.semanticFingerprint()).append(',')
                    .append(value.run()).append('\n');
        }
        return out.toString();
    }

    private static String json(List<DelosDeleteReinsertAttributionMeasurement> values) {
        StringBuilder out = new StringBuilder("[\n");
        for (int index = 0; index < values.size(); index++) {
            DelosDeleteReinsertAttributionMeasurement value = values.get(index);
            out.append("  {\"provider\":\"").append(value.provider().id())
                    .append("\",\"keyMode\":\"").append(value.keyMode())
                    .append("\",\"transactionBoundary\":\"")
                    .append(value.transactionBoundary())
                    .append("\",\"outcome\":\"").append(value.outcome())
                    .append("\",\"cyclesPerIteration\":").append(value.cyclesPerIteration())
                    .append(",\"rowCount\":").append(value.rowCount())
                    .append(",\"payloadSize\":").append(value.payloadSize())
                    .append(",\"fixtureCommitBatchSize\":")
                    .append(value.fixtureCommitBatchSize())
                    .append(",\"warmups\":").append(value.warmups())
                    .append(",\"iterations\":").append(value.iterations())
                    .append(",\"measuredCycles\":").append(value.measuredCycles())
                    .append(",\"transactionsPerCycle\":").append(value.transactionsPerCycle())
                    .append(",\"sourceReadNanos\":").append(value.sourceReadNanos())
                    .append(",\"deleteExecuteNanos\":").append(value.deleteExecuteNanos())
                    .append(",\"deleteTransactionEndNanos\":")
                    .append(value.deleteTransactionEndNanos())
                    .append(",\"insertExecuteNanos\":").append(value.insertExecuteNanos())
                    .append(",\"finalTransactionEndNanos\":")
                    .append(value.finalTransactionEndNanos())
                    .append(",\"totalTimedNanos\":").append(value.totalTimedNanos())
                    .append(",\"averageCycleNanos\":")
                    .append(String.format(Locale.ROOT, "%.3f", value.averageCycleNanos()))
                    .append(",\"pageReadOperations\":").append(value.pageReadOperations())
                    .append(",\"pageReadBytes\":").append(value.pageReadBytes())
                    .append(",\"pageWriteOperations\":").append(value.pageWriteOperations())
                    .append(",\"pageWriteBytes\":").append(value.pageWriteBytes())
                    .append(",\"contentOnlyForceOperations\":")
                    .append(value.contentOnlyForceOperations())
                    .append(",\"metadataForceOperations\":")
                    .append(value.metadataForceOperations())
                    .append(",\"semanticFingerprint\":").append(value.semanticFingerprint())
                    .append(",\"run\":").append(value.run()).append('}');
            if (index + 1 < values.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        return out.append("]\n").toString();
    }

    private static String summary(
            List<Integer> rowCounts,
            int cyclesPerIteration,
            int warmups,
            int iterations,
            int runs,
            List<DelosDeleteReinsertAttributionMeasurement> values) {
        StringBuilder out = new StringBuilder();
        out.append("DelosDB JDBC delete/reinsert attribution\n")
                .append("========================================\n\n")
                .append("Generated at: ").append(Instant.now()).append('\n')
                .append("Rows: ").append(rowCounts).append('\n')
                .append("Cycles per iteration: ").append(cyclesPerIteration).append('\n')
                .append("Warmups: ").append(warmups).append('\n')
                .append("Iterations: ").append(iterations).append('\n')
                .append("Runs: ").append(runs).append('\n')
                .append("MVCC identity reservation block size: ")
                .append(System.getProperty(IDENTITY_RESERVATION_BLOCK_SIZE_PROPERTY))
                .append('\n')
                .append("Timed phases: source read, delete execution, optional delete transaction end, ")
                .append("insert execution, final transaction end\n")
                .append("Semantic verification/restoration outside timed phases: true\n")
                .append("RawStore I/O deltas exclude semantic verification/restoration: true\n")
                .append("TWO_TRANSACTIONS + ROLLBACK semantics: delete commits, insert rolls back, ")
                .append("source row is restored outside timed phases\n")
                .append("Phase timers are diagnostic attribution, not an S0 threshold\n\n");
        for (DelosDeleteReinsertAttributionMeasurement value : values) {
            out.append(value.provider().id()).append(' ')
                    .append(value.keyMode()).append(' ')
                    .append(value.transactionBoundary()).append(' ')
                    .append(value.outcome()).append(" run=").append(value.run())
                    .append(" avg-cycle=").append(microseconds(value.averageCycleNanos()))
                    .append("us source=")
                    .append(microseconds(value.sourceReadNanos() / (double) value.measuredCycles()))
                    .append("us delete=")
                    .append(microseconds(value.deleteExecuteNanos() / (double) value.measuredCycles()))
                    .append("us delete-end=")
                    .append(microseconds(
                            value.deleteTransactionEndNanos() / (double) value.measuredCycles()))
                    .append("us insert=")
                    .append(microseconds(value.insertExecuteNanos() / (double) value.measuredCycles()))
                    .append("us final-end=")
                    .append(microseconds(
                            value.finalTransactionEndNanos() / (double) value.measuredCycles()))
                    .append("us page-writes=").append(value.pageWriteOperations())
                    .append(" page-write-bytes=").append(value.pageWriteBytes())
                    .append(" forces=")
                    .append(value.contentOnlyForceOperations() + value.metadataForceOperations())
                    .append('\n');
        }
        return out.toString();
    }

    private static String microseconds(double nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000.0d);
    }
}
