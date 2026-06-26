package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * MODULE6A smoke: source-gated Derby store/access MVCC boundary map.
 *
 * <p>This smoke intentionally does not implement a new access method. It proves
 * the current boundary facts that Plan 3 must attack next: heap and btree still
 * work, delos_mvcc provider identity is catalog-visible, the current CREATE
 * TABLE physical conglomerate path is still heap, and the current MVCC SQL
 * execution path still has transitional Delos ResultSet seams.</p>
 */
public final class Module6aDerbyStoreAccessBoundarySmoke {
    private static final String DATABASE_PATH = "build/module6a-derby-store-access-boundary-db";
    private static final String MVCC_TABLE = "MODULE6A_MVCC";
    private static final String HEAP_TABLE = "MODULE6A_HEAP";

    private static final List<String> NATIVE_ROUTE_PROPERTIES = List.of(
            "delosdb.storage.phaseF3.tableScanBranchProbe",
            "delosdb.storage.phaseF5.nativeMvccInsert",
            "delosdb.storage.phaseG3.nativeSelectAll",
            "delosdb.storage.phaseF4.nativeMvccSelectEquality",
            "delosdb.storage.phaseG1.nativeRangePredicates",
            "delosdb.storage.phaseG2.nativeBetweenPredicates",
            "delosdb.storage.phaseL31.nativeNullPredicates",
            "delosdb.storage.phaseL33.nativeOrPredicateResidual",
            "delosdb.storage.phaseL34.nativeProjectionVariants",
            "delosdb.storage.phaseL35.nativeOrderByResidual",
            "delosdb.storage.phaseG4.nativeCountAggregate",
            "delosdb.storage.phaseF6.nativeMvccDeleteEquality",
            "delosdb.storage.phaseF7.nativeMvccUpdateEquality");

    private Module6aDerbyStoreAccessBoundarySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        try {
            assertBoundaryDocumentExists();
            assertInheritedSourceBoundaryFacts();
            assertRuntimeCatalogAndHeapBtreeStillWork();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertBoundaryDocumentExists() throws Exception {
        String document = Files.readString(Path.of("docs/storage/derby-store-access-boundary.md"));
        requireContains(document,
                "Final Derby store/access provider integration is still incomplete.",
                "MODULE6A boundary document must keep the honest status");
        requireContains(document,
                "physical Derby conglomerate created by CREATE TABLE is still heap",
                "MODULE6A boundary document must name the heap-conglomerate contradiction");
        requireContains(document,
                "MODULE6B — MVCC access-method registration preflight",
                "MODULE6A boundary document must freeze the next store/access step");
    }

    private static void assertInheritedSourceBoundaryFacts() throws Exception {
        String createTable = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/CreateTableConstantAction.java"));
        requireContains(createTable,
                "tc.createConglomerate(",
                "CREATE TABLE must still enter TransactionController.createConglomerate");
        requireContains(createTable,
                "\"heap\", // we're requesting a heap conglomerate",
                "MODULE6A records the current fact that CREATE TABLE still requests heap");

        String tableScan = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/TableScanResultSet.java"));
        requireContains(tableScan,
                "tc.openCompiledScan(",
                "inherited TableScanResultSet must still open through TransactionController.openCompiledScan");

        String rowChanger = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java"));
        requireContains(rowChanger,
                "baseCC.insertAndFetchLocation(baseRow.getRowArray(), baseRowLocation)",
                "inherited INSERT path must still use ConglomerateController.insertAndFetchLocation");
        requireContains(rowChanger,
                "baseCC.delete(baseRowLocation)",
                "inherited DELETE path must still use ConglomerateController.delete(RowLocation)");
        requireContains(rowChanger,
                "baseCC.replace(baseRowLocation,",
                "inherited UPDATE path must still use ConglomerateController.replace(RowLocation,...)");

        String accessManager = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMAccessManager.java"));
        requireContains(accessManager,
                "findMethodFactoryByImpl(String impltype)",
                "RAMAccessManager must still expose implementation-type lookup");
        requireContains(accessManager,
                "registerAccessMethod(MethodFactory factory)",
                "RAMAccessManager must still expose access-method registration");

        String ramTransaction = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMTransaction.java"));
        requireContains(ramTransaction,
                "accessmanager.findMethodFactoryByImpl(implementation)",
                "RAMTransaction.createConglomerate must still resolve implementation type through RAMAccessManager");
        requireContains(ramTransaction,
                "cfactory.createConglomerate(",
                "RAMTransaction.createConglomerate must still delegate physical creation to ConglomerateFactory");
        requireContains(ramTransaction,
                "openCompiledScan(",
                "RAMTransaction must still expose inherited openCompiledScan path");

        String conglomerateFactory = Files.readString(Path.of(
                "delosdb-derby-store-api/src/main/java/org/apache/derby/iapi/store/access/conglomerate/ConglomerateFactory.java"));
        requireContains(conglomerateFactory,
                "int getConglomerateFactoryId()",
                "ConglomerateFactory must still define factory ids");
        requireContains(conglomerateFactory,
                "Conglomerate createConglomerate(",
                "ConglomerateFactory must still define physical conglomerate creation");

        String resultSetFactory = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"));
        requireContains(resultSetFactory,
                "DelosTableScanResultSet.createIfEnabled",
                "MODULE6A records the current SELECT transitional result-set seam");
        requireContains(resultSetFactory,
                "DelosInsertResultSet.createIfEnabled",
                "MODULE6A records the current INSERT transitional result-set seam");
        requireContains(resultSetFactory,
                "DelosDeleteResultSet.createIfEnabled",
                "MODULE6A records the current DELETE transitional result-set seam");
        requireContains(resultSetFactory,
                "DelosUpdateResultSet.createIfEnabled",
                "MODULE6A records the current UPDATE transitional result-set seam");
    }

    private static void assertRuntimeCatalogAndHeapBtreeStillWork() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + " (id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6A_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP." + HEAP_TABLE + " WHERE id = 1"),
                    "heap table and btree index compatibility should remain green during MODULE6A");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE + " (id INT, name VARCHAR(32)) USING delos_mvcc");
            SmokeUtils.assertEquals("delos_mvcc", storageProvider(statement, MVCC_TABLE),
                    "delos_mvcc provider identity must remain catalog-visible");
            SmokeUtils.assertEquals(null, storageProvider(statement, HEAP_TABLE),
                    "heap table must not get delos_mvcc provider identity");
            if (!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE)) {
                throw new AssertionError("delos_mvcc table identity was not registered for current provider route");
            }
            if (DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_TABLE)) {
                throw new AssertionError("heap table was incorrectly registered as delos_mvcc");
            }
        }
    }

    private static String storageProvider(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT STORAGEPROVIDER FROM SYS.SYSTABLES WHERE TABLENAME = '" + tableName + "'")) {
            if (!rows.next()) {
                throw new AssertionError("No SYSTABLES row found for " + tableName);
            }
            String provider = rows.getString(1);
            if (rows.next()) {
                throw new AssertionError("More than one SYSTABLES row found for " + tableName);
            }
            return provider;
        }
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NATIVE_ROUTE_PROPERTIES) {
            if (Boolean.getBoolean(propertyName)) {
                throw new AssertionError("MODULE6A must not rely on old native proof property: " + propertyName);
            }
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NATIVE_ROUTE_PROPERTIES) {
            System.clearProperty(propertyName);
        }
    }

    private static void requireContains(String source, String expected, String label) {
        if (source == null || !source.contains(expected)) {
            throw new AssertionError(label + " expected source to contain: " + expected);
        }
    }
}
