/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package delosdb.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * N1.4 guard: RowChanger-backed heap mutation is wrappable only behind a
 * narrow internal adapter first.  This guard deliberately proves the decision
 * and the absence of premature heap SQL mutation routing.
 */
public final class StoragePhaseN14HeapRowChangerAdapterDecisionSmoke {
    private StoragePhaseN14HeapRowChangerAdapterDecisionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveDecisionDocument();
        proveRowChangerSurfaceIsWrappable();
        provePriorDirectProofsStillExist();
        proveNoPrematureHeapMutationRouting();
        proveGradleWiring();
        System.out.println("storage_phase_n14_heap_rowchanger_adapter_decision: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n14-heap-rowchanger-adapter-decision.md"), List.of(
                "Decision: YES, but internal-only adapter first",
                "The safe next milestone is N1.5, not N2",
                "Do **not** start N2 yet",
                "Do **not** start N3 yet",
                "EngineHeapRowChangerMutationAdapter",
                "insert(ExecRow row) -> RowLocation",
                "update(ExecRow oldRow, ExecRow newRow, RowLocation rowLocation)",
                "delete(ExecRow row, RowLocation rowLocation)",
                "no SQL routing",
                "heap implementation of DelosMutableTableAccess",
                "heap row reservation",
                "heap locking parity claim"));
    }

    private static void proveRowChangerSurfaceIsWrappable() throws Exception {
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/iapi/sql/execute/RowChanger.java"), List.of(
                "RowLocation insertRow(ExecRow baseRow",
                "public void deleteRow(ExecRow baseRow, RowLocation baseRowLocation)",
                "public void updateRow(ExecRow oldBaseRow",
                "ExecRow newBaseRow",
                "RowLocation baseRowLocation"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java"), List.of(
                "baseCC.insertAndFetchLocation",
                "baseCC.insert(baseRow.getRowArray())",
                "baseCC.delete(baseRowLocation)",
                "baseCC.replace(baseRowLocation",
                "public void finish()"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/iapi/sql/execute/ExecutionFactory.java"), List.of(
                "getRowChanger(long heapConglom",
                "StaticCompiledOpenConglomInfo heapSCOCI",
                "DynamicCompiledOpenConglomInfo heapDCOCI",
                "TransactionController tc"));
    }

    private static void provePriorDirectProofsStillExist() throws Exception {
        assertSourceContains(Path.of(
                "dev/storage-phase-n12-heap-rowchanger-insert-proof-smoke/src/main/java/delosdb/smoke/StoragePhaseN12HeapRowChangerInsertProofSmoke.java"), List.of(
                "ExecutionFactory.getRowChanger",
                "rowChanger.open(TransactionController.MODE_RECORD)",
                "rowChanger.insertRow(row, true)",
                "setupContextStack",
                "restoreContextStack"));

        assertSourceContains(Path.of(
                "dev/storage-phase-n13-heap-rowchanger-delete-update-proof-smoke/src/main/java/delosdb/smoke/StoragePhaseN13HeapRowChangerDeleteUpdateProofSmoke.java"), List.of(
                "rowChanger.updateRow(oldRow, newRow, rowLocation)",
                "rowChanger.deleteRow(row, rowLocation)",
                "setupContextStack",
                "restoreContextStack"));
    }

    private static void proveNoPrematureHeapMutationRouting() throws Exception {
        Path adapterPath = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapRowChangerMutationAdapter.java");
        if (Files.exists(adapterPath)) {
            assertSourceContains(adapterPath, List.of(
                    "EngineHeapRowChangerMutationAdapter",
                    "must remain internal-only",
                    "RowChanger",
                    "insert(ExecRow row)",
                    "update(ExecRow oldRow",
                    "delete(ExecRow row"));
            assertSourceNotContains(adapterPath, List.of(
                    "implements DelosMutableTableAccess",
                    "DelosHeapInsertResultSet",
                    "DelosHeapDeleteResultSet",
                    "DelosHeapUpdateResultSet"));
        }
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapMutableTableAccess.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapInsertResultSet.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapDeleteResultSet.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapUpdateResultSet.java"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessLiveCandidate.java"), List.of(
                "avoids heap mutation",
                "provider registration"));

        assertSourceNotContains(Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java"), List.of(
                "tryLock(",
                "reserveMutation("));

        assertSourceNotContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"), List.of(
                "DelosHeapInsertResultSet",
                "DelosHeapDeleteResultSet",
                "DelosHeapUpdateResultSet"));
    }

    private static void proveGradleWiring() throws Exception {
        assertSourceContains(Path.of("gradle/storage-native-execution-closeout.gradle"), List.of(
                "storage-phase-n14-heap-rowchanger-adapter-decision-smoke",
                "compileStoragePhaseN14HeapRowChangerAdapterDecisionSmoke",
                "storagePhaseN14HeapRowChangerAdapterDecisionSmoke",
                "verifyStoragePhaseN14HeapRowChangerAdapterDecision",
                "dependsOn         : 'verifyStoragePhaseN13HeapRowChangerDeleteUpdateProof'",
                "Verified N1.4 heap RowChanger adapter decision without heap mutation SQL routing"));

        assertSourceContains(Path.of("gradle/delosdb-permanent-storage-guards.gradle"), List.of(
                "through N1.4 heap RowChanger adapter decision"));

        assertSourceContains(Path.of("scripts/delete-stale-storage-smoke-dbs.sh"), List.of(
                "storage-phase-n14-heap-rowchanger-adapter-decision-db"));
    }

    private static void assertSourceContains(Path path, List<String> markers) throws Exception {
        String source = read(path);
        for (String marker : markers) {
            require(source.contains(marker), path + " is missing required N1.4 marker: " + marker);
        }
    }

    private static void assertSourceNotContains(Path path, List<String> forbiddenMarkers) throws Exception {
        String source = read(path);
        for (String marker : forbiddenMarkers) {
            require(!source.contains(marker), path + " contains forbidden N1.4 marker: " + marker);
        }
    }

    private static void assertPathMissing(Path path) {
        require(!Files.exists(path), "N1.4 must not introduce premature heap mutation file: " + path);
    }

    private static String read(Path path) throws IOException {
        require(Files.exists(path), "Missing source path: " + path);
        return Files.readString(path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
