package delosdb.smoke;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared helpers for DelosDB local smoke programs.
 */
public final class SmokeUtils {
    private SmokeUtils() {
    }

    public static void loadEmbeddedDriver() throws ClassNotFoundException {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
    }

    public static Connection connect(String databasePath, boolean create) throws SQLException {
        String url = "jdbc:derby:" + databasePath + (create ? ";create=true" : "");
        return DriverManager.getConnection(url);
    }

    public static void shutdown(String databasePath) throws SQLException {
        try {
            DriverManager.getConnection("jdbc:derby:" + databasePath + ";shutdown=true").close();
        } catch (SQLException expected) {
            if ("08006".equals(expected.getSQLState()) || "XJ004".equals(expected.getSQLState())) {
                return;
            }

            String message = expected.getMessage();
            if (message != null && message.contains("No suitable driver")) {
                return;
            }

            throw expected;
        }
    }

    public static void assertContains(String actual, String expected, String label) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(label + " expected to contain '" + expected + "' but was: " + actual);
        }
    }

    public static void assertEquals(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static String singleString(Statement statement, String sql) throws SQLException {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError("No row returned for " + sql);
            }
            String value = results.getString(1);
            if (results.next()) {
                throw new AssertionError("More than one row returned for " + sql);
            }
            return value;
        }
    }
}
