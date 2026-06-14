package delosdb.smoke;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies the SQL-visible DelosDB extension registry utility surface.
 */
public final class ExtensionRegistrySqlVisibilitySmoke {
    private ExtensionRegistrySqlVisibilitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = Path.of(args[0]).toAbsolutePath().toString();
        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + databasePath + ";create=true");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("VALUES SYSCS_UTIL.DELOSDB_EXTENSIONS()")) {
            if (!rs.next()) {
                throw new IllegalStateException("DELOSDB_EXTENSIONS() returned no rows");
            }
            String summary = rs.getString(1);
            requireContains(summary, "index btree enabled");
            requireContains(summary, "storage heap enabled");
            requireContains(summary, "function delos enabled");
            requireContains(summary, "cost_model btree enabled");
            requireContains(summary, "type derby enabled");

            System.out.println(summary);
        }

        System.out.println("DelosDB extension registry SQL visibility smoke test passed.");
    }

    private static void requireContains(String summary, String expected) {
        if (summary == null || !summary.contains(expected)) {
            throw new IllegalStateException(
                    "Expected extension summary to contain '" + expected + "' but was: " + summary);
        }
    }
}
