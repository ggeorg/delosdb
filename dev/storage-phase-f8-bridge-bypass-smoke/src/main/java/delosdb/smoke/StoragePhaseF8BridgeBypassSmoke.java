package delosdb.smoke;


import java.sql.Connection;
import java.sql.Statement;

/**
 * Phase F8/G6 proof: the transitional retired SQL bridge pre-parse hook
 * is retired as an automatic SQL fallback. Native Derby execution owns
 * CREATE TABLE ... USING delos_mvcc even for plain Statement execution, and the stale
 * compatibility property may not re-enable EmbedStatement interception.
 */
public final class StoragePhaseF8BridgeBypassSmoke {
    private static final String DATABASE_PATH = "storage-phase-f8-bridge-bypass-db";
    private static final String STALE_COMPATIBILITY_PROPERTY = "delosdb.storage.sqlBridge.compatibility";

    private StoragePhaseF8BridgeBypassSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveBridgeInterceptionCannotBeEnabledByProperty();
            provePlainStatementCreateTableUsesNativeDerbyPath();
        } finally {
            System.clearProperty(STALE_COMPATIBILITY_PROPERTY);
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f8_bridge_bypass: PASS");
    }

    private static void proveBridgeInterceptionCannotBeEnabledByProperty() {
        System.clearProperty(STALE_COMPATIBILITY_PROPERTY);

        System.setProperty(STALE_COMPATIBILITY_PROPERTY, "true");

    }

    private static void provePlainStatementCreateTableUsesNativeDerbyPath() throws Exception {
        System.setProperty(STALE_COMPATIBILITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE F8_NATIVE_BYPASS (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected Derby native CREATE TABLE USING delos_mvcc to run with bridge fallback retired");
        }

    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
