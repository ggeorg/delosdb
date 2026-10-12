/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaProtocolEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaServerPhaseEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostProcessDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateCooldownAfterRun;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.hostStateRecoveryEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.sqlSemanticOracleEnabled;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ServerEndpoint;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.OracleEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DrdaProtocolEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseMeasurement.deleteRecursively;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkContainerCampaign.ContainerServer;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DrdaServerPhaseEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Row;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkContainerCampaign.prepareContainerEnvironment;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkContainerCampaign.startContainer;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkHostObservation.captureHostProcessSample;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkHostObservation.captureHostState;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkHostObservation.coolDownHost;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkHostObservation.startHostProcessSampler;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkHostObservation.stopHostProcessSampler;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkHostObservation.writeHostProcessDiagnosticHeader;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkHostObservation.writeHostRecoveryConfiguration;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkHostObservation.writeHostStateDiagnosticHeader;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkJoinExperiments.runPhase2AF05JoinDecomposition;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkJoinExperiments.runPhase2BF05CardinalityCostingProof;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkJoinExperiments.runPhase2DF05ResidualIndexedAccessDecomposition;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkJoinExperiments.runPhase2EF05BaseFetchAttribution;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkJoinExperiments.runPhase2HF05PostHintResidualAttribution;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkJoinExperiments.runPhase2JF05CompatibilityNativePathDecomposition;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkJoinExperiments.runPhase2UF04CurrentJoinDecomposition;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkJoinExperiments.runPhase2VF04EmbeddedConcurrency;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkRelationalExperiments.runPhase2KF03ProjectionMaterializationDecomposition;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkRelationalExperiments.runPhase2NF04F06RelationalDecomposition;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkRelationalExperiments.runPhase2OF06GroupInputJfrAttribution;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkRelationalExperiments.runPhase2PF06CanonicalPlanFalsification;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkRelationalExperiments.runPhase2QF07SortDecomposition;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkRelationalExperiments.runPhase2RF07EmbeddedConcurrency;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkRelationalExperiments.runPhase2SF07EmbeddedJfrAttribution;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkRelationalExperiments.runPhase2TF07StreamingFalsification;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.loadDrdaProtocolEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.loadDrdaServerPhaseEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.loadOracleEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.loadRows;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.validateDrdaProtocolEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.validateDrdaServerPhaseEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.validateOracleEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.validateRows;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeCapabilityCsv;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeContainerSemanticEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeDispersionCsv;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeDrdaProtocolEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeDrdaServerPhaseEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeMergedCsv;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeOracleEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeRangeScanCsv;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeRatioCsv;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeScalingCsv;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.writeSummary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** JDBC concurrency comparison with deterministic semantic verification. */
public final class DelosJdbcCrossEngineConcurrency {

