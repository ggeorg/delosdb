package delosdb.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * N1 heap mutation mapping proof.
 *
 * <p>This smoke intentionally performs source-shape verification only. N1 is a
 * feasibility/mapping gate, not a new heap mutation route.</p>
 */
public final class StoragePhaseN1HeapMutationMappingProofSmoke {
    private StoragePhaseN1HeapMutationMappingProofSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveDecisionDocument();
        proveRowChangerOwnsHeapMutationBehavior();
        proveHeapDmlStillUsesDerbyMutationStack();
        proveM3HeapReadRouteRemainsReadOnly();
        proveNoPrematureHeapMutationProviderContract();
        proveReferenceSourceResponsibilitiesWereChecked();
        System.out.println("storage_phase_n1_heap_mutation_mapping_proof: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n1-heap-mutation-mapping-proof.md"), List.of(
                "Storage Phase N1 — Heap mutation mapping proof",
                "N1 decision: **defer heap mutation parity**",
                "Do **not** start N2 yet",
                "RowChangerImpl is not just a low-level heap write primitive",
                "no generic heap mutation provider API is introduced",
                "N1.1 — define the minimum honest heap mutation context shape"));
    }

    private static void proveRowChangerOwnsHeapMutationBehavior() throws Exception {
        Path rowChanger = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java");
        String source = readSource(rowChanger);
        require(source.contains("class RowChangerImpl") && source.contains("implements")
                        && source.contains("RowChanger"),
                "Expected RowChangerImpl to remain Derby's RowChanger implementation");
        assertSourceContains(rowChanger, List.of(
                "private ConglomerateController baseCC",
                "private RowLocation",
                "baseRowLocation",
                "tc.openCompiledConglomerate(",
                "tc.openConglomerate(",
                "TransactionController.OPENMODE_FORUPDATE",
                "activation.setHeapConglomerateController(baseCC)",
                "new IndexSetChanger(",
                "isc.insert(baseRow, baseRowLocation)",
                "isc.delete(baseRow, baseRowLocation)",
                "isc.update(oldBaseRow, newBaseRow, baseRowLocation)",
                "baseCC.insertAndFetchLocation(baseRow.getRowArray(), baseRowLocation)",
                "baseCC.insert(baseRow.getRowArray())",
                "baseCC.delete(baseRowLocation)",
                "baseCC.replace(baseRowLocation",
                "public ConglomerateController getHeapConglomerateController()"));
    }

    private static void proveHeapDmlStillUsesDerbyMutationStack() throws Exception {
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/InsertResultSet.java"), List.of(
                "class InsertResultSet extends DMLWriteGeneratedColumnsResultSet implements TargetResultSet",
                "private RowChanger",
                "getExecutionFactory()",
                "getRowChanger(",
                "rowChanger.open(lockMode)",
                "rowChanger.insertRow(row, false)",
                "rowChanger.insertRow(row, true)"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DeleteResultSet.java"), List.of(
                "class DeleteResultSet extends DMLWriteResultSet",
                "protected RowChanger",
                "protected ConglomerateController",
                "getRowChanger(",
                "rc.open(lockMode)",
                "(RowLocation) (rlColumn).getObject()",
                "rc.deleteRow(row,baseRowLocation)",
                "source.markRowAsDeleted()"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/UpdateResultSet.java"), List.of(
                "class UpdateResultSet extends DMLWriteGeneratedColumnsResultSet",
                "getRowChanger(",
                "rowChanger.open(lockMode)",
                "RowLocation baseRowLocation",
                "sourceResultSet.updateRow(newBaseRow, rowChanger)",
                "rowChanger.updateRow(row,newBaseRow,baseRowLocation)"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosInsertResultSet.java"), List.of(
                "lookup.isEmpty() || lookup.get().isDefaultStorageProvider()",
                "return Optional.empty()"));
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosDeleteResultSet.java"), List.of(
                "lookup.isEmpty() || lookup.get().isDefaultStorageProvider()",
                "return Optional.empty()"));
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosUpdateResultSet.java"), List.of(
                "lookup.isEmpty() || lookup.get().isDefaultStorageProvider()",
                "return Optional.empty()"));
    }

    private static void proveM3HeapReadRouteRemainsReadOnly() throws Exception {
        Path heapRead = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapLiveTableScanResultSet.java");
        assertSourceContains(heapRead, List.of(
                "M3 property-gated heap SELECT live route",
                "!params.forUpdate",
                "EngineHeapTableAccessLiveCandidate",
                "heapAccess.scan(heapContext(transactionController), List.of(), DelosProjection.all())"));
        assertSourceDoesNotContain(heapRead, List.of(
                "insertRow(",
                "deleteRow(",
                "updateRow(",
                "RowChangerImpl",
                "ConglomerateController",
                "DelosMutableTableAccess",
                "reserveMutation("));

        Path candidate = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessLiveCandidate.java");
        assertSourceContains(candidate, List.of(
                "public final class EngineHeapTableAccessLiveCandidate implements DelosFilterableTableAccess",
                "public DelosScan scan(",
                "TransactionController#openScan",
                "TransactionController#openCompiledScan"));
        assertSourceDoesNotContain(candidate, List.of(
                "implements DelosMutableTableAccess",
                "insert(",
                "delete(",
                "update(",
                "RowChangerImpl",
                "ConglomerateController"));
    }

    private static void proveNoPrematureHeapMutationProviderContract() throws Exception {
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapInsertResultSet.java"));
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapDeleteResultSet.java"));
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapUpdateResultSet.java"));
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapMutableTableAccess.java"));

        Path mutableContract = Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java");
        if (Files.exists(mutableContract)) {
            assertSourceDoesNotContain(mutableContract, List.of(
                    "tryLock(",
                    "reserveMutation(",
                    "RowChangerImpl",
                    "ConglomerateController"));
        }

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "EngineMvccTableAccess"));
        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "EngineHeapMutableTableAccess",
                "EngineHeapTableAccessLiveCandidate"));
    }

    private static void proveReferenceSourceResponsibilitiesWereChecked() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n1-heap-mutation-mapping-proof.md"), List.of(
                "The uploaded PostgreSQL source has a table access method boundary for mutations",
                "The uploaded MariaDB source has handler-level write/update/delete methods",
                "The uploaded Apache Calcite source separates table modification planning"));
    }

    private static void assertSourceContains(Path path, List<String> markers) throws Exception {
        String source = readSource(path);
        for (String marker : markers) {
            require(source.contains(marker), path + " is missing required N1 marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path path, List<String> markers) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        String source = readSource(path);
        for (String marker : markers) {
            require(!source.contains(marker), path + " contains forbidden N1 marker: " + marker);
        }
    }

    private static void assertFileAbsent(Path path) {
        require(!Files.exists(path), "N1 must not introduce premature heap mutation file: " + path);
    }

    private static String readSource(Path path) throws Exception {
        require(Files.exists(path), "Missing N1 source path: " + path);
        return Files.readString(path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
