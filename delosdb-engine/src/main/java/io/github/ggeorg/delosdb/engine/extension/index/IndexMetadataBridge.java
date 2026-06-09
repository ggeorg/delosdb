package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import org.apache.derby.catalog.IndexDescriptor;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Internal adapter from Derby index descriptors to DelosDB provider-neutral
 * index metadata.
 *
 * <p>This class is intentionally small and diagnostic-only. It gives the
 * DelosDB provider layer a stable metadata shape without exposing Derby access
 * methods, conglomerates, scan controllers, or optimizer classes.</p>
 */
@InternalApi
public final class IndexMetadataBridge {
    private IndexMetadataBridge() {
    }

    public static IndexMetadata from(String indexName, IndexDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        String normalizedIndexName = requireText(indexName, "indexName");
        return new IndexMetadata(
                descriptor.indexProviderName(),
                normalizedIndexName,
                Arrays.stream(descriptor.baseColumnPositions())
                        .mapToObj(position -> "baseColumn:" + position)
                        .toList(),
                Map.of(
                        "derbyIndexType", descriptor.indexType(),
                        "numberOfOrderedColumns", Integer.toString(descriptor.numberOfOrderedColumns())));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
