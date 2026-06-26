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
 * MODULE10C smoke: retire the unused QueryTreeNode SQL classifier bridge.
 *
 * <p>Core delos_mvcc SQL now routes through inherited Derby compilation,
 * execution, and store/access provider identity. The old QueryTreeNode
 * classifier was a proof-only bridge for SQL-text shaped MVCC routing. This
 * smoke exercises normal inherited SQL CRUD and restart behavior without any
 * classifier route, native registry lifecycle, WAL, index, native I/O, or
 * parser/optimizer redesign.</p>
 */
public final class Module10cRetireQueryTreeClassifierSmoke {
    private static final String DATABASE_PATH = "build/module10c-retire-query-tree-classifier-db";
    private static final String TABLE_NAME = "MODULE10C_CLASSIFIER";

    private Module10cRetireQueryTreeClassifierSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearRetiredBridgeProperties();

        try {
            createAndMutateThroughInheritedSql();
            restartWithClearedRuntimeState();
            reopenAndVerifyInheritedSql();
        } finally {
            clearRetiredBridgeProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createAndMutateThroughInheritedSql() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedMvccCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'two')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (3, 'three')");

            SmokeUtils.assertEquals(List.of("one", "two", "three"), names(statement),
                    "MODULE10C inherited INSERT/SELECT before mutations");
            assertNativeRegistryEmpty("CREATE/INSERT/SELECT must not use retired native registry");

            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'one-new' WHERE id = 1"),
                    "MODULE10C inherited UPDATE must affect one row");
            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 2"),
                    "MODULE10C inherited committed DELETE must affect one row");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 3"),
                        "MODULE10C rollback DELETE setup must affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of("one-new", "three"), names(statement),
                    "MODULE10C inherited visible state before restart");
            assertNativeRegistryEmpty("UPDATE/DELETE/rollback must not use retired native registry");
            require(MvccConglomerateController.insertCountForTesting() >= 3,
                    "MODULE10C INSERT must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.updateCountForTesting() >= 1,
                    "MODULE10C UPDATE must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE10C DELETE and rollback DELETE must reach inherited MvccConglomerateController");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10C SELECT must reach inherited MvccScanController");
        }
    }

    private static void restartWithClearedRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE10C restart must clear inherited MVCC runtime state before reopen");
        resetInheritedMvccCounters();
    }

    private static void reopenAndVerifyInheritedSql() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals(List.of("one-new", "three"), names(statement),
                    "MODULE10C inherited visible state after restart");
            assertNativeRegistryEmpty("restart reopen must not use retired native registry");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10C post-restart SELECT must reach inherited MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE10C post-restart SELECT must reload inherited MVCC state");
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
            throw new AssertionError(label + ": retired native registry was populated for APP." + TABLE_NAME);
        }
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
    }

    private static void resetInheritedMvccCounters() {
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void clearRetiredBridgeProperties() {
        for (String property : List.of(
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
                "delosdb.storage.phase.l4.nativeOptimizerCostConsumption")) {
            System.clearProperty(property);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
