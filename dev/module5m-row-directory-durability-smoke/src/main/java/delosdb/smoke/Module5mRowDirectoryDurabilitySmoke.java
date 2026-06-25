package delosdb.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccIndexTuple;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowDirectoryStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccVersionLocator;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;

/**
 * MODULE5M smoke: row-directory durability preflight.
 *
 * <p>This does not add an index, vacuum, native I/O, or a new SQL bridge route.
 * It proves the narrow next step after durable transaction status/restart/WAL
 * skeleton work: the page-backed MVCC table now persists the logical
 * {@code MvccRowId -> head MvccVersionLocator} mapping explicitly in a forced
 * row-directory sidecar, while older versions and tombstones remain reachable
 * through the version chain in version pages.</p>
 */
public final class Module5mRowDirectoryDurabilitySmoke {
    private static final Path STORAGE_DIRECTORY = Path.of("build/module5m-row-directory-durability");
    private static final Path PAGE_FILE = STORAGE_DIRECTORY.resolve("module5m_row_directory.dmvcc");
    private static final String HEAP_DATABASE_PATH = "build/module5m-heap-smoke-db";

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

    private Module5mRowDirectoryDurabilitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(HEAP_DATABASE_PATH);
        SmokeUtils.deleteRecursively(STORAGE_DIRECTORY);
        SmokeUtils.deleteRecursively(Path.of(HEAP_DATABASE_PATH));
        clearNativeRouteProperties();

        try {
            assertRowDirectoryDurability();
            assertHeapStillWorks();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            SmokeUtils.shutdownQuietly(HEAP_DATABASE_PATH);
        }
    }

    private static void assertRowDirectoryDurability() throws Exception {
        Files.createDirectories(STORAGE_DIRECTORY);

        MvccIndexTuple insert;
        MvccIndexTuple update;
        MvccIndexTuple delete;
        MvccRowId rowId;

        try (PageBackedMvccTable table = PageBackedMvccTable.open(PAGE_FILE)) {
            insert = table.insertCommitted("1", "A", 1L, 1L);
            rowId = insert.rowId();
            requireHead(table, rowId, insert.versionId(), MvccVersionId.NONE, insert.versionLocator(), false,
                    "insert should persist the first row-directory head");
            SmokeUtils.assertEquals(Optional.of("A"), table.read("1", new MvccCommitSequence(1L)),
                    "inserted row should be visible before reopen");
        }

        requireRowDirectoryFile();
        try (PageBackedMvccTable table = PageBackedMvccTable.open(PAGE_FILE)) {
            requireHead(table, rowId, insert.versionId(), MvccVersionId.NONE, insert.versionLocator(), false,
                    "row-directory insert head should survive reopen");
            SmokeUtils.assertEquals(Optional.of("A"), table.read("1", new MvccCommitSequence(1L)),
                    "inserted row should be visible after reopen");

            update = table.updateCommitted("1", "B", 2L, 2L);
            SmokeUtils.assertEquals(rowId, update.rowId(), "update must keep the same logical row id");
            requireHead(table, rowId, update.versionId(), insert.versionId(), update.versionLocator(), false,
                    "update should move the durable row-directory head");
            requireVersion(table, rowId, insert.versionId(), "old inserted version should remain reachable");
            requireVersion(table, rowId, update.versionId(), "updated version should be reachable");
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(PAGE_FILE)) {
            requireHead(table, rowId, update.versionId(), insert.versionId(), update.versionLocator(), false,
                    "row-directory update head should survive reopen");
            requireVersion(table, rowId, insert.versionId(), "old inserted version should remain reachable after update reopen");
            requireVersion(table, rowId, update.versionId(), "updated version should remain reachable after reopen");
            SmokeUtils.assertEquals(Optional.of("B"), table.read("1", new MvccCommitSequence(2L)),
                    "updated value should be visible after reopen");

            delete = table.deleteCommitted("1", 3L, 3L);
            SmokeUtils.assertEquals(rowId, delete.rowId(), "delete tombstone must keep the same logical row id");
            requireHead(table, rowId, delete.versionId(), update.versionId(), delete.versionLocator(), true,
                    "delete should move the durable row-directory head to a tombstone");
            requireVersion(table, rowId, insert.versionId(), "inserted version should remain reachable after delete");
            requireVersion(table, rowId, update.versionId(), "updated version should remain reachable after delete");
            requireVersion(table, rowId, delete.versionId(), "delete tombstone should be reachable");
            SmokeUtils.assertEquals(Optional.empty(), table.read("1", new MvccCommitSequence(3L)),
                    "deleted row should not be visible after committed tombstone");
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(PAGE_FILE)) {
            requireHead(table, rowId, delete.versionId(), update.versionId(), delete.versionLocator(), true,
                    "row-directory tombstone head should survive reopen");
            requireVersion(table, rowId, insert.versionId(), "inserted version should remain reachable after tombstone reopen");
            requireVersion(table, rowId, update.versionId(), "updated version should remain reachable after tombstone reopen");
            requireVersion(table, rowId, delete.versionId(), "tombstone version should remain reachable after reopen");
            SmokeUtils.assertEquals(Optional.empty(), table.read("1", new MvccCommitSequence(3L)),
                    "committed tombstone must remain authoritative after reopen");
        }
    }

    private static void requireHead(
            PageBackedMvccTable table,
            MvccRowId rowId,
            MvccVersionId expectedHeadVersion,
            MvccVersionId expectedPreviousVersion,
            MvccVersionLocator expectedLocator,
            boolean expectedTombstone,
            String message) {
        MvccRowDirectoryStore.RowHeadRecord head = table.rowDirectoryHeadForRowId(rowId)
                .orElseThrow(() -> new AssertionError(message + ": missing durable row-directory head for " + rowId));
        SmokeUtils.assertEquals(expectedHeadVersion, head.headVersionId(), message + ": wrong head version");
        SmokeUtils.assertEquals(expectedPreviousVersion, head.previousVersionId(), message + ": wrong previous version");
        SmokeUtils.assertEquals(expectedLocator, head.headLocator(), message + ": wrong head locator");
        SmokeUtils.assertEquals(expectedTombstone, head.tombstone(), message + ": wrong tombstone flag");
    }

    private static void requireVersion(PageBackedMvccTable table, MvccRowId rowId, MvccVersionId versionId, String message) {
        if (!table.hasVersion(rowId, versionId)) {
            throw new AssertionError(message + ": " + rowId + " / " + versionId);
        }
    }

    private static void requireRowDirectoryFile() throws Exception {
        Path rowDirectoryPath = PageBackedMvccTable.rowDirectoryPath(PAGE_FILE);
        if (!Files.exists(rowDirectoryPath) || Files.size(rowDirectoryPath) == 0L) {
            throw new AssertionError("MODULE5M expected forced row-directory sidecar at " + rowDirectoryPath);
        }
    }

    private static void assertHeapStillWorks() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(HEAP_DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP.MODULE5M_HEAP (id INT, name VARCHAR(20))");
            statement.executeUpdate("INSERT INTO APP.MODULE5M_HEAP VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP.MODULE5M_HEAP WHERE id = 1"),
                    "heap table should still work during MODULE5M");
        }
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NATIVE_ROUTE_PROPERTIES) {
            assertPropertyNotSet(propertyName);
        }
    }

    private static void assertPropertyNotSet(String propertyName) {
        if (Boolean.getBoolean(propertyName)) {
            throw new AssertionError("MODULE5M must not rely on old native proof property: " + propertyName);
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NATIVE_ROUTE_PROPERTIES) {
            System.clearProperty(propertyName);
        }
    }
}
