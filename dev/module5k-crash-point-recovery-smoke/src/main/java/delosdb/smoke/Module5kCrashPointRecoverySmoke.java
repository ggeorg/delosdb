package delosdb.smoke;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogWriter;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccPageMutationLog;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowPayload;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowPayloadCodec;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * MODULE5K smoke: crash-point recovery proof for the current durable MVCC path.
 *
 * <p>This is still not full ARIES and not Derby WAL. The smoke exercises the
 * dangerous points that MODULE5H/I/J made testable: version records that reach a
 * durable log without a commit must stay invisible, durable commit status/log
 * records must make committed changes visible after reopen, and uncommitted
 * delete/update versions must not corrupt the last committed row image.</p>
 */
public final class Module5kCrashPointRecoverySmoke {
    private static final Path PROVIDER_STORAGE = Path.of("build/module5k-provider-crash-points");
    private static final Path PAGE_STORAGE = Path.of("build/module5k-page-crash-points");
    private static final String HEAP_DATABASE_PATH = "build/module5k-heap-smoke-db";

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

    private Module5kCrashPointRecoverySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(HEAP_DATABASE_PATH);
        SmokeUtils.deleteRecursively(PROVIDER_STORAGE);
        SmokeUtils.deleteRecursively(PAGE_STORAGE);
        SmokeUtils.deleteRecursively(Path.of(HEAP_DATABASE_PATH));
        clearNativeRouteProperties();

