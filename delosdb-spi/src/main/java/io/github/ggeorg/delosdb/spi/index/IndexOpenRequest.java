package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral request to open or create physical index access.
 */
@ExperimentalSpi("Initial physical index open request; transaction and storage handles remain bridge-owned.")
public record IndexOpenRequest(
        IndexMetadata metadata,
        IndexOpenMode mode,
        boolean readOnly,
        Map<String, String> properties
) {
    public IndexOpenRequest {
        metadata = Objects.requireNonNull(metadata, "metadata");
        mode = Objects.requireNonNull(mode, "mode");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    public static IndexOpenRequest openExisting(IndexMetadata metadata) {
        return new IndexOpenRequest(metadata, IndexOpenMode.OPEN_EXISTING, false, Map.of());
    }

    public static IndexOpenRequest readOnly(IndexMetadata metadata) {
        return new IndexOpenRequest(metadata, IndexOpenMode.OPEN_EXISTING, true, Map.of());
    }

    public static IndexOpenRequest create(IndexMetadata metadata) {
        return new IndexOpenRequest(metadata, IndexOpenMode.CREATE, false, Map.of());
    }

    public static IndexOpenRequest rebuild(IndexMetadata metadata) {
        return new IndexOpenRequest(metadata, IndexOpenMode.REBUILD, false, Map.of());
    }
}
