package delosdb.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * N1.1 heap mutation context shape guard.
 *
 * <p>This smoke intentionally checks source shape only. N1.1 defines the
 * minimum honest RowChanger-backed heap mutation context for later direct
 * proofs; it must not introduce heap mutation SQL routing.</p>
 */
public final class StoragePhaseN11HeapMutationContextShapeSmoke {
    private StoragePhaseN11HeapMutationContextShapeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveDecisionDocument();
        proveExecutionFactoryContextShape();
        proveResultSetsAlreadyCarryRequiredContext();
        proveRowChangerRuntimeInputsRemainExplicit();
        proveM3HeapReadRouteStillReadOnly();
        proveNoHeapDeleteUpdateProviderActivation();
        System.out.println("storage_phase_n11_heap_mutation_context_shape: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n11-heap-mutation-context-shape.md"), List.of(
                "Storage Phase N1.1 — Heap mutation context shape",
                "N1.1 is a shape gate only",
                "Minimum honest context",
                "heapConglom",
                "heapSCOCI",
                "heapDCOCI",
                "irgs",
                "indexCIDS",
                "indexSCOCIs",
                "indexDCOCIs",
                "baseRowReadList",
                "baseRowReadMap",
                "TransactionController",
                "Activation",
                "lockMode decoded from compiled constants",
                "Do **not** start N2 yet",
                "N1.2 may build a direct, non-SQL-routed RowChanger-backed heap INSERT proof"));
    }

    private static void proveExecutionFactoryContextShape() throws Exception {
        Path executionFactory = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/iapi/sql/execute/ExecutionFactory.java");
        assertSourceContains(executionFactory, List.of(
                "getRowChanger(long heapConglom",
                "StaticCompiledOpenConglomInfo heapSCOCI",
                "DynamicCompiledOpenConglomInfo heapDCOCI",
                "IndexRowGenerator[] irgs",
                "long[] indexCIDS",
                "StaticCompiledOpenConglomInfo[] indexSCOCIs",
                "DynamicCompiledOpenConglomInfo[] indexDCOCIs",
                "int numberOfColumns",
                "TransactionController tc",
                "int[] changedColumnIds",
                "FormatableBitSet\tbaseRowReadList",
                "int[] baseRowReadMap",
                "Activation activation"));

        Path genericExecutionFactory = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericExecutionFactory.java");
        assertSourceContains(genericExecutionFactory, List.of(
                "return new RowChangerImpl( heapConglom",
                "heapSCOCI, heapDCOCI",
                "irgs, indexCIDS, indexSCOCIs, indexDCOCIs",
                "changedColumnIds, tc",
                "baseRowReadList",
                "baseRowReadMap, activation"));
    }

    private static void proveResultSetsAlreadyCarryRequiredContext() throws Exception {
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/InsertResultSet.java"), List.of(
                "heapConglom = constants.conglomId",
                "constants.heapSCOCI",
                "heapDCOCI",
                "constants.irgs",
                "constants.indexCIDS",
                "constants.indexSCOCIs",
                "indexDCOCIs",
                "constants.getStreamStorableHeapColIds()",
                "rowChanger.setIndexNames(constants.indexNames)",
                "int lockMode = decodeLockMode(constants.lockMode)",
                "rowChanger.open(lockMode)",
                "rowChanger.insertRow(row, false)",
                "rowChanger.insertRow(row, true)"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DeleteResultSet.java"), List.of(
                "baseRowReadList = constants.getBaseRowReadList()",
                "constants.heapSCOCI",
                "heapDCOCI",
                "constants.irgs",
                "constants.indexCIDS",
                "constants.indexSCOCIs",
                "indexDCOCIs",
                "baseRowReadList",
                "lockMode = decodeLockMode(constants.lockMode)",
                "rc.open(lockMode)",
                "(RowLocation) (rlColumn).getObject()",
                "rc.deleteRow(row,baseRowLocation)"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/UpdateResultSet.java"), List.of(
                "heapConglom = constants.conglomId",
                "baseRowReadList = constants.getBaseRowReadList()",
                "rowChanger = lcc.getLanguageConnectionFactory().getExecutionFactory()",
                "getRowChanger( heapConglom",
                "constants.heapSCOCI",
                "heapDCOCI",
                "constants.irgs",
                "constants.indexCIDS",
                "constants.indexSCOCIs",
                "indexDCOCIs",
                "constants.changedColumnIds",
                "constants.getBaseRowReadMap()",
                "rowChanger.setIndexNames(constants.indexNames)",
                "rowChanger.open(lockMode)",
                "RowLocation baseRowLocation",
                "rowChanger.updateRow(row,newBaseRow,baseRowLocation)"));
    }

    private static void proveRowChangerRuntimeInputsRemainExplicit() throws Exception {
        Path rowChanger = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java");
        assertSourceContains(rowChanger, List.of(
                "long heapConglom",
                "DynamicCompiledOpenConglomInfo heapDCOCI",
                "StaticCompiledOpenConglomInfo heapSCOCI",
                "IndexRowGenerator[] irgs",
                "long[] indexCIDS",
                "DynamicCompiledOpenConglomInfo[] indexDCOCIs",
                "StaticCompiledOpenConglomInfo[] indexSCOCIs",
                "private final Activation",
                "TransactionController\ttc",
                "FormatableBitSet \tbaseRowReadList",
                "private int[]\t\tbaseRowReadMap",
                "private ConglomerateController baseCC",
                "private RowLocation\tbaseRowLocation",
                "private IndexSetChanger isc",
                "public void open(int lockMode)",
                "public void open(int lockMode, boolean wait)",
                "public RowLocation insertRow(ExecRow baseRow, boolean getRL)",
                "public void deleteRow(ExecRow baseRow, RowLocation baseRowLocation)",
                "public void updateRow(ExecRow oldBaseRow",
                "baseCC.insertAndFetchLocation(baseRow.getRowArray(), baseRowLocation)",
                "baseCC.delete(baseRowLocation)",
                "baseCC.replace(baseRowLocation",
                "isc.insert(baseRow, baseRowLocation)",
                "isc.delete(baseRow, baseRowLocation)",
                "isc.update(oldBaseRow, newBaseRow, baseRowLocation)"));
    }

    private static void proveM3HeapReadRouteStillReadOnly() throws Exception {
        Path heapRead = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapLiveTableScanResultSet.java");
        assertSourceContains(heapRead, List.of(
                "M3 property-gated heap SELECT live route",
                "EngineHeapTableAccessLiveCandidate",
                "heapAccess.scan(heapContext(transactionController), List.of(), DelosProjection.all())"));
        assertSourceDoesNotContain(heapRead, List.of(
                "RowChangerImpl",
                "rowChanger",
                "insertRow(",
                "deleteRow(",
                "updateRow(",
                "DelosMutableTableAccess",
                "EngineHeapMutableTableAccess"));
    }

    private static void proveNoHeapDeleteUpdateProviderActivation() throws Exception {
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapMutableTableAccess.java"));
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapDeleteResultSet.java"));
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapUpdateResultSet.java"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "EngineMvccTableAccess"));
        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "EngineHeapMutableTableAccess",
                "EngineHeapTableAccessLiveCandidate"));

        Path mutableContract = Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java");
        if (Files.exists(mutableContract)) {
            assertSourceDoesNotContain(mutableContract, List.of(
                    "tryLock(",
                    "reserveMutation(",
                    "RowChangerImpl",
                    "ConglomerateController"));
        }
    }

    private static void assertSourceContains(Path path, List<String> markers) throws Exception {
        String source = readSource(path);
        for (String marker : markers) {
            require(source.contains(marker), path + " is missing required N1.1 marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path path, List<String> markers) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        String source = readSource(path);
        for (String marker : markers) {
            require(!source.contains(marker), path + " contains forbidden N1.1 marker: " + marker);
        }
    }

    private static void assertFileAbsent(Path path) {
        require(!Files.exists(path), "N1.1 must not introduce heap mutation provider file: " + path);
    }

    private static String readSource(Path path) throws Exception {
        require(Files.exists(path), "Missing N1.1 source path: " + path);
        return Files.readString(path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
