package delosdb.smoke;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies SQL-visible TypeProvider metadata through SYSCS_UTIL.DELOSDB_TYPES().
 */
public final class TypeProviderSqlVisibilitySmoke {
    private TypeProviderSqlVisibilitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = Path.of(args[0]).toAbsolutePath().toString();
        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + databasePath + ";create=true");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("VALUES SYSCS_UTIL.DELOSDB_TYPES()")) {
            if (!rs.next()) {
                throw new IllegalStateException("DELOSDB_TYPES() returned no rows");
            }

            String summary = rs.getString(1);
            requireContains(summary, "derby INTEGER jdbc=INTEGER java=java.lang.Integer nullable=true comparable=true");
            requireContains(summary, "derby VARCHAR jdbc=VARCHAR java=java.lang.String nullable=true comparable=true");
            requireContains(summary, "derby DECIMAL jdbc=DECIMAL java=java.math.BigDecimal nullable=true comparable=true");
            requireContains(summary, "derby DATE jdbc=DATE java=java.sql.Date nullable=true comparable=true");
            requireContains(summary, "derby BLOB jdbc=BLOB java=java.sql.Blob nullable=true comparable=false");

            System.out.println(summary);
        }

        System.out.println("DelosDB TypeProvider SQL visibility smoke test passed.");
    }

    private static void requireContains(String summary, String expected) {
        if (summary == null || !summary.contains(expected)) {
            throw new IllegalStateException(
                    "Expected type summary to contain '" + expected + "' but was: " + summary);
        }
    }
}
