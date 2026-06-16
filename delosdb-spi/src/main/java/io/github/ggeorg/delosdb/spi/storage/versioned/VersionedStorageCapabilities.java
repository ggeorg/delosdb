package io.github.ggeorg.delosdb.spi.storage.versioned;

import java.util.Set;
import java.util.TreeSet;

/** Capability flags for a versioned storage provider. */
public record VersionedStorageCapabilities(Set<String> values) {
    public static final String SNAPSHOT_VISIBILITY = "snapshot-visibility";
    public static final String TABLE_SCAN = "table-scan";
    public static final String MANUAL_CLEANUP = "manual-cleanup";
    public static final String IN_MEMORY_PROTOTYPE = "in-memory-prototype";

    public VersionedStorageCapabilities {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("capabilities must not be empty");
        }
        values = Set.copyOf(new TreeSet<>(values));
    }

    public boolean supports(String capability) {
        return values.contains(capability);
    }
}
