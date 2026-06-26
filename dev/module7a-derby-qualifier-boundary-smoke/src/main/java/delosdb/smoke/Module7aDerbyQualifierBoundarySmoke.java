package delosdb.smoke;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MODULE7A smoke: source-gated Derby predicate / qualifier boundary map.
 *
 * <p>This is an explicit source-map module.  Source checks are allowed here
 * because the deliverable is a boundary map, not a behavior proof.  Normal
 * MODULE7 behavior smokes should remain runtime-only.</p>
 */
public final class Module7aDerbyQualifierBoundarySmoke {
    private Module7aDerbyQualifierBoundarySmoke() {
    }

    public static void main(String[] args) throws Exception {
        assertDocumentedBoundaryMap();
        assertTableScanPassesQualifiersToStore();
        assertStoreQualifierContractIsMapped();
        assertProjectRestrictResidualPredicatePathIsMapped();
        assertMvccScanCurrentQualifierGapIsMapped();
        assertDeleteAndUpdateMutationSourcesAreMapped();
    }

    private static void assertDocumentedBoundaryMap() throws Exception {
        String doc = read("docs/storage/mvcc-derby-qualifier-boundary.md");
        requireContains(doc, "MODULE7A Derby predicate / qualifier boundary map");
        requireContains(doc, "store qualifiers passed to ScanController");
        requireContains(doc, "residual generated restriction above the scan");
        requireContains(doc, "not yet qualifier-aware");
        requireContains(doc, "DELETE mutates the rows selected by its source result set");
        requireContains(doc, "UPDATE mutates the rows selected by its source result set");
        requireContains(doc, "behavior modules must remain runtime-focused");
    }

    private static void assertTableScanPassesQualifiersToStore() throws Exception {
        String tableScan = read("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/TableScanResultSet.java");
        requireContains(tableScan, "public    Qualifier[][] qualifiers;");
        requireContains(tableScan, "this.qualifiers = qualifiers;");

        String bulkScan = read("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/BulkTableScanResultSet.java");
        requireContains(bulkScan, "tc.openCompiledScan(");
        requireContains(bulkScan, "qualifiers,");

        String ramTransaction = read("delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMTransaction.java");
        requireContains(ramTransaction, "public ScanController openCompiledScan(");
        requireContains(ramTransaction, "Qualifier                       qualifier[][]");
        requireContains(ramTransaction, "openScan(");
    }

    private static void assertStoreQualifierContractIsMapped() throws Exception {
        String qualifier = read("delosdb-storage-derby/src/main/java/org/apache/derby/iapi/store/access/Qualifier.java");
        requireContains(qualifier, "public interface Qualifier");
        requireContains(qualifier, "int getColumnId();");
        requireContains(qualifier, "StoreDataValue getOrderable() throws StandardException;");
        requireContains(qualifier, "int getOperator();");
        requireContains(qualifier, "boolean negateCompareResult();");
        requireContains(qualifier, "boolean getOrderedNulls();");
        requireContains(qualifier, "boolean getUnknownRV();");
    }

    private static void assertProjectRestrictResidualPredicatePathIsMapped() throws Exception {
        String projectRestrict = read("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/ProjectRestrictResultSet.java");
        requireContains(projectRestrict, "public GeneratedMethod restriction;");
        requireContains(projectRestrict, "candidateRow = source.getNextRowCore();");
        requireContains(projectRestrict, "restriction.invoke(activation)");
        requireContains(projectRestrict, "rowsFiltered++");
    }

    private static void assertMvccScanCurrentQualifierGapIsMapped() throws Exception {
        String conglom = read("delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerate.java");
        requireContains(conglom, "Qualifier[][] qualifier");
        requireContains(conglom, "return new MvccScanController(this, xactManager, hold);");

        String scan = read("delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccScanController.java");
        requireContains(scan, "public void reopenScan(");
        requireContains(scan, "Qualifier[][] qualifier");
        requireContains(scan, "scan = state.table().openScan(snapshot, state.transactions());");
        requireContains(scan, "public boolean fetchNext(StoreDataValue[] destRow)");
        requireContains(scan, "copyCurrentRow(destRow, null);");
    }

    private static void assertDeleteAndUpdateMutationSourcesAreMapped() throws Exception {
        String deleteResultSet = read("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DeleteResultSet.java");
        requireContains(deleteResultSet, "source.openCore();");
        requireContains(deleteResultSet, "row = getNextRowCore(source);");
        requireContains(deleteResultSet, "rc.deleteRow(row,baseRowLocation);");

        String updateResultSet = read("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/UpdateResultSet.java");
        requireContains(updateResultSet, "sourceResultSet.openCore();");
        requireContains(updateResultSet, "row = getNextRowCore(sourceResultSet);");
        requireContains(updateResultSet, "rowChanger.updateRow(row,newBaseRow,baseRowLocation);");

        String rowChanger = read("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java");
        requireContains(rowChanger, "baseCC.delete(baseRowLocation);");
        requireContains(rowChanger, "baseCC.replace(baseRowLocation,");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void requireContains(String source, String expected) {
        if (!source.contains(expected)) {
            throw new AssertionError("MODULE7A source boundary expected to contain: " + expected);
        }
    }
}
