package delosdb.smoke;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Phase F1a proof: provider syntax is already owned by Derby's parser/prepare
 * path. Preparing CREATE TABLE ... USING delos_mvcc must not be a bridge route.
 */
public final class StoragePhaseF1aProviderSyntaxSmoke {
    private StoragePhaseF1aProviderSyntaxSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-f1a-provider-syntax-db", true)) {

            try (PreparedStatement ignored = connection.prepareStatement(
                    "CREATE TABLE F1A_PROVIDER_SYNTAX (id INT, value VARCHAR(32)) USING delos_mvcc")) {
                // Preparing is enough for this proof: Derby parser/binder/codegen accepted the provider clause.
            }


            requireFailsToPrepare(connection,
                    "CREATE TABLE F1A_BAD_PROVIDER_SYNTAX (id INT) USONG delos_mvcc",
                    "bad provider-clause keyword must be rejected by Derby parser");
        } finally {
            SmokeUtils.shutdown("storage-phase-f1a-provider-syntax-db");
        }

        System.out.println("storage_phase_f1a_provider_syntax: PASS");
    }

    private static void requireFailsToPrepare(Connection connection, String sql, String message) throws SQLException {
        try (PreparedStatement ignored = connection.prepareStatement(sql)) {
            throw new IllegalStateException(message);
        } catch (SQLException expected) {
            // Any Derby parse/bind failure is acceptable here.  The positive prepare above is the ownership proof.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
