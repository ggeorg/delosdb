package io.github.ggeorg.delosdb.engine.extension.storage.versioned;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.Locale;
import java.util.Set;

/**
 * Names reserved for experimental versioned-storage providers that DelosDB can
 * recognize at SQL bind time before an execution bridge exists.
 *
 * <p>This class is intentionally name-only. The Derby-compatible engine must
 * not depend on the experimental MVCC implementation module just to reject
 * {@code CREATE TABLE ... USING delos_mvcc} safely.</p>
 */
@InternalApi
public final class KnownVersionedStorageProviders {
    public static final String DELOS_MVCC = "delos_mvcc";

    private static final Set<String> KNOWN_PROVIDER_NAMES = Set.of(DELOS_MVCC);

    private KnownVersionedStorageProviders() {
    }

    public static boolean isKnownVersionedProvider(String providerName) {
        if (providerName == null) {
            return false;
        }
        return KNOWN_PROVIDER_NAMES.contains(providerName.toLowerCase(Locale.ROOT));
    }
}
