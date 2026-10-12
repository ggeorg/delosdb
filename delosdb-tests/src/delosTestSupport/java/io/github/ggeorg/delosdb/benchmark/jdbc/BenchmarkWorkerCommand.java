/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ServerEndpoint;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.benchmarkContractId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaProtocolEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaServerPhaseEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f02EmbeddedConcurrencyDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f02ScaleSurfaceDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f04DrdaRowAdvanceJfrDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f04DrdaServerPhaseDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f04FanoutJfrDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f04ServerPlanCostMatrixEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f07DrdaServerPhaseDiagnosticEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08ContentionScalingSliceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08FixedCostClientScalingEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08MultiRowInsertControlEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08TransactionStatusCrossEngineEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.freshRealisticTransactionFitnessEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2A1ThroughputSentinelEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2BThroughputSentinelEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2C3PostgresqlUpdateComparisonEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2C3UpdateThroughputSentinelEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.insertTableShape;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mutationSchemaAttributionEnabled;
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
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccRefreshMultiJoinStatisticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.postModernizationMutationCheckpointEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.refreshSimpleJoinStatisticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.sqlSemanticOracleEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.targetIds;

/** Builds the forked JVM command, including target flags and profiling artifacts. */
final class BenchmarkWorkerCommand {
    private BenchmarkWorkerCommand() {
    }

