package io.github.ggeorg.delosdb.engine.extension.sql;

import io.github.ggeorg.delosdb.engine.extension.ExtensionResolutionException;
import io.github.ggeorg.delosdb.engine.extension.index.BuiltInIndexProviders;
import io.github.ggeorg.delosdb.engine.extension.index.IndexProviderResolver;
import io.github.ggeorg.delosdb.engine.extension.storage.BuiltInStorageProviders;
import io.github.ggeorg.delosdb.engine.extension.storage.StorageProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

import java.util.Locale;

/**
 * Central validation for the small DelosDB SQL extension surface.
 *
 * <p>v0 deliberately supports only built-in providers. The parser accepts the
 * optional provider clauses, but binding must keep Derby behavior unchanged by
 * resolving omitted providers to the built-in defaults and rejecting unknown
 * names before any physical storage/index work is attempted.</p>
 */
@InternalApi
public final class SqlExtensionProviderValidation {
    private SqlExtensionProviderValidation() {
    }

    public static String normalizeIndexProviderName(String providerName) {
        return normalizeProviderName(providerName, BuiltInIndexProviders.defaultProviderName());
    }

    public static String normalizeStorageProviderName(String providerName) {
        return normalizeProviderName(providerName, BuiltInStorageProviders.defaultProviderName());
    }

    public static void requireIndexProvider(String providerName) throws StandardException {
        try {
            IndexProviderResolver.builtIns().requireEnabled(normalizeIndexProviderName(providerName));
        } catch (ExtensionResolutionException e) {
            throw unsupportedProvider("CREATE INDEX", normalizeIndexProviderName(providerName));
        }
    }

    public static void requireStorageProvider(String providerName) throws StandardException {
        try {
            StorageProviderResolver.builtIns().requireEnabled(normalizeStorageProviderName(providerName));
        } catch (ExtensionResolutionException e) {
            throw unsupportedProvider("CREATE TABLE", normalizeStorageProviderName(providerName));
        }
    }

    private static String normalizeProviderName(String providerName, String defaultProviderName) {
        if (providerName == null) {
            return defaultProviderName;
        }
        return providerName.toLowerCase(Locale.ROOT);
    }

    private static StandardException unsupportedProvider(String statementName, String providerName) {
        return StandardException.newException(
                SQLState.NOT_IMPLEMENTED,
                statementName + " USING " + providerName);
    }
}
