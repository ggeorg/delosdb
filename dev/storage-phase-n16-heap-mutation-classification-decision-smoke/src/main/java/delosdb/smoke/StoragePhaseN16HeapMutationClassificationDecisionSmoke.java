/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package delosdb.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * N1.6 source-shape guard for deciding the first safe heap mutation live route.
 */
public final class StoragePhaseN16HeapMutationClassificationDecisionSmoke {
    private StoragePhaseN16HeapMutationClassificationDecisionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveDecisionDocument();
        proveAdapterStillInternalOnly();
        provePriorAdapterProofRemainsPresent();
        proveNoHeapMutationSqlRoutingYet();
        proveNoGenericHeapMutationContractYet();
        proveGradleWiring();
        System.out.println("storage_phase_n16_heap_mutation_classification_decision: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n16-heap-mutation-classification-decision.md"), List.of(
                "Storage Phase N1.6 — Heap mutation classification decision",
                "N2 may start with heap INSERT only",
                "DelosHeapInsertResultSet",
                "property-gated heap INSERT live route",
                "EngineHeapRowChangerMutationAdapter as the implementation seam",
                "unsupported INSERT shapes fall back to ordinary Derby InsertResultSet",
                "heap DELETE live route",
                "heap UPDATE live route",
                "EngineHeapMutableTableAccess",
                "generic DelosMutableTableAccess.tryLock(...)",
                "After N1.6 is green, the next safe step is",
                "N2 — property-gated heap INSERT live route"));
    }

    private static void proveAdapterStillInternalOnly() throws Exception {
        Path adapter = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapRowChangerMutationAdapter.java");
        assertSourceContains(adapter, List.of(
                "public final class EngineHeapRowChangerMutationAdapter implements AutoCloseable",
                "private final RowChanger rowChanger",
                "public RowLocation insert(ExecRow row)",
                "public void update(ExecRow oldRow, ExecRow newRow, RowLocation rowLocation)",
                "public void delete(ExecRow row, RowLocation rowLocation)",
                "must remain internal-only",
                "Do not wire this adapter from GenericResultSetFactory"));
        assertSourceNotContains(adapter, List.of(
                "implements DelosMutableTableAccess",
                "tryLock(",
                "reserveMutation("));
    }

    private static void provePriorAdapterProofRemainsPresent() throws Exception {
        Path proof = Path.of(
                "dev/storage-phase-n15-heap-rowchanger-adapter-proof-smoke/src/main/java/delosdb/smoke/StoragePhaseN15HeapRowChangerAdapterProofSmoke.java");
        assertSourceContains(proof, List.of(
                "EngineHeapRowChangerMutationAdapter.open",
                "adapter.insert(inserted)",
                "adapter.update(oldUpdated, newUpdated, updateLocation)",
                "adapter.delete(deleted, deleteLocation)",
                "verifyStoragePhaseN15HeapRowChangerAdapterProof"));
    }

    private static void proveNoHeapMutationSqlRoutingYet() throws Exception {
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapInsertResultSet.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapDeleteResultSet.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapUpdateResultSet.java"));

        Path factory = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java");
        assertSourceContains(factory, List.of(
                "DelosInsertResultSet.createIfEnabled(params)",
                "return new InsertResultSet(params)",
                "DelosDeleteResultSet.createIfEnabled(source, activation)",
                "return new DeleteResultSet(source, activation",
                "DelosUpdateResultSet.createIfEnabled(",
                "source, generationClauses, checkGM, activation)"));
        assertSourceNotContains(factory, List.of(
                "DelosHeapInsertResultSet",
                "DelosHeapDeleteResultSet",
                "DelosHeapUpdateResultSet"));
    }

    private static void proveNoGenericHeapMutationContractYet() throws Exception {
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapMutableTableAccess.java"));

        Path kernel = Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java");
        if (Files.exists(kernel)) {
            assertSourceNotContains(kernel, List.of(
                    "tryLock(",
                    "reserveMutation(",
                    "RowLocation"));
        }

        Path proofAdapter = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessProof.java");
        if (Files.exists(proofAdapter)) {
            assertSourceNotContains(proofAdapter, List.of(
                    "implements DelosMutableTableAccess",
                    "tryLock(",
                    "reserveMutation("));
        }
    }

    private static void proveGradleWiring() throws Exception {
        assertSourceContains(Path.of("gradle/storage-native-execution-closeout.gradle"), List.of(
                "storage-phase-n16-heap-mutation-classification-decision-smoke",
                "compileStoragePhaseN16HeapMutationClassificationDecisionSmoke",
                "storagePhaseN16HeapMutationClassificationDecisionSmoke",
                "verifyStoragePhaseN16HeapMutationClassificationDecision",
                "verifyStoragePhaseN15HeapRowChangerAdapterProof",
                "StoragePhaseN16HeapMutationClassificationDecisionSmoke",
                "through N1.6"));
        assertSourceContains(Path.of("gradle/delosdb-permanent-storage-guards.gradle"), List.of(
                "through N1.6 heap mutation classification decision"));
    }

    private static void assertPathMissing(Path path) {
        require(!Files.exists(path), path + " must not exist before N2/N3 starts");
    }

    private static void assertSourceContains(Path path, List<String> markers) throws Exception {
        String source = Files.readString(path);
        for (String marker : markers) {
            require(source.contains(marker), path + " is missing required N1.6 marker: " + marker);
        }
    }

    private static void assertSourceNotContains(Path path, List<String> forbidden) throws Exception {
        String source = Files.readString(path);
        for (String marker : forbidden) {
            require(!source.contains(marker), path + " must not contain N1.6-forbidden marker: " + marker);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
