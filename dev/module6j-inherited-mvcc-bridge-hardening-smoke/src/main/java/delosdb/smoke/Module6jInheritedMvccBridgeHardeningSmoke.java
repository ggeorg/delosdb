package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE6J smoke: compensation/audit pass after retiring the transitional MVCC
 * Delos*ResultSet bypass layer.  This is deliberately a guard smoke, not a new
 * feature proof.
 */
public final class Module6jInheritedMvccBridgeHardeningSmoke {
    private static final String DATABASE_PATH = "build/module6j-inherited-mvcc-bridge-hardening-db";
    private static final String CRUD_TABLE = "MODULE6J_CRUD";
    private static final String DELETE_TABLE = "MODULE6J_DELETE";
    private static final String ROLLBACK_TABLE = "MODULE6J_ROLLBACK";
    private static final String HEAP_TABLE = "MODULE6J_HEAP";

    private Module6jInheritedMvccBridgeHardeningSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertSourceBridgeCutoverIsClean();
            assertDocumentationRecordsCurrentBoundary();
            assertRuntimeInheritedCrudIgnoresOldMvccProofProperties();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourceBridgeCutoverIsClean() throws Exception {
        for (String retiredFile : RetiredMvccResultsets.FILES) {
            require(!Files.exists(Path.of(retiredFile)),
                    "MODULE6J must keep retired MVCC result-set scaffold deleted: " + retiredFile);
        }

        String factory = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"));
        for (String retiredFactoryCall : RetiredMvccResultsets.FACTORY_CALLS) {
            requireNotContains(factory, retiredFactoryCall,
                    "MODULE6J must not reintroduce an MVCC GenericResultSetFactory bypass");
        }
        for (String retiredClassReference : RetiredMvccResultsets.CLASS_REFERENCES) {
            requireNotContains(factory, retiredClassReference,
                    "MODULE6J must not keep compile-time references to retired MVCC result sets in GenericResultSetFactory");
        }
        requireContains(factory,
                "return new TableScanResultSet(params);",
                "MODULE6J must keep SELECT on inherited TableScanResultSet");
        requireContains(factory,
                "return new InsertResultSet(params);",
                "MODULE6J must keep INSERT on inherited InsertResultSet");
        requireContains(factory,
                "return new DeleteResultSet(source, activation );",
                "MODULE6J must keep DELETE on inherited DeleteResultSet");
        requireContains(factory,
                "return new UpdateResultSet(UpdateResultSetParameters.normal(",
                "MODULE6J must keep UPDATE on inherited UpdateResultSet");

        String providerLookup = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanProviderLookup.java"));
        requireContains(providerLookup,
                "legacyNativeMvccCrudProofRoutesEnabledForTesting()",
                "MODULE6J must keep the old proof-route honesty guard visible");
        requireContains(providerLookup,
                "return false;",
                "MODULE6J proof-route honesty guard must remain disabled");
        for (String retiredClassReference : RetiredMvccResultsets.CLASS_REFERENCES) {
            requireNotContains(providerLookup, retiredClassReference + ".",
                    "MODULE6J must not keep executable references to retired MVCC result sets");
        }
    }

    private static void assertDocumentationRecordsCurrentBoundary() throws Exception {
        String status = Files.readString(Path.of("docs/storage/mvcc-derby-store-access-status.md"));
        requireContains(status,
                "CREATE TABLE ... USING delos_mvcc` creates an MVCC physical conglomerate",
                "MODULE6J status doc must record physical MVCC table creation");
        requireContains(status,
                "Normal inherited SQL `SELECT` reaches `TableScanResultSet`",
                "MODULE6J status doc must record inherited SELECT path");
        requireContains(status,
                "Normal inherited SQL `INSERT` reaches `InsertResultSet`",
                "MODULE6J status doc must record inherited INSERT path");
        requireContains(status,
                "Normal inherited SQL `DELETE` reaches `DeleteResultSet`",
                "MODULE6J status doc must record inherited DELETE path");
        requireContains(status,
                "Normal inherited SQL `UPDATE` reaches `UpdateResultSet`",
                "MODULE6J status doc must record inherited UPDATE path");
        requireContains(status,
                "WHERE predicate correctness for MVCC scans",
                "MODULE6J status doc must keep predicate limitation honest");
        requireContains(status,
                "Index-backed MVCC access",
                "MODULE6J status doc must keep index limitation honest");
    }

    private static void assertRuntimeInheritedCrudIgnoresOldMvccProofProperties() throws Exception {
        enableNativeMvccProofProperties();

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6J_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            statement.executeUpdate("UPDATE APP." + HEAP_TABLE + " SET name = 'heap2' WHERE id = 1");
            SmokeUtils.assertEquals(List.of("heap2"), names(statement, HEAP_TABLE),
                    "MODULE6J heap UPDATE and btree compatibility must remain green");
            statement.executeUpdate("DELETE FROM APP." + HEAP_TABLE + " WHERE id = 1");
            SmokeUtils.assertEquals(List.of(), names(statement, HEAP_TABLE),
                    "MODULE6J heap DELETE compatibility must remain green");

            statement.executeUpdate("CREATE TABLE APP." + CRUD_TABLE + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, CRUD_TABLE) & 0x0fL,
                    "MODULE6J delos_mvcc base table must not fall back to heap physical conglomerate");

            MvccConglomerateController.resetInsertCountForTesting();
            MvccConglomerateController.resetUpdateCountForTesting();
            MvccScanController.resetOpenCountForTesting();

