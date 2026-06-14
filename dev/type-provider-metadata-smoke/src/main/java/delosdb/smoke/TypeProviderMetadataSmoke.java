package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.type.TypeProviderRegistry;
import io.github.ggeorg.delosdb.engine.extension.type.TypeProviderResolver;
import io.github.ggeorg.delosdb.spi.type.TypeDescriptor;
import io.github.ggeorg.delosdb.spi.type.TypeProvider;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies TypeProvider v0 metadata for Derby's built-in SQL type catalog.
 */
public final class TypeProviderMetadataSmoke {
    private TypeProviderMetadataSmoke() {
    }

    public static void main(String[] args) {
        TypeProviderRegistry registry = TypeProviderRegistry.builtIns();
        TypeProviderResolver resolver = registry.resolver();
        TypeProvider derby = resolver.requireEnabled("derby");

        requireEquals("derby", derby.name(), "provider name");
        requireTrue(derby.capabilities().builtInCatalogTypes(), "built-in catalog capability");
        requireTrue(derby.capabilities().scalarTypes(), "scalar type capability");
        requireTrue(derby.capabilities().jdbcMetadata(), "JDBC metadata capability");

        Set<String> typeNames = derby.types().stream()
                .map(TypeDescriptor::typeName)
                .collect(Collectors.toUnmodifiableSet());
        requireContains(typeNames, "INTEGER");
        requireContains(typeNames, "VARCHAR");
        requireContains(typeNames, "DECIMAL");
        requireContains(typeNames, "DATE");
        requireContains(typeNames, "TIMESTAMP");
        requireContains(typeNames, "BLOB");

        ExtensionDescriptor descriptor = registry.descriptors().find(ExtensionType.TYPE, "derby")
                .orElseThrow(() -> new AssertionError("Missing TypeProvider descriptor"));
        requireEquals(ExtensionState.ENABLED, descriptor.state(), "descriptor state");
        requireContains(descriptor.capabilities(), "default-type-provider");
        requireContains(descriptor.capabilities(), "type-metadata");
        requireContains(descriptor.capabilities(), "derby-built-in-types");
        requireContains(descriptor.capabilities(), "jdbc-metadata");

        ExtensionDescriptor builtinDescriptor = BuiltInExtensions.derbyTypeProvider();
        requireEquals(ExtensionType.TYPE, builtinDescriptor.type(), "built-in descriptor type");
        requireEquals("derby", builtinDescriptor.name(), "built-in descriptor name");

        System.out.println("DelosDB TypeProvider metadata smoke test passed.");
    }

    private static void requireContains(Set<String> values, String expected) {
        if (!values.contains(expected)) {
            throw new AssertionError("Expected set to contain " + expected + " but was " + values);
        }
    }

    private static void requireContains(java.util.List<String> values, String expected) {
        if (!values.contains(expected)) {
            throw new AssertionError("Expected list to contain " + expected + " but was " + values);
        }
    }

    private static void requireEquals(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void requireTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Expected true: " + label);
        }
    }
}
