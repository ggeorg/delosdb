package delosdb.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;

import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.access.mvcc.MvccRowLocation;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE6D smoke: direct inherited store/access MVCC scan proof.
 *
 * <p>This stays below SQL execution. It proves that rows inserted through the
 * inherited ConglomerateController shape can be read through the inherited
 * ScanController shape using MVCC visibility rules. Later milestones may switch
 * CREATE TABLE physical routing; this smoke remains below SQL execution and
 * does not switch TableScanResultSet.</p>
 */
public final class Module6dDirectStoreAccessMvccScanSmoke {
    private static final String DATABASE_PATH = "build/module6d-direct-store-access-mvcc-scan-db";

    private Module6dDirectStoreAccessMvccScanSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearNativeRouteProperties();

        try {
            assertSourceScanFacts();
            assertRuntimeDirectStoreAccessScan();
            assertCreateTableRouteIsWithinPlanBoundary();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourceScanFacts() throws Exception {
        String state = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerateState.java"));
        requireContains(state,
                "new MvccTable<>",
                "MODULE6D store/access state must use the MVCC visibility kernel");
        requireContains(state,
                "new MvccTransactionManager()",
                "MODULE6D store/access state must own an MVCC transaction manager");

        String controller = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerateController.java"));
        requireContains(controller,
                "state.table().insert",
                "inherited MVCC ConglomerateController must feed rows into the MVCC table");
        requireContains(controller,
                "state.transactions().commit(writer)",
                "inherited end-transaction close must commit controller-local MVCC writes in this preflight");
        requireContains(controller,
                "state.transactions().abort(writer)",
                "normal close must abort controller-local MVCC writes in this preflight");

        String scan = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccScanController.java"));
        requireContains(scan,
                "state.table().openScan(snapshot, state.transactions())",
                "inherited MVCC ScanController must open a snapshot against the MVCC table");
        requireContains(scan,
                "public boolean fetchNext(StoreDataValue[] destRow)",
                "MODULE6D must implement fetchNext through the inherited scan interface");
    }

    private static void assertRuntimeDirectStoreAccessScan() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE MODULE6D_HEAP(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6D_HEAP_NAME_IDX ON MODULE6D_HEAP(name) USING btree");
            statement.executeUpdate("INSERT INTO MODULE6D_HEAP VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM MODULE6D_HEAP WHERE id = 1"),
                    "heap and btree must remain green while proving direct MVCC store/access scan");

            EmbedConnection embed = connection.unwrap(EmbedConnection.class);
            TransactionController tc = embed.getLanguageConnection().getTransactionExecute();
            StoreDataValue[] template = new StoreDataValue[] { new SQLInteger() };
            long conglomId = tc.createConglomerate(
                    "delos_mvcc",
                    template,
                    null,
                    null,
                    new Properties(),
                    TransactionController.IS_DEFAULT);
            require((conglomId & 0x0fL) == ConglomerateFactory.MVCC_FACTORY_ID,
                    "MVCC conglomerate id must encode the reserved MVCC factory id");

            StoreRowLocation committedLocation = insertAndCommit(tc, conglomId, 11);
            ConglomerateController activeController = insertAndKeepActive(tc, conglomId, 22);
            insertAndAbort(tc, conglomId, 33);

            StoreDataValue[] scanRow = new StoreDataValue[] { new SQLInteger() };
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
            require(scan instanceof MvccScanController,
                    "openScan must use the MVCC ScanController implementation");
            require(scan.fetchNext(scanRow),
                    "direct inherited MVCC scan must see committed row");
            SmokeUtils.assertEquals(11, ((SQLInteger) scanRow[0]).getInt(),
                    "direct inherited MVCC scan must return committed row payload");
            StoreRowLocation fetchedLocation = scan.newRowLocationTemplate();
            scan.fetchLocation(fetchedLocation);
            require(MvccRowLocation.from(fetchedLocation).rowId() == MvccRowLocation.from(committedLocation).rowId(),
                    "direct inherited scan must expose logical MVCC row location for committed row");
            require(!scan.fetchNext(new StoreDataValue[] { new SQLInteger() }),
                    "direct inherited MVCC scan must hide active and aborted rows");
            scan.close();

            StoreDataValue[] fetched = new StoreDataValue[] { new SQLInteger() };
            ConglomerateController reader = tc.openConglomerate(
                    conglomId,
                    false,
                    0,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_SERIALIZABLE);
            require(reader.fetch(committedLocation, fetched, null),
                    "inherited MVCC ConglomerateController.fetch must see committed row by logical location");
            SmokeUtils.assertEquals(11, ((SQLInteger) fetched[0]).getInt(),
                    "fetch by logical row location must return committed row payload");
            reader.close();

            activeController.close();
        }
    }

    private static StoreRowLocation insertAndCommit(TransactionController tc, long conglomId, int value) throws Exception {
        ConglomerateController controller = tc.openConglomerate(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE);
        StoreRowLocation location = controller.newRowLocationTemplate();
        controller.insertAndFetchLocation(new StoreDataValue[] { new SQLInteger(value) }, location);
        require(MvccRowLocation.from(location).rowId() > 0L,
                "committed insert must return a logical MVCC row id");
        controller.closeForEndTransaction(false);
        return location;
    }

    private static ConglomerateController insertAndKeepActive(TransactionController tc, long conglomId, int value)
            throws Exception {
        ConglomerateController controller = tc.openConglomerate(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE);
        StoreRowLocation location = controller.newRowLocationTemplate();
        controller.insertAndFetchLocation(new StoreDataValue[] { new SQLInteger(value) }, location);
        require(MvccRowLocation.from(location).rowId() > 0L,
                "active insert must return a logical MVCC row id without becoming visible to another scan");
        return controller;
    }

    private static void insertAndAbort(TransactionController tc, long conglomId, int value) throws Exception {
        ConglomerateController controller = tc.openConglomerate(
                conglomId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE);
        StoreRowLocation location = controller.newRowLocationTemplate();
        controller.insertAndFetchLocation(new StoreDataValue[] { new SQLInteger(value) }, location);
        require(MvccRowLocation.from(location).rowId() > 0L,
                "aborted insert must return a logical MVCC row id before abort");
        controller.close();
    }

    private static void assertCreateTableRouteIsWithinPlanBoundary() throws Exception {
        String createTable = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/CreateTableConstantAction.java"));
        boolean preModule6eHeapOnlyRoute = createTable.contains(
                "\"heap\", // we're requesting a heap conglomerate");
        boolean module6ePhysicalProviderRoute = createTable.contains("physicalConglomerateImplementation()")
                && createTable.contains("return \"delos_mvcc\"")
                && createTable.contains("return \"heap\"");
        require(preModule6eHeapOnlyRoute || module6ePhysicalProviderRoute,
                "MODULE6D smoke must accept either its original heap-only CREATE TABLE boundary "
                        + "or the later MODULE6E physical provider switch");
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            if (Boolean.getBoolean(propertyName)) {
                throw new AssertionError("MODULE6D must not rely on old native proof property: " + propertyName);
            }
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            System.clearProperty(propertyName);
        }
    }

    private static void requireContains(String source, String expected, String label) {
        if (source == null || !source.contains(expected)) {
            throw new AssertionError(label + " expected source to contain: " + expected);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static final class NativeRouteProperties {
        private static final String[] NAMES = new String[] {
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
                "delosdb.storage.phaseF7.nativeMvccUpdateEquality"
        };
    }
}
