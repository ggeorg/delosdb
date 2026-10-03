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

/**
 * Owns the configuration contract for F08 INSERT diagnostics and controls.
 *
 * <p>The Gradle task identifies the intended contract. This class then validates
 * the actual runtime configuration used by both coordinator and worker JVMs.
 * Experiment-specific evidence checks remain with the experiment that produces
 * those artifacts; workload shape and incompatible-control rules live here.</p>
 */
final class F08BenchmarkContract {
    static final String NONE = "";
    static final String TRANSACTION_STATUS = "f08-transaction-status";
    static final String FIXED_COST_SCALING = "f08-fixed-cost-scaling";
    static final String CONTENTION_SLICE = "f08-contention-slice";
    static final String BTREE_LEAF_LATCH_PROOF = "f08-btree-leaf-latch-proof";
    static final String BTREE_SPLIT_AUTHORITY_PROOF = "f08-btree-split-authority-proof";
    static final String DURABLE_COMMIT_CANONICAL = "rawstore-durable-commit-canonical";

    private static final String INSERT_100 = "INSERT_100";
    private static final String BARE = "BARE";
    private static final String PRIMARY_KEY_ONLY = "PRIMARY_KEY_ONLY";
    private static final String PROFILE_TARGETS =
            "delos_heap_drda,delos_mvcc_drda,upstream_derby_drda";

    private F08BenchmarkContract() {
    }

    static Result validate(String contractId, Context context) {
        String normalized = contractId == null ? NONE : contractId.trim().toLowerCase(Locale.ROOT);
        boolean f08FlagsPresent = context.controls().transactionStatusCrossEngine()
                || context.controls().fixedCostClientScaling()
                || context.controls().contentionScalingSlice()
                || context.controls().btreeLeafLatchProof()
                || context.controls().rawStoreDurableCommitServer()
                || context.controls().rawStoreDurableCommitDiagnosticsServer();
        if (normalized.isEmpty()) {
            if (f08FlagsPresent) {
                throw new IllegalArgumentException(
                        "F08 benchmark controls require an explicit benchmark contract");
            }
            return Result.inactive();
        }

        return switch (normalized) {
            case TRANSACTION_STATUS -> validateTransactionStatus(context, false, false);
            case FIXED_COST_SCALING -> validateTransactionStatus(context, true, false);
            case CONTENTION_SLICE -> validateTransactionStatus(context, true, true);
            case BTREE_LEAF_LATCH_PROOF -> validateBTreeProof(context, false);
            case BTREE_SPLIT_AUTHORITY_PROOF -> validateBTreeProof(context, true);
            case DURABLE_COMMIT_CANONICAL -> validateDurableCommit(context);
            default -> throw new IllegalArgumentException(
                    "Unknown F08 benchmark contract: " + contractId);
        };
    }

