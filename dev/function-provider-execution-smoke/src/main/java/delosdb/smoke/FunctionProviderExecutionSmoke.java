package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.function.FunctionProviderResolver;
import io.github.ggeorg.delosdb.spi.function.FunctionDescriptor;

import java.sql.Connection;
import java.sql.ResultSet;
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
        SmokeUtils.loadEmbeddedDriver();

        FunctionDescriptor descriptor = FunctionProviderResolver.builtIns()
                .findFunction("app", "delos_version")
                .orElseThrow(() -> new AssertionError("Missing APP.DELOS_VERSION descriptor"));
        if (!descriptor.hasExternalName()) {
            throw new AssertionError("APP.DELOS_VERSION descriptor does not expose an external routine name");
        }

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(createFunctionSql(descriptor));

            try (ResultSet resultSet = statement.executeQuery("values " + descriptor.qualifiedName() + "()")) {
                if (!resultSet.next()) {
                    throw new AssertionError("APP.DELOS_VERSION returned no row");
                }
                String actual = resultSet.getString(1);
                if (actual == null || actual.isBlank() || "DelosDB".equals(actual)) {
                    throw new AssertionError("Expected real DelosDB version string but was " + actual);
                }
                System.out.println(descriptor.qualifiedName() + "()=" + actual + " provider=" + descriptor.providerName());
            }
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB FunctionProvider execution smoke test passed.");
    }

    private static String createFunctionSql(FunctionDescriptor descriptor) {
        return "create function " + descriptor.qualifiedName() + "() "
                + "returns " + descriptor.returnType() + " "
                + "language java parameter style java no sql deterministic "
                + "external name '" + descriptor.externalName() + "'";
    }
}