            statement.executeUpdate("INSERT INTO APP." + CRUD_TABLE + " VALUES (101, 'before')");
            require(MvccConglomerateController.insertCountForTesting() > 0,
                    "MODULE6J INSERT must reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of("before"), names(statement, CRUD_TABLE),
                    "MODULE6J SELECT must see committed inherited MVCC INSERT");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6J SELECT must reach MvccScanController through inherited TableScanResultSet");

            statement.executeUpdate("UPDATE APP." + CRUD_TABLE + " SET name = 'after'");
            require(MvccConglomerateController.updateCountForTesting() > 0,
                    "MODULE6J UPDATE must reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of("after"), names(statement, CRUD_TABLE),
                    "MODULE6J SELECT must see committed inherited MVCC UPDATE");
            SmokeUtils.assertEquals(List.of(101), ids(statement, CRUD_TABLE),
                    "MODULE6J UPDATE must preserve non-updated MVCC columns");

            statement.executeUpdate("CREATE TABLE APP." + DELETE_TABLE + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + DELETE_TABLE + " VALUES (202, 'delete-me')");
            MvccConglomerateController.resetDeleteCountForTesting();
            statement.executeUpdate("DELETE FROM APP." + DELETE_TABLE);
            require(MvccConglomerateController.deleteCountForTesting() > 0,
                    "MODULE6J DELETE must reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of(), names(statement, DELETE_TABLE),
                    "MODULE6J SELECT must hide committed inherited MVCC DELETE");

            statement.executeUpdate("CREATE TABLE APP." + ROLLBACK_TABLE + "(id INT, name VARCHAR(32)) USING delos_mvcc");
        }

        try (Connection writer = SmokeUtils.connect(DATABASE_PATH, false)) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("INSERT INTO APP." + ROLLBACK_TABLE + " VALUES (303, 'rollback')");
            }
            writer.rollback();
        }

        try (Connection reader = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = reader.createStatement()) {
            SmokeUtils.assertEquals(List.of(), names(statement, ROLLBACK_TABLE),
                    "MODULE6J rolled-back inherited MVCC INSERT must remain invisible");
        }
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + tableName)) {
            List<Integer> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getInt(1));
            }
            values.sort(Integer::compareTo);
            return values;
        }
    }

    private static List<String> names(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT name FROM APP." + tableName)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            values.sort(String::compareTo);
            return values;
        }
    }

    private static long baseConglomerateNumber(Statement statement, String tableName) throws Exception {
        String sql = "SELECT c.CONGLOMERATENUMBER "
                + "FROM SYS.SYSCONGLOMERATES c, SYS.SYSTABLES t "
                + "WHERE c.TABLEID = t.TABLEID "
                + "AND c.ISINDEX = FALSE "
                + "AND t.TABLENAME = '" + tableName + "'";
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new AssertionError("Missing base conglomerate for " + tableName);
            }
            long value = rows.getLong(1);
            if (rows.next()) {
                throw new AssertionError("More than one base conglomerate for " + tableName);
            }
            return value;
        }
    }

    private static void enableNativeMvccProofProperties() {
        for (String propertyName : NativeMvccProofProperties.NAMES) {
            System.setProperty(propertyName, "true");
        }
    }

    private static void clearNativeMvccProofProperties() {
        for (String propertyName : NativeMvccProofProperties.NAMES) {
            System.clearProperty(propertyName);
        }
    }

    private static void requireContains(String source, String expected, String label) {
        if (source == null || !source.contains(expected)) {
            throw new AssertionError(label + " expected source to contain: " + expected);
        }
    }

    private static void requireNotContains(String source, String unexpected, String label) {
        if (source != null && source.contains(unexpected)) {
            throw new AssertionError(label + " unexpected source content: " + unexpected);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static final class RetiredMvccResultsets {
        private static final String[] FILES = new String[] {
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java",
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosInsertResultSet.java",
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosDeleteResultSet.java",
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosUpdateResultSet.java"
        };

        private static final String[] FACTORY_CALLS = new String[] {
                "DelosTableScanResultSet.createIfEnabled",
                "DelosInsertResultSet.createIfEnabled",
                "DelosDeleteResultSet.createIfEnabled",
                "DelosUpdateResultSet.createIfEnabled"
        };

        private static final String[] CLASS_REFERENCES = new String[] {
                "DelosTableScanResultSet",
                "DelosInsertResultSet",
                "DelosDeleteResultSet",
                "DelosUpdateResultSet"
        };
    }

    private static final class NativeMvccProofProperties {
        private static final String[] NAMES = new String[] {
                "delosdb.storage.phaseF3.tableScanBranchProbe",
                "delosdb.storage.phaseF32.delosTableScanSkeleton",
                "delosdb.storage.phaseF4.nativeMvccSelectEquality",
                "delosdb.storage.phaseG1.nativeRangePredicates",
                "delosdb.storage.phaseG2.nativeBetweenPredicates",
                "delosdb.storage.phaseL31.nativeNullPredicates",
                "delosdb.storage.phaseL33.nativeOrPredicateResidual",
                "delosdb.storage.phaseL34.nativeProjectionVariants",
                "delosdb.storage.phaseL35.nativeOrderByResidual",
                "delosdb.storage.phaseG3.nativeSelectAll",
                "delosdb.storage.phaseG4.nativeCountAggregate",
                "delosdb.storage.phaseF5.nativeMvccInsert",
                "delosdb.storage.phaseF6.nativeMvccDeleteEquality",
                "delosdb.storage.phaseF7.nativeMvccUpdateEquality"
        };
    }
}
