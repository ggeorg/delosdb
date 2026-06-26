package delosdb.smoke;

import org.apache.derby.iapi.store.access.AccessFactory;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.access.conglomerate.MethodFactory;
import org.apache.derby.impl.jdbc.EmbedConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.jar.JarFile;

/**
 * MODULE6B smoke: inherited Derby access-method registration preflight.
 *
 * <p>This smoke proves that Derby's existing {@link AccessFactory} can discover
 * and register a {@code delos_mvcc} access method by implementation id. It does
 * not change CREATE TABLE routing, open an MVCC conglomerate, or route SQL
 * execution through the new factory. MODULE6C owns the first MVCC conglomerate
 * skeleton.</p>
 */
public final class Module6bMvccAccessMethodRegistrationSmoke {
    private static final String DATABASE_PATH = "build/module6b-mvcc-access-method-registration-db";

    private Module6bMvccAccessMethodRegistrationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        clearNativeRouteProperties();

        try {
            assertSourceRegistrationFacts();
            assertRuntimePackagingFacts();
            assertRuntimeAccessMethodRegistration();
            assertCreateTableRouteStillUnchanged();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourceRegistrationFacts() throws Exception {
        String factory = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerateFactory.java"));
        requireContains(factory,
                "public static final String IMPLEMENTATION_ID = \"delos_mvcc\"",
                "MVCC access method must use the delos_mvcc implementation id");
        requireContains(factory,
                "return ConglomerateFactory.MVCC_FACTORY_ID",
                "MVCC access method must use the explicit MVCC factory id");
        requireContains(factory,
                "STORE_FEATURE_NOT_IMPLEMENTED",
                "MODULE6B factory must not implement create/read conglomerate yet");

        String api = Files.readString(Path.of(
                "delosdb-derby-store-api/src/main/java/org/apache/derby/iapi/store/access/conglomerate/ConglomerateFactory.java"));
        requireContains(api,
                "MVCC_FACTORY_ID     = 0x02",
                "ConglomerateFactory must reserve an explicit MVCC factory id");

        String modules = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/modules.properties"));
        requireContains(modules,
                "derby.module.access.delos_mvcc=org.apache.derby.impl.store.access.mvcc.MvccConglomerateFactory",
                "Derby module registry must expose the delos_mvcc access method");
    }

    private static void assertRuntimePackagingFacts() throws Exception {
        try (JarFile derbyJar = new JarFile("build/libs/derby.jar")) {
            require(derbyJar.getEntry(
                    "org/apache/derby/impl/store/access/mvcc/MvccConglomerateFactory.class") != null,
                    "derby.jar must include the freshly compiled delos_mvcc access method class");
            require(derbyJar.getEntry(
                    "org/apache/derby/iapi/store/access/conglomerate/ConglomerateFactory.class") != null,
                    "derby.jar must include the patched ConglomerateFactory API");
        }
    }

    private static void assertRuntimeAccessMethodRegistration() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE MODULE6B_HEAP(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6B_HEAP_NAME_IDX ON MODULE6B_HEAP(name) USING btree");
            statement.executeUpdate("INSERT INTO MODULE6B_HEAP VALUES (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "SELECT name FROM MODULE6B_HEAP WHERE id = 1"),
                    "heap and btree must remain green while registering delos_mvcc access method");

            AccessFactory accessFactory = accessFactory(connection);
            MethodFactory heap = accessFactory.findMethodFactoryByImpl("heap");
            MethodFactory btree = accessFactory.findMethodFactoryByImpl("BTREE");
            MethodFactory mvcc = accessFactory.findMethodFactoryByImpl("delos_mvcc");

            require(heap instanceof ConglomerateFactory, "heap access method must still be a ConglomerateFactory");
            require(btree instanceof ConglomerateFactory, "btree access method must still be a ConglomerateFactory");
            require(mvcc != null, "delos_mvcc access method must be discoverable by implementation id");
            require(mvcc instanceof ConglomerateFactory,
                    "delos_mvcc access method must register as a ConglomerateFactory, got "
                            + mvcc.getClass().getName());

            ConglomerateFactory mvccFactory = (ConglomerateFactory) mvcc;
            SmokeUtils.assertEquals("delos_mvcc", mvcc.primaryImplementationType(),
                    "delos_mvcc primary implementation id must be discoverable through RAMAccessManager");
            SmokeUtils.assertEquals(ConglomerateFactory.MVCC_FACTORY_ID, mvccFactory.getConglomerateFactoryId(),
                    "delos_mvcc access method must return the reserved MVCC factory id");
            require(mvcc.supportsImplementation("delos_mvcc"),
                    "delos_mvcc access method must support the delos_mvcc implementation id");
            require(!mvcc.supportsImplementation("heap"),
                    "delos_mvcc access method must not claim heap implementation support");
        }
    }

    private static void assertCreateTableRouteStillUnchanged() throws Exception {
        String createTable = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/CreateTableConstantAction.java"));
        requireContains(createTable,
                "\"heap\", // we're requesting a heap conglomerate",
                "MODULE6B must not switch CREATE TABLE physical conglomerate creation yet");
    }

    private static AccessFactory accessFactory(Connection connection) throws Exception {
        EmbedConnection embed = connection.unwrap(EmbedConnection.class);
        return embed.getLanguageConnection().getTransactionExecute().getAccessManager();
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            if (Boolean.getBoolean(propertyName)) {
                throw new AssertionError("MODULE6B must not rely on old native proof property: " + propertyName);
            }
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            System.clearProperty(propertyName);
        }
    }

    private static void requireContains(String source, String expected, String label) {
        if (source == null || !source.contains(expected)) {
            throw new AssertionError(label + " expected source to contain: " + expected);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static final class NativeRouteProperties {
        private static final String[] NAMES = new String[] {
                "delosdb.storage.phaseF3.tableScanBranchProbe",
                "delosdb.storage.phaseF5.nativeMvccInsert",
                "delosdb.storage.phaseG3.nativeSelectAll",
                "delosdb.storage.phaseF4.nativeMvccSelectEquality",
                "delosdb.storage.phaseG1.nativeRangePredicates",
                "delosdb.storage.phaseG2.nativeBetweenPredicates",
                "delosdb.storage.phaseL31.nativeNullPredicates",
                "delosdb.storage.phaseL33.nativeOrPredicateResidual",
                "delosdb.storage.phaseL34.nativeProjectionVariants",
                "delosdb.storage.phaseL35.nativeOrderByResidual",
                "delosdb.storage.phaseG4.nativeCountAggregate",
                "delosdb.storage.phaseF6.nativeMvccDeleteEquality",
                "delosdb.storage.phaseF7.nativeMvccUpdateEquality"
        };
    }
}
