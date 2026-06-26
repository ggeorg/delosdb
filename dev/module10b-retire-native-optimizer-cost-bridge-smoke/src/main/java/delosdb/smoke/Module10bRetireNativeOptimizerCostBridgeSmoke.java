package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.apache.derby.impl.sql.compile.DelosNativeTableCostLookup;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE10B smoke: retire the duplicated native optimizer cost bridge from the
 * normal delos_mvcc optimizer path.
 *
 * <p>After MODULE10A, normal MVCC table lifecycle belongs to the inherited
 * Derby store/access provider. This smoke deliberately enables the old native
 * cost probe/consumption properties and proves they no longer open the retired
 * {@link DelosNativeTableRegistry} side registry or record a native cost lookup
 * during normal SQL compilation/execution. The query must still execute through
 * inherited MVCC scan state.</p>
 */
public final class Module10bRetireNativeOptimizerCostBridgeSmoke {
    private static final String DATABASE_PATH = "build/module10b-retire-native-optimizer-cost-bridge-db";
    private static final String TABLE_NAME = "MODULE10B_COST";

    private Module10bRetireNativeOptimizerCostBridgeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearNativeCostProperties();

        try {
            createFixture();
            runWithRetiredNativeCostPropertiesEnabled();
        } finally {
            clearNativeCostProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createFixture() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'two')");
            assertNativeRegistryEmpty("fixture creation must not populate the retired native registry");
        }
    }

    private static void runWithRetiredNativeCostPropertiesEnabled() throws Exception {
        DelosNativeTableCostLookup.resetForTesting();
        MvccScanController.resetOpenCountForTesting();
        System.setProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_PROBE_PROPERTY, "true");
        System.setProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_CONSUMPTION_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals(List.of("one", "two"), names(statement),
                    "MODULE10B inherited MVCC SELECT with retired native cost properties enabled");

            SmokeUtils.assertEquals(0, DelosNativeTableCostLookup.lookupCountForTesting(),
                    "MODULE10B retired native optimizer cost bridge must not observe or consume cost");
            assertNativeRegistryEmpty("retired native optimizer cost bridge must not open native registry");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10B SELECT must still reach inherited MvccScanController");
        }
    }

    private static java.util.List<String> names(Statement statement) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
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
        DelosNativeTableCostLookup.resetForTesting();
    }

    private static void clearNativeCostProperties() {
        System.clearProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_PROBE_PROPERTY);
        System.clearProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_CONSUMPTION_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
