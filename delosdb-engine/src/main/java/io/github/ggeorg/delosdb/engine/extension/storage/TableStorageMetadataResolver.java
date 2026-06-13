package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.TableStorageMetadata;
import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.SchemaDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.shared.common.error.StandardException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

/**
 * Internal helper for StorageProvider v0 metadata proofs.
 *
 * <p>This deliberately reads Derby table descriptors and reports DelosDB's
 * provider-neutral table-storage metadata. StorageProvider v0 keeps all table
 * storage on the built-in heap provider; this helper makes that metadata seam
 * observable without changing the physical Derby storage implementation.</p>
 */
@InternalApi
public final class TableStorageMetadataResolver {
    private TableStorageMetadataResolver() {
    }

    public static TableStorageMetadata resolve(Connection connection, String schemaName, String tableName)
            throws SQLException, StandardException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tableName, "tableName");
        if (!(connection instanceof EmbedConnection embedConnection)) {
            throw new IllegalArgumentException(
                    "StorageProvider metadata resolution requires an embedded Derby connection");
        }

        LanguageConnectionContext lcc = embedConnection.getLanguageConnection();
        ContextManager contextManager = lcc.getContextManager();
        ContextService contextService = ContextService.getFactory();
        boolean contextSet = false;
        try {
            contextService.setCurrentContextManager(contextManager);
            contextSet = true;
            return resolveInLanguageContext(lcc, schemaName, tableName);
        } finally {
            if (contextSet) {
                contextService.resetCurrentContextManager(contextManager);
            }
        }
    }


    public static String describe(Connection connection, String schemaName, String tableName)
            throws SQLException, StandardException {
        TableStorageMetadata metadata = resolve(connection, schemaName, tableName);
        return metadata.schemaName()
                + "."
                + metadata.tableName()
                + " storageProvider="
                + metadata.providerName();
    }

    private static TableStorageMetadata resolveInLanguageContext(
            LanguageConnectionContext lcc, String schemaName, String tableName)
            throws StandardException {
        DataDictionary dataDictionary = lcc.getDataDictionary();
        TransactionController transactionController = lcc.getTransactionExecute();
        SchemaDescriptor schema = schemaName == null || schemaName.isBlank()
                ? lcc.getDefaultSchema()
                : dataDictionary.getSchemaDescriptor(normalizeIdentifier(schemaName), transactionController, true);
        TableDescriptor table = dataDictionary.getTableDescriptor(
                normalizeIdentifier(tableName), schema, transactionController);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: "
                    + schema.getSchemaName() + "." + tableName);
        }

        String providerName = table.getStorageProviderName();
        StorageProviderResolver.builtIns().requireEnabled(providerName);
        return TableStorageMetadata.of(providerName, schema.getSchemaName(), table.getName());
    }

    private static String normalizeIdentifier(String identifier) {
        return identifier.trim().toUpperCase(Locale.ROOT);
    }
}
