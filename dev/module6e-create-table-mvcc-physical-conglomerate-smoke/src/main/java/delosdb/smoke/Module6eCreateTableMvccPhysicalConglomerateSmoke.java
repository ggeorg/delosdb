package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;

/**
 * MODULE6E smoke: CREATE TABLE physical conglomerate switch preflight.
 *
 * <p>This smoke proves only the physical creation truth: heap tables keep heap
 * conglomerates, while {@code USING delos_mvcc} base tables now request an MVCC
 * conglomerate from Derby store/access. It does not route normal SQL SELECT or
 * DML through the inherited MVCC controllers yet; MODULE6F-MODULE6H own those
 * bridge-killer proofs.</p>
 */
public final class Module6eCreateTableMvccPhysicalConglomerateSmoke {
    private static final String DATABASE_PATH = "build/module6e-create-table-mvcc-physical-conglomerate-db";
    private static final String HEAP_TABLE = "MODULE6E_HEAP";
    private static final String MVCC_TABLE = "MODULE6E_MVCC";

    private Module6eCreateTableMvccPhysicalConglomerateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        try {
            assertSourcePhysicalCreationSwitch();
            assertRuntimePhysicalConglomerateSwitch();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourcePhysicalCreationSwitch() throws Exception {
        String createTable = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/CreateTableConstantAction.java"));
        requireContains(createTable,
                "String conglomerateImplementation = physicalConglomerateImplementation()",
                "CREATE TABLE must select a physical conglomerate implementation before createConglomerate");
        requireContains(createTable,
                "return \"delos_mvcc\"",
                "delos_mvcc tables must request the MVCC access method");
        requireContains(createTable,
                "return \"heap\"",
                "heap tables must continue to request heap conglomerates");
        requireContains(createTable,
                "registerNativeDelosTableIfNeeded",
                "MODULE6E must preserve the existing provider-identity registration bridge while inherited SQL paths remain incomplete");
    }

    private static void assertRuntimePhysicalConglomerateSwitch() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + " (id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6E_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP." + HEAP_TABLE + " WHERE id = 1"),
                    "heap table and btree index must stay green while switching delos_mvcc physical creation");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE + " (id INT, name VARCHAR(32)) USING delos_mvcc");

            SmokeUtils.assertEquals(null, storageProvider(statement, HEAP_TABLE),
                    "heap table must not receive delos_mvcc provider identity");
            SmokeUtils.assertEquals("delos_mvcc", storageProvider(statement, MVCC_TABLE),
                    "delos_mvcc provider identity must remain catalog-visible");

            long heapConglomerate = baseConglomerateNumber(statement, HEAP_TABLE);
            long mvccConglomerate = baseConglomerateNumber(statement, MVCC_TABLE);

            SmokeUtils.assertEquals(0L, heapConglomerate & 0x0fL,
                    "heap base conglomerate must keep heap factory id in low conglom bits");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID, mvccConglomerate & 0x0fL,
                    "delos_mvcc base conglomerate must use the MVCC factory id in low conglom bits");

            if (!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE)) {
                throw new AssertionError("MODULE6E must preserve transitional provider registration until inherited SQL execution is green");
            }
            if (DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_TABLE)) {
                throw new AssertionError("heap table must not register as delos_mvcc");
            }
        }
    }

    private static String storageProvider(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT STORAGEPROVIDER FROM SYS.SYSTABLES WHERE TABLENAME = '" + tableName + "'")) {
            if (!rows.next()) {
                throw new AssertionError("Missing SYS.SYSTABLES row for " + tableName);
            }
            String value = rows.getString(1);
            if (rows.next()) {
                throw new AssertionError("More than one SYS.SYSTABLES row for " + tableName);
            }
            return value;
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

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            if (Boolean.getBoolean(propertyName)) {
                throw new AssertionError("MODULE6E must not rely on old native proof property: " + propertyName);
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
