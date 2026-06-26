package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.FileChannelPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.OffHeapPageVolume;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerate;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE12 smoke: inherited MVCC storage-I/O correctness matrix.
 *
 * <p>This smoke keeps inherited Derby integration as the gate. The durable proof
 * enters through Derby SQL/store/access and verifies that MODULE11 page-volume
 * state is complete, aligned, readable, forced, and authoritative after restart.
 * It also exercises the raw Delos page-volume contract over FileChannel and
 * off-heap volumes without pretending that off-heap can prove durable reopen.</p>
 */
public final class Module12InheritedMvccStorageIoMatrixSmoke {
    private static final String DATABASE_PATH = "build/module12-inherited-mvcc-storage-io-matrix-db";
    private static final String MVCC_TABLE = "MODULE12_IO";

    private Module12InheritedMvccStorageIoMatrixSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        SmokeUtils.deleteRecursively(Path.of("build/module12-raw-volume-matrix"));
        clearRuntimeState();
        clearNativeMvccProofProperties();

        try {
            exerciseRawPageVolumeMatrix();
            StateFiles stateFiles = createInheritedMvccPageVolumeState();
            verifyDurableFileChannelPageVolume(stateFiles.pageFile());
            shutdownAndClearRuntimeState();
            reopenAndAssertInheritedState(stateFiles);
            assertTornInheritedPageVolumeRejected(stateFiles);
        } finally {
            clearNativeMvccProofProperties();
            clearRuntimeState();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void exerciseRawPageVolumeMatrix() throws Exception {
        Path directory = Path.of("build/module12-raw-volume-matrix");
        Files.createDirectories(directory);
        exerciseVolume("offheap", OffHeapPageVolume.open(), DelosPageVolume.SyncPolicy.NONE);
        Path fileChannelPath = directory.resolve("file-channel.pages");
        try (FileChannelPageVolume volume = FileChannelPageVolume.open(fileChannelPath, DelosPageVolume.SyncPolicy.FULL)) {
            exerciseVolume("file-channel", volume, DelosPageVolume.SyncPolicy.FULL);
        }
        require(Files.size(fileChannelPath) == DelosPage.PAGE_SIZE,
                "MODULE12 FileChannel raw volume must write exactly one complete page");

        Path torn = directory.resolve("torn.pages");
        Files.write(torn, new byte[] {1, 2, 3});
        assertThrows("MODULE12 FileChannel pageCount must reject torn page-volume length",
                () -> FileChannelPageVolume.open(torn, DelosPageVolume.SyncPolicy.FULL));

        Path corrupt = directory.resolve("corrupt.pages");
        try (FileChannelPageVolume volume = FileChannelPageVolume.open(corrupt, DelosPageVolume.SyncPolicy.FULL)) {
            DelosPage page = DelosPage.empty(new DelosPageId(0L), DelosPage.DATA_PAGE_TYPE).withPageLsn(42L);
            page.appendRecord("corrupt-me".getBytes(StandardCharsets.UTF_8));
            volume.writePage(page);
            volume.force();
        }
        try (RandomAccessFile file = new RandomAccessFile(corrupt.toFile(), "rw")) {
            file.seek(0L);
            file.writeInt(0x0bad_f00d);
        }
        try (FileChannelPageVolume volume = FileChannelPageVolume.open(corrupt, DelosPageVolume.SyncPolicy.FULL)) {
            assertThrows("MODULE12 FileChannel readPage must reject invalid page magic",
                    () -> volume.readPage(new DelosPageId(0L)));
        }
    }

    private static void exerciseVolume(String label, DelosPageVolume volume, DelosPageVolume.SyncPolicy expectedPolicy)
            throws Exception {
        try (DelosPageVolume closeable = volume) {
            SmokeUtils.assertEquals(expectedPolicy, closeable.syncPolicy(),
                    "MODULE12 " + label + " sync policy must match selected provider");
            SmokeUtils.assertEquals(0L, closeable.pageCount(),
                    "MODULE12 " + label + " volume must start empty");
            DelosPage page = closeable.allocatePage(DelosPage.DATA_PAGE_TYPE).withPageLsn(7L);
            int slot = page.appendRecord((label + ":payload").getBytes(StandardCharsets.UTF_8));
            closeable.writePage(page);
            closeable.force();
            SmokeUtils.assertEquals(1L, closeable.pageCount(),
                    "MODULE12 " + label + " volume must have one page after allocation");
            DelosPage read = closeable.readPage(new DelosPageId(0L));
            SmokeUtils.assertEquals(7L, read.pageLsn(),
                    "MODULE12 " + label + " volume must preserve pageLSN in complete-page image");
            SmokeUtils.assertEquals(label + ":payload", new String(read.readRecord(slot), StandardCharsets.UTF_8),
                    "MODULE12 " + label + " volume must read back complete page payload");
            assertThrows("MODULE12 " + label + " read beyond pageCount must fail",
                    () -> closeable.readPage(new DelosPageId(1L)));
        }
        assertThrows("MODULE12 " + label + " closed volume must reject pageCount",
                volume::pageCount);
    }

    private static StateFiles createInheritedMvccPageVolumeState() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        resetInheritedCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(64)) USING delos_mvcc");
            long conglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    conglomId & 0x0fL,
                    "MODULE12 table must use inherited MVCC physical conglomerate identity");

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'two')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'rollback-live')");
            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'one-new' WHERE id = 1");
            statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 2");

            connection.setAutoCommit(false);
            try {
                SmokeUtils.assertEquals(1,
                        statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE + " WHERE id = 3"),
                        "MODULE12 rollback DELETE must initially affect one row");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE12 visible ids before restart must match committed MVCC state");
            SmokeUtils.assertEquals(List.of("one-new", "rollback-live"), names(statement),
                    "MODULE12 visible names before restart must match committed MVCC state");

            Path pageFile = MvccConglomerate.pageVolumeStateFileForTesting(0, conglomId);
            Path rowDirectoryFile = MvccConglomerate.rowDirectoryStateFileForTesting(0, conglomId);
            assertCompletePageFile(pageFile,
                    "MODULE12 inherited MVCC page-volume state must exist as complete pages before restart");
            assertNonEmptyFile(rowDirectoryFile,
                    "MODULE12 inherited MVCC row-directory sidecar must exist before restart");

            require(MvccConglomerateController.insertCountForTesting() >= 3,
                    "MODULE12 INSERTs must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.updateCountForTesting() >= 1,
                    "MODULE12 UPDATE must reach inherited MvccConglomerateController");
            require(MvccConglomerateController.deleteCountForTesting() >= 2,
                    "MODULE12 DELETEs must reach inherited MvccConglomerateController");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE12 SELECT must reach inherited MvccScanController before restart");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE12 must not resurrect retired native registry bridge");
            return new StateFiles(conglomId, pageFile, rowDirectoryFile);
        }
    }

    private static void verifyDurableFileChannelPageVolume(Path pageFile) throws Exception {
        assertCompletePageFile(pageFile,
                "MODULE12 inherited MVCC page-volume state must be page-size aligned before direct volume read");
        try (FileChannelPageVolume volume = FileChannelPageVolume.open(pageFile, DelosPageVolume.SyncPolicy.FULL)) {
            long count = volume.pageCount();
            require(count > 0L,
                    "MODULE12 inherited MVCC page-volume state must contain at least one page");
            for (long pageNumber = 0L; pageNumber < count; pageNumber++) {
                DelosPage page = volume.readPage(new DelosPageId(pageNumber));
                require(page.slotCount() > 0,
                        "MODULE12 inherited MVCC page " + pageNumber + " must contain version records");
            }
            volume.force();
        }
    }

    private static void shutdownAndClearRuntimeState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        SmokeUtils.assertEquals(0, MvccConglomerate.stateCountForTesting(),
                "MODULE12 restart proof must clear inherited MVCC runtime cache before reopen");
        resetInheritedCounters();
    }

    private static void reopenAndAssertInheritedState(StateFiles stateFiles) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            long reopenedConglomId = baseConglomerateNumber(statement, MVCC_TABLE);
            SmokeUtils.assertEquals(stateFiles.conglomId(), reopenedConglomId,
                    "MODULE12 MVCC conglomerate id must be stable across restart");
            SmokeUtils.assertEquals(List.of(1, 3), ids(statement),
                    "MODULE12 visible ids must reload from FileChannel page-volume state after restart");
            SmokeUtils.assertEquals(List.of("one-new", "rollback-live"), names(statement),
                    "MODULE12 visible names must reload from FileChannel page-volume state after restart");
            assertCompletePageFile(stateFiles.pageFile(),
                    "MODULE12 reopened inherited MVCC page-volume state must remain complete-page aligned");
            assertNonEmptyFile(stateFiles.rowDirectoryFile(),
                    "MODULE12 reopened inherited MVCC row-directory state must remain present");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE12 post-restart SELECT must reach inherited MvccScanController");
            require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                    "MODULE12 reopen must not populate retired native registry bridge");
        }
    }

    private static void assertTornInheritedPageVolumeRejected(StateFiles stateFiles) throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        clearRuntimeState();
        try (RandomAccessFile file = new RandomAccessFile(stateFiles.pageFile().toFile(), "rw")) {
            long originalLength = file.length();
            require(originalLength > 0L,
                    "MODULE12 inherited MVCC page-volume state must contain bytes for truncation proof");
            file.setLength(originalLength - 1L);
        }
        clearRuntimeState();
        SmokeUtils.loadEmbeddedDriver();
        assertThrows("MODULE12 inherited MVCC reopen must reject torn page-volume state",
                () -> {
                    try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
                         Statement statement = connection.createStatement()) {
                        ids(statement);
                    }
                });
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

    private static void assertCompletePageFile(Path path, String label) throws Exception {
        assertNonEmptyFile(path, label);
        long size = Files.size(path);
        require(size % DelosPage.PAGE_SIZE == 0L,
                label + ": size " + size + " is not a multiple of Delos page size " + DelosPage.PAGE_SIZE);
    }

    private static void assertNonEmptyFile(Path path, String label) throws Exception {
        require(path != null, label + " path must not be null");
        require(Files.exists(path), label + ": missing " + path);
        require(Files.size(path) > 0L, label + ": empty " + path);
    }

    private static void clearRuntimeState() {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerate.clearStatesForTesting();
        MvccStoreAccessTransactionRegistry.clearForTesting();
    }

    private static void resetInheritedCounters() {
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void clearNativeMvccProofProperties() {
        for (String propertyName : NativeMvccProofProperties.NAMES) {
            System.clearProperty(propertyName);
        }
    }

    private static void assertThrows(String label, ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (Throwable expected) {
            return;
        }
        throw new AssertionError(label + ": expected failure");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record StateFiles(long conglomId, Path pageFile, Path rowDirectoryFile) {
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
