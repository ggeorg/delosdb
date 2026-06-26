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
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccRowLocation;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE8B smoke: inherited MVCC RowLocation consistency closure.
 *
 * <p>This is a runtime-only proof cluster. It stays on Derby's inherited
 * store/access boundary and proves that {@link StoreRowLocation} remains
 * logical-row-id authoritative across stale locator hints, committed deletes,
 * rollback deletes, and multiple updates. Physical locator hints are treated as
 * optional hints only; direct fetch and scan positioning must agree.</p>
 */
public final class Module8bInheritedRowlocationConsistencySmoke {
    private static final String DATABASE_PATH = "build/module8b-inherited-rowlocation-consistency-db";
    private static final String TABLE_NAME = "MODULE8B_ROWLOCATION_CONSISTENCY";

    private Module8bInheritedRowlocationConsistencySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertInheritedRowLocationConsistency();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertInheritedRowLocationConsistency() throws Exception {
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
                    "MODULE8B table must use an MVCC physical conglomerate");

            insertFixtureRows(statement);

            StoreRowLocation staleBase = captureRowLocationForId(connection, conglomId, 1, "stale-old");
            StoreRowLocation committedDelete = captureRowLocationForId(connection, conglomId, 2, "delete-me");
            StoreRowLocation rollbackDelete = captureRowLocationForId(connection, conglomId, 3, "rollback-live");
            StoreRowLocation multiUpdate = captureRowLocationForId(connection, conglomId, 4, "multi-old");

            assertStaleLocatorHintDoesNotOverrideRowId(statement, connection, conglomId, staleBase);
            assertCommittedDeleteDoesNotResurrectRow(statement, connection, conglomId, committedDelete);
            assertRollbackDeleteKeepsRowVisible(statement, connection, conglomId, rollbackDelete);
            assertMultiUpdateResolvesLatestCommittedState(statement, connection, conglomId, multiUpdate);

            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE8B must exercise MvccScanController through inherited store/access scans");
            require(MvccConglomerateController.updateCountForTesting() >= 3,
                    "MODULE8B SQL UPDATE operations must reach MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE8B SQL DELETE operations must reach MvccConglomerateController");
        }
    }

    private static void insertFixtureRows(Statement statement) throws Exception {
        statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'stale-old')");
        statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'delete-me')");
        statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (3, 'rollback-live')");
        statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (4, 'multi-old')");
        SmokeUtils.assertEquals(List.of(1, 2, 3, 4), ids(statement,
                "SELECT id FROM APP." + TABLE_NAME),
                "MODULE8B fixture rows must be visible through inherited SQL SELECT");
    }

    private static void assertStaleLocatorHintDoesNotOverrideRowId(
            Statement statement,
            Connection connection,
            long conglomId,
            StoreRowLocation originalLocation) throws Exception {
        MvccRowLocation original = MvccRowLocation.from(originalLocation);
        StoreRowLocation staleHint = new MvccRowLocation(original.rowId(), 999_999L, 777);
        require(MvccRowLocation.from(staleHint).hasLocatorHint(),
                "MODULE8B stale fixture must carry a locator hint");

        SmokeUtils.assertEquals(1,
                statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'stale-new' WHERE id = 1"),
                "MODULE8B stale-hint fixture UPDATE must affect one row");
        assertVisibleBySql(statement, 1, "stale-new", "MODULE8B stale-hint baseline SQL visibility");
        assertDirectFetchAndScanPositionAgree(connection, conglomId, staleHint, 1, "stale-new",
                "MODULE8B stale locator hint must not override rowId");
    }

    private static void assertCommittedDeleteDoesNotResurrectRow(
            Statement statement,
            Connection connection,
            long conglomId,
            StoreRowLocation deletedLocation) throws Exception {
        SmokeUtils.assertEquals(1,
                statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 2"),
                "MODULE8B committed DELETE must affect one row");
        assertMissingBySql(statement, 2, "MODULE8B committed DELETE SQL visibility");
        assertDirectFetchAndScanPositionMiss(connection, conglomId, deletedLocation,
                "MODULE8B old RowLocation after committed DELETE must not resurrect row");
    }

    private static void assertRollbackDeleteKeepsRowVisible(
            Statement statement,
            Connection connection,
            long conglomId,
            StoreRowLocation rollbackLocation) throws Exception {
        connection.setAutoCommit(false);
        try {
            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 3"),
                    "MODULE8B rollback DELETE must initially affect one row");
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }

        assertVisibleBySql(statement, 3, "rollback-live", "MODULE8B rollback DELETE SQL visibility");
        assertDirectFetchAndScanPositionAgree(connection, conglomId, rollbackLocation, 3, "rollback-live",
                "MODULE8B rollback DELETE must keep old RowLocation visible");
    }

    private static void assertMultiUpdateResolvesLatestCommittedState(
            Statement statement,
            Connection connection,
            long conglomId,
            StoreRowLocation originalLocation) throws Exception {
        SmokeUtils.assertEquals(1,
                statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'multi-one' WHERE id = 4"),
                "MODULE8B first multi-update must affect one row");
        SmokeUtils.assertEquals(1,
                statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'multi-two' WHERE id = 4"),
                "MODULE8B second multi-update must affect one row");
        assertVisibleBySql(statement, 4, "multi-two", "MODULE8B multi-update SQL visibility");
        assertDirectFetchAndScanPositionAgree(connection, conglomId, originalLocation, 4, "multi-two",
                "MODULE8B old RowLocation must resolve latest committed multi-update state");
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
                    "MODULE8B capture must use MvccScanController through inherited store/access scan");
            StoreDataValue[] row = rowTemplate();
            while (scan.fetchNext(row)) {
                if (((SQLInteger) row[0]).getInt() == expectedId) {
                    SmokeUtils.assertEquals(expectedName, stringValue(row[1]),
                            "MODULE8B captured row payload must match fixture for id " + expectedId);
                    StoreRowLocation location = scan.newRowLocationTemplate();
                    scan.fetchLocation(location);
                    MvccRowLocation mvccLocation = MvccRowLocation.from(location);
                    require(mvccLocation.rowId() > 0L,
                            "MODULE8B captured RowLocation must carry a stable logical row id");
                    require(!mvccLocation.hasLocatorHint(),
                            "MODULE8B baseline capture must be rowId-only before stale-hint mutation");
                    return (StoreRowLocation) StoreTypeUtil.cloneValue(location, true);
                }
                row = rowTemplate();
            }
            throw new AssertionError("MODULE8B did not find row id " + expectedId + " during RowLocation capture");
        } finally {
            scan.close();
        }
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

    private static void assertVisibleBySql(Statement statement, int id, String expectedName, String label)
            throws Exception {
        SmokeUtils.assertEquals(expectedName,
                SmokeUtils.singleString(statement,
                        "SELECT name FROM APP." + TABLE_NAME + " WHERE id = " + id),
                label);
    }

    private static void assertMissingBySql(Statement statement, int id, String label) throws Exception {
        SmokeUtils.assertEquals(0,
                count(statement, "SELECT COUNT(*) FROM APP." + TABLE_NAME + " WHERE id = " + id),
                label);
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

    private static List<Integer> ids(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
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
