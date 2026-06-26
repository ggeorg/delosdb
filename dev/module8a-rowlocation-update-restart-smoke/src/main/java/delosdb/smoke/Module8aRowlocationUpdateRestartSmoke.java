package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccRowLocation;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE8A smoke: MVCC RowLocation update/restart proof.
 *
 * <p>This is a runtime-only proof. It captures a logical MVCC RowLocation from
 * the inherited Derby scan path before an UPDATE, commits the UPDATE, performs a
 * real Derby shutdown/reopen, and proves the old RowLocation resolves by rowId
 * to the latest committed visible version.</p>
 */
public final class Module8aRowlocationUpdateRestartSmoke {
    private static final String DATABASE_PATH = "build/module8a-rowlocation-update-restart-db";
    private static final String TABLE_NAME = "MODULE8A_ROWLOCATION_UPDATE";

    private Module8aRowlocationUpdateRestartSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        StoreRowLocation capturedLocation = null;
        try {
            capturedLocation = createCaptureAndUpdate();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertOldRowLocationResolvesLatestVersion(capturedLocation);
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static StoreRowLocation createCaptureAndUpdate() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, TABLE_NAME);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE8A table must use an MVCC physical conglomerate");

            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'old')");

            StoreRowLocation captured = captureOnlyVisibleRowLocation(connection, conglomId, "old");
            MvccRowLocation mvccLocation = MvccRowLocation.from(captured);
            require(mvccLocation.rowId() > 0L,
                    "MODULE8A captured RowLocation must carry a stable logical row id");
            require(!mvccLocation.hasLocatorHint(),
                    "MODULE8A currently captures rowId-only locations; locator staleness is MODULE8B");

            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + TABLE_NAME + " SET name = 'new' WHERE id = 1"),
                    "MODULE8A inherited SQL UPDATE must update one row");
            require(MvccConglomerateController.updateCountForTesting() > 0,
                    "MODULE8A SQL UPDATE must reach MvccConglomerateController through inherited RowChanger");

            SmokeUtils.assertEquals("new",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP." + TABLE_NAME + " WHERE id = 1"),
                    "MODULE8A updated row must be visible before restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE8A SELECT/capture must reach MvccScanController through inherited scan path");

            return captured;
        }
    }

    private static StoreRowLocation captureOnlyVisibleRowLocation(
            Connection connection,
            long conglomId,
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
                    "MODULE8A capture must use MvccScanController through inherited store/access scan");
            StoreDataValue[] row = rowTemplate();
            require(scan.fetchNext(row),
                    "MODULE8A capture scan must find the inserted MVCC row");
            SmokeUtils.assertEquals(1, ((SQLInteger) row[0]).getInt(),
                    "MODULE8A captured row id must match fixture");
            SmokeUtils.assertEquals(expectedName, row[1].getString(),
                    "MODULE8A captured row payload must match fixture");
            StoreRowLocation location = scan.newRowLocationTemplate();
            scan.fetchLocation(location);
            require(!scan.fetchNext(rowTemplate()),
                    "MODULE8A fixture must contain only one visible MVCC row before update");
            return (StoreRowLocation) location.cloneValue(true);
        } finally {
            scan.close();
        }
    }

    private static void forceRealShutdownAndClearInMemoryState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void reopenAndAssertOldRowLocationResolvesLatestVersion(StoreRowLocation capturedLocation)
            throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long conglomId = baseConglomerateNumber(statement, TABLE_NAME);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE8A table must remain an MVCC physical conglomerate after restart");
            SmokeUtils.assertEquals("new",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP." + TABLE_NAME + " WHERE id = 1"),
                    "MODULE8A updated row must survive Derby shutdown/reopen");

            assertFetchByOldRowLocationReturnsLatest(connection, conglomId, capturedLocation);
            assertScanPositionByOldRowLocationReturnsLatest(connection, conglomId, capturedLocation);
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE8A post-restart direct positioning must reach MvccScanController");
        }
    }

    private static void assertFetchByOldRowLocationReturnsLatest(
            Connection connection,
            long conglomId,
            StoreRowLocation capturedLocation) throws Exception {
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
            require(controller.fetch(capturedLocation, row, null),
                    "MODULE8A old RowLocation must still fetch a visible row after update/restart");
            SmokeUtils.assertEquals(1, ((SQLInteger) row[0]).getInt(),
                    "MODULE8A fetch by old RowLocation must preserve row identity");
            SmokeUtils.assertEquals("new", row[1].getString(),
                    "MODULE8A fetch by old RowLocation must return latest committed value");
        } finally {
            controller.close();
        }
    }

    private static void assertScanPositionByOldRowLocationReturnsLatest(
            Connection connection,
            long conglomId,
            StoreRowLocation capturedLocation) throws Exception {
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
                    "MODULE8A positionAtRowLocation must use MvccScanController");
            require(scan.positionAtRowLocation(capturedLocation),
                    "MODULE8A old RowLocation must position after update/restart");
            StoreDataValue[] row = rowTemplate();
            scan.fetch(row);
            SmokeUtils.assertEquals(1, ((SQLInteger) row[0]).getInt(),
                    "MODULE8A positionAtRowLocation must preserve row identity");
            SmokeUtils.assertEquals("new", row[1].getString(),
                    "MODULE8A positionAtRowLocation must return latest committed value");
        } finally {
            scan.close();
        }
    }

    private static StoreDataValue[] rowTemplate() {
        return new StoreDataValue[] { new SQLInteger(), new SQLVarchar() };
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
