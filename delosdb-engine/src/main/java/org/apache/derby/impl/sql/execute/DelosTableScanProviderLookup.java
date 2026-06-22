/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.sql.execute;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.SchemaDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.util.IdUtil;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Provider lookup seam for the Phase F native table-scan path.
 *
 * <p>The generated activation currently calls
 * {@link GenericResultSetFactory#getTableScanResultSet} with the existing Derby
 * argument shape, including {@code tableName}. Phase F deliberately keeps that
 * bytecode shape intact. This helper proves the factory-side branch can resolve
 * the table descriptor and its persisted storage-provider name from the
 * activation/language context without involving the transitional SQL bridge.</p>
 */
public final class DelosTableScanProviderLookup
{
    private DelosTableScanProviderLookup()
    {
    }

    public static Optional<Result> find(Activation activation, String tableName)
            throws StandardException
    {
        if (activation == null) { return Optional.empty(); }
        return find(activation.getLanguageConnectionContext(), tableName);
    }

    public static Optional<Result> find(LanguageConnectionContext lcc, String tableName)
            throws StandardException
    {
        if (lcc == null || tableName == null || tableName.trim().isEmpty()) {
            return Optional.empty();
        }

        ParsedName parsedName = parseTableName(lcc, tableName);
        DataDictionary dataDictionary = lcc.getDataDictionary();
        TransactionController transactionController = lcc.getTransactionExecute();
        SchemaDescriptor schema = dataDictionary.getSchemaDescriptor(
                parsedName.schemaName(), transactionController, false);
        if (schema == null) { return Optional.empty(); }

        TableDescriptor table = dataDictionary.getTableDescriptor(
                parsedName.tableName(), schema, transactionController);
        if (table == null) { return Optional.empty(); }

        return Optional.of(new Result(
                schema.getSchemaName(),
                table.getName(),
                table.getStorageProviderName()));
    }

    private static ParsedName parseTableName(LanguageConnectionContext lcc, String tableName)
            throws StandardException
    {
        String[] parts = IdUtil.parseMultiPartSQLIdentifier(tableName.trim());
        if (parts.length == 1) {
            SchemaDescriptor defaultSchema = lcc.getDefaultSchema();
            return new ParsedName(defaultSchema.getSchemaName(), parts[0]);
        }
        if (parts.length == 2) {
            return new ParsedName(parts[0], parts[1]);
        }
        throw StandardException.plainWrapException(new IllegalArgumentException(
                "Expected one- or two-part table name for Delos table-scan provider lookup: "
                        + tableName));
    }

    private record ParsedName(String schemaName, String tableName)
    {
        private ParsedName
        {
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(tableName, "tableName");
        }
    }

    private static String normalizeStorageProviderName(String providerName)
    {
        if (providerName == null) { return TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME; }
        String normalizedName = providerName.trim().toLowerCase(Locale.ROOT);
        return normalizedName.isEmpty() ? TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME : normalizedName;
    }

    public record Result(String schemaName, String tableName, String storageProviderName)
    {
        public Result
        {
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(tableName, "tableName");
            storageProviderName = normalizeStorageProviderName(storageProviderName);
        }

        public boolean isDefaultStorageProvider()
        {
            return TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME.equals(storageProviderName);
        }

        public boolean isProvider(String providerName)
        {
            return storageProviderName.equals(normalizeStorageProviderName(providerName));
        }
    }
}
