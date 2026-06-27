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
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.impl.jdbc.EmbedConnection;

/**
 * MODULE15 smoke: Derby-safe MVCC vacuum horizon.
 *
 * <p>The proof enters through inherited Derby SQL/store/access. It asks Derby's
 * inherited purge hook to run the MVCC vacuum. Active inherited scans must block
 * cleanup conservatively; once the scan closes, vacuum may compact durable
 * page-volume versions without changing visible SQL rows or making stale
 * RowLocation locator hints authoritative.</p>
 */
public final class Module15DerbySafeMvccVacuumHorizonSmoke {
    private static final String DATABASE_PATH = "build/module15-derby-safe-mvcc-vacuum-horizon-db";
    private static final String MVCC_TABLE = "MODULE15_VACUUM";
    private static final DelosStorageDiagnostics MVCC_DIAGNOSTICS = DelosStorageDiagnosticsRegistry.mvcc();

    private Module15DerbySafeMvccVacuumHorizonSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearRuntimeState();
        clearNativeMvccProofProperties();

        try {
            VacuumState state = createRowsAndVacuumThroughInheritedPurge();
            shutdownAndClearRuntimeState();
            reopenAndAssertVacuumedState(state);
        } finally {
            clearNativeMvccProofProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static VacuumState createRowsAndVacuumThroughInheritedPurge() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE15 table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one-a')");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'one-b' WHERE id = 1");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'one-c' WHERE id = 1");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'deleted')");
            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 2");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'three')");

            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE15 visible ids before vacuum must match committed MVCC state");
            SmokeUtils.assertEquals(List.of("one-c", "three"), names(statement),
                    "MODULE15 visible names before vacuum must match latest committed MVCC state");

            int beforeVacuumVersions = MVCC_DIAGNOSTICS.physicalVersionCountForTesting(0, conglomId);
            require(beforeVacuumVersions >= 5,
                    "MODULE15 fixture must create multiple durable MVCC versions before vacuum, found "
                            + beforeVacuumVersions);
            StoreRowLocation stalePreVacuumLocation = captureRowLocation(connection, conglomId, 1, "one-c");
            require(MVCC_DIAGNOSTICS.hasLocatorHint(stalePreVacuumLocation),
                    "MODULE15 captured RowLocation must carry a page-volume locator hint before vacuum");

            assertActiveInheritedScanBlocksVacuum(connection, conglomId, beforeVacuumVersions);
            runInheritedPurge(connection, conglomId);
            require(!MVCC_DIAGNOSTICS.lastVacuumSkippedForTesting(0, conglomId),
                    "MODULE15 vacuum must run once retained inherited scan is closed");
            require(MVCC_DIAGNOSTICS.lastVacuumRemovedVersionsForTesting(0, conglomId) > 0,
                    "MODULE15 vacuum must remove obsolete durable versions after horizon release");
            int afterVacuumVersions = MVCC_DIAGNOSTICS.physicalVersionCountForTesting(0, conglomId);
            require(afterVacuumVersions < beforeVacuumVersions,
                    "MODULE15 vacuum must reduce physical version count from " + beforeVacuumVersions
                            + " but found " + afterVacuumVersions);
            SmokeUtils.assertEquals(afterVacuumVersions,
                    MVCC_DIAGNOSTICS.lastVacuumRemainingVersionsForTesting(0, conglomId),
                    "MODULE15 last vacuum result must report the remaining physical versions");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE15 vacuum must not change visible ids");
            SmokeUtils.assertEquals(List.of("one-c", "three"), names(statement),
                    "MODULE15 vacuum must not change visible names");
            SmokeUtils.assertEquals("WRITTEN", MVCC_DIAGNOSTICS.checkpointStatusForTesting(0, conglomId),
                    "MODULE15 vacuum must rewrite Derby-visible MVCC checkpoint metadata");

            assertFetchByOldRowLocationReturnsLatest(connection, conglomId, stalePreVacuumLocation);
            assertScanPositionByOldRowLocationReturnsLatest(connection, conglomId, stalePreVacuumLocation);

