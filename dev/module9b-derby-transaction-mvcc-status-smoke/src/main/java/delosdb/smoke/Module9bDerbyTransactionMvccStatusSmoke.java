package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE9B smoke: Derby transaction lifecycle to MVCC status.
 *
 * <p>This is the first inherited-store proof that MVCC transaction outcomes are
 * durable status records attached to Derby commit/rollback boundaries. It keeps
 * MODULE9A's provider-owned state boundary, then proves committed, aborted, and
 * active-at-crash MVCC transactions are recovered through the same inherited
 * Derby MVCC conglomerate provider. It does not add WAL, ARIES, checkpoints,
 * indexes, native I/O, or bridge persistence.</p>
 */
public final class Module9bDerbyTransactionMvccStatusSmoke {
    private static final String DATABASE_PATH = "build/module9b-derby-transaction-mvcc-status-db";
    private static final String TABLE_NAME = "MODULE9B_STATUS";

    private Module9bDerbyTransactionMvccStatusSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
        clearNativeMvccProofProperties();

        try {
            createTableAndDriveDerbyLifecycles();
            simulateActiveDerbyTransactionCrash();
            forceRestartAfterCrashSimulation();
            reopenAndAssertDurableStatusesControlVisibility();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            MvccConglomerate.clearStatesForTesting();
            MvccStoreAccessTransactionRegistry.clearForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createTableAndDriveDerbyLifecycles() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetInsertCountForTesting();
        MvccScanController.resetOpenCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE_NAME
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement) & 0x0fL,
                    "MODULE9B table must have an MVCC physical conglomerate");

            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'committed')");
            require(MvccStoreAccessTransactionRegistry.totalPendingCountForTesting() > 0,
                    "MODULE9B Derby transaction must own a pending MVCC writer before commit");
            connection.commit();
            SmokeUtils.assertEquals(0, MvccStoreAccessTransactionRegistry.totalPendingCountForTesting(),
                    "MODULE9B Derby commit must complete the pending MVCC writer");

            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'rollback')");
            require(MvccStoreAccessTransactionRegistry.totalPendingCountForTesting() > 0,
                    "MODULE9B Derby transaction must own a pending MVCC writer before rollback");
            connection.rollback();
            SmokeUtils.assertEquals(0, MvccStoreAccessTransactionRegistry.totalPendingCountForTesting(),
                    "MODULE9B Derby rollback must complete the pending MVCC writer");
            connection.setAutoCommit(true);

            SmokeUtils.assertEquals(List.of(1), ids(statement),
                    "MODULE9B only committed Derby transaction must be visible before crash simulation");
            require(MvccConglomerateController.insertCountForTesting() >= 2,
                    "MODULE9B INSERTs must reach MvccConglomerateController through inherited SQL");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE9B SELECT must reach MvccScanController through inherited TableScanResultSet");
        }

        assertStatusCoverage("after committed and rolled-back Derby transactions",
                true, true, false);
    }

    private static void simulateActiveDerbyTransactionCrash() throws Exception {
        MvccConglomerateController.resetInsertCountForTesting();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO APP." + TABLE_NAME + " VALUES (3, 'active-crash')");
            require(MvccConglomerateController.insertCountForTesting() > 0,
                    "MODULE9B active-at-crash INSERT must reach MvccConglomerateController");
            require(MvccStoreAccessTransactionRegistry.totalPendingCountForTesting() > 0,
                    "MODULE9B active-at-crash Derby transaction must leave a pending MVCC writer");

            assertStatusCoverage("while Derby transaction is still active",
                    true, true, true);

            MvccStoreAccessTransactionRegistry.clearForTesting();
            MvccConglomerate.clearStatesForTesting();
        }
    }

    private static void forceRestartAfterCrashSimulation() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
        MvccConglomerateController.resetInsertCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void reopenAndAssertDurableStatusesControlVisibility() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement) & 0x0fL,
                    "MODULE9B MVCC table identity must survive restart");
            SmokeUtils.assertEquals(List.of(1), ids(statement),
                    "MODULE9B committed row must survive restart while rolled-back and active-at-crash rows stay invisible");
            SmokeUtils.assertEquals("committed",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP." + TABLE_NAME + " WHERE id = 1"),
                    "MODULE9B committed value must survive restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE9B post-restart SELECT must reach inherited MvccScanController");
        }

        assertStatusCoverage("after restart",
                true, true, true);
    }

    private static void assertStatusCoverage(
            String label,
            boolean requireCommitted,
            boolean requireAborted,
            boolean requireRecoveryPending) throws Exception {
        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses = recoveredStatuses();
        require(!statuses.isEmpty(), "MODULE9B must persist MVCC transaction statuses " + label);
        Map<MvccTransactionStatus, Integer> counts = new EnumMap<>(MvccTransactionStatus.class);
        for (MvccTransactionStatusRecord record : statuses.values()) {
            counts.merge(record.status(), 1, Integer::sum);
        }
        if (requireCommitted) {
            require(counts.getOrDefault(MvccTransactionStatus.COMMITTED, 0) > 0,
                    "MODULE9B must recover a committed MVCC transaction status " + label + ": " + counts);
        }
        if (requireAborted) {
            require(counts.getOrDefault(MvccTransactionStatus.ABORTED, 0) > 0,
                    "MODULE9B must recover an aborted MVCC transaction status " + label + ": " + counts);
        }
        if (requireRecoveryPending) {
            require(counts.getOrDefault(MvccTransactionStatus.RECOVERY_PENDING, 0) > 0,
                    "MODULE9B must recover active-at-crash status as RECOVERY_PENDING " + label + ": " + counts);
        }
    }

    private static Map<MvccTransactionId, MvccTransactionStatusRecord> recoveredStatuses() throws Exception {
        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses = new LinkedHashMap<>();
        Path directory = inheritedStoreDirectory();
        if (!Files.isDirectory(directory)) {
            return statuses;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString().endsWith(".txstatus"))
                    .sorted()
                    .toList()) {
                statuses.putAll(MvccTransactionStatusStore.open(file).recoverStatuses());
            }
        }
        return Map.copyOf(statuses);
    }

    private static Path inheritedStoreDirectory() {
        return Path.of(DATABASE_PATH)
                .resolve(DelosMvccStorageProvider.DATABASE_STORAGE_DIRECTORY_NAME)
                .resolve("inherited-store");
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

    private static long baseConglomerateNumber(Statement statement) throws Exception {
        String sql = "SELECT c.CONGLOMERATENUMBER "
                + "FROM SYS.SYSCONGLOMERATES c, SYS.SYSTABLES t "
                + "WHERE c.TABLEID = t.TABLEID "
                + "AND c.ISINDEX = FALSE "
                + "AND t.TABLENAME = '" + TABLE_NAME + "'";
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new AssertionError("Missing base conglomerate for " + TABLE_NAME);
            }
            long value = rows.getLong(1);
            if (rows.next()) {
                throw new AssertionError("More than one base conglomerate for " + TABLE_NAME);
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
