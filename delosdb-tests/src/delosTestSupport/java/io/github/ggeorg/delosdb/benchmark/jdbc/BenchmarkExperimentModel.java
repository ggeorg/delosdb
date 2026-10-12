/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Distribution;
import java.util.List;
import java.util.Map;

/** Shared value contracts and property prefixes for legacy experiments. */
final class BenchmarkExperimentModel {
    private BenchmarkExperimentModel() {
    }

    static final String PHASE2A_PREFIX = "delosdb.phase2.f05JoinDecomposition.";

    static final String PHASE2B_PREFIX = "delosdb.phase2.f05CardinalityCostingProof.";

    static final String PHASE2D_PREFIX = "delosdb.phase2.f05ResidualIndexedAccess.";

    static final String PHASE2E_PREFIX = "delosdb.phase2.f05BaseFetchAttribution.";

    static final String PHASE2H_PREFIX = "delosdb.phase2.f05PostHintResidualAttribution.";

    static final String PHASE2J_PREFIX = "delosdb.phase2.f05CompatibilityNativePath.";

    static final String PHASE2K_PREFIX = "delosdb.phase2.f03ProjectionMaterialization.";

    static final String PHASE2N_PREFIX = "delosdb.phase2.f04F06RelationalDecomposition.";

    static final String PHASE2O_PREFIX = "delosdb.phase2.f06GroupInputJfr.";

    static final String PHASE2P_PREFIX = "delosdb.phase2.f06CanonicalPlanFalsification.";

    static final String PHASE2Q_PREFIX = "delosdb.phase2.f07SortDecomposition.";

    static final String PHASE2U_PREFIX = "delosdb.phase2.f04CurrentJoinDecomposition.";

    static final String PHASE2V_PREFIX = "delosdb.phase2.f04EmbeddedConcurrency.";

    record Phase2RConcurrencyMeasurement(
            long fingerprint,
            List<Double> throughputs,
            Distribution distribution,
            DelosMeasurementValidityContract.DispersionDecision decision) {
    }

    record Phase2EProfile(double elapsedMillis, int iterations) {
        double millisPerExecution() {
            return elapsedMillis / iterations;
        }
    }

    record Phase2UVariant(
            String sql, int expectedRows, int resultColumns, int[] parameters) {
        Phase2UVariant {
            parameters = parameters.clone();
        }

        @Override
        public int[] parameters() {
            return parameters.clone();
        }
    }

    record Phase2VFixtures(
            String heapBase, String mvccBase, List<String> statisticsTables) {
        Phase2VFixtures {
            statisticsTables = List.copyOf(statisticsTables);
        }
    }

    record Phase2OFeatures(
            boolean gen2C3Enabled,
            boolean projectedCurrentRead,
            boolean baseFetchPrefetch,
            boolean physicalScanCost,
            boolean singlePassCurrentScan,
            boolean reusableCurrentScanTemplate,
            boolean streamingBulkScan,
            boolean fastCurrentVisibilityFetch,
            boolean skipCurrentTagFetch,
            boolean lazyCurrentCreatorFetch,
            boolean lazyCurrentIdentityFetch,
            boolean nonHoldableProfile) {
    }

    record Phase2PMeasurement(
            long fingerprint,
            List<Double> samples,
            Distribution distribution,
            DelosMeasurementValidityContract.DispersionDecision decision) {
    }

    record Phase2PPlanMetric(String shape, double rows, double cost) {
    }

    record Phase2PFeatures(
            boolean gen2C3Enabled,
            boolean projectedCurrentRead,
            boolean baseFetchPagePrefetch,
            boolean physicalScanCost,
            boolean physicalRowLocationCost,
            boolean singlePassCurrentScan,
            boolean reusableCurrentScanTemplate,
            boolean streamingBulkScan,
            boolean fastCurrentVisibilityFetch,
            boolean skipCurrentTagFetch,
            boolean lazyCurrentCreatorFetch,
            boolean lazyCurrentIdentityFetch) {
    }

    record Phase2PResult(
            int holdability,
            boolean statisticsCatalogStable,
            Map<String, Phase2PMeasurement> measurements,
            Phase2PPlanMetric heapNaturalBefore,
            Phase2PPlanMetric heapForcedBefore,
            Phase2PPlanMetric mvccNaturalBefore,
            Phase2PPlanMetric mvccForcedBefore,
            Phase2PPlanMetric mvccNaturalAfter,
            Phase2PPlanMetric mvccForcedAfter) {
    }
}
