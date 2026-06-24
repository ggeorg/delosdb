package delosdb.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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


    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            IOException[] failure = new IOException[1];
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (failure[0] == null) {
                        failure[0] = exception;
                    } else {
                        failure[0].addSuppressed(exception);
                    }
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
        }
    }

    public static void shutdownQuietly(String databasePath) {
        if (databasePath == null || databasePath.isBlank()) {
            return;
        }
        try {
            shutdown(databasePath);
        } catch (SQLException ignored) {
            // Best-effort cleanup path for smoke programs.
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


    public static int executePreparedUpdate(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                int parameterIndex = i + 1;
                if (value instanceof Integer integer) {
                    statement.setInt(parameterIndex, integer.intValue());
                } else if (value instanceof String string) {
                    statement.setString(parameterIndex, string);
                } else if (value == null) {
                    statement.setObject(parameterIndex, null);
                } else {
                    statement.setObject(parameterIndex, value);
                }
            }
            return statement.executeUpdate();
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
