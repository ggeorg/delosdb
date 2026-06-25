package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MODULE5C proof smoke: Derby transaction lifecycle owns MVCC visibility.
 *
 * <p>This intentionally uses normal Derby JDBC commit/rollback over a
 * delos_mvcc table. It does not use the regex SQL bridge. The proof closes the
 * previous statement-local auto-commit leak where native MVCC statements could
 * commit before Derby's outer transaction completed.</p>
 */
public final class Module5cDerbyTransactionMvccSmoke {
    private static final String DATABASE_PATH = "build/module5c-derby-transaction-mvcc-db";
    private static final String TABLE = "MODULE5C_TX_SCOPE";

    private Module5cDerbyTransactionMvccSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        enableNativeMvccRoute();

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE
                    + " (id INT, name VARCHAR(20)) USING delos_mvcc");

            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO APP." + TABLE + " VALUES (1, 'rollback')");
            SmokeUtils.assertEquals(List.of(1), ids(statement),
                    "same Derby transaction should see its own MVCC insert");
            connection.rollback();
            SmokeUtils.assertEquals(List.of(), ids(statement),
                    "Derby rollback should abort the attached MVCC transaction");

            statement.executeUpdate("INSERT INTO APP." + TABLE + " VALUES (2, 'commit')");
            SmokeUtils.assertEquals(List.of(2), ids(statement),
                    "same Derby transaction should see second own MVCC insert");
            connection.commit();
            SmokeUtils.assertEquals(List.of(2), ids(statement),
                    "Derby commit should make attached MVCC transaction visible");

            statement.executeUpdate("INSERT INTO APP." + TABLE + " VALUES (3, 'rollback-again')");
            SmokeUtils.assertEquals(List.of(2, 3), ids(statement),
                    "same Derby transaction should see committed row plus own pending row");
            connection.rollback();
            SmokeUtils.assertEquals(List.of(2), ids(statement),
                    "second Derby rollback should leave only committed MVCC rows visible");
        } finally {
            clearNativeMvccRoute();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static List<Integer> ids(Statement statement) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, name FROM APP." + TABLE)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        ids.sort(Integer::compareTo);
        return List.copyOf(ids);
    }

    private static void enableNativeMvccRoute() {
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY, "true");
    }

    private static void clearNativeMvccRoute() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
    }
}
