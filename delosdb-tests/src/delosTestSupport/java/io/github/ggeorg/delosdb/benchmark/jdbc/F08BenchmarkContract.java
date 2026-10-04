/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.util.List;
import java.util.Locale;

/** Owns configuration rules shared by the surviving F08 INSERT benchmark modes. */
final class F08BenchmarkContract {
    static final String NONE = "";
    static final String TRANSACTION_STATUS = "f08-transaction-status";
    static final String MULTI_ROW_INSERT = "f08-multi-row-insert";
    static final String FIXED_COST_SCALING = "f08-fixed-cost-scaling";
    static final String CONTENTION_SLICE = "f08-contention-slice";

    private static final String INSERT_100 = "INSERT_100";
    private static final String BARE = "BARE";
    private static final String PRIMARY_KEY_ONLY = "PRIMARY_KEY_ONLY";
    private static final String PROFILE_TARGETS =
            "delos_heap_drda,delos_mvcc_drda,upstream_derby_drda";

    private F08BenchmarkContract() {
    }

    static void validate(String contractId, Context context) {
        String normalized = contractId == null ? NONE : contractId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            if (context.controls().hasF08Controls()) {
                throw new IllegalArgumentException(
                        "F08 benchmark controls require an explicit benchmark contract");
            }
            return;
        }

