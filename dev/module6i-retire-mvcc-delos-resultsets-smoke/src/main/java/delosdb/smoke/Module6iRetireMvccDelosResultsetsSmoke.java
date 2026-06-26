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
 * MODULE6I smoke: retire the transitional MVCC Delos*ResultSet bypasses after
 * inherited Derby store/access SELECT, INSERT, DELETE, and UPDATE are green.
 */
public final class Module6iRetireMvccDelosResultsetsSmoke {
    private static final String DATABASE_PATH = "build/module6i-retire-mvcc-delos-resultsets-db";
    private static final String CRUD_TABLE = "MODULE6I_CRUD";
    private static final String DELETE_TABLE = "MODULE6I_DELETE";
    private static final String HEAP_TABLE = "MODULE6I_HEAP";

    private Module6iRetireMvccDelosResultsetsSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        try {
            assertSourceRetiredMvccDelosResultsets();
            assertRuntimeMvccCrudStillUsesInheritedPaths();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourceRetiredMvccDelosResultsets() throws Exception {
        for (String retiredFile : RetiredMvccResultsets.FILES) {
            require(!Files.exists(Path.of(retiredFile)),
                    "MODULE6I cleanup must delete stale MVCC result-set scaffold: " + retiredFile);
        }

        String factory = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"));
        for (String retiredFactoryCall : RetiredMvccResultsets.FACTORY_CALLS) {
            requireNotContains(factory, retiredFactoryCall,
                    "MODULE6I must remove the old MVCC GenericResultSetFactory bypass");
        }
        requireContains(factory,
                "return new TableScanResultSet(params);",
                "MODULE6I must keep MVCC SELECT on inherited TableScanResultSet");
        requireContains(factory,
                "return new InsertResultSet(params);",
                "MODULE6I must keep MVCC INSERT on inherited InsertResultSet");
        requireContains(factory,
                "return new DeleteResultSet(source, activation );",
                "MODULE6I must keep MVCC DELETE on inherited DeleteResultSet");
        requireContains(factory,
                "return new UpdateResultSet(UpdateResultSetParameters.normal(",
                "MODULE6I must keep MVCC UPDATE on inherited UpdateResultSet");

        String providerLookup = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanProviderLookup.java"));
        for (String retiredClassReference : RetiredMvccResultsets.CLASS_REFERENCES) {
            requireNotContains(providerLookup, retiredClassReference + ".",
                    "MODULE6I must not keep compile-time references to retired MVCC result sets");
        }
        requireContains(providerLookup,
                "legacyNativeMvccCrudProofRoutesEnabledForTesting()",
                "MODULE6I must keep the old proof-route honesty guard");
        requireContains(providerLookup,
                "return false;",
                "MODULE6I must keep old native proof routes disabled");
    }

    private static void assertRuntimeMvccCrudStillUsesInheritedPaths() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6I_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            statement.executeUpdate("UPDATE APP." + HEAP_TABLE + " SET name = 'heap2' WHERE id = 1");
            SmokeUtils.assertEquals(List.of("heap2"), names(statement, HEAP_TABLE),
                    "heap CRUD and btree must remain green while retiring MVCC Delos*ResultSets");
            statement.executeUpdate("DELETE FROM APP." + HEAP_TABLE + " WHERE id = 1");
            SmokeUtils.assertEquals(List.of(), names(statement, HEAP_TABLE),
                    "heap DELETE must remain green while retiring MVCC Delos*ResultSets");

            statement.executeUpdate("CREATE TABLE APP." + CRUD_TABLE + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, CRUD_TABLE) & 0x0fL,
                    "MODULE6I requires delos_mvcc tables to use MVCC physical conglomerates");

            MvccConglomerateController.resetInsertCountForTesting();
            MvccScanController.resetOpenCountForTesting();
            statement.executeUpdate("INSERT INTO APP." + CRUD_TABLE + " VALUES (101, 'before')");
            require(MvccConglomerateController.insertCountForTesting() > 0,
                    "MODULE6I INSERT must still reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of("before"), names(statement, CRUD_TABLE),
                    "MODULE6I SELECT must read inherited MVCC INSERT rows");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6I SELECT must still open MvccScanController through inherited TableScanResultSet");

            MvccConglomerateController.resetUpdateCountForTesting();
            statement.executeUpdate("UPDATE APP." + CRUD_TABLE + " SET name = 'after'");
            require(MvccConglomerateController.updateCountForTesting() > 0,
                    "MODULE6I UPDATE must still reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of("after"), names(statement, CRUD_TABLE),
                    "MODULE6I SELECT must see inherited MVCC UPDATE rows");
            SmokeUtils.assertEquals(List.of(101), ids(statement, CRUD_TABLE),
                    "MODULE6I UPDATE must preserve non-updated MVCC columns");

            statement.executeUpdate("CREATE TABLE APP." + DELETE_TABLE + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + DELETE_TABLE + " VALUES (202, 'delete-me')");
            MvccConglomerateController.resetDeleteCountForTesting();
            statement.executeUpdate("DELETE FROM APP." + DELETE_TABLE);
            require(MvccConglomerateController.deleteCountForTesting() > 0,
                    "MODULE6I DELETE must still reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of(), names(statement, DELETE_TABLE),
                    "MODULE6I SELECT must hide inherited MVCC DELETE rows");
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

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            if (Boolean.getBoolean(propertyName)) {
                throw new AssertionError("MODULE6I must not rely on old native proof property: " + propertyName);
            }
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NativeRouteProperties.NAMES) {
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

    private static final class NativeRouteProperties {
        private static final String[] NAMES = new String[] {
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
                "delosdb.storage.phaseF7.nativeMvccUpdateEquality"
        };
    }
}