            require(MVCC_DIAGNOSTICS.updateCountForTesting() >= 2,
                    "MODULE15 UPDATEs must reach inherited MvccConglomerateController");
            require(MVCC_DIAGNOSTICS.deleteCountForTesting() >= 1,
                    "MODULE15 DELETE must reach inherited MvccConglomerateController");
            require(MVCC_DIAGNOSTICS.scanOpenCountForTesting() > 0,
                    "MODULE15 SELECT/direct scan must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE15 must not resurrect retired native registry bridge");
            return new VacuumState(conglomId, stalePreVacuumLocation, afterVacuumVersions);
        }
    }

    private static void assertActiveInheritedScanBlocksVacuum(
            Connection connection,
            long conglomId,
            int expectedVersionCount) throws Exception {
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
            require(MVCC_DIAGNOSTICS.isProviderScan(scan),
                    "MODULE15 retained scan must use inherited MvccScanController");
            StoreDataValue[] row = rowTemplate();
            require(scan.fetchNext(row),
                    "MODULE15 retained scan must hold a visible MVCC snapshot before vacuum");
            runInheritedPurge(connection, conglomId);
            require(MVCC_DIAGNOSTICS.lastVacuumSkippedForTesting(0, conglomId),
                    "MODULE15 active inherited scan must conservatively skip vacuum");
            SmokeUtils.assertContains(
                    MVCC_DIAGNOSTICS.lastVacuumReasonForTesting(0, conglomId),
                    "retained inherited MVCC",
                    "MODULE15 skipped vacuum reason must identify retained inherited snapshot");
            SmokeUtils.assertEquals(expectedVersionCount,
                    MVCC_DIAGNOSTICS.physicalVersionCountForTesting(0, conglomId),
                    "MODULE15 skipped vacuum must not remove versions while scan is open");
        } finally {
            scan.close();
        }
    }

    private static void runInheritedPurge(Connection connection, long conglomId) throws Exception {
        EmbedConnection embed = connection.unwrap(EmbedConnection.class);
        TransactionController tc = embed.getLanguageConnection().getTransactionExecute();
        tc.purgeConglomerate(conglomId);
    }

    private static void reopenAndAssertVacuumedState(VacuumState state) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long reopenedConglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals(state.conglomId(), reopenedConglomId,
                    "MODULE15 MVCC conglomerate id must remain stable after vacuum restart");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE15 vacuumed restart must not resurrect deleted rows");
            SmokeUtils.assertEquals(List.of("one-c", "three"), names(statement),
                    "MODULE15 vacuumed restart must keep latest committed visible rows");
            SmokeUtils.assertEquals(state.remainingVersions(),
                    MVCC_DIAGNOSTICS.physicalVersionCountForTesting(0, state.conglomId()),
                    "MODULE15 restart must preserve compacted physical version count");
            SmokeUtils.assertEquals("VALID", MVCC_DIAGNOSTICS.checkpointStatusForTesting(0, state.conglomId()),
                    "MODULE15 vacuumed checkpoint must validate after restart");
            assertFetchByOldRowLocationReturnsLatest(connection, state.conglomId(), state.stalePreVacuumLocation());
            assertScanPositionByOldRowLocationReturnsLatest(connection, state.conglomId(), state.stalePreVacuumLocation());
            require(MVCC_DIAGNOSTICS.scanOpenCountForTesting() > 0,
                    "MODULE15 post-vacuum restart must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE15 restart must not populate retired native registry bridge");
        }
    }

    private static StoreRowLocation captureRowLocation(
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
            require(MVCC_DIAGNOSTICS.isProviderScan(scan),
                    "MODULE15 RowLocation capture must use inherited MvccScanController");
            StoreDataValue[] row = rowTemplate();
            while (scan.fetchNext(row)) {
                int id = ((SQLInteger) row[0]).getInt();
                if (id == expectedId) {
                    SmokeUtils.assertEquals(expectedName, ((DataValueDescriptor) row[1]).getString(),
                            "MODULE15 captured row payload must match fixture");
                    StoreRowLocation location = scan.newRowLocationTemplate();
                    scan.fetchLocation(location);
                    return (StoreRowLocation) StoreTypeUtil.cloneValue(location, true);
                }
                row = rowTemplate();
            }
            throw new AssertionError("MODULE15 could not capture RowLocation for id " + expectedId);
        } finally {
            scan.close();
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
                    "MODULE15 stale RowLocation hint must still fetch by rowId after vacuum");
            SmokeUtils.assertEquals(1, ((SQLInteger) row[0]).getInt(),
                    "MODULE15 fetch by stale RowLocation must preserve logical row identity");
            SmokeUtils.assertEquals("one-c", ((DataValueDescriptor) row[1]).getString(),
                    "MODULE15 fetch by stale RowLocation must return latest committed value");
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
            require(MVCC_DIAGNOSTICS.isProviderScan(scan),
                    "MODULE15 positionAtRowLocation must use inherited MvccScanController");
            require(scan.positionAtRowLocation(capturedLocation),
                    "MODULE15 stale RowLocation hint must position by rowId after vacuum");
            StoreDataValue[] row = rowTemplate();
            scan.fetch(row);
            SmokeUtils.assertEquals(1, ((SQLInteger) row[0]).getInt(),
                    "MODULE15 positioned RowLocation must preserve logical row identity");
            SmokeUtils.assertEquals("one-c", ((DataValueDescriptor) row[1]).getString(),
                    "MODULE15 positioned RowLocation must return latest committed value");
        } finally {
            scan.close();
        }
    }

    private static StoreDataValue[] rowTemplate() {
        return new StoreDataValue[] { new SQLInteger(), new SQLVarchar() };
    }

    private static List<Integer> ids(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + MVCC_TABLE)) {
            List<Integer> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getInt(1));
            }
            values.sort(Integer::compareTo);
            return List.copyOf(values);
        }
    }

    private static List<String> names(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT name FROM APP." + MVCC_TABLE)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            values.sort(String::compareTo);
            return List.copyOf(values);
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

    private static void shutdownAndClearRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MVCC_DIAGNOSTICS.runtimeStateCountForTesting(),
                "MODULE15 restart proof must clear inherited MVCC runtime cache before reopen");
        resetInheritedCounters();
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MVCC_DIAGNOSTICS.clearRuntimeStateForTesting();
    }

    private static void resetInheritedCounters() {
        MVCC_DIAGNOSTICS.resetMutationCountersForTesting();
        MVCC_DIAGNOSTICS.resetScanCountersForTesting();
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

    private record VacuumState(long conglomId, StoreRowLocation stalePreVacuumLocation, int remainingVersions) {
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
