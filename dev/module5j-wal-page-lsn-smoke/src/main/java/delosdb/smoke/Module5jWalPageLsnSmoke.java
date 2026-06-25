package delosdb.smoke;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.FileChannelPageVolume;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogWriter;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MODULE5J smoke: minimal provider-local WAL/pageLSN skeleton.
 *
 * <p>This is not full ARIES and not Derby WAL. It proves the next narrow
 * discipline after MODULE5H/I: MVCC begin/version/commit/abort records are
 * forced to a provider-local log, committed status still survives through the
 * persistent status store, and page-backed MVCC version pages store the version
 * log LSN in page metadata when a committed page write happens.</p>
 */
public final class Module5jWalPageLsnSmoke {
    private static final Path STORAGE_DIRECTORY = Path.of("build/module5j-wal-page-lsn-provider");
    private static final String HEAP_DATABASE_PATH = "build/module5j-heap-smoke-db";

    private static final List<String> NATIVE_ROUTE_PROPERTIES = List.of(
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
            "delosdb.storage.phaseF7.nativeMvccUpdateEquality");

    private Module5jWalPageLsnSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(HEAP_DATABASE_PATH);
        SmokeUtils.deleteRecursively(STORAGE_DIRECTORY);
        SmokeUtils.deleteRecursively(Path.of(HEAP_DATABASE_PATH));
        clearNativeRouteProperties();

        try {
            assertMinimalWalAndPageLsnDiscipline();
            assertHeapStillWorks();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            SmokeUtils.shutdownQuietly(HEAP_DATABASE_PATH);
        }
    }

    private static void assertMinimalWalAndPageLsnDiscipline() throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "module5j_wal_page_lsn");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.openPageBacked(STORAGE_DIRECTORY);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(1L, List.of(1, "A"), insert);
        transactions.commit(insert);

        TxContext update = transactions.begin();
        table.update(1L, List.of(1, "B"), update);
        transactions.commit(update);

        TxContext delete = transactions.begin();
        table.delete(1L, delete);
        transactions.abort(delete);

        TxContext reader = transactions.begin();
        try {
            SmokeUtils.assertEquals(Optional.of(List.of(1, "B")), table.read(1L, reader.currentView()),
                    "aborted delete should not hide the committed update");
        } finally {
            transactions.abort(reader);
        }

        List<MvccLogRecord> records = MvccLogWriter.open(DelosMvccStorageProvider.mvccLogPath(STORAGE_DIRECTORY))
                .recoverRecords();
        requireRecord(records, MvccLogRecord.Type.BEGIN_TXN);
        MvccLogRecord insertVersion = requireRecord(records, MvccLogRecord.Type.INSERT_VERSION);
        MvccLogRecord updateVersion = requireRecord(records, MvccLogRecord.Type.UPDATE_VERSION);
        requireRecord(records, MvccLogRecord.Type.DELETE_VERSION);
        requireRecord(records, MvccLogRecord.Type.COMMIT_TXN);
        requireRecord(records, MvccLogRecord.Type.ABORT_TXN);
        assertMonotonicLsns(records);
        assertCommitAfterVersion(records, insertVersion);
        assertCommitAfterVersion(records, updateVersion);

        Path statusFile = DelosMvccStorageProvider.transactionStatusPath(STORAGE_DIRECTORY);
        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses = MvccTransactionStatusStore.open(statusFile)
                .recoverStatuses();
        newestStatus(statuses, MvccTransactionStatus.COMMITTED)
                .orElseThrow(() -> new AssertionError("MODULE5J did not persist a committed MVCC status"));
        newestStatus(statuses, MvccTransactionStatus.ABORTED)
                .orElseThrow(() -> new AssertionError("MODULE5J did not persist an aborted MVCC status"));

        Path pageFile = onlyPageFile(STORAGE_DIRECTORY);
        try (FileChannelPageVolume volume = FileChannelPageVolume.open(pageFile)) {
            DelosPage page = volume.readPage(new DelosPageId(0L));
            SmokeUtils.assertEquals(updateVersion.lsn().value(), page.pageLsn(),
                    "pageLSN should match the newest committed version log record written to the page");
        }
    }

    private static void assertHeapStillWorks() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(HEAP_DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP.MODULE5J_HEAP (id INT, name VARCHAR(20))");
            statement.executeUpdate("INSERT INTO APP.MODULE5J_HEAP VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP.MODULE5J_HEAP WHERE id = 1"),
                    "heap table should still work during MODULE5J");
        }
    }

    private static MvccLogRecord requireRecord(List<MvccLogRecord> records, MvccLogRecord.Type type) {
        return records.stream()
                .filter(record -> record.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing MVCC log record type: " + type));
    }

    private static void assertMonotonicLsns(List<MvccLogRecord> records) {
        DelosLogSequenceNumber previous = DelosLogSequenceNumber.NONE;
        for (MvccLogRecord record : records) {
            if (record.lsn().compareTo(previous) <= 0) {
                throw new AssertionError("MVCC log LSNs are not monotonic at " + record);
            }
            previous = record.lsn();
        }
    }

    private static void assertCommitAfterVersion(List<MvccLogRecord> records, MvccLogRecord versionRecord) {
        records.stream()
                .filter(record -> record.transactionId().equals(versionRecord.transactionId()))
                .filter(record -> record.type() == MvccLogRecord.Type.COMMIT_TXN)
                .filter(record -> record.lsn().compareTo(versionRecord.lsn()) > 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("transaction " + versionRecord.transactionId()
                        + " has no forced COMMIT_TXN after " + versionRecord.type()));
    }

    private static Path onlyPageFile(Path storageDirectory) throws Exception {
        try (var paths = Files.list(storageDirectory)) {
            List<Path> pageFiles = paths
                    .filter(path -> path.getFileName().toString().endsWith(".dmvcc"))
                    .sorted()
                    .toList();
            if (pageFiles.size() != 1) {
                throw new AssertionError("expected one page-backed MVCC file but found " + pageFiles);
            }
            return pageFiles.get(0);
        }
    }

    private static Optional<MvccTransactionStatusRecord> newestStatus(
            Map<MvccTransactionId, MvccTransactionStatusRecord> statuses,
            MvccTransactionStatus expectedStatus) {
        return statuses.entrySet().stream()
                .filter(entry -> entry.getValue().status() == expectedStatus)
                .max(Comparator.comparingLong(entry -> entry.getKey().value()))
                .map(Map.Entry::getValue);
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NATIVE_ROUTE_PROPERTIES) {
            assertPropertyNotSet(propertyName);
        }
    }

    private static void assertPropertyNotSet(String propertyName) {
        if (Boolean.getBoolean(propertyName)) {
            throw new AssertionError("MODULE5J must not rely on old native proof property: " + propertyName);
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NATIVE_ROUTE_PROPERTIES) {
            System.clearProperty(propertyName);
        }
    }
}
