package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE10E smoke: retire the remaining native-registry table-access helper
 * from the old result-set support seam.
 *
 * <p>MODULE10A retired native-registry lifecycle authority. MODULE10C/10D
 * retired the query-tree classifier and native optimizer cost bridge. This
 * smoke keeps old native proof properties enabled as inert compatibility noise
 * while normal delos_mvcc SQL continues through inherited Derby store/access
 * controllers and the native registry remains empty.</p>
 */
public final class Module10eRetireNativeResultsetRegistryBridgeSmoke {
    private static final String DATABASE_PATH = "build/module10e-retire-native-resultset-registry-bridge-db";
    private static final String TABLE_NAME = "MODULE10E_RESULTSET_REGISTRY";

    private Module10eRetireNativeResultsetRegistryBridgeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearRetiredBridgeProperties();

        try {
            enableRetiredNativeProofProperties();
            createMutateAndVerifyInheritedPath();
            restartWithClearedRuntimeState();
            reopenAndVerifyInheritedPath();
        } finally {
            clearRetiredBridgeProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createMutateAndVerifyInheritedPath() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'two')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (3, 'three')");
            SmokeUtils.assertEquals(List.of("one", "two", "three"), names(statement),
                    "MODULE10E inherited visible state before mutation");

            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'one-new' WHERE id = 1"),
                    "MODULE10E inherited UPDATE must affect one row");
            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 2"),
                    "MODULE10E inherited committed DELETE must affect one row");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 3"),
                        "MODULE10E rollback DELETE setup must affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of("one-new", "three"), names(statement),
                    "MODULE10E inherited visible state before restart");
            assertNativeRegistryEmpty("retired native proof properties must not open native registry");
            require(MvccConglomerateController.insertCountForTesting() >= 3,
                    "MODULE10E INSERT must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.updateCountForTesting() >= 1,
                    "MODULE10E UPDATE must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE10E DELETE and rollback DELETE must reach inherited MvccConglomerateController");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10E SELECT must reach inherited MvccScanController");
        }
    }

    private static void restartWithClearedRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE10E restart must clear inherited MVCC runtime state before reopen");
        resetInheritedCounters();
    }

    private static void reopenAndVerifyInheritedPath() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals(List.of("one-new", "three"), names(statement),
                    "MODULE10E inherited visible state after restart");
            assertNativeRegistryEmpty("restart reopen must not use retired native registry access");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10E post-restart SELECT must reach inherited MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE10E post-restart SELECT must reload inherited MVCC state");
        }
    }

    private static List<String> names(Statement statement) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet results = statement.executeQuery(
                "SELECT name FROM APP." + TABLE_NAME + " ORDER BY id")) {
            while (results.next()) {
                names.add(results.getString(1));
            }
        }
        return names;
    }

    private static void assertNativeRegistryEmpty(String label) {
        if (DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", TABLE_NAME)) {
            throw new AssertionError(label + ": native registry was populated for APP." + TABLE_NAME);
        }
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
    }

    private static void resetInheritedCounters() {
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void enableRetiredNativeProofProperties() {
        for (String property : retiredNativeProofProperties()) {
            System.setProperty(property, "true");
        }
    }

    private static void clearRetiredBridgeProperties() {
        for (String property : retiredNativeProofProperties()) {
            System.clearProperty(property);
        }
    }

    private static List<String> retiredNativeProofProperties() {
        return List.of(
                "delosdb.storage.phaseF3.tableScanBranchProbe",
                "delosdb.storage.phaseF32.delosTableScanSkeleton",
                "delosdb.storage.phaseF4.nativeMvccSelectEquality",
                "delosdb.storage.phaseF5.nativeMvccInsert",
                "delosdb.storage.phaseF6.nativeMvccDeleteEquality",
                "delosdb.storage.phaseF7.nativeMvccUpdateEquality",
                "delosdb.storage.phaseG1.nativeRangePredicates",
                "delosdb.storage.phaseG2.nativeBetweenPredicates",
                "delosdb.storage.phaseG3.nativeSelectAll",
                "delosdb.storage.phaseG4.nativeCountAggregate",
                "delosdb.storage.phaseL31.nativeNullPredicates",
                "delosdb.storage.phaseL33.nativeOrPredicateResidual",
                "delosdb.storage.phaseL34.nativeProjectionVariants",
                "delosdb.storage.phaseL35.nativeOrderByResidual",
                "delosdb.storage.phase.h2.nativeTableCostProbe",
                "delosdb.storage.phase.l4.nativeOptimizerCostConsumption");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
