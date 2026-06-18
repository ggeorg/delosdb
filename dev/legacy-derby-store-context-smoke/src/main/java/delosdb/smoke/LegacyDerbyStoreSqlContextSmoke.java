package delosdb.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.apache.derby.iapi.sql.conn.ConnectionUtil;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.store.access.DiskHashtable;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Verifies legacy Derby store code can still reach the small SQL/session
 * information it needs through StoreExecutionContext instead of importing
 * LanguageConnectionContext or StatementContext directly.
 */
public final class LegacyDerbyStoreSqlContextSmoke {
    private LegacyDerbyStoreSqlContextSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table ds4_context_probe(id int primary key, name varchar(32))");
            statement.executeUpdate("insert into ds4_context_probe values (1, 'alpha')");

            statement.executeUpdate(
                    "create function ds4_disk_hashtable_probe() returns int "
                            + "external name 'delosdb.smoke.LegacyDerbyStoreSqlContextSmoke.diskHashtableProbe' "
                            + "language java parameter style java");
            statement.executeUpdate("call SYSCS_UTIL.SYSCS_SET_RUNTIMESTATISTICS(1)");
            assertSingleInt(statement, "values ds4_disk_hashtable_probe()", 0, "DiskHashtable probe failure count");

            assertTransactionTableContext(statement);
            assertBackupJarVersionCheck(connection, statement, Path.of(databasePath).getParent());
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB legacy Derby store SQL context smoke test passed.");
    }

    public static int diskHashtableProbe() throws SQLException {
        try {
            LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
            if (lcc == null) {
                throw new SQLException("Cannot get current LanguageConnectionContext");
            }
            TransactionController tc = lcc.getTransactionExecute();
            DataValueDescriptor[] template = {new SQLInteger(), new SQLVarchar()};
            DiskHashtable table = new DiskHashtable(
                    tc,
                    template,
                    null,
                    new int[] {0},
                    false,
                    false);
            try {
                DataValueDescriptor[] first = {new SQLInteger(1), new SQLVarchar("one")};
                DataValueDescriptor[] second = {new SQLInteger(2), new SQLVarchar("two")};
                table.put(first[0], first);
                table.put(second[0], second);
                assertDiskRow(table.get(new SQLInteger(1)), "one");
                assertDiskRow(table.remove(new SQLInteger(2)), "two");
                if (table.get(new SQLInteger(2)) != null) {
                    return 1;
                }
                return 0;
            } finally {
                table.close();
            }
        } catch (StandardException e) {
            throw new SQLException("DiskHashtable probe failed", e);
        }
    }

    private static void assertDiskRow(Object rowObject, String expected) throws SQLException {
        if (!(rowObject instanceof DataValueDescriptor[] row)) {
            throw new SQLException("Expected DiskHashtable row but got " + rowObject);
        }
        String actual = row[1].getString();
        if (!expected.equals(actual)) {
            throw new SQLException("Expected DiskHashtable value " + expected + " but got " + actual);
        }
    }

    private static void assertTransactionTableContext(Statement statement) throws SQLException {
        boolean sawUserTransaction = false;
        try (ResultSet results = statement.executeQuery(
                "select username, cast(sql_text as varchar(512)) "
                        + "from syscs_diag.transaction_table "
                        + "where type = 'UserTransaction'")) {
            while (results.next()) {
                sawUserTransaction = true;
                String username = results.getString(1);
                if (username == null || username.isBlank()) {
                    throw new AssertionError("Transaction table username was not populated through store context");
                }
                results.getString(2); // Exercises statement text retrieval through the neutral store context.
            }
        }
        if (!sawUserTransaction) {
            throw new AssertionError("Expected at least one user transaction in SYSCS_DIAG.TRANSACTION_TABLE");
        }
    }

    private static void assertBackupJarVersionCheck(Connection connection, Statement statement, Path workDir)
            throws SQLException, IOException {
        Path jar = workDir.resolve("ds4-context-probe.jar").toAbsolutePath();
        createProbeJar(jar);
        statement.executeUpdate("call SQLJ.INSTALL_JAR('" + jar.toUri() + "', 'APP.DS4_CONTEXT_PROBE_JAR', 0)");
        Path backupDir = workDir.resolve("backup").toAbsolutePath();
        statement.executeUpdate("call SYSCS_UTIL.SYSCS_BACKUP_DATABASE('" + escapeSql(backupDir.toString()) + "')");
        if (!Files.exists(backupDir)) {
            throw new AssertionError("Expected backup directory to be created: " + backupDir);
        }
        connection.commit();
    }

    private static void createProbeJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("delosdb/ds4/probe.txt");
            out.putNextEntry(entry);
            out.write("ds4".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static String escapeSql(String value) {
        return value.replace("'", "''");
    }

    private static void assertSingleInt(Statement statement, String sql, int expected, String label) throws SQLException {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError("No row returned for " + label);
            }
            int actual = results.getInt(1);
            SmokeUtils.assertEquals(expected, actual, label);
            if (results.next()) {
                throw new AssertionError("More than one row returned for " + label);
            }
        }
    }
}
