package io.github.ggeorg.delosdb.storage.mvcc;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;

/**
 * Explicit opt-in adapter for opening the experimental MVCC storage provider.
 *
 * <p>This is deliberately not wired into Derby's default heap/store path. It is
 * the narrow Phase 6 boundary used by smoke tests and future SQL opt-in wiring:
 * callers must set {@value #STORAGE_PROVIDER_PROPERTY} to {@value #PROVIDER_MVCC}
 * and must supply a durable storage directory.</p>
 */
public final class DelosMvccStoreAdapter {
    public static final String STORAGE_PROVIDER_PROPERTY = "delosdb.storage.provider";
    public static final String STORAGE_DIRECTORY_PROPERTY = "delosdb.storage.mvcc.path";
    public static final String PROVIDER_MVCC = "mvcc";
    public static final String PROVIDER_DELOS_MVCC = DelosMvccStorageProvider.PROVIDER_NAME;

    private DelosMvccStoreAdapter() {
    }

    /** Returns whether the supplied properties explicitly request the MVCC provider. */
    public static boolean isEnabled(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return isMvccProvider(properties.getProperty(STORAGE_PROVIDER_PROPERTY));
    }

    /** Opens the page-backed MVCC provider only when the opt-in property is present. */
    public static VersionedStorageProvider open(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String providerName = properties.getProperty(STORAGE_PROVIDER_PROPERTY);
        if (!isMvccProvider(providerName)) {
            throw new IllegalStateException("Experimental delos_mvcc storage requires explicit "
                    + STORAGE_PROVIDER_PROPERTY + "=" + PROVIDER_MVCC + " opt-in");
        }
        String storagePath = properties.getProperty(STORAGE_DIRECTORY_PROPERTY);
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("Experimental delos_mvcc storage requires "
                    + STORAGE_DIRECTORY_PROPERTY);
        }
        return openPageBacked(Path.of(storagePath));
    }

    /** Opens the page-backed MVCC provider for an already-resolved opt-in storage directory. */
    public static VersionedStorageProvider openPageBacked(Path storageDirectory) {
        return DelosMvccStorageProvider.openPageBacked(Objects.requireNonNull(storageDirectory, "storageDirectory"));
    }

    private static boolean isMvccProvider(String providerName) {
        if (providerName == null) {
            return false;
        }
        String normalized = providerName.trim().toLowerCase(Locale.ROOT);
        return PROVIDER_MVCC.equals(normalized) || PROVIDER_DELOS_MVCC.equals(normalized);
    }
}