        switch (normalized) {
            case TRANSACTION_STATUS -> validateTransactionStatus(context);
            case MULTI_ROW_INSERT -> validateMultiRowInsert(context);
            case FIXED_COST_SCALING -> validateFixedCostScaling(context);
            case CONTENTION_SLICE -> validateContentionSlice(context);
            default -> throw new IllegalArgumentException(
                    "Unknown F08 benchmark contract: " + contractId);
        }
    }

    private static void validateTransactionStatus(Context context) {
        validateCommonTransactionStatus(context);
        Controls controls = context.controls();
        require(!controls.fixedCostClientScaling() && !controls.contentionScalingSlice(),
                "F08 transaction-status contract cannot use fixed-cost/contention-slice mode");
        require(!controls.multiRowInsertControl(),
                "F08 multi-row INSERT control requires its dedicated benchmark contract");
        require(context.shape().clients().equals(List.of(8)),
                "F08 transaction-status contract requires exactly 8 clients");
        requireMutuallyCompatibleExperiments(controls);
    }

    private static void validateMultiRowInsert(Context context) {
        validateCommonTransactionStatus(context);
        Controls controls = context.controls();
        require(!controls.fixedCostClientScaling() && !controls.contentionScalingSlice(),
                "F08 multi-row INSERT contract cannot use scaling modes");
        require(context.targets().fullServerTargets()
                        && context.shape().workloads().equals(List.of(INSERT_100))
                        && context.shape().clients().equals(List.of(8))
                        && PRIMARY_KEY_ONLY.equals(context.shape().tableShape()),
                "F08 multi-row INSERT contract requires the full SERVER matrix, "
                        + "8 clients, PRIMARY_KEY_ONLY, and INSERT_100");
        requireNoSharedAuthorityExperiment(controls,
                "F08 multi-row INSERT contract cannot combine with another F08 storage experiment");
    }

    private static void validateFixedCostScaling(Context context) {
        validateCommonTransactionStatus(context);
        Controls controls = context.controls();
        require(controls.fixedCostClientScaling() && !controls.contentionScalingSlice(),
                "F08 fixed-cost contract requires fixed-cost mode without contention-slice mode");
        require(!controls.multiRowInsertControl(),
                "F08 fixed-cost scaling requires JDBC batch shape");
        require(context.targets().fullServerTargets()
                        && context.shape().workloads().equals(List.of(INSERT_100))
                        && context.shape().clients().equals(List.of(1, 2, 4, 8))
                        && PRIMARY_KEY_ONLY.equals(context.shape().tableShape())
                        && context.shape().payload() == 16,
                "F08 fixed-cost client scaling requires the full SERVER matrix, "
                        + "PRIMARY_KEY_ONLY INSERT_100, payload=16, and clients=1,2,4,8");
        requireNoSharedAuthorityExperiment(controls,
                "F08 fixed-cost scaling cannot combine with another F08 storage experiment");
    }

    private static void validateContentionSlice(Context context) {
        validateCommonTransactionStatus(context);
        Controls controls = context.controls();
        require(controls.fixedCostClientScaling() && controls.contentionScalingSlice(),
                "F08 contention-slice contract requires fixed-cost and contention-slice modes");
        require(!controls.multiRowInsertControl(),
                "F08 contention-scaling slice requires JDBC batch shape");
        List<Integer> clients = context.shape().clients();
        require(context.targets().transactionStatusAttributionTargets()
                        && clients.size() == 1
                        && List.of(1, 2, 4, 8).contains(clients.get(0))
                        && context.shape().workloads().equals(List.of(INSERT_100))
                        && PRIMARY_KEY_ONLY.equals(context.shape().tableShape())
                        && context.shape().payload() == 16,
                "F08 contention-scaling slice requires Derby-family SERVER targets, "
                        + "PRIMARY_KEY_ONLY INSERT_100, payload=16, and one client count from 1,2,4,8");
        require(PROFILE_TARGETS.equals(context.profileServerTargets()),
                "F08 transaction-status attribution requires server profiling for " + PROFILE_TARGETS);
        require(!controls.rootRoutingSnapshotServer()
                        && !controls.branchRoutingSnapshotServer(),
                "F08 contention-scaling slice cannot combine with routing experiments");
    }

    private static void validateCommonTransactionStatus(Context context) {
        Controls controls = context.controls();
        require(controls.transactionStatusCrossEngine(),
                "F08 contract requires f08TransactionStatusCrossEngine=true");
        require(context.targets().fullServerTargets()
                        || context.targets().transactionStatusAttributionTargets(),
                "F08 contract requires the full SERVER matrix or Derby-family attribution targets");
        require(context.shape().workloads().size() == 1
                        && context.shape().workloads().get(0).startsWith("INSERT_"),
                "F08 contract requires exactly one INSERT workload");
        require(context.shape().widths().equals(List.of(1)),
                "F08 contract requires width 1");
        require(context.timing().sqlSemanticOracle(),
                "F08 contract requires SQL semantic oracle");
        requireFreshSingleInterval(context);

        boolean bare = BARE.equals(context.shape().tableShape());
        boolean primaryKey = PRIMARY_KEY_ONLY.equals(context.shape().tableShape());
        require(bare || primaryKey,
                "F08 contract requires BARE or PRIMARY_KEY_ONLY table shape");
        require((!bare || controls.mvccGen2A1Server())
                        && (!primaryKey || controls.mvccGen2BServer())
                        && controls.mvccTransactionStatusVisibilityServer(),
                "F08 contract server mode does not match table shape/status visibility");
        if (context.targets().transactionStatusAttributionTargets()) {
            require(PROFILE_TARGETS.equals(context.profileServerTargets()),
                    "F08 transaction-status attribution requires server profiling for " + PROFILE_TARGETS);
        }
    }

    private static void requireMutuallyCompatibleExperiments(Controls controls) {
        require(!controls.branchRoutingSnapshotServer() || controls.rootRoutingSnapshotServer(),
                "F08 branch-routing snapshot requires root-routing snapshot");
    }

    private static void requireNoSharedAuthorityExperiment(Controls controls, String message) {
        require(!controls.rootRoutingSnapshotServer()
                        && !controls.branchRoutingSnapshotServer(),
                message);
    }

    private static void requireFreshSingleInterval(Context context) {
        Timing timing = context.timing();
        require(timing.warmups() == 0
                        && timing.iterations() == 1
                        && Double.compare(timing.minimumWarmupSeconds(), 0.0d) == 0
                        && timing.maximumWarmupIterations() == 1
                        && Double.compare(timing.minimumMeasuredSeconds(), 0.0d) == 0
                        && timing.maximumMeasuredIterations() == 1,
                "F08 contract requires one fresh measured INSERT interval per worker");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    record Context(
            TargetScope targets,
            RunShape shape,
            Timing timing,
            Controls controls,
            String profileServerTargets) {
    }

    record TargetScope(
            boolean fullServerTargets,
            boolean transactionStatusAttributionTargets) {
    }

    record RunShape(
            List<String> workloads,
            List<Integer> clients,
            List<Integer> widths,
            String tableShape,
            int payload) {
    }

    record Timing(
            boolean sqlSemanticOracle,
            int warmups,
            int iterations,
            double minimumWarmupSeconds,
            int maximumWarmupIterations,
            double minimumMeasuredSeconds,
            int maximumMeasuredIterations) {
    }

    record Controls(
            boolean transactionStatusCrossEngine,
            boolean fixedCostClientScaling,
            boolean contentionScalingSlice,
            boolean mvccGen2A1Server,
            boolean mvccGen2BServer,
            boolean mvccTransactionStatusVisibilityServer,
            boolean multiRowInsertControl,
            boolean rootRoutingSnapshotServer,
            boolean branchRoutingSnapshotServer) {

        boolean hasF08Controls() {
            return transactionStatusCrossEngine
                    || fixedCostClientScaling
                    || contentionScalingSlice
                    || multiRowInsertControl
                    || rootRoutingSnapshotServer
                    || branchRoutingSnapshotServer;
        }
    }
}
