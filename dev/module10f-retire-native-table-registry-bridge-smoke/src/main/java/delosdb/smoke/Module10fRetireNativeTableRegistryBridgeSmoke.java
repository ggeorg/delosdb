package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE10F smoke: retire the native table-registry implementation as a bridge
 * surface. The class remains only as an inert compatibility sentinel for older
 * development smokes that still clear/check it.
 */
public final class Module10fRetireNativeTableRegistryBridgeSmoke {
    private static final String DATABASE_PATH = "build/module10f-retire-native-table-registry-bridge-db";
    private static final String TABLE_NAME = "MODULE10F_TABLE_REGISTRY";

    private static final Set<String> RETIRED_BRIDGE_METHODS = Set.of(
            "registerNativeExecutionTable",
            "openNativeExecutionTableAccess",
            "commitDerbyTransaction",
            "rollbackDerbyTransaction");

    private Module10fRetireNativeTableRegistryBridgeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();

        try {
            assertRetiredRegistryBridgeApiAbsent();
            createMutateRestartAndVerifyInheritedPath();
        } finally {
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertRetiredRegistryBridgeApiAbsent() {
        for (Method method : DelosNativeTableRegistry.class.getDeclaredMethods()) {
            if (RETIRED_BRIDGE_METHODS.contains(method.getName())) {
                throw new AssertionError("MODULE10F retired native registry bridge method is still present: "
                        + method.getName());
            }
        }
        assertNativeRegistryEmpty("MODULE10F retired native registry sentinel must start empty");
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        assertNativeRegistryEmpty("MODULE10F retired native registry sentinel must remain empty after clear");
    }

    private static void createMutateRestartAndVerifyInheritedPath() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'two')");
            SmokeUtils.assertEquals(List.of("one", "two"), names(statement),
                    "MODULE10F inherited visible state after insert");

            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'one-new' WHERE id = 1"),
                    "MODULE10F inherited UPDATE must affect one row");
            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 2"),
                    "MODULE10F inherited DELETE must affect one row");
            SmokeUtils.assertEquals(List.of("one-new"), names(statement),
                    "MODULE10F inherited visible state before restart");

            assertNativeRegistryEmpty("MODULE10F normal SQL must not populate retired native registry");
            require(MvccConglomerateController.insertCountForTesting() >= 2,
                    "MODULE10F INSERT must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.updateCountForTesting() >= 1,
                    "MODULE10F UPDATE must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 1,
                    "MODULE10F DELETE must reach inherited MvccConglomerateController");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10F SELECT must reach inherited MvccScanController");
        }

        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE10F restart must clear inherited MVCC runtime state before reopen");
        resetInheritedCounters();

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals(List.of("one-new"), names(statement),
                    "MODULE10F inherited visible state after restart");
            assertNativeRegistryEmpty("MODULE10F restart reopen must not populate retired native registry");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10F post-restart SELECT must reach inherited MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE10F post-restart SELECT must reload inherited MVCC state");
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