    private DelosJdbcCrossEngineConcurrency() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "occupancy-contract".equals(args[0])) {
            BenchmarkConfiguration.fromSystemProperties().validate();
            System.out.println("F08 occupancy configuration validated; no measurement executed.");
            return;
        }
        if (args.length == 1 && "phase2a-f05-join-decomposition".equals(args[0])) {
            runPhase2AF05JoinDecomposition();
            return;
        }
        if (args.length == 1 && "phase2b-f05-cardinality-costing-proof".equals(args[0])) {
            runPhase2BF05CardinalityCostingProof();
            return;
        }
        if (args.length == 1 && "phase2d-f05-residual-indexed-access".equals(args[0])) {
            runPhase2DF05ResidualIndexedAccessDecomposition();
            return;
        }
        if (args.length == 1 && "phase2e-f05-base-fetch-attribution".equals(args[0])) {
            runPhase2EF05BaseFetchAttribution();
            return;
        }
        if (args.length == 1 && "phase2h-f05-post-hint-residual-attribution".equals(args[0])) {
            runPhase2HF05PostHintResidualAttribution();
            return;
        }
        if (args.length == 1 && "phase2j-f05-compatibility-native-path".equals(args[0])) {
            runPhase2JF05CompatibilityNativePathDecomposition();
            return;
        }
        if (args.length == 1 && "phase2k-f03-projection-materialization".equals(args[0])) {
            runPhase2KF03ProjectionMaterializationDecomposition();
            return;
        }
        if (args.length == 1 && "phase2n-f04-f06-relational-decomposition".equals(args[0])) {
            runPhase2NF04F06RelationalDecomposition();
            return;
        }
        if (args.length == 1 && "phase2o-f06-group-input-jfr".equals(args[0])) {
            runPhase2OF06GroupInputJfrAttribution();
            return;
        }
        if (args.length == 1 && "phase2p-f06-canonical-plan-falsification".equals(args[0])) {
            runPhase2PF06CanonicalPlanFalsification();
            return;
        }
        if (args.length == 1 && "phase2q-f07-sort-decomposition".equals(args[0])) {
            runPhase2QF07SortDecomposition();
            return;
        }
        if (args.length == 1 && "phase2r-f07-embedded-concurrency".equals(args[0])) {
            runPhase2RF07EmbeddedConcurrency();
            return;
        }
        if (args.length == 1 && "phase2s-f07-embedded-jfr".equals(args[0])) {
            runPhase2SF07EmbeddedJfrAttribution();
            return;
        }
        if (args.length == 1 && "phase2t-f07-streaming-falsification".equals(args[0])) {
            runPhase2TF07StreamingFalsification();
            return;
        }
        if (args.length == 1 && "phase2u-f04-current-join-decomposition".equals(args[0])) {
            runPhase2UF04CurrentJoinDecomposition();
            return;
        }
        if (args.length == 1 && "phase2v-f04-embedded-concurrency".equals(args[0])) {
            runPhase2VF04EmbeddedConcurrency();
            return;
        }
        BenchmarkConfiguration options = BenchmarkConfiguration.fromSystemProperties();
        options.validate();
        if (args.length == 1 && "worker".equals(args[0])) {
            BenchmarkWorkloadRunner.run(options);
        } else if (args.length == 0) {
            runCoordinator(options);
        } else {
            throw new IllegalArgumentException(
                    "Expected no argument, exactly 'worker', exactly 'phase2a-f05-join-decomposition',"
                            + " exactly 'phase2b-f05-cardinality-costing-proof',"
                            + " exactly 'phase2d-f05-residual-indexed-access',"
                            + " exactly 'phase2e-f05-base-fetch-attribution',"
                            + " exactly 'phase2h-f05-post-hint-residual-attribution',"
                            + " exactly 'phase2j-f05-compatibility-native-path',"
                            + " exactly 'phase2k-f03-projection-materialization',"
                            + " exactly 'phase2n-f04-f06-relational-decomposition',"
                            + " exactly 'phase2o-f06-group-input-jfr',"
                            + " exactly 'phase2p-f06-canonical-plan-falsification',"
                            + " exactly 'phase2q-f07-sort-decomposition',"
                            + " exactly 'phase2r-f07-embedded-concurrency',"
                            + " exactly 'phase2s-f07-embedded-jfr',"
                            + " exactly 'phase2t-f07-streaming-falsification',"
                            + " exactly 'phase2u-f04-current-join-decomposition',"
                            + " or exactly 'phase2v-f04-embedded-concurrency'");
        }
    }

    private static void runCoordinator(BenchmarkConfiguration options) throws Exception {
        if (!"false".equals(System.getProperty(PREFIX + "sane"))) {
            throw new IllegalStateException(
                    "Cross-engine concurrency comparison requires -Pdelosdb.sane=false");
        }
        deleteRecursively(options.reportDirectory());
        deleteRecursively(options.databaseRoot());
        Files.createDirectories(options.reportDirectory().resolve("workers"));
        Files.createDirectories(options.reportDirectory().resolve("logs"));
        Files.createDirectories(options.databaseRoot());

        if (options.containerMode()) {
            prepareContainerEnvironment(options);
        }

        if (hostStateDiagnosticsEnabled()) {
            writeHostStateDiagnosticHeader(options);
            captureHostState(options, "SUITE_START", 0, "-");
        }
        if (hostProcessDiagnosticsEnabled()) {
            writeHostProcessDiagnosticHeader(options);
            captureHostProcessSample(options, "SUITE_START", 0, "-", null);
        }
        if (hostStateRecoveryEnabled()) {
            writeHostRecoveryConfiguration(options);
        }

        try {
            for (int run = 1; run <= options.runs(); run++) {
                List<Target> targets = new ArrayList<>(options.targetValues());
                if (((run - 1) & 2) != 0) {
                    Collections.reverse(targets);
                }
                for (Target target : targets) {
                    if (hostStateDiagnosticsEnabled()) {
                        captureHostState(options, "BEFORE_WORKER", run, target.id());
                    }
                    try {
                        if (target.isContainer()) {
                            try (ContainerServer server = startContainer(options, target, run)) {
                                try {
                                    launchWorker(options, target, run, server.endpoint());
                                } catch (Throwable failure) {
                                    failure.addSuppressed(new IllegalStateException(
                                            "Container log for " + target.id() + ":\n" + server.logs()));
                                    throw failure;
                                }
                            }
                        } else {
                            launchWorker(options, target, run, null);
                        }
                    } finally {
                        if (hostStateDiagnosticsEnabled()) {
                            captureHostState(options, "AFTER_WORKER", run, target.id());
                        }
                    }
                }
                if (hostStateRecoveryEnabled() && run == hostStateCooldownAfterRun()) {
                    coolDownHost(options, run);
                }
            }
        } finally {
            if (hostStateDiagnosticsEnabled()) {
                captureHostState(options, "SUITE_END", options.runs(), "-");
            }
            if (hostProcessDiagnosticsEnabled()) {
                captureHostProcessSample(options, "SUITE_END", options.runs(), "-", null);
            }
        }

        List<Row> rows = loadRows(options);
        validateRows(options, rows);
        if (sqlSemanticOracleEnabled()) {
            List<OracleEvidence> oracleEvidence = loadOracleEvidence(options);
            validateOracleEvidence(options, oracleEvidence);
            writeOracleEvidence(options, oracleEvidence);
        }
        if (drdaProtocolEvidenceEnabled()) {
            List<DrdaProtocolEvidence> protocolEvidence = loadDrdaProtocolEvidence(options);
            validateDrdaProtocolEvidence(options, protocolEvidence);
            writeDrdaProtocolEvidence(options, protocolEvidence);
        }
        if (drdaServerPhaseEvidenceEnabled()) {
            List<DrdaServerPhaseEvidence> serverEvidence = loadDrdaServerPhaseEvidence(options);
            validateDrdaServerPhaseEvidence(options, serverEvidence);
            writeDrdaServerPhaseEvidence(options, serverEvidence);
        }
        writeMergedCsv(options, rows);
        writeRatioCsv(options, rows);
        writeScalingCsv(options, rows);
        writeDispersionCsv(options, rows);
        writeRangeScanCsv(options, rows);
        writeCapabilityCsv(options);
        writeSummary(options, rows);
        if (options.containerMode()) {
            writeContainerSemanticEvidence(options, rows);
        }
    }

    private static void launchWorker(
            BenchmarkConfiguration options, Target target, int run, ServerEndpoint endpoint) throws Exception {
        RawStoreStabilityDiagnostics.awaitQuietHost(options.reportDirectory(), target.id(), run);
        List<String> command = BenchmarkWorkerCommand.build(options, target, run, endpoint);
        Path log = options.reportDirectory().resolve("logs")
                .resolve(String.format(Locale.ROOT, "%02d-%s.log", run, target.id()));
        new BenchmarkWorkerProcess(command, options.projectDirectory(), log,
                options.workerTimeoutSeconds(), target.id(), run).execute(process -> {
                    Thread sampler = hostProcessDiagnosticsEnabled()
                            ? startHostProcessSampler(options, target, run, process) : null;
                    return () -> stopHostProcessSampler(sampler);
                });
    }

}
