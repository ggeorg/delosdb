package io.github.ggeorg.delosdb.engine.extension.sql;

import io.github.ggeorg.delosdb.engine.extension.ExtensionResolutionException;
import io.github.ggeorg.delosdb.engine.extension.index.BuiltInIndexProviders;
import io.github.ggeorg.delosdb.engine.extension.index.IndexProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.types.DelosStorageProviderIds;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

import java.util.Locale;

/**
 * Central validation for the DelosDB SQL provider clauses.
 *
 * <p>Index providers resolve through the executable index-provider registry.
 * Table storage uses the catalog identities owned by the Derby heap and the
 * DelosDB MVCC access-method bridge.</p>
 */
@InternalApi
public final class SqlExtensionProviderValidation {
    private SqlExtensionProviderValidation() {
    }

    public static String normalizeIndexProviderName(String providerName) {
        return normalizeProviderName(providerName, BuiltInIndexProviders.defaultProviderName());
    }

    public static String normalizeStorageProviderName(String providerName) {
        return normalizeProviderName(providerName, TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME);
    }

    public static void requireIndexProvider(String providerName) throws StandardException {
        String normalizedName = normalizeIndexProviderName(providerName);
        try {
            IndexProviderResolver.builtIns().requireEnabled(normalizedName);
        } catch (ExtensionResolutionException e) {
            throw unsupportedProvider("CREATE INDEX", normalizedName);
        }
        if (!BuiltInIndexProviders.isSqlCreatable(normalizedName)) {
            throw unsupportedProvider("CREATE INDEX", normalizedName);
        }
    }

    public static void requireStorageProvider(String providerName) throws StandardException {
        String normalizedName = normalizeStorageProviderName(providerName);
        if (TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME.equals(normalizedName)
                || DelosStorageProviderIds.MVCC_PROVIDER_ID.equals(normalizedName)) {
            return;
        }
        throw unsupportedProvider("CREATE TABLE", normalizedName);
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
