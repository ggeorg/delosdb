package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexCapabilities;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;
import org.apache.derby.catalog.IndexDescriptor;

import java.util.Objects;

/**
 * Internal bridge from Derby index descriptors to DelosDB index-provider
 * capability reporting.
 *
 * <p>This class is still diagnostic/preparatory only. It resolves the provider
 * identity stored in the descriptor, builds provider-neutral {@link IndexMetadata},
 * and asks the matching internal {@link IndexProvider} for capabilities. It does
 * not influence optimizer selection, costing, storage, catalog lifecycle, or
 * query execution.</p>
 */
@InternalApi
public final class IndexProviderCapabilityBridge {
    private IndexProviderCapabilityBridge() {
    }

    /**
     * Reports capabilities using the built-in provider registry.
     */
    public static IndexCapabilities builtInCapabilitiesFor(String indexName, IndexDescriptor descriptor) {
        return capabilitiesFor(
                IndexProviderResolver.builtIns(BuiltInExtensions.newRegistryWithBuiltIns()),
                indexName,
                descriptor);
    }

    /**
     * Reports capabilities through the supplied resolver.
     */
    public static IndexCapabilities capabilitiesFor(
            IndexProviderResolver resolver,
            String indexName,
            IndexDescriptor descriptor) {
        Objects.requireNonNull(resolver, "resolver");
        IndexMetadata metadata = IndexMetadataBridge.from(indexName, descriptor);
        IndexProvider provider = resolver.requireEnabled(metadata.providerName());
        return provider.capabilities(metadata);
    }
}
