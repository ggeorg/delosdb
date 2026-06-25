package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.storage.mvcc.MvccVersion;
import io.github.ggeorg.delosdb.storage.mvcc.MvccVisibility;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MODULE5H smoke: live Derby commit/rollback reaches durable MVCC status.
 *
 * <p>This is still not WAL and not final Derby store/access integration.  The
 * proof is narrower: a {@code USING delos_mvcc} table reached through the
 * provider-identity ResultSet path writes a forced transaction-status outcome
 * before Derby commit/rollback returns. Heap remains on Derby's inherited path,
 * and no old native proof properties are enabled.</p>
 */
public final class Module5hPersistentMvccStatusSmoke {
    private static final String DATABASE_PATH = "build/module5h-persistent-mvcc-status-db";
    private static final String MVCC_TABLE = "MODULE5H_STATUS_MVCC";
    private static final String HEAP_TABLE = "MODULE5H_STATUS_HEAP";

    private Module5hPersistentMvccStatusSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        Path statusFile = statusFileForDatabase(DATABASE_PATH);

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + " (id INT, name VARCHAR(20)) USING delos_mvcc");
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE
                    + " (id INT, name VARCHAR(20))");

            assertNativeRoutePropertiesAreNotSet();

            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'commit')");
            connection.commit();

            Map<MvccTransactionId, MvccTransactionStatusRecord> afterCommit = statuses(statusFile);
            MvccTransactionStatusRecord committed = newestStatus(afterCommit, MvccTransactionStatus.COMMITTED)
                    .orElseThrow(() -> new AssertionError(
                            "Derby commit path did not write durable COMMITTED MVCC status"));
            if (committed.commitSequence().equals(MvccCommitSequence.NONE)) {
                throw new AssertionError("durable COMMITTED status must carry a commit sequence");
            }

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'rollback')");
            connection.rollback();

            Map<MvccTransactionId, MvccTransactionStatusRecord> afterRollback = statuses(statusFile);
            newestStatus(afterRollback, MvccTransactionStatus.ABORTED)
                    .orElseThrow(() -> new AssertionError(
                            "Derby rollback path did not write durable ABORTED MVCC status"));

            connection.setAutoCommit(true);
            SmokeUtils.assertEquals(List.of(1), ids(statement, MVCC_TABLE),
                    "rollback row should be invisible through provider-identity SELECT");

            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (7, 'heap')");
            SmokeUtils.assertEquals(List.of(7), ids(statement, HEAP_TABLE),
                    "default heap table should still use Derby's inherited heap path");
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }

        Map<MvccTransactionId, MvccTransactionStatusRecord> reopened = statuses(statusFile);
        newestStatus(reopened, MvccTransactionStatus.COMMITTED)
                .orElseThrow(() -> new AssertionError("COMMITTED MVCC status did not survive close/reopen"));
        newestStatus(reopened, MvccTransactionStatus.ABORTED)
                .orElseThrow(() -> new AssertionError("ABORTED MVCC status did not survive close/reopen"));

        assertActiveAtCrashIsInvisible(Path.of("build/module5h-active-at-crash-status"));
    }

    private static Path statusFileForDatabase(String databasePath) {
        Path storageDirectory = DelosMvccStorageProvider.databaseStorageDirectory(Path.of(databasePath));
        return DelosMvccStorageProvider.transactionStatusPath(storageDirectory);
    }

    private static Map<MvccTransactionId, MvccTransactionStatusRecord> statuses(Path statusFile) {
        return MvccTransactionStatusStore.open(statusFile).recoverStatuses();
    }

    private static Optional<MvccTransactionStatusRecord> newestStatus(
            Map<MvccTransactionId, MvccTransactionStatusRecord> statuses,
            MvccTransactionStatus expectedStatus) {
        return statuses.entrySet().stream()
                .filter(entry -> entry.getValue().status() == expectedStatus)
                .max(Comparator.comparingLong(entry -> entry.getKey().value()))
                .map(Map.Entry::getValue);
    }

    private static void assertActiveAtCrashIsInvisible(Path scratchDirectory) throws Exception {
        SmokeUtils.deleteRecursively(scratchDirectory);
        Path statusFile = scratchDirectory.resolve("tx-status.log");
        MvccTransactionStatusStore statusStore = MvccTransactionStatusStore.open(statusFile);
        MvccTransactionManager writer = new MvccTransactionManager(statusStore);
        MvccTransaction active = writer.begin();

        MvccTransactionManager recovered = new MvccTransactionManager(MvccTransactionStatusStore.open(statusFile));
        SmokeUtils.assertEquals(MvccTransactionStatus.RECOVERY_PENDING,
                recovered.statusOf(active.id()),
                "ACTIVE-at-crash transaction should reopen as RECOVERY_PENDING");

        MvccTransaction reader = recovered.begin();
        MvccSnapshot snapshot = recovered.snapshot(reader);
        MvccVersion<String> uncommittedVersion = new MvccVersion<>("pending", active.id());
        if (MvccVisibility.isVisible(uncommittedVersion, snapshot, recovered)) {
            throw new AssertionError("ACTIVE-at-crash version must not be visible after reopen");
        }
        recovered.abort(reader);
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, name FROM APP." + tableName)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        ids.sort(Integer::compareTo);
        return List.copyOf(ids);
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
    }

    private static void assertPropertyNotSet(String propertyName) {
        if (Boolean.getBoolean(propertyName)) {
            throw new AssertionError("MODULE5H must not rely on old native proof property: " + propertyName);
        }
    }

    private static void clearNativeRouteProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
    }
}