        try {
            assertProviderCrashPoints();
            assertPageMutationCrashPoints();
            assertHeapStillWorks();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            SmokeUtils.shutdownQuietly(HEAP_DATABASE_PATH);
        }
    }

    private static void assertProviderCrashPoints() throws Exception {
        assertProviderCrashAfterVersionAppendBeforeCommit(PROVIDER_STORAGE.resolve("uncommitted-insert"));
        assertProviderCrashAfterCommitStatusBeforeStorageTerminal(PROVIDER_STORAGE.resolve("status-before-terminal"));
        assertProviderCrashAfterDeleteBeforeCommit(PROVIDER_STORAGE.resolve("uncommitted-delete"));
        assertProviderCrashAfterUpdateBeforeCommit(PROVIDER_STORAGE.resolve("uncommitted-update"));
        assertProviderCommittedDeleteDoesNotResurrect(PROVIDER_STORAGE.resolve("committed-delete"));
        assertProviderCommittedUpdateIsNotHalfVisible(PROVIDER_STORAGE.resolve("committed-update"));
    }

    private static void assertProviderCrashAfterVersionAppendBeforeCommit(Path storageDirectory) throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "module5k_uncommitted_insert");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();
        TxContext transaction = transactions.begin();
        table.insert(1L, row(1, "uncommitted"), transaction);

        List<MvccLogRecord> logRecords = MvccLogWriter.open(DelosMvccStorageProvider.mvccLogPath(storageDirectory))
                .recoverRecords();
        requireRecord(logRecords, MvccLogRecord.Type.INSERT_VERSION, transaction.transactionId());
        requireNoRecord(logRecords, MvccLogRecord.Type.COMMIT_TXN, transaction.transactionId());

        SmokeUtils.assertEquals(Optional.empty(), reopenedRead(storageDirectory, metadata, 1L),
                "crash after version append before commit must keep inserted row invisible");
    }

    private static void assertProviderCrashAfterCommitStatusBeforeStorageTerminal(Path storageDirectory) throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "module5k_status_before_terminal");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();
        TxContext transaction = transactions.begin();
        table.insert(2L, row(2, "status-visible"), transaction);

        MvccTransactionStatusStore.open(DelosMvccStorageProvider.transactionStatusPath(storageDirectory))
                .recordCommitted(new MvccTransactionId(transaction.transactionId()), new MvccCommitSequence(1L));

        List<MvccLogRecord> logRecords = MvccLogWriter.open(DelosMvccStorageProvider.mvccLogPath(storageDirectory))
                .recoverRecords();
        requireRecord(logRecords, MvccLogRecord.Type.INSERT_VERSION, transaction.transactionId());
        requireNoRecord(logRecords, MvccLogRecord.Type.COMMIT_TXN, transaction.transactionId());

        String storageLogContent = Files.readString(storageDirectory.resolve("delos-mvcc-storage.log"));
        if (storageLogContent.contains("\tCOMMIT\t" + transaction.transactionId())) {
            throw new AssertionError("status-before-terminal fixture unexpectedly wrote provider storage COMMIT");
        }

        SmokeUtils.assertEquals(Optional.of(row(2, "status-visible")), reopenedRead(storageDirectory, metadata, 2L),
                "durable COMMITTED status must make row visible even when the old terminal log record was not written");
    }

    private static void assertProviderCrashAfterDeleteBeforeCommit(Path storageDirectory) throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "module5k_delete_before_commit");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(3L, row(3, "alive"), insert);
        transactions.commit(insert);

        TxContext delete = transactions.begin();
        table.delete(3L, delete);

        requireRecord(MvccLogWriter.open(DelosMvccStorageProvider.mvccLogPath(storageDirectory)).recoverRecords(),
                MvccLogRecord.Type.DELETE_VERSION, delete.transactionId());
        SmokeUtils.assertEquals(Optional.of(row(3, "alive")), reopenedRead(storageDirectory, metadata, 3L),
                "crash after delete before commit must not hide the committed row");
    }

    private static void assertProviderCrashAfterUpdateBeforeCommit(Path storageDirectory) throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "module5k_update_before_commit");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(4L, row(4, "old"), insert);
        transactions.commit(insert);

        TxContext update = transactions.begin();
        table.update(4L, row(4, "new"), update);

        requireRecord(MvccLogWriter.open(DelosMvccStorageProvider.mvccLogPath(storageDirectory)).recoverRecords(),
                MvccLogRecord.Type.UPDATE_VERSION, update.transactionId());
        SmokeUtils.assertEquals(Optional.of(row(4, "old")), reopenedRead(storageDirectory, metadata, 4L),
                "crash after update before commit must expose only the old committed value");
    }

    private static void assertProviderCommittedDeleteDoesNotResurrect(Path storageDirectory) throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "module5k_committed_delete");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(5L, row(5, "delete-me"), insert);
        transactions.commit(insert);

        TxContext delete = transactions.begin();
        table.delete(5L, delete);
        transactions.commit(delete);

        SmokeUtils.assertEquals(Optional.empty(), reopenedRead(storageDirectory, metadata, 5L),
                "committed delete must not resurrect after reopen");
    }

    private static void assertProviderCommittedUpdateIsNotHalfVisible(Path storageDirectory) throws Exception {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "module5k_committed_update");
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext insert = transactions.begin();
        table.insert(6L, row(6, "old"), insert);
        transactions.commit(insert);

        TxContext update = transactions.begin();
        table.update(6L, row(6, "new"), update);
        transactions.commit(update);

        SmokeUtils.assertEquals(Optional.of(row(6, "new")), reopenedRead(storageDirectory, metadata, 6L),
                "committed update must reopen as the complete new value, not a half-visible update");
        SmokeUtils.assertEquals(List.of(row(6, "new")), reopenedScanValues(storageDirectory, metadata),
                "committed update scan should expose only the newest visible row image");
    }

    private static void assertPageMutationCrashPoints() throws Exception {
        assertPageCrashAfterVersionBeforeCommit(PAGE_STORAGE.resolve("version-before-commit"));
        assertPageCrashAfterCommitBeforePageForce(PAGE_STORAGE.resolve("commit-before-page-force"));
        assertPageCrashAfterDeleteBeforeCommit(PAGE_STORAGE.resolve("delete-before-commit"));
        assertPageCrashAfterUpdateBeforeCommit(PAGE_STORAGE.resolve("update-before-commit"));
        assertPageCommittedDeleteDoesNotResurrect(PAGE_STORAGE.resolve("committed-delete"));
        assertPageCommittedUpdateIsNotHalfVisible(PAGE_STORAGE.resolve("committed-update"));
    }

    private static void assertPageCrashAfterVersionBeforeCommit(Path directory) throws Exception {
        Path tablePath = tablePath(directory);
        Path logPath = logPath(directory);
        MvccPageMutationLog log = MvccPageMutationLog.open(logPath);
        log.appendVersion(10L, valueRecord("1", "uncommitted", 1L, 1L, 0L, 10L));

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tablePath, logPath)) {
            SmokeUtils.assertEquals(Optional.empty(), table.read("1", new MvccCommitSequence(Long.MAX_VALUE)),
                    "page recovery must ignore a version record without commit");
        }
    }

    private static void assertPageCrashAfterCommitBeforePageForce(Path directory) throws Exception {
        Path tablePath = tablePath(directory);
        Path logPath = logPath(directory);
        MvccPageMutationLog log = MvccPageMutationLog.open(logPath);
        log.appendVersion(20L, valueRecord("2", "committed", 1L, 1L, 0L, 20L));
        log.appendCommit(20L, 1L);

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tablePath, logPath)) {
            SmokeUtils.assertEquals(Optional.of("committed"), table.read("2", new MvccCommitSequence(Long.MAX_VALUE)),
                    "page recovery must redo committed log record when page force did not happen");
        }
    }

    private static void assertPageCrashAfterDeleteBeforeCommit(Path directory) throws Exception {
        Path tablePath = tablePath(directory);
        Path logPath = logPath(directory);
        MvccPageMutationLog log = MvccPageMutationLog.open(logPath);
        log.appendVersion(30L, valueRecord("3", "alive", 1L, 1L, 0L, 30L));
        log.appendCommit(30L, 1L);
        log.appendVersion(31L, tombstoneRecord("3", 1L, 2L, 1L, 31L));

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tablePath, logPath)) {
            SmokeUtils.assertEquals(Optional.of("alive"), table.read("3", new MvccCommitSequence(Long.MAX_VALUE)),
                    "page recovery must ignore uncommitted delete tombstone");
        }
    }

    private static void assertPageCrashAfterUpdateBeforeCommit(Path directory) throws Exception {
        Path tablePath = tablePath(directory);
        Path logPath = logPath(directory);
        MvccPageMutationLog log = MvccPageMutationLog.open(logPath);
        log.appendVersion(40L, valueRecord("4", "old", 1L, 1L, 0L, 40L));
        log.appendCommit(40L, 1L);
        log.appendVersion(41L, valueRecord("4", "new", 1L, 2L, 1L, 41L));

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tablePath, logPath)) {
            SmokeUtils.assertEquals(Optional.of("old"), table.read("4", new MvccCommitSequence(Long.MAX_VALUE)),
                    "page recovery must ignore uncommitted update version");
        }
    }

    private static void assertPageCommittedDeleteDoesNotResurrect(Path directory) throws Exception {
        Path tablePath = tablePath(directory);
        Path logPath = logPath(directory);
        MvccPageMutationLog log = MvccPageMutationLog.open(logPath);
        log.appendVersion(50L, valueRecord("5", "delete-me", 1L, 1L, 0L, 50L));
        log.appendCommit(50L, 1L);
        log.appendVersion(51L, tombstoneRecord("5", 1L, 2L, 1L, 51L));
        log.appendCommit(51L, 2L);

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tablePath, logPath)) {
            SmokeUtils.assertEquals(Optional.empty(), table.read("5", new MvccCommitSequence(Long.MAX_VALUE)),
                    "committed page delete must not resurrect after recovery");
        }
    }

    private static void assertPageCommittedUpdateIsNotHalfVisible(Path directory) throws Exception {
        Path tablePath = tablePath(directory);
        Path logPath = logPath(directory);
        MvccPageMutationLog log = MvccPageMutationLog.open(logPath);
        log.appendVersion(60L, valueRecord("6", "old", 1L, 1L, 0L, 60L));
        log.appendCommit(60L, 1L);
        log.appendVersion(61L, valueRecord("6", "new", 1L, 2L, 1L, 61L));
        log.appendCommit(61L, 2L);

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tablePath, logPath)) {
            SmokeUtils.assertEquals(Optional.of("new"), table.read("6", new MvccCommitSequence(Long.MAX_VALUE)),
                    "committed page update must recover as the complete new version");
            SmokeUtils.assertEquals(List.of("new"), table.visibleRows(new MvccCommitSequence(Long.MAX_VALUE)).stream()
                            .map(MvccRowPayload::valueAsUtf8)
                            .toList(),
                    "page update recovery must expose one newest visible row image");
        }
    }

    private static Optional<List<Object>> reopenedRead(
            Path storageDirectory,
            VersionedTableMetadata metadata,
            long key) {
        DelosMvccStorageProvider recoveredProvider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recoveredProvider.openTable(metadata);
        VersionedTransactionCoordinator transactions = recoveredProvider.transactionCoordinator();
        TxContext reader = transactions.begin();
        try {
            return recoveredTable.read(key, reader.currentView());
        } finally {
            transactions.abort(reader);
        }
    }

    private static List<List<Object>> reopenedScanValues(Path storageDirectory, VersionedTableMetadata metadata) {
        DelosMvccStorageProvider recoveredProvider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recoveredProvider.openTable(metadata);
        VersionedTransactionCoordinator transactions = recoveredProvider.transactionCoordinator();
        TxContext reader = transactions.begin();
        try (VersionedScan<Long, List<Object>> scan = recoveredTable.openScan(reader.currentView())) {
            java.util.ArrayList<List<Object>> values = new java.util.ArrayList<>();
            while (scan.next()) {
                values.add(scan.row().value());
            }
            return List.copyOf(values);
        } finally {
            transactions.abort(reader);
        }
    }

    private static MvccVersionRecord valueRecord(
            String key,
            String value,
            long rowId,
            long versionId,
            long previousVersionId,
            long transactionId) {
        return versionRecord(
                key,
                value.getBytes(StandardCharsets.UTF_8),
                rowId,
                versionId,
                previousVersionId,
                transactionId,
                0);
    }

    private static MvccVersionRecord tombstoneRecord(
            String key,
            long rowId,
            long versionId,
            long previousVersionId,
            long transactionId) {
        return versionRecord(
                key,
                new byte[0],
                rowId,
                versionId,
                previousVersionId,
                transactionId,
                MvccVersionRecordFlags.TOMBSTONE);
    }

    private static MvccVersionRecord versionRecord(
            String key,
            byte[] value,
            long rowId,
            long versionId,
            long previousVersionId,
            long transactionId,
            int flags) {
        return new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(rowId),
                        new MvccVersionId(versionId),
                        new MvccVersionId(previousVersionId),
                        new MvccTransactionId(transactionId),
                        (flags & MvccVersionRecordFlags.TOMBSTONE) != 0
                                ? new MvccTransactionId(transactionId)
                                : MvccTransactionId.NONE,
                        MvccCommitSequence.NONE,
                        flags),
                MvccRowPayloadCodec.encode(new MvccRowPayload(key, value)));
    }

    private static Path tablePath(Path directory) throws Exception {
        Files.createDirectories(directory);
        return directory.resolve("table.dmvcc");
    }

    private static Path logPath(Path directory) throws Exception {
        Files.createDirectories(directory);
        return directory.resolve("table.dmvcc.log");
    }

    private static List<Object> row(int id, String name) {
        return List.of(id, name);
    }

    private static void requireRecord(List<MvccLogRecord> records, MvccLogRecord.Type type, long transactionId) {
        records.stream()
                .filter(record -> record.type() == type)
                .filter(record -> record.transactionId().value() == transactionId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + type + " for tx " + transactionId));
    }

    private static void requireNoRecord(List<MvccLogRecord> records, MvccLogRecord.Type type, long transactionId) {
        records.stream()
                .filter(record -> record.type() == type)
                .filter(record -> record.transactionId().value() == transactionId)
                .findFirst()
                .ifPresent(record -> {
                    throw new AssertionError("unexpected " + type + " for tx " + transactionId + ": " + record);
                });
    }

    private static void assertHeapStillWorks() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(HEAP_DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP.MODULE5K_HEAP (id INT, name VARCHAR(20))");
            statement.executeUpdate("INSERT INTO APP.MODULE5K_HEAP VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP.MODULE5K_HEAP WHERE id = 1"),
                    "heap table should still work during MODULE5K");
        }
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NATIVE_ROUTE_PROPERTIES) {
            assertPropertyNotSet(propertyName);
        }
    }

    private static void assertPropertyNotSet(String propertyName) {
        if (Boolean.getBoolean(propertyName)) {
            throw new AssertionError("MODULE5K must not rely on old native proof property: " + propertyName);
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NATIVE_ROUTE_PROPERTIES) {
            System.clearProperty(propertyName);
        }
    }
}
