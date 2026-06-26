package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

/**
 * Retired compatibility sentinel for the old native MVCC table registry.
 *
 * <p><strong>MODULE10F bridge status:</strong> normal {@code delos_mvcc}
 * tables no longer register, open, commit, roll back, or execute through this
 * native side registry. Inherited Derby store/access now owns MVCC table state
 * and transaction outcomes through the {@code MvccConglomerate} path.</p>
 *
 * <p>The class remains only because older development smokes still call the
 * two testing hooks below while the historical smoke suite is being retired.
 * The hooks are intentionally inert: clearing does nothing, and the registry is
 * always empty.</p>
 */
@InternalApi
public final class DelosNativeTableRegistry {
    private DelosNativeTableRegistry() {
    }

    /** Test-only compatibility hook for older smokes; the retired registry is always empty. */
    public static void clearRegisteredTablesForTesting() {
        // No-op: the native registry no longer owns normal MVCC table state.
    }

    /** Test-only compatibility hook proving the retired native registry is not populated. */
    public static boolean hasRegisteredTableForTesting(String schemaName, String tableName) {
        return false;
    }
}
