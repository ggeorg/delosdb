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
import org.apache.derby.iapi.store.access.conglomerate.MethodFactory;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccRowLocation;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE6C smoke: first inherited Derby MVCC conglomerate skeleton.
 *
 * <p>This proves the registered {@code delos_mvcc} access method can create an
 * inherited Derby conglomerate skeleton and open inherited scan/controller
 * skeletons. It intentionally does not change CREATE TABLE routing or execute
 * normal SQL through the new access method.</p>
 */
public final class Module6cMvccConglomerateSkeletonSmoke {
    private static final String DATABASE_PATH = "build/module6c-mvcc-conglomerate-skeleton-db";

    private Module6cMvccConglomerateSkeletonSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearNativeRouteProperties();

        try {
            assertSourceSkeletonFacts();
            assertRuntimeSkeletonThroughInheritedStoreAccess();
            assertCreateTableRouteStillUnchanged();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourceSkeletonFacts() throws Exception {
        require(Files.exists(Path.of(
                        "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerate.java")),
                "MODULE6C must add an MVCC conglomerate skeleton");
        require(Files.exists(Path.of(
                        "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccScanController.java")),
                "MODULE6C must add an MVCC scan-controller skeleton");
        require(Files.exists(Path.of(
                        "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerateController.java")),
                "MODULE6C must add an MVCC conglomerate-controller skeleton");
        require(Files.exists(Path.of(
                        "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccRowLocation.java")),
                "MODULE6C must add a logical MVCC row-location skeleton");

        String rowLocation = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccRowLocation.java"));
        requireContains(rowLocation, "private long rowId", "MVCC row location must carry stable logical row id");
        requireContains(rowLocation, "locatorPageId", "MVCC row location may carry locator hint");
        requireContains(rowLocation, "locatorSlotId", "MVCC row location may carry locator hint");
    }

    private static void assertRuntimeSkeletonThroughInheritedStoreAccess() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE MODULE6C_HEAP(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6C_HEAP_NAME_IDX ON MODULE6C_HEAP(name) USING btree");
            statement.executeUpdate("INSERT INTO MODULE6C_HEAP VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM MODULE6C_HEAP WHERE id = 1"),
                    "heap and btree must remain green while adding MVCC store/access skeleton");

            EmbedConnection embed = connection.unwrap(EmbedConnection.class);
            TransactionController tc = embed.getLanguageConnection().getTransactionExecute();
            MethodFactory methodFactory = tc.getAccessManager().findMethodFactoryByImpl("delos_mvcc");
            require(methodFactory instanceof ConglomerateFactory,
                    "delos_mvcc must remain discoverable as a ConglomerateFactory");

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

            ConglomerateController controller = tc.openConglomerate(
                    conglomId,
                    false,
                    0,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_SERIALIZABLE);
            require(controller instanceof MvccConglomerateController,
                    "openConglomerate must reach the MVCC ConglomerateController skeleton");

            StoreRowLocation rowLocation = controller.newRowLocationTemplate();
            require(rowLocation instanceof MvccRowLocation,
                    "MVCC controller must return a logical MVCC row-location template");
            controller.insertAndFetchLocation(template, rowLocation);
            MvccRowLocation mvccLocation = MvccRowLocation.from(rowLocation);
            require(mvccLocation.rowId() > 0L, "MVCC row location must carry a stable logical row id");
            require(!mvccLocation.hasLocatorHint(), "MODULE6C skeleton must not treat page/slot as logical identity");
            controller.close();

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
                    "openScan must reach the MVCC ScanController skeleton");
            require(!scan.fetchNext(template), "MODULE6C scan skeleton must not pretend to read MVCC rows yet");
            scan.close();
        }
    }

    private static void assertCreateTableRouteStillUnchanged() throws Exception {
        String createTable = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/CreateTableConstantAction.java"));
        requireContains(createTable,
                "\"heap\", // we're requesting a heap conglomerate",
                "MODULE6C must not switch CREATE TABLE physical conglomerate creation yet");
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            if (Boolean.getBoolean(propertyName)) {
                throw new AssertionError("MODULE6C must not rely on old native proof property: " + propertyName);
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
