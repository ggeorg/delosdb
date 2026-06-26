package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE10D smoke: remove the old native optimizer cost lookup bridge class.
 *
 * <p>MODULE10B removed the normal optimizer call site. MODULE10D deletes the
 * now-dead helper so the old native-registry cost bridge cannot come back as an
 * accidental dependency. Old diagnostic cost properties are deliberately set;
 * they must be inert while inherited Derby SQL/store/access remains the path for
 * delos_mvcc SELECT.</p>
 */
public final class Module10dRetireNativeCostLookupSmoke {
    private static final String DATABASE_PATH = "build/module10d-retire-native-cost-lookup-db";
    private static final String TABLE_NAME = "MODULE10D_COST_LOOKUP";
    private static final String RETIRED_COST_LOOKUP_CLASS =
            "org.apache.derby.impl.sql.compile.DelosNativeTableCostLookup";
    private static final String NATIVE_TABLE_COST_PROBE_PROPERTY =
            "delosdb.storage.phase.h2.nativeTableCostProbe";
    private static final String NATIVE_TABLE_COST_CONSUMPTION_PROPERTY =
            "delosdb.storage.phase.l4.nativeOptimizerCostConsumption";

    private Module10dRetireNativeCostLookupSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearRetiredCostProperties();

        try {
            requireRetiredCostLookupClassAbsent();
            createFixture();
            runSelectWithRetiredCostPropertiesEnabled();
        } finally {
            clearRetiredCostProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createFixture() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'two')");
            SmokeUtils.assertEquals(List.of("one", "two"), names(statement),
                    "MODULE10D inherited MVCC SELECT before retired cost-property check");
            assertNativeRegistryEmpty("fixture creation must not use retired native registry");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10D fixture SELECT must reach inherited MvccScanController");
        }
    }

    private static void runSelectWithRetiredCostPropertiesEnabled() throws Exception {
        resetInheritedCounters();
        System.setProperty(NATIVE_TABLE_COST_PROBE_PROPERTY, "true");
        System.setProperty(NATIVE_TABLE_COST_CONSUMPTION_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals(List.of("one", "two"), names(statement),
                    "MODULE10D inherited MVCC SELECT with retired native cost properties enabled");
            requireRetiredCostLookupClassAbsent();
            assertNativeRegistryEmpty("retired native cost properties must not open native registry");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE10D SELECT must still reach inherited MvccScanController");
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

    private static void requireRetiredCostLookupClassAbsent() {
        try {
            Class.forName(RETIRED_COST_LOOKUP_CLASS, false,
                    Module10dRetireNativeCostLookupSmoke.class.getClassLoader());
            throw new AssertionError("retired native optimizer cost lookup class is still present: "
                    + RETIRED_COST_LOOKUP_CLASS);
        } catch (ClassNotFoundException expected) {
            // Expected after cleanup removed the dead bridge helper.
        }
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

    private static void resetInheritedCounters() {
        MvccScanController.resetOpenCountForTesting();
    }

    private static void clearRetiredCostProperties() {
        System.clearProperty(NATIVE_TABLE_COST_PROBE_PROPERTY);
        System.clearProperty(NATIVE_TABLE_COST_CONSUMPTION_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
