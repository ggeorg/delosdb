package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies the legacy Derby store still persists database properties and boots
 * an existing database after the store no longer imports DataDictionary
 * constants directly.
 */
public final class LegacyDerbyStorePropertyConglomerateSmoke {
    private static final String PROPERTY_NAME = "derby.locks.waitTimeout";
    private static final String PROPERTY_VALUE = "17";

    private LegacyDerbyStorePropertyConglomerateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table ds3_property_conglomerate(id int primary key, name varchar(32))");
            statement.executeUpdate("insert into ds3_property_conglomerate values (1, 'created')");
            statement.executeUpdate("call SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY('" + PROPERTY_NAME + "', '" + PROPERTY_VALUE + "')");
            assertProperty(statement, PROPERTY_VALUE, "property value after set");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertSingleRow(statement);
            assertProperty(statement, PROPERTY_VALUE, "property value after reboot");
            statement.executeUpdate("drop table ds3_property_conglomerate");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB legacy Derby store property conglomerate smoke test passed.");
    }

    private static void assertSingleRow(Statement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery("select name from ds3_property_conglomerate where id = 1")) {
            if (!results.next()) {
                throw new AssertionError("Missing row after reboot");
            }
            SmokeUtils.assertEquals("created", results.getString(1), "row value after reboot");
            if (results.next()) {
                throw new AssertionError("Unexpected extra row after reboot");
            }
        }
    }

    private static void assertProperty(Statement statement, String expected, String label) throws SQLException {
        try (ResultSet results = statement.executeQuery(
                "values SYSCS_UTIL.SYSCS_GET_DATABASE_PROPERTY('" + PROPERTY_NAME + "')")) {
            if (!results.next()) {
                throw new AssertionError("No database property row returned for " + label);
            }
            SmokeUtils.assertEquals(expected, results.getString(1), label);
            if (results.next()) {
                throw new AssertionError("More than one database property row returned for " + label);
            }
        }
    }
}
