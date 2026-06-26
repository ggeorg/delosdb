package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccRowLocation;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE9C smoke: durable RowLocation and visibility restart cluster.
 *
 * <p>This proof re-runs the MODULE8 RowLocation semantics after MODULE9A/9B
 * made inherited MVCC state and transaction outcomes provider-owned instead of
 * static-JVM authoritative. It captures {@link StoreRowLocation} instances
 * before update/delete operations, commits or rolls back through Derby, shuts
 * Derby down, clears the inherited MVCC runtime caches, reopens the database,
 * and then proves old RowLocations still resolve against the reloaded inherited
 * MVCC provider. It adds no new MVCC semantics, bridge behavior, WAL, indexes,
 * checkpointing, native I/O, or buffer manager work.</p>
 */
public final class Module9cDurableRowlocationVisibilityRestartSmoke {
    private static final String DATABASE_PATH = "build/module9c-durable-rowlocation-visibility-restart-db";
    private static final String TABLE_NAME = "MODULE9C_RESTART";

    private Module9cDurableRowlocationVisibilityRestartSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
        clearNativeMvccProofProperties();

        CapturedLocations locations = null;
        try {
            locations = createMutateAndCaptureLocations();
            restartWithClearedInheritedMvccRuntimeState();
            reopenAndAssertDurableRowLocationSemantics(locations);
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            MvccConglomerate.clearStatesForTesting();
            MvccStoreAccessTransactionRegistry.clearForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static CapturedLocations createMutateAndCaptureLocations() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, TABLE_NAME);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE9C table must use an MVCC physical conglomerate");

            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'update-old')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'delete-me')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (3, 'rollback-live')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (4, 'multi-old')");
            SmokeUtils.assertEquals(List.of(1, 2, 3, 4), ids(statement),
                    "MODULE9C fixture rows must be visible before mutation");

            StoreRowLocation updateLocation = captureRowLocationForId(connection, conglomId, 1, "update-old");
            StoreRowLocation deleteLocation = captureRowLocationForId(connection, conglomId, 2, "delete-me");
            StoreRowLocation rollbackLocation = captureRowLocationForId(connection, conglomId, 3, "rollback-live");
            StoreRowLocation multiUpdateLocation = captureRowLocationForId(connection, conglomId, 4, "multi-old");
            StoreRowLocation staleHintLocation = staleHintFor(updateLocation);

            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'update-new' WHERE id = 1"),
                    "MODULE9C update fixture must update one row");
            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 2"),
                    "MODULE9C committed delete fixture must delete one row");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 3"),
                        "MODULE9C rollback delete fixture must initially delete one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'multi-one' WHERE id = 4"),
                    "MODULE9C first multi-update must update one row");
            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'multi-two' WHERE id = 4"),
                    "MODULE9C second multi-update must update one row");

            assertSqlVisibleState(statement,
                    "MODULE9C visible state before restart must match latest committed MVCC state");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, staleHintLocation, 1, "update-new",
                    "MODULE9C stale locator before restart");
            assertDirectFetchAndScanPositionMiss(connection, conglomId, deleteLocation,
                    "MODULE9C committed delete before restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, rollbackLocation, 3, "rollback-live",
                    "MODULE9C rollback delete before restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, multiUpdateLocation, 4, "multi-two",
                    "MODULE9C multi-update before restart");

            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE9C must exercise inherited MvccScanController before restart");
            require(MvccConglomerateController.updateCountForTesting() >= 3,
                    "MODULE9C SQL UPDATE operations must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE9C SQL DELETE operations must reach inherited MvccConglomerateController");

            return new CapturedLocations(
                    cloneLocation(updateLocation),
                    cloneLocation(staleHintLocation),
                    cloneLocation(deleteLocation),
                    cloneLocation(rollbackLocation),
                    cloneLocation(multiUpdateLocation));
        }
    }

    private static void restartWithClearedInheritedMvccRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE9C must clear inherited MVCC runtime state before reopen");
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void reopenAndAssertDurableRowLocationSemantics(CapturedLocations locations) throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long conglomId = baseConglomerateNumber(statement, TABLE_NAME);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE9C MVCC table identity must survive restart");
            assertSqlVisibleState(statement,
                    "MODULE9C visible state after restart must match durable committed MVCC state");

            assertDirectFetchAndScanPositionAgree(connection, conglomId, locations.updateLocation(), 1, "update-new",
                    "MODULE9C old RowLocation after update across durable restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, locations.staleHintLocation(), 1, "update-new",
                    "MODULE9C stale locator hint across durable restart");
            assertDirectFetchAndScanPositionMiss(connection, conglomId, locations.deleteLocation(),
                    "MODULE9C committed delete across durable restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, locations.rollbackLocation(), 3,
                    "rollback-live", "MODULE9C rollback delete across durable restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, locations.multiUpdateLocation(), 4,
                    "multi-two", "MODULE9C multi-update chain across durable restart");

            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE9C post-restart checks must reach inherited MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE9C reopen must reload inherited MVCC provider state after clearing the runtime cache");
        }
    }

    private static StoreRowLocation captureRowLocationForId(
            Connection connection,
            long conglomId,
            int expectedId,
            String expectedName) throws Exception {
        EmbedConnection embed = connection.unwrap(EmbedConnection.class);
        TransactionController tc = embed.getLanguageConnection().getTransactionExecute();
        ScanController scan = tc.openScan(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE,
                null,
                null,
                0,
                null,
                null,
                0);
        try {
            require(scan instanceof MvccScanController,
                    "MODULE9C capture must use MvccScanController through inherited store/access scan");
            StoreDataValue[] row = rowTemplate();
            while (scan.fetchNext(row)) {
                if (((SQLInteger) row[0]).getInt() == expectedId) {
                    SmokeUtils.assertEquals(expectedName, stringValue(row[1]),
                            "MODULE9C captured row payload must match fixture for id " + expectedId);
                    StoreRowLocation location = scan.newRowLocationTemplate();
                    scan.fetchLocation(location);
                    MvccRowLocation mvccLocation = MvccRowLocation.from(location);
                    require(mvccLocation.rowId() > 0L,
                            "MODULE9C captured RowLocation must carry a stable logical row id");
                    require(!mvccLocation.hasLocatorHint(),
                            "MODULE9C baseline capture must be rowId-only before stale-hint mutation");
                    return cloneLocation(location);
                }
                row = rowTemplate();
            }
            throw new AssertionError("MODULE9C did not find row id " + expectedId + " during RowLocation capture");
        } finally {
            scan.close();
        }
    }

    private static StoreRowLocation staleHintFor(StoreRowLocation location) {
        MvccRowLocation original = MvccRowLocation.from(location);
        StoreRowLocation staleHint = new MvccRowLocation(original.rowId(), 999_999L, 777);
        require(MvccRowLocation.from(staleHint).hasLocatorHint(),
                "MODULE9C stale fixture must carry a locator hint");
        return staleHint;
    }

    private static StoreRowLocation cloneLocation(StoreRowLocation location) throws Exception {
        return (StoreRowLocation) StoreTypeUtil.cloneValue(location, true);
    }

    private static void assertDirectFetchAndScanPositionAgree(
            Connection connection,
            long conglomId,
            StoreRowLocation location,
            int expectedId,
            String expectedName,
            String label) throws Exception {
        StoreDataValue[] fetched = fetchByRowLocation(connection, conglomId, location, true, label);
        SmokeUtils.assertEquals(expectedId, ((SQLInteger) fetched[0]).getInt(),
                label + " direct fetch must preserve row identity");
        SmokeUtils.assertEquals(expectedName, stringValue(fetched[1]),
                label + " direct fetch must return expected visible value");

        StoreDataValue[] positioned = positionByRowLocation(connection, conglomId, location, true, label);
        SmokeUtils.assertEquals(expectedId, ((SQLInteger) positioned[0]).getInt(),
                label + " scan position must preserve row identity");
        SmokeUtils.assertEquals(expectedName, stringValue(positioned[1]),
                label + " scan position must return expected visible value");
    }

    private static void assertDirectFetchAndScanPositionMiss(
            Connection connection,
            long conglomId,
            StoreRowLocation location,
            String label) throws Exception {
        fetchByRowLocation(connection, conglomId, location, false, label);
        positionByRowLocation(connection, conglomId, location, false, label);
    }

    private static StoreDataValue[] fetchByRowLocation(
            Connection connection,
            long conglomId,
            StoreRowLocation location,
            boolean expectVisible,
            String label) throws Exception {
        EmbedConnection embed = connection.unwrap(EmbedConnection.class);
        TransactionController tc = embed.getLanguageConnection().getTransactionExecute();
        ConglomerateController controller = tc.openConglomerate(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE);
        try {
            StoreDataValue[] row = rowTemplate();
            boolean visible = controller.fetch(location, row, null);
            SmokeUtils.assertEquals(expectVisible, visible, label + " direct fetch visibility");
            return row;
        } finally {
            controller.close();
        }
    }

    private static StoreDataValue[] positionByRowLocation(
            Connection connection,
            long conglomId,
            StoreRowLocation location,
            boolean expectVisible,
            String label) throws Exception {
        EmbedConnection embed = connection.unwrap(EmbedConnection.class);
        TransactionController tc = embed.getLanguageConnection().getTransactionExecute();
        ScanController scan = tc.openScan(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE,
                null,
                null,
                0,
                null,
                null,
                0);
        try {
            require(scan instanceof MvccScanController,
                    label + " scan position must use MvccScanController");
            boolean visible = scan.positionAtRowLocation(location);
            SmokeUtils.assertEquals(expectVisible, visible, label + " scan position visibility");
            StoreDataValue[] row = rowTemplate();
            if (visible) {
                scan.fetch(row);
            }
            return row;
        } finally {
            scan.close();
        }
    }

    private static void assertSqlVisibleState(Statement statement, String label) throws Exception {
        SmokeUtils.assertEquals(List.of(1, 3, 4), ids(statement), label + " visible ids");
        SmokeUtils.assertEquals("update-new", nameForId(statement, 1), label + " updated row");
        SmokeUtils.assertEquals(0, count(statement, "SELECT COUNT(*) FROM APP." + TABLE_NAME + " WHERE id = 2"),
                label + " committed delete must be invisible");
        SmokeUtils.assertEquals("rollback-live", nameForId(statement, 3), label + " rolled-back delete row");
        SmokeUtils.assertEquals("multi-two", nameForId(statement, 4), label + " multi-update row");
    }

    private static StoreDataValue[] rowTemplate() {
        return new StoreDataValue[] { new SQLInteger(), new SQLVarchar() };
    }

    private static String stringValue(StoreDataValue value) throws Exception {
        return ((DataValueDescriptor) value).getString();
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

    private static String nameForId(Statement statement, int id) throws Exception {
        return SmokeUtils.singleString(statement,
                "SELECT name FROM APP." + TABLE_NAME + " WHERE id = " + id);
    }

    private static int count(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new AssertionError("No count returned for " + sql);
            }
            int value = rows.getInt(1);
            if (rows.next()) {
                throw new AssertionError("More than one count returned for " + sql);
            }
            return value;
        }
    }

    private static List<Integer> ids(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + TABLE_NAME)) {
            List<Integer> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getInt(1));
            }
            values.sort(Integer::compareTo);
            return List.copyOf(values);
        }
    }

    private static void clearNativeMvccProofProperties() {
        for (String propertyName : NativeMvccProofProperties.NAMES) {
            System.clearProperty(propertyName);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private record CapturedLocations(
            StoreRowLocation updateLocation,
            StoreRowLocation staleHintLocation,
            StoreRowLocation deleteLocation,
            StoreRowLocation rollbackLocation,
            StoreRowLocation multiUpdateLocation) {
    }

    private static final class NativeMvccProofProperties {
        private static final String[] NAMES = new String[] {
                "delosdb.storage.probe",
                "delosdb.storage.native.insert",
                "delosdb.storage.native.select.all",
                "delosdb.storage.native.select.eq",
                "delosdb.storage.native.select.range",
                "delosdb.storage.native.select.between",
                "delosdb.storage.native.select.null",
                "delosdb.storage.native.select.or",
                "delosdb.storage.native.select.projection.variants",
                "delosdb.storage.native.select.order.residual",
                "delosdb.storage.native.select.count",
                "delosdb.storage.native.delete.eq",
                "delosdb.storage.native.update.eq"
        };
    }
}