    static List<String> build(
            BenchmarkConfiguration options, Target target, int run, ServerEndpoint endpoint) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(options.javaExecutable().toString());
        command.add("-Xms" + options.childHeap());
        command.add("-Xmx" + options.childHeap());
        command.add("-XX:+AlwaysPreTouch");
        RawStoreStabilityDiagnostics.configureWorker(command, options.reportDirectory(), target.id(), run);
        if (RawStoreOccupancyBenchmark.campaign()) {
            addProperty(command, "rawStoreOccupancyCampaign", true);
            boolean occupancy = RawStoreOccupancyBenchmark.enabledForRun(run,
                    System.getProperty(PREFIX + "rawStoreOccupancyEnabled", "false"));
            addProperty(command, "rawStoreOccupancyMode", occupancy);
            if (target == Target.DELOS_HEAP && occupancy) {
                command.add("-Ddelosdb.diagnostic.rawStoreOccupancy.enabled=true");
                command.add("-Ddelosdb.diagnostic.rawStoreOccupancy.sampleEvery="
                        + System.getProperty(PREFIX + "rawStoreOccupancySampleEvery", "32"));
            }
        }
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean("delosdb.experimental.heapPageReadImage")) {
            command.add("-Ddelosdb.experimental.heapPageReadImage=true");
        }
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean("delosdb.diagnostic.heapPageReadImage")) {
            command.add("-Ddelosdb.diagnostic.heapPageReadImage=true");
        }
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean(PREFIX + "heapPageReadImageDiagnostics")) {
            addProperty(command, "heapPageReadImageDiagnostics", true);
        }
        if (target == Target.DELOS_HEAP
                && Boolean.getBoolean("delosdb.experimental.fastRecordReadLock")) {
            command.add("-Ddelosdb.experimental.fastRecordReadLock=true");
        }
        if (target == Target.DELOS_MVCC) {
            if (mvccGen2TransactionStatusVisibilityServerEnabled()) {
                command.add("-Ddelosdb.experimental.mvccGen2TransactionStatusVisibility.enabled=true");
            }
            if (mvccGen2C3ReadServerEnabled()) {
                command.add("-Ddelosdb.experimental.mvccGen2B.pk.enabled=true");
                command.add("-Ddelosdb.experimental.mvccGen2C1.history.enabled=true");
                command.add("-Ddelosdb.experimental.mvccGen2C2.archivedUndo.enabled=true");
            }
            if (mvccGen2ProjectedCurrentReadEnabled()) {
                command.add("-Ddelosdb.experimental.mvccGen2ProjectedCurrentRead.enabled=true");
            }
            command.add("-Ddelosdb.experimental.mvccPhysicalScanCost.enabled="
                    + mvccPhysicalScanCostEnabled());
            command.add("-Ddelosdb.experimental.mvccPhysicalRowLocationCost.enabled="
                    + mvccPhysicalRowLocationCostEnabled());
            command.add("-Ddelosdb.experimental.mvccGen2SinglePassCurrentScan.enabled="
                    + mvccGen2SinglePassCurrentScanEnabled());
            String slots = System.getProperty(
                    PREFIX + "mvccCurrentRowReadCacheSlots", "").trim();
            if (!slots.isEmpty()) {
                command.add("-Ddelosdb.mvcc.currentRowReadCache.slots=" + slots);
            }
            if (Boolean.getBoolean(PREFIX + "mvccSnapshotLeaseDiagnostics")) {
                command.add("-Ddelosdb.diagnostic.mvccSnapshotLease=true");
                addProperty(command, "mvccSnapshotLeaseDiagnostics", true);
            }
            if (Boolean.getBoolean(PREFIX + "mvccSnapshotLeaseRegistry")) {
                command.add("-Ddelosdb.experimental.mvccSnapshotLeaseRegistry=true");
                String leaseSlots = System.getProperty(
                        PREFIX + "mvccSnapshotLeaseRegistrySlots", "").trim();
                if (!leaseSlots.isEmpty()) {
                    command.add("-Ddelosdb.experimental.mvccSnapshotLeaseRegistry.slots="
                            + leaseSlots);
                }
            }
            if (Boolean.getBoolean(PREFIX + "mvccBaseFetchPagePrefetch")) {
                command.add("-Ddelosdb.experimental.mvccBaseFetchPagePrefetch=true");
            }
        }
        String rangeBulkFetchDefault = System.getProperty(
                PREFIX + "rangeBulkFetchDefault", "").trim();
        if (!rangeBulkFetchDefault.isEmpty()) {
            command.add("-D" + PREFIX + "rangeBulkFetchDefault=" + rangeBulkFetchDefault);
        }
        if (shouldProfileWorker(target)) {
            Path profileDirectory = options.reportDirectory().resolve("profiles");
            Files.createDirectories(profileDirectory);
            Path recording = profileDirectory.resolve(String.format(
                    Locale.ROOT, "%02d-%s.jfr", run, target.id()));
            Path compilationLog = profileDirectory.resolve(String.format(
                    Locale.ROOT, "%02d-%s-hotspot.log", run, target.id()));
            if (!System.getProperty(PREFIX + "profileTargets", "").isBlank()) {
                command.add("-XX:FlightRecorderOptions=stackdepth=256");
            }
            command.add("-XX:+UnlockDiagnosticVMOptions");
            command.add("-XX:+LogCompilation");
            command.add("-XX:LogFile=" + compilationLog);
            command.add("-XX:StartFlightRecording=filename=" + recording
                    + ",settings=profile,dumponexit=true,maxsize=512m");
        }
        if (target == Target.SQLITE) {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
        if (sqlSemanticOracleEnabled()) {
            addProperty(command, "sqlSemanticOracle", true);
        }
        if (drdaProtocolEvidenceEnabled() && target.isDrda()) {
            command.add("-Ddelosdb.diagnostic.drdaProtocolEvidence=true");
            addProperty(command, "drdaProtocolEvidence", true);
        }
        if (drdaServerPhaseEvidenceEnabled() && target.isDrda()) {
            addProperty(command, "drdaServerPhaseEvidence", true);
        }
        if (f04DrdaServerPhaseDiagnosticEnabled()) {
            addProperty(command, "f04DrdaServerPhaseDiagnostic", true);
        }
        if (f04DrdaRowAdvanceJfrDiagnosticEnabled()) {
            addProperty(command, "f04DrdaRowAdvanceJfrDiagnostic", true);
        }
        if (f04FanoutJfrDiagnosticEnabled()) {
            addProperty(command, "f04FanoutJfrDiagnostic", true);
        }
        String profileServerTargets = System.getProperty(
                PREFIX + "profileServerTargets", "").trim();
        if (!profileServerTargets.isEmpty()) {
            addProperty(command, "profileServerTargets", profileServerTargets);
        }
        if (f07DrdaServerPhaseDiagnosticEnabled()) {
            addProperty(command, "f07DrdaServerPhaseDiagnostic", true);
        }
        command.add("-cp");
        command.add(options.benchmarkClasses() + java.io.File.pathSeparator + options.classpath(target));
        addProperty(command, "targets", targetIds(options.targetValues()));
        addProperty(command, "target", target.id());
        addProperty(command, "run", run);
        addProperty(command, "databaseRoot", options.databaseRoot());
        addProperty(command, "reportDirectory", options.reportDirectory().resolve("workers"));
        addProperty(command, "rows", options.rows());
        addProperty(command, "sqliteSharedCache", options.sqliteSharedCache());
        addProperty(command, "clients", options.clients());
        addProperty(command, "widths", options.widths());
        addProperty(command, "workloads", options.workloads());
        addProperty(command, "insertTableShape", insertTableShape());
        if (mutationSchemaAttributionEnabled()) {
            addProperty(command, "mutationSchemaAttribution", true);
        }
        if (gen2A1ThroughputSentinelEnabled()) {
            addProperty(command, "gen2A1ThroughputSentinel", true);
        }
        if (gen2BThroughputSentinelEnabled()) {
            addProperty(command, "gen2BThroughputSentinel", true);
        }
        if (gen2C3UpdateThroughputSentinelEnabled()) {
            addProperty(command, "gen2C3UpdateThroughputSentinel", true);
        }
        if (gen2C3PostgresqlUpdateComparisonEnabled()) {
            addProperty(command, "gen2C3PostgresqlUpdateComparison", true);
        }
        if (mvccGen2A1ServerEnabled()) {
            addProperty(command, "mvccGen2A1Server", true);
        }
        if (mvccGen2BServerEnabled()) {
            addProperty(command, "mvccGen2BServer", true);
        }
        if (mvccGen2TransactionStatusVisibilityServerEnabled()) {
            addProperty(command, "mvccGen2TransactionStatusVisibilityServer", true);
        }
        if (!benchmarkContractId().isEmpty()) {
            addProperty(command, "benchmarkContract", benchmarkContractId());
        }
        if (f08TransactionStatusCrossEngineEnabled()) {
            addProperty(command, "f08TransactionStatusCrossEngine", true);
        }
        if (f08FixedCostClientScalingEnabled()) {
            addProperty(command, "f08FixedCostClientScaling", true);
        }
        if (f08ContentionScalingSliceEnabled()) {
            addProperty(command, "f08ContentionScalingSlice", true);
        }
        if (f08MultiRowInsertControlEnabled()) {
            addProperty(command, "f08MultiRowInsertControl", true);
        }
        if (mvccGen2C1ServerEnabled()) {
            addProperty(command, "mvccGen2C1Server", true);
        }
        if (mvccGen2C3UpdateServerEnabled()) {
            addProperty(command, "mvccGen2C3UpdateServer", true);
        }
        if (postModernizationMutationCheckpointEnabled()) {
            addProperty(command, "postModernizationMutationCheckpoint", true);
        }
        if (mvccGen2C3ReadServerEnabled()) {
            addProperty(command, "mvccGen2C3ReadServer", true);
        }
        if (f02ScaleSurfaceDiagnosticEnabled()) {
            addProperty(command, "f02ScaleSurfaceDiagnostic", true);
        }
        if (f02EmbeddedConcurrencyDiagnosticEnabled()) {
            addProperty(command, "f02EmbeddedConcurrencyDiagnostic", true);
        }
        if (freshRealisticTransactionFitnessEnabled()) {
            addProperty(command, "freshRealisticTransactionFitness", true);
        }
        if (mvccGen2ProjectedCurrentReadEnabled()) {
            addProperty(command, "mvccGen2ProjectedCurrentRead", true);
        }
        if (mvccRefreshMultiJoinStatisticsEnabled()) {
            addProperty(command, "mvccRefreshMultiJoinStatistics", true);
        }
        if (refreshSimpleJoinStatisticsEnabled()) {
            addProperty(command, "refreshSimpleJoinStatistics", true);
        }
        if (f04ServerPlanCostMatrixEnabled()) {
            addProperty(command, "f04ServerPlanCostMatrix", true);
        }
        addProperty(command, "transactionsPerClient", options.transactionsPerClient());
        addProperty(command, "fixedWorkloadOperationBudgetPerClient",
                options.fixedWorkloadOperationBudgetPerClient());
        addProperty(command, "rangeScanTargetRowsPerClient", options.rangeScanTargetRowsPerClient());
        addProperty(command, "rangeScanMinQueriesPerClient", options.rangeScanMinQueriesPerClient());
        addProperty(command, "rangeScanMaxQueriesPerClient", options.rangeScanMaxQueriesPerClient());
        addProperty(command, "h2RangeFetchSize", options.h2RangeFetchSize());
        addProperty(command, "payload", options.payload());
        addProperty(command, "fixtureBatch", options.fixtureBatch());
        addProperty(command, "warmups", options.warmups());
        addProperty(command, "iterations", options.iterations());
        addProperty(command, "minimumWarmupSeconds", options.minimumWarmupSeconds());
        addProperty(command, "maximumWarmupIterations", options.maximumWarmupIterations());
        addProperty(command, "minimumMeasuredSeconds", options.minimumMeasuredSeconds());
        addProperty(command, "maximumMeasuredIterations", options.maximumMeasuredIterations());
        addProperty(command, "caseTimeoutSeconds", options.caseTimeoutSeconds());
        addProperty(command, "closeCursorsAtCommit",
                Boolean.getBoolean(PREFIX + "closeCursorsAtCommit"));
        if (endpoint != null) {
            addProperty(command, "remoteJdbcUrl", endpoint.jdbcUrl());
            addProperty(command, "remoteUser", endpoint.user());
            addProperty(command, "remotePassword", endpoint.password());
        }
        command.add(DelosJdbcCrossEngineConcurrency.class.getName());
        command.add("worker");

        return List.copyOf(command);
    }

    private static boolean shouldProfileWorker(Target target) {
        String configuredTargets = System.getProperty(PREFIX + "profileTargets", "").trim();
        if (!configuredTargets.isEmpty()) {
            for (String configured : configuredTargets.split(",")) {
                if (target.id().equalsIgnoreCase(configured.trim())) {
                    return true;
                }
            }
        }
        return target == Target.DELOS_MVCC
                && Boolean.getBoolean(PREFIX + "profileDelosMvccWorkers");
    }

    private static void addProperty(List<String> command, String name, Object value) {
        command.add("-D" + PREFIX + name + '=' + value);
    }
}
