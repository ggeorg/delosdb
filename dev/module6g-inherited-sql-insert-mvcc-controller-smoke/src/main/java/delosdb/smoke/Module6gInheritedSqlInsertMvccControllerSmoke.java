package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;

/**
 * MODULE6G smoke: normal Derby SQL INSERT reaches MVCC through the inherited
 * InsertResultSet -> RowChangerImpl -> MvccConglomerateController path.
 *
 * <p>SQL SELECT is expected to use the MODULE6F inherited scan path. DELETE and
 * UPDATE remain out of scope for MODULE6G.</p>
 */
public final class Module6gInheritedSqlInsertMvccControllerSmoke {
    private static final String DATABASE_PATH = "build/module6g-inherited-sql-insert-mvcc-controller-db";
    private static final String MVCC_TABLE = "MODULE6G_MVCC";
    private static final String MVCC_ROLLBACK_TABLE = "MODULE6G_ROLLBACK";
    private static final String MVCC_ACTIVE_TABLE = "MODULE6G_ACTIVE";
    private static final String HEAP_TABLE = "MODULE6G_HEAP";

    private Module6gInheritedSqlInsertMvccControllerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        try {
            assertRuntimeInheritedSqlInsert();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertRuntimeInheritedSqlInsert() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6G_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM APP." + HEAP_TABLE + " WHERE id = 1"),
                    "heap INSERT/SELECT and btree must remain green while switching MVCC INSERT to inherited RowChanger");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE + "(id INT) USING delos_mvcc");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, MVCC_TABLE) & 0x0fL,
                    "MODULE6G requires delos_mvcc table to have an MVCC physical conglomerate");

            MvccConglomerateController.resetInsertCountForTesting();
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (101)");
            require(MvccConglomerateController.insertCountForTesting() > 0,
                    "normal SQL INSERT must reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of(101), ids(statement, MVCC_TABLE),
                    "normal SQL SELECT must see the committed row inserted through inherited MVCC store/access");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_ROLLBACK_TABLE + "(id INT) USING delos_mvcc");
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("INSERT INTO APP." + MVCC_ROLLBACK_TABLE + " VALUES (202)");
                require(!ids(statement, MVCC_ROLLBACK_TABLE).contains(202),
                        "uncommitted SQL INSERT must not be visible before Derby commit completes");
                connection.rollback();
                connection.setAutoCommit(true);
                SmokeUtils.assertEquals(List.of(), ids(statement, MVCC_ROLLBACK_TABLE),
                        "rolled-back SQL INSERT must stay invisible through inherited MVCC scan");
            } finally {
                connection.setAutoCommit(true);
            }

            try (Connection activeWriter = SmokeUtils.connect(DATABASE_PATH, false);
                 Statement activeStatement = activeWriter.createStatement();
                 Connection reader = SmokeUtils.connect(DATABASE_PATH, true);
                 Statement readerStatement = reader.createStatement()) {
                activeStatement.executeUpdate("CREATE TABLE APP." + MVCC_ACTIVE_TABLE + "(id INT) USING delos_mvcc");
                activeWriter.commit();
                activeWriter.setAutoCommit(false);
                activeStatement.executeUpdate("INSERT INTO APP." + MVCC_ACTIVE_TABLE + " VALUES (303)");
                require(!ids(readerStatement, MVCC_ACTIVE_TABLE).contains(303),
                        "active SQL INSERT must be invisible to another Derby transaction");
                activeWriter.rollback();
            }

            require(MvccStoreAccessTransactionRegistry.pendingCountForTesting(
                    connection.unwrap(org.apache.derby.impl.jdbc.EmbedConnection.class)
                            .getLanguageConnection()
                            .getTransactionExecute()) == 0,
                    "MODULE6G must not leak pending MVCC store/access writers after commit/rollback");
        }
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + tableName)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        ids.sort(Integer::compareTo);
        return List.copyOf(ids);
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
                throw new AssertionError("MODULE6G must not rely on old native proof property: " + propertyName);
            }
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            System.clearProperty(propertyName);
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