    private static Result validateTransactionStatus(
            Context context, boolean fixedCost, boolean contentionSlice) {
        require(context.controls().transactionStatusCrossEngine(),
                "F08 transaction-status contract requires f08TransactionStatusCrossEngine=true");
        require(context.controls().fixedCostClientScaling() == fixedCost,
                "F08 contract fixed-cost mode does not match task configuration");
        require(context.controls().contentionScalingSlice() == contentionSlice,
                "F08 contract contention-slice mode does not match task configuration");
        require(!context.controls().btreeLeafLatchProof(),
                "F08 transaction-status contract cannot use the B-tree proof flag");
        require(!context.controls().rawStoreDurableCommitDiagnosticsServer()
                        && !context.controls().rawStoreDurableCommitServer(),
                "Durable commit comparison requires its dedicated benchmark contract");

        boolean attributionTargets = context.targets().transactionStatusAttributionTargets();
        require(context.targets().fullServerTargets() || attributionTargets,
                "F08 transaction-status contract requires the full SERVER matrix or attribution targets");
        require(context.shape().workloads().size() == 1 && context.shape().workloads().get(0).startsWith("INSERT_"),
                "F08 transaction-status cross-engine diagnostic requires exactly one INSERT workload");

        List<Integer> actualClients = context.shape().clients();
        if (contentionSlice) {
            require(actualClients.size() == 1 && List.of(1, 2, 4, 8).contains(actualClients.get(0)),
                    "F08 contention-scaling slice has invalid clients=" + actualClients);
        } else {
            List<Integer> expectedClients = fixedCost ? List.of(1, 2, 4, 8) : List.of(8);
            require(actualClients.equals(expectedClients),
                    "F08 transaction-status cross-engine diagnostic has invalid clients=" + actualClients);
        }
        require(context.shape().widths().equals(List.of(1)),
                "F08 transaction-status cross-engine diagnostic requires width 1");
        require(context.timing().sqlSemanticOracle(),
                "F08 transaction-status cross-engine diagnostic requires SQL semantic oracle");
        requireFreshSingleInterval(context,
                "F08 transaction-status cross-engine diagnostic requires one fresh measured INSERT interval per worker");

        boolean bare = BARE.equals(context.shape().tableShape());
        boolean primaryKey = PRIMARY_KEY_ONLY.equals(context.shape().tableShape());
        require(bare || primaryKey,
                "F08 transaction-status cross-engine diagnostic requires BARE or PRIMARY_KEY_ONLY");
        require((!bare || context.controls().mvccGen2A1Server())
                        && (!primaryKey || context.controls().mvccGen2BServer())
                        && context.controls().mvccTransactionStatusVisibilityServer(),
                "F08 transaction-status cross-engine diagnostic server mode does not match table shape/status visibility");

        if (fixedCost) {
            boolean validTargets = contentionSlice
                    ? attributionTargets
                    : context.targets().fullServerTargets();
            boolean multiInsertBareScaling = !contentionSlice
                    && context.controls().rawStoreMultiInsertPageServer()
                    && bare;
            require(validTargets
                            && context.shape().workloads().equals(List.of(INSERT_100))
                            && (primaryKey || multiInsertBareScaling)
                            && context.shape().payload() == 16
                            && !context.controls().multiRowInsertControl(),
                    contentionSlice
                            ? "F08 contention-scaling slice requires Derby-family SERVER targets, "
                                    + "PRIMARY_KEY_ONLY INSERT_100, payload=16, and JDBC batch shape"
                            : "F08 fixed-cost client scaling requires the full SERVER matrix, "
                                    + (context.controls().rawStoreMultiInsertPageServer()
                                            ? "BARE or PRIMARY_KEY_ONLY INSERT_100"
                                            : "PRIMARY_KEY_ONLY INSERT_100")
                                    + ", payload=16, and JDBC batch shape");
        }

        if (context.controls().multiRowInsertControl()) {
            require(context.targets().fullServerTargets()
                            && context.shape().workloads().equals(List.of(INSERT_100))
                            && primaryKey,
                    "F08 multi-row INSERT control requires the full SERVER matrix, PRIMARY_KEY_ONLY, and INSERT_100");
        }

        if (attributionTargets) {
            require(PROFILE_TARGETS.equals(context.profileServerTargets()),
                    "F08 transaction-status attribution requires server profiling for " + PROFILE_TARGETS);
        }

        return new Result(true, attributionTargets, false);
    }

    private static Result validateBTreeProof(Context context, boolean structuralProof) {
        require(!context.controls().transactionStatusCrossEngine()
                        && !context.controls().fixedCostClientScaling()
                        && !context.controls().contentionScalingSlice(),
                "F08 B-tree proof cannot enable transaction-status scaling modes");
        require(context.controls().btreeLeafLatchProof(),
                "F08 B-tree proof contract requires f08BTreeLeafLatchProof=true");
        require(context.controls().btreeStructuralDiagnostics() == structuralProof,
                structuralProof
                        ? "F08 B-tree split-authority proof requires structural diagnostics"
                        : "F08 B-tree leaf-latch proof must not enable structural diagnostics");
        require(context.targets().delosHeapOnlyTargets()
                        && context.shape().workloads().equals(List.of(INSERT_100))
                        && context.shape().clients().equals(List.of(1, 2, 4, 8))
                        && context.shape().widths().equals(List.of(1))
                        && PRIMARY_KEY_ONLY.equals(context.shape().tableShape())
                        && context.shape().payload() == 16
                        && context.controls().pageLatchDiagnostics()
                        && context.controls().heapAuthorityDiagnostics()
                        && !context.controls().rawStorePageValiditySnapshotServer()
                        && !context.controls().rawStorePreframedLogAppendServer()
                        && !context.controls().rawStoreCombinedLogAppendServer()
                        && !context.controls().rawStoreConcurrentLogAppendServer()
                        && !context.controls().rawStoreMultiInsertPageServer()
                        && !context.controls().btreeInsertRootRoutingSnapshotServer()
                        && !context.controls().btreeInsertBranchRoutingSnapshotServer()
                        && !context.controls().multiRowInsertControl(),
                "F08 B-tree proof requires embedded Delos Heap, PRIMARY_KEY_ONLY INSERT_100, "
                        + "payload=16, clients=1,2,4,8, page-latch/heap-authority diagnostics, "
                        + "and no competing F08 RawStore/B-tree experiment");
        requireFreshSingleInterval(context,
                "F08 B-tree proof requires one fresh measured INSERT interval per worker");
        return new Result(true, false, true);
    }

