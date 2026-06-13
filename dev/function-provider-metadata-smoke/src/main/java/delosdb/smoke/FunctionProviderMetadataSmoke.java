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
 * Verifies the metadata-only FunctionProvider v0 seam.
 */
public final class FunctionProviderMetadataSmoke {
    private FunctionProviderMetadataSmoke() {
    }

    public static void main(String[] args) {
        FunctionProviderRegistry registry = FunctionProviderRegistry.builtIns();
        FunctionProviderResolver resolver = registry.resolver();

        FunctionProvider provider = resolver.requireDefault();
        assertEquals(BuiltInFunctionProviders.defaultProviderName(), provider.name(), "default provider name");

        FunctionDescriptor delosVersion = resolver.findFunction("sys", "delos_version")
                .orElseThrow(() -> new AssertionError("Missing built-in SYS.DELOS_VERSION function metadata"));
        assertEquals("builtin", delosVersion.providerName(), "function provider");
        assertEquals("SYS.DELOS_VERSION", delosVersion.qualifiedName(), "qualified function name");
        assertEquals("VARCHAR(32)", delosVersion.returnType(), "return type");
        assertEquals(List.of(), delosVersion.parameterTypes(), "parameter types");

        assertTrue(provider.capabilities(delosVersion).scalar(), "function should be scalar");
        assertTrue(provider.capabilities(delosVersion).deterministic(), "function should be deterministic");
        assertTrue(!provider.capabilities(delosVersion).readsSqlData(), "function should not read SQL data");

        ExtensionRegistry descriptors = registry.descriptors();
        ExtensionDescriptor descriptor = descriptors.find(ExtensionType.FUNCTION, "builtin")
                .orElseThrow(() -> new AssertionError("Missing builtin FunctionProvider descriptor"));
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
