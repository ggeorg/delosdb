package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.function.FunctionProviderResolver;
import io.github.ggeorg.delosdb.spi.function.FunctionDescriptor;

/**
 * Verifies FunctionProvider-backed function metadata is visible in a compact,
 * provider-neutral diagnostic string.
 */
public final class FunctionProviderVisibilitySmoke {
    private FunctionProviderVisibilitySmoke() {
    }

    public static void main(String[] args) {
        FunctionProviderResolver resolver = FunctionProviderResolver.builtIns();
        FunctionDescriptor descriptor = resolver.findFunction("app", "delos_version")
                .orElseThrow(() -> new AssertionError("Missing APP.DELOS_VERSION function metadata"));

        String summary = resolver.describe(descriptor);
        assertContains(summary, "provider=delos");
        assertContains(summary, "function=APP.DELOS_VERSION");
        assertContains(summary, "returnType=VARCHAR(32)");
        assertContains(summary, "parameters=[]");
        assertContains(summary, "scalar=true");
        assertContains(summary, "deterministic=true");
        assertContains(summary, "readsSqlData=false");
        assertContains(summary,
                "externalName=io.github.ggeorg.delosdb.engine.extension.function.DelosDbBuiltInFunctions.delosVersion");

        System.out.println(summary);
        System.out.println("DelosDB FunctionProvider visibility smoke test passed.");
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new AssertionError("Expected <" + expected + "> in <" + actual + ">");
        }
    }
}