    private static Result validateDurableCommit(Context context) {
        require(context.controls().transactionStatusCrossEngine()
                        && !context.controls().fixedCostClientScaling()
                        && !context.controls().contentionScalingSlice()
                        && !context.controls().btreeLeafLatchProof(),
                "Durable commit comparison requires the canonical F08 transaction-status lane");
        require(context.targets().fullServerTargets()
                        && context.shape().workloads().equals(List.of(INSERT_100))
                        && context.shape().widths().equals(List.of(1)),
                "Durable commit comparison requires the full SERVER matrix, INSERT_100, and width 1");
        require(context.shape().clients().equals(List.of(8)),
                "Durable commit comparison has invalid clients=" + context.shape().clients()
                        + "; requires exactly 8 clients");
        require(PRIMARY_KEY_ONLY.equals(context.shape().tableShape()),
                "Durable commit comparison table shape must be PRIMARY_KEY_ONLY");
        require(context.shape().payload() == 128,
                "Durable commit comparison requires payload=128");
        require(context.timing().sqlSemanticOracle()
                        && context.controls().mvccGen2BServer()
                        && context.controls().mvccTransactionStatusVisibilityServer(),
                "Durable commit comparison requires SQL semantic oracle and Gen2-B transaction-status visibility");
        requireFreshSingleInterval(context,
                "Durable commit comparison requires one fresh measured INSERT interval per worker");
        require(context.controls().rawStoreDurableCommitDiagnosticsServer(),
                "Durable commit comparison requires coordinator diagnostics");
        require(context.controls().rawStorePageValiditySnapshotServer()
                        && context.controls().rawStoreMultiInsertPageServer()
                        && context.controls().btreeInsertRootRoutingSnapshotServer()
                        && context.controls().btreeInsertBranchRoutingSnapshotServer(),
                "Durable commit comparison requires the established shared-authority stack");
        require(context.controls().rawStoreLogBufferSizeOverride().isEmpty()
                        && !context.controls().rawStoreDurabilityTestNoSyncServer()
                        && !context.controls().rawStoreConcurrentLogAppendServer()
                        && !context.controls().rawStorePreframedLogAppendServer()
                        && !context.controls().rawStoreCombinedLogAppendServer(),
                "Durable commit comparison requires normal durability, default log buffer, and inherited WAL append");
        return new Result(true, false, false);
    }

    private static void requireFreshSingleInterval(Context context, String message) {
        require(context.timing().warmups() == 0
                        && context.timing().iterations() == 1
                        && Double.compare(context.timing().minimumWarmupSeconds(), 0.0d) == 0
                        && context.timing().maximumWarmupIterations() == 1
                        && Double.compare(context.timing().minimumMeasuredSeconds(), 0.0d) == 0
                        && context.timing().maximumMeasuredIterations() == 1,
                message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    record Result(boolean active, boolean transactionStatusAttribution, boolean btreeProof) {
        private static Result inactive() {
            return new Result(false, false, false);
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
            boolean transactionStatusAttributionTargets,
            boolean delosHeapOnlyTargets) {
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
            boolean btreeLeafLatchProof,
            boolean btreeStructuralDiagnostics,
            boolean mvccGen2A1Server,
            boolean mvccGen2BServer,
            boolean mvccTransactionStatusVisibilityServer,
            boolean pageLatchDiagnostics,
            boolean heapAuthorityDiagnostics,
            boolean multiRowInsertControl,
            boolean rawStorePageValiditySnapshotServer,
            boolean rawStorePreframedLogAppendServer,
            boolean rawStoreCombinedLogAppendServer,
            boolean rawStoreConcurrentLogAppendServer,
            boolean rawStoreMultiInsertPageServer,
            boolean rawStoreDurableCommitServer,
            boolean rawStoreDurableCommitDiagnosticsServer,
            String rawStoreLogBufferSizeOverride,
            boolean rawStoreDurabilityTestNoSyncServer,
            boolean btreeInsertRootRoutingSnapshotServer,
            boolean btreeInsertBranchRoutingSnapshotServer) {
    }

}
