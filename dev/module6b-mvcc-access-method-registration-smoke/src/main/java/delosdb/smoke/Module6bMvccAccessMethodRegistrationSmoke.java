package delosdb.smoke;

import org.apache.derby.iapi.store.access.AccessFactory;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.access.conglomerate.MethodFactory;
import org.apache.derby.impl.jdbc.EmbedConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * MODULE6B smoke: inherited Derby access-method registration preflight.
 *
 * <p>This smoke proves that Derby's existing {@link AccessFactory} can discover
 * and register a {@code delos_mvcc} access method by implementation id. Later
 * milestones may switch delos_mvcc physical table creation to this factory
 * without changing this registration proof. This smoke still does not route
 * normal SQL SELECT/INSERT/DELETE/UPDATE through the inherited MVCC store path.</p>
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
            assertRuntimeClassVisibility();
            assertRuntimeAccessMethodRegistration();
            assertCreateTableRouteUsesProviderSwitch();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourceRegistrationFacts() throws Exception {
        String factory = Files.readString(Path.of(
                "delosdb-storage-bridge/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerateFactory.java"));
        requireContains(factory,
                "public static final String IMPLEMENTATION_ID = \"delos_mvcc\"",
                "MVCC access method must use the delos_mvcc implementation id");
        requireContains(factory,
                "return ConglomerateFactory.MVCC_FACTORY_ID",
                "MVCC access method must use the explicit MVCC factory id");
        requireContains(factory,
                "new MvccConglomerate",
                "MODULE6C may now provide the first create/read conglomerate skeleton while keeping registration intact");

        String api = Files.readString(Path.of(
                "delosdb-derby-store-api/src/main/java/org/apache/derby/iapi/store/access/conglomerate/ConglomerateFactory.java"));
        requireContains(api,
                "MVCC_FACTORY_ID     = 0x02",
                "ConglomerateFactory must reserve an explicit MVCC factory id");

        String modules = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/modules.properties"));
        requireNotContains(modules,
                "derby.module.access.delos_mvcc=org.apache.derby.impl.store.access.mvcc.MvccConglomerateFactory",
                "delos_mvcc must no longer use direct modules.properties registration");
        requireNotContains(modules,
                "cloudscape.config.access.delos_mvcc=all",
                "delos_mvcc must no longer require direct modules.properties configuration");

        String serviceDescriptor = Files.readString(Path.of(
                "delosdb-storage-bridge/src/main/resources/META-INF/services/"
                        + "org.apache.derby.iapi.store.access.conglomerate.ExternalAccessMethodProvider"));
        requireContains(serviceDescriptor,
                "org.apache.derby.impl.store.access.mvcc.DerbyMvccAccessMethodProvider",
                "bridge must expose delos_mvcc through the neutral ExternalAccessMethodProvider service descriptor");

        String serviceProvider = Files.readString(Path.of(
                "delosdb-storage-bridge/src/main/java/org/apache/derby/impl/store/access/mvcc/"
                        + "DerbyMvccAccessMethodProvider.java"));
        requireContains(serviceProvider,
                "implements ExternalAccessMethodProvider",
                "MVCC access method must be registered through the neutral external provider contract");
        requireContains(serviceProvider,
                "supportsImplementation(String implementationId)",
                "MVCC service provider must advertise implementation-id support");
        requireContains(serviceProvider,
                "supportsFactoryId(int factoryId)",
                "MVCC service provider must advertise factory-id support");
        requireContains(serviceProvider,
                "new MvccConglomerateFactory()",
                "service provider must still boot the Derby ConglomerateFactory adapter");

        String ramAccessManager = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMAccessManager.java"));
        requireContains(ramAccessManager,
                "bootExternalAccessMethod(impltype, conglomProperties)",
                "RAMAccessManager must fall back to the external access-method service hook");
        requireContains(ramAccessManager,
                "ServiceLoader.load(ExternalAccessMethodProvider.class)",
                "RAMAccessManager must discover external access-method providers through ServiceLoader");
    }

    private static void assertRuntimeClassVisibility() throws Exception {
        Class<?> factoryClass = Class.forName(
                "org.apache.derby.impl.store.access.mvcc.MvccConglomerateFactory");
        require(ConglomerateFactory.class.isAssignableFrom(factoryClass),
                "delos_mvcc access method class must be visible through the normal runtime classpath "
                        + "and assignable to ConglomerateFactory");
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

    private static void assertCreateTableRouteUsesProviderSwitch() throws Exception {
        String createTable = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/CreateTableConstantAction.java"));
        requireContains(createTable,
                "physicalConglomerateImplementation()",
                "CREATE TABLE must now use the MODULE6E provider-aware physical conglomerate selector");
        requireContains(createTable,
                "return \"delos_mvcc\"",
                "delos_mvcc tables must request the registered MVCC access method after MODULE6E");
        requireContains(createTable,
                "return \"heap\"",
                "heap tables must continue to request heap physical conglomerates after MODULE6E");
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

    private static void requireNotContains(String source, String forbidden, String label) {
        if (source != null && source.contains(forbidden)) {
            throw new AssertionError(label + " expected source not to contain: " + forbidden);
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
