package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE7B smoke: inherited MVCC SELECT WHERE equality is selective.
 *
 * <p>This is a runtime behavior proof, not a source audit. MODULE7A owns the
 * source-gated qualifier map. This smoke proves that normal Derby SQL SELECT
 * over an MVCC physical table returns the correct visible rows for simple
 * equality and non-matching equality predicates.</p>
 */
public final class Module7bInheritedSqlSelectWhereEqualitySmoke {
    private static final String DATABASE_PATH = "build/module7b-inherited-sql-select-where-equality-db";
    private static final String MVCC_TABLE = "MODULE7B_MVCC";
    private static final String HEAP_TABLE = "MODULE7B_HEAP";

    private Module7bInheritedSqlSelectWhereEqualitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertRuntimeSelectWhereEquality();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertRuntimeSelectWhereEquality() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            createHeapCompatibilityFixture(statement);
            SmokeUtils.assertEquals(List.of("heap-two"), names(statement,
                    "SELECT name FROM APP." + HEAP_TABLE + " WHERE id = 2"),
                    "MODULE7B heap WHERE equality must remain green");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            assertMvccPhysicalConglomerate(statement, MVCC_TABLE);

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'two')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'three')");

            MvccScanController.resetOpenCountForTesting();
            MvccScanController.resetQualifierRejectCountForTesting();

            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement,
                    "SELECT id FROM APP." + MVCC_TABLE),
                    "MODULE7B baseline MVCC SELECT must see all committed rows");
            SmokeUtils.assertEquals(List.of(2), ids(statement,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE id = 2"),
                    "MODULE7B MVCC SELECT WHERE id = 2 must return only row 2");
            SmokeUtils.assertEquals(List.of(), ids(statement,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE id = 999"),
                    "MODULE7B MVCC SELECT WHERE id = 999 must return no rows");
            SmokeUtils.assertEquals(List.of("three"), names(statement,
                    "SELECT name FROM APP." + MVCC_TABLE + " WHERE name = 'three'"),
                    "MODULE7B MVCC SELECT WHERE name = 'three' must return only row three");
            SmokeUtils.assertEquals(List.of(), names(statement,
                    "SELECT name FROM APP." + MVCC_TABLE + " WHERE name = 'missing'"),
                    "MODULE7B MVCC SELECT WHERE name = 'missing' must return no rows");

            try (PreparedStatement prepared = connection.prepareStatement(
                    "SELECT name FROM APP." + MVCC_TABLE + " WHERE id = ?")) {
                prepared.setInt(1, 1);
                SmokeUtils.assertEquals(List.of("one"), names(prepared),
                        "MODULE7B prepared MVCC SELECT WHERE id = ? must honor bound equality value 1");
                prepared.setInt(1, 3);
                SmokeUtils.assertEquals(List.of("three"), names(prepared),
                        "MODULE7B prepared MVCC SELECT WHERE id = ? must honor bound equality value 3");
            }

            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE7B MVCC SELECT WHERE equality must reach MvccScanController");
        }
    }

    private static void createHeapCompatibilityFixture(Statement statement) throws Exception {
        statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
        statement.executeUpdate("CREATE INDEX MODULE7B_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap-one')");
        statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (2, 'heap-two')");
    }

    private static void assertMvccPhysicalConglomerate(Statement statement, String tableName) throws Exception {
        SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                baseConglomerateNumber(statement, tableName) & 0x0fL,
                "MODULE7B " + tableName + " must use an MVCC physical conglomerate");
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

    private static List<Integer> ids(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return ids(rows);
        }
    }

    private static List<Integer> ids(ResultSet rows) throws Exception {
        List<Integer> values = new ArrayList<>();
        while (rows.next()) {
            values.add(rows.getInt(1));
        }
        values.sort(Integer::compareTo);
        return List.copyOf(values);
    }

    private static List<String> names(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return names(rows);
        }
    }

    private static List<String> names(PreparedStatement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery()) {
            return names(rows);
        }
    }

    private static List<String> names(ResultSet rows) throws Exception {
        List<String> values = new ArrayList<>();
        while (rows.next()) {
            values.add(rows.getString(1));
        }
        values.sort(String::compareTo);
        return List.copyOf(values);
    }

    private static void clearNativeMvccProofProperties() {
        for (String propertyName : NATIVE_MVCC_PROOF_PROPERTIES) {
            System.clearProperty(propertyName);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static final String[] NATIVE_MVCC_PROOF_PROPERTIES = new String[] {
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
