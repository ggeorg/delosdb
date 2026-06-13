package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.function.DelosDbBuiltInFunctions;
import io.github.ggeorg.delosdb.engine.extension.function.FunctionProviderResolver;
import io.github.ggeorg.delosdb.spi.function.FunctionDescriptor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies the built-in FunctionProvider metadata can drive execution of a SQL
 * function without introducing external providers or CREATE FUNCTION extensions.
 */
public final class FunctionProviderExecutionSmoke {
    private FunctionProviderExecutionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");

        FunctionDescriptor descriptor = FunctionProviderResolver.builtIns()
                .findFunction("app", "delos_version")
                .orElseThrow(() -> new AssertionError("Missing APP.DELOS_VERSION descriptor"));
        if (!descriptor.hasExternalName()) {
            throw new AssertionError("APP.DELOS_VERSION descriptor does not expose an external routine name");
        }

        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + databasePath + ";create=true");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(createFunctionSql(descriptor));

            try (ResultSet resultSet = statement.executeQuery("values " + descriptor.qualifiedName() + "()")) {
                if (!resultSet.next()) {
                    throw new AssertionError("APP.DELOS_VERSION returned no row");
                }
                String actual = resultSet.getString(1);
                if (!DelosDbBuiltInFunctions.VERSION.equals(actual)) {
                    throw new AssertionError("Expected " + DelosDbBuiltInFunctions.VERSION + " but was " + actual);
                }
                System.out.println(descriptor.qualifiedName() + "()=" + actual + " provider=" + descriptor.providerName());
            }
        } finally {
            shutdown(databasePath);
        }

        System.out.println("DelosDB FunctionProvider execution smoke test passed.");
    }

    private static String createFunctionSql(FunctionDescriptor descriptor) {
        return "create function " + descriptor.qualifiedName() + "() "
                + "returns " + descriptor.returnType() + " "
                + "language java parameter style java no sql deterministic "
                + "external name '" + descriptor.externalName() + "'";
    }

    private static void shutdown(String databasePath) throws SQLException {
        try {
            DriverManager.getConnection("jdbc:derby:" + databasePath + ";shutdown=true").close();
        } catch (SQLException expected) {
            if ("08006".equals(expected.getSQLState())) {
                return;
            }

            String message = expected.getMessage();
            if (message != null && message.contains("No suitable driver")) {
                return;
            }

            throw expected;
        }
    }
}
