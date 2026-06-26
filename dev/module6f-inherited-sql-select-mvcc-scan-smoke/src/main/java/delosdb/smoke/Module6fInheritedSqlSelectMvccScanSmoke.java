package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.access.mvcc.MvccRowLocation;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE6F smoke: normal Derby SQL SELECT reaches MVCC through the inherited
 * TableScanResultSet -> TransactionController.openCompiledScan -> MvccScanController path.
 *
 * <p>This proof intentionally seeds rows through inherited store/access APIs
 * to keep the SELECT assertion focused. MODULE6G owns inherited SQL INSERT.
 * This smoke must not add a new SQL bridge or proof property.</p>
 */
public final class Module6fInheritedSqlSelectMvccScanSmoke {
    private static final String DATABASE_PATH = "build/module6f-inherited-sql-select-mvcc-scan-db";
    private static final String MVCC_TABLE = "MODULE6F_MVCC";
    private static final String HEAP_TABLE = "MODULE6F_HEAP";

    private Module6fInheritedSqlSelectMvccScanSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        try {
            assertRuntimeInheritedSqlSelect();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertRuntimeInheritedSqlSelect() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6F_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP." + HEAP_TABLE + " WHERE id = 1"),
                    "heap SELECT and btree must remain green while switching MVCC SELECT to inherited scan");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE + "(id INT) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID, conglomId & 0x0fL,
                    "MODULE6F requires delos_mvcc table to have an MVCC physical conglomerate");

            EmbedConnection embed = connection.unwrap(EmbedConnection.class);
            TransactionController tc = embed.getLanguageConnection().getTransactionExecute();

            StoreRowLocation committedLocation = insertAndCommit(tc, conglomId, 11);
            ConglomerateController activeController = insertAndKeepActive(tc, conglomId, 22);
            insertAndAbort(tc, conglomId, 33);

            MvccScanController.resetOpenCountForTesting();
            SmokeUtils.assertEquals(List.of(11), ids(statement, MVCC_TABLE),
                    "normal SQL SELECT must see committed MVCC row and hide active/aborted rows");
            require(MvccScanController.openCountForTesting() > 0,
                    "normal SQL SELECT must open MvccScanController through inherited TableScanResultSet");

            require(MvccRowLocation.from(committedLocation).rowId() > 0L,
                    "store-access seed must produce a logical MVCC row location");
            activeController.close();
        }
    }

    private static StoreRowLocation insertAndCommit(TransactionController tc, long conglomId, int value) throws Exception {
        ConglomerateController controller = tc.openConglomerate(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE);
        StoreRowLocation location = controller.newRowLocationTemplate();
        controller.insertAndFetchLocation(new StoreDataValue[] { new SQLInteger(value) }, location);
        controller.closeForEndTransaction(false);
        return location;
    }

    private static ConglomerateController insertAndKeepActive(TransactionController tc, long conglomId, int value)
            throws Exception {
        ConglomerateController controller = tc.openConglomerate(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE);
        StoreRowLocation location = controller.newRowLocationTemplate();
        controller.insertAndFetchLocation(new StoreDataValue[] { new SQLInteger(value) }, location);
        return controller;
    }

    private static void insertAndAbort(TransactionController tc, long conglomId, int value) throws Exception {
        ConglomerateController controller = tc.openConglomerate(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE);
        StoreRowLocation location = controller.newRowLocationTemplate();
        controller.insertAndFetchLocation(new StoreDataValue[] { new SQLInteger(value) }, location);
        controller.close();
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + tableName)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        ids.sort(Integer::compareTo);
        return List.copyOf(ids);
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
                throw new AssertionError("MODULE6F must not rely on old native proof property: " + propertyName);
            }
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            System.clearProperty(propertyName);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
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
