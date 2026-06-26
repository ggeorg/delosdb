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
 * MODULE10A smoke: retire the duplicated native bridge lifecycle from normal
 * delos_mvcc table execution.
 *
 * <p>After MODULE9A/9B/9C, normal {@code CREATE TABLE ... USING delos_mvcc},
 * commit, rollback, scan, insert, update, delete, and restart behavior should
 * be owned by the inherited Derby store/access MVCC provider. This smoke proves
 * that creating and mutating a normal MVCC table no longer populates the old
 * {@link DelosNativeTableRegistry} side registry, while inherited MVCC SQL still
 * works before and after a cache-clearing restart. It adds no new bridge route,
 * WAL, index, optimizer, native I/O, buffer manager, or predicate behavior.</p>
 */
public final class Module10aRetireNativeBridgeLifecycleSmoke {
    private static final String DATABASE_PATH = "build/module10a-retire-native-bridge-lifecycle-db";
    private static final String TABLE_NAME = "MODULE10A_BRIDGE";

    private Module10aRetireNativeBridgeLifecycleSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearNativeBridgeProofProperties();

        try {
            createAndMutateThroughInheritedPath();
            restartWithClearedRuntimeState();
            reopenAndAssertInheritedPathStillOwnsState();
        } finally {
            clearNativeBridgeProofProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createAndMutateThroughInheritedPath() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedMvccCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            assertNativeRegistryEmpty("CREATE TABLE must not populate the retired native registry");

            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'two')");
            SmokeUtils.assertEquals(List.of("one", "two"), names(statement),
                    "MODULE10A inherited MVCC INSERT/SELECT state before update");
            assertNativeRegistryEmpty("INSERT/SELECT must stay off the retired native registry");

            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'one-new' WHERE id = 1"),
                    "MODULE10A inherited MVCC UPDATE must affect one row");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 2"),
                        "MODULE10A rollback DELETE setup must affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of("one-new", "two"), names(statement),
                    "MODULE10A inherited visible state before restart");
            assertNativeRegistryEmpty("UPDATE/rollback DELETE must stay off the retired native registry");

            require(MvccConglomerateController.insertCountForTesting() >= 2,
                    "MODULE10A INSERT must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.updateCountForTesting() >= 1,
                    "MODULE10A UPDATE must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 1,
                    "MODULE10A DELETE must reach inherited MvccConglomerateController");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10A SELECT must reach inherited MvccScanController");
        }
    }

    private static void restartWithClearedRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE10A must clear inherited MVCC runtime state before reopen");
        resetInheritedMvccCounters();
    }

    private static void reopenAndAssertInheritedPathStillOwnsState() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals(List.of("one-new", "two"), names(statement),
                    "MODULE10A inherited visible state after restart");
            assertNativeRegistryEmpty("restart reopen must not repopulate the retired native registry");

            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10A post-restart SELECT must reach inherited MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE10A post-restart SELECT must reload inherited MVCC state");
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

    private static void clearNativeBridgeProofProperties() {
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
