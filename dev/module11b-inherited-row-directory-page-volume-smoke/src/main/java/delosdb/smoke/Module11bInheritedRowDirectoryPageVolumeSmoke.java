package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowDirectoryStore;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * MODULE11B smoke: inherited MVCC row-directory page-volume persistence.
 *
 * <p>MODULE11A moved the inherited MVCC provider from the MODULE9A snapshot
 * file to Delos page-volume backed committed state. MODULE11B tightens the
 * row-directory side of that boundary: Derby-facing {@link StoreRowLocation}
 * instances captured through inherited {@link ScanController#fetchLocation}
 * must now carry the durable row-directory head locator as a hint while keeping
 * {@code rowId} as authority. The smoke uses a real stale page/slot hint from
 * the durable row-directory, not a synthetic bad locator.</p>
 */
public final class Module11bInheritedRowDirectoryPageVolumeSmoke {
    private static final String DATABASE_PATH = "build/module11b-inherited-row-directory-page-volume-db";
    private static final String TABLE_NAME = "MODULE11B_ROWDIR";

    private Module11bInheritedRowDirectoryPageVolumeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearNativeMvccProofProperties();

        CapturedLocations locations = null;
        try {
            locations = createMutateAndAssertRowDirectoryHints();
            restartWithClearedInheritedMvccRuntimeState();
            reopenAndAssertRowDirectoryReload(locations);
        } finally {
            clearNativeMvccProofProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static CapturedLocations createMutateAndAssertRowDirectoryHints() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, TABLE_NAME);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE11B table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'update-old')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'delete-me')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (3, 'rollback-live')");
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (4, 'multi-old')");

            StoreRowLocation updateLocation = captureRowLocationForId(connection, conglomId, 1, "update-old");
            StoreRowLocation deleteLocation = captureRowLocationForId(connection, conglomId, 2, "delete-me");
            StoreRowLocation rollbackLocation = captureRowLocationForId(connection, conglomId, 3, "rollback-live");
            StoreRowLocation multiUpdateLocation = captureRowLocationForId(connection, conglomId, 4, "multi-old");

            require(MvccRowLocation.from(updateLocation).hasLocatorHint(),
                    "MODULE11B captured update RowLocation must carry durable row-directory locator hint");
            require(MvccRowLocation.from(deleteLocation).hasLocatorHint(),
                    "MODULE11B captured delete RowLocation must carry durable row-directory locator hint");
            require(MvccRowLocation.from(rollbackLocation).hasLocatorHint(),
                    "MODULE11B captured rollback RowLocation must carry durable row-directory locator hint");
            require(MvccRowLocation.from(multiUpdateLocation).hasLocatorHint(),
                    "MODULE11B captured multi-update RowLocation must carry durable row-directory locator hint");

            statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'update-new' WHERE id = 1");
            statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 2");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + TABLE_NAME + " WHERE id = 3"),
                        "MODULE11B rollback DELETE fixture must initially affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'multi-one' WHERE id = 4");
            statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'multi-two' WHERE id = 4");

            assertSqlVisibleState(statement, "MODULE11B visible state before restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, updateLocation, 1, "update-new",
                    "MODULE11B stale durable locator after update before restart");
            assertDirectFetchAndScanPositionMiss(connection, conglomId, deleteLocation,
                    "MODULE11B stale durable locator after committed delete before restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, rollbackLocation, 3, "rollback-live",
                    "MODULE11B durable locator after rollback delete before restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, multiUpdateLocation, 4, "multi-two",
                    "MODULE11B stale durable locator after multi-update before restart");

            assertDurableRowDirectoryHeads(conglomId, "MODULE11B row-directory heads before restart");
            require(MvccConglomerateController.insertCountForTesting() >= 4,
                    "MODULE11B INSERTs must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.updateCountForTesting() >= 3,
                    "MODULE11B UPDATEs must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE11B DELETEs must reach inherited MvccConglomerateController");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE11B must exercise inherited MvccScanController before restart");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", TABLE_NAME),
                    "MODULE11B must not resurrect the retired native table registry bridge");

            return new CapturedLocations(
                    conglomId,
                    cloneLocation(updateLocation),
                    cloneLocation(deleteLocation),
                    cloneLocation(rollbackLocation),
                    cloneLocation(multiUpdateLocation));
        }
    }

    private static void restartWithClearedInheritedMvccRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE11B restart proof must clear inherited MVCC runtime cache before reopen");
        resetInheritedCounters();
    }

    private static void reopenAndAssertRowDirectoryReload(CapturedLocations locations) throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long conglomId = baseConglomerateNumber(statement, TABLE_NAME);
            SmokeUtils.assertEquals(locations.conglomId(), conglomId,
                    "MODULE11B MVCC conglomerate id must survive restart");
            assertSqlVisibleState(statement, "MODULE11B visible state after restart");

            assertDirectFetchAndScanPositionAgree(connection, conglomId, locations.updateLocation(), 1, "update-new",
                    "MODULE11B stale durable locator after update across restart");
            assertDirectFetchAndScanPositionMiss(connection, conglomId, locations.deleteLocation(),
                    "MODULE11B stale durable locator after committed delete across restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, locations.rollbackLocation(), 3,
                    "rollback-live", "MODULE11B durable locator after rollback delete across restart");
            assertDirectFetchAndScanPositionAgree(connection, conglomId, locations.multiUpdateLocation(), 4,
                    "multi-two", "MODULE11B stale durable locator after multi-update across restart");

            StoreRowLocation updateLocationAfterRestart = captureRowLocationForId(connection, conglomId, 1,
                    "update-new");
            MvccRowLocation updateMvccLocation = MvccRowLocation.from(updateLocationAfterRestart);
            require(updateMvccLocation.hasLocatorHint(),
                    "MODULE11B post-restart scan-captured RowLocation must carry reloaded row-directory hint");
            require(updateMvccLocation.locatorSlotId() >= 0,
                    "MODULE11B row-directory locator hint must include a non-negative slot id");

            assertDurableRowDirectoryHeads(conglomId, "MODULE11B row-directory heads after restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE11B post-restart checks must reach inherited MvccScanController");
            require(MvccConglomerate.stateCountForTesting() > 0,
                    "MODULE11B reopen must reload inherited MVCC provider state");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", TABLE_NAME),
                    "MODULE11B reopen must not populate the retired native table registry bridge");
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
                    "MODULE11B capture must use MvccScanController through inherited store/access scan");
            StoreDataValue[] row = rowTemplate();
            while (scan.fetchNext(row)) {
                if (((SQLInteger) row[0]).getInt() == expectedId) {
                    SmokeUtils.assertEquals(expectedName, stringValue(row[1]),
                            "MODULE11B captured row payload must match fixture for id " + expectedId);
                    StoreRowLocation location = scan.newRowLocationTemplate();
                    scan.fetchLocation(location);
                    MvccRowLocation mvccLocation = MvccRowLocation.from(location);
                    SmokeUtils.assertEquals((long) expectedId, mvccLocation.rowId(),
                            "MODULE11B fixture row id must match logical row id in this append-only fixture");
                    return cloneLocation(location);
                }
                row = rowTemplate();
            }
            throw new AssertionError("MODULE11B did not find row id " + expectedId + " during RowLocation capture");
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

    private static void assertDurableRowDirectoryHeads(long conglomId, String label) throws Exception {
        Path pageFile = MvccConglomerate.pageVolumeStateFileForTesting(0, conglomId);
        Path rowDirectoryFile = MvccConglomerate.rowDirectoryStateFileForTesting(0, conglomId);
        Path legacySnapshotFile = MvccConglomerate.legacySnapshotFileForTesting(0, conglomId);
        require(Files.exists(pageFile) && Files.size(pageFile) > 0L,
                label + " must have a non-empty page-volume state file");
        require(Files.exists(rowDirectoryFile) && Files.size(rowDirectoryFile) > 0L,
                label + " must have a non-empty row-directory sidecar");
        require(!Files.exists(legacySnapshotFile),
                label + " must not reintroduce the MODULE9A ad-hoc snapshot authority");

        Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> heads =
                MvccRowDirectoryStore.open(rowDirectoryFile).recoverHeads();
        assertHead(heads, 1L, false, label + " updated row");
        assertHead(heads, 2L, true, label + " committed deleted row");
        assertHead(heads, 3L, false, label + " rollback delete row");
        assertHead(heads, 4L, false, label + " multi-update row");
    }

    private static void assertHead(
            Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> heads,
            long rowId,
            boolean tombstone,
            String label) {
        MvccRowDirectoryStore.RowHeadRecord head = heads.get(new MvccRowId(rowId));
        require(head != null, label + " must have a durable row-directory head");
        SmokeUtils.assertEquals(tombstone, head.tombstone(), label + " tombstone state");
        SmokeUtils.assertEquals("row:" + rowId, head.key(), label + " logical row key");
        require(head.headLocator().slotId() >= 0,
                label + " must carry a durable page/slot locator");
    }

    private static void assertSqlVisibleState(Statement statement, String label) throws Exception {
        SmokeUtils.assertEquals(List.of(1, 3, 4), ids(statement), label + " visible ids");
        SmokeUtils.assertEquals("update-new", nameForId(statement, 1), label + " updated row");
        SmokeUtils.assertEquals(0, count(statement, "SELECT COUNT(*) FROM APP." + TABLE_NAME + " WHERE id = 2"),
                label + " committed delete must be invisible");
        SmokeUtils.assertEquals("rollback-live", nameForId(statement, 3), label + " rolled-back delete row");
        SmokeUtils.assertEquals("multi-two", nameForId(statement, 4), label + " multi-update row");
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

    private static StoreDataValue[] rowTemplate() {
        return new StoreDataValue[] { new SQLInteger(), new SQLVarchar() };
    }

    private static String stringValue(StoreDataValue value) throws Exception {
        return ((DataValueDescriptor) value).getString();
    }

    private static StoreRowLocation cloneLocation(StoreRowLocation location) throws Exception {
        return (StoreRowLocation) StoreTypeUtil.cloneValue(location, true);
    }

    private static void resetInheritedCounters() {
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
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
            long conglomId,
            StoreRowLocation updateLocation,
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
