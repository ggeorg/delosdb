package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.function.BuiltInFunctionProviders;
import io.github.ggeorg.delosdb.engine.extension.function.FunctionProviderRegistry;
import io.github.ggeorg.delosdb.engine.extension.function.FunctionProviderResolver;
import io.github.ggeorg.delosdb.spi.function.FunctionDescriptor;
import io.github.ggeorg.delosdb.spi.function.FunctionProvider;

import java.util.List;

/**
 * Verifies the metadata FunctionProvider v0 seam.
 */
public final class FunctionProviderMetadataSmoke {
    private FunctionProviderMetadataSmoke() {
    }

    public static void main(String[] args) {
        FunctionProviderRegistry registry = FunctionProviderRegistry.builtIns();
        FunctionProviderResolver resolver = registry.resolver();

        FunctionProvider provider = resolver.requireDefault();
        assertEquals(BuiltInFunctionProviders.defaultProviderName(), provider.name(), "default provider name");

        FunctionDescriptor delosVersion = resolver.findFunction("app", "delos_version")
                .orElseThrow(() -> new AssertionError("Missing built-in APP.DELOS_VERSION function metadata"));
        assertEquals(BuiltInFunctionProviders.defaultProviderName(), delosVersion.providerName(), "function provider");
        assertEquals("APP.DELOS_VERSION", delosVersion.qualifiedName(), "qualified function name");
        assertEquals("VARCHAR(32)", delosVersion.returnType(), "return type");
        assertEquals(List.of(), delosVersion.parameterTypes(), "parameter types");
        assertTrue(delosVersion.hasExternalName(), "function should expose its Java routine external name");
        assertEquals(
                "io.github.ggeorg.delosdb.engine.extension.function.DelosDbBuiltInFunctions.delosVersion",
                delosVersion.externalName(),
                "function external name");

        assertTrue(provider.capabilities().scalar(), "function should be scalar");
        assertTrue(provider.capabilities().deterministic(), "function should be deterministic");
        assertTrue(!provider.capabilities().readsSqlData(), "function should not read SQL data");

        ExtensionRegistry descriptors = registry.descriptors();
        ExtensionDescriptor descriptor = descriptors.find(ExtensionType.FUNCTION, BuiltInFunctionProviders.defaultProviderName())
                .orElseThrow(() -> new AssertionError("Missing built-in FunctionProvider descriptor"));
        assertEquals(ExtensionState.ENABLED, descriptor.state(), "descriptor state");
        assertEquals(BuiltInExtensions.BUILTIN_VERSION, descriptor.version(), "descriptor version");
        assertContains(descriptor.capabilities(), "default-function-provider");
        assertContains(descriptor.capabilities(), "function-metadata");
        assertContains(descriptor.capabilities(), "scalar-function");
        assertContains(descriptor.capabilities(), "deterministic");
        assertContains(descriptor.capabilities(), "no-sql-data");

        System.out.println("DelosDB FunctionProvider metadata smoke test passed.");
    }

    private static void assertContains(List<String> actual, String expected) {
        if (!actual.contains(expected)) {
            throw new AssertionError("Expected capability " + expected + " in " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
