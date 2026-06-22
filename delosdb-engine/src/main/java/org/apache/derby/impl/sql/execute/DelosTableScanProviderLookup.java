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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    /**
     * Test/proof gate for Phase F3.1.  Production execution keeps the provider
     * lookup branch inert until F3.2 replaces the observation with a real
     * Delos-backed result set branch.
     */
    public static final String FACTORY_PROBE_PROPERTY =
            "delosdb.storage.phaseF3.tableScanBranchProbe";

    /**
     * Test/proof gate for Phase F3.2.  When enabled, a non-default provider
     * table scan returns the DelosTableScanResultSet skeleton instead of the
     * normal Derby heap scan.  Production execution keeps this disabled until
     * F4 implements real MVCC row materialization.
     */
    public static final String FACTORY_SKELETON_BRANCH_PROPERTY =
            DelosTableScanResultSet.SKELETON_BRANCH_PROPERTY;

    public static final String FACTORY_SKELETON_REACHED_MESSAGE =
            DelosTableScanResultSet.SKELETON_REACHED_MESSAGE;

    private static final AtomicReference<Result> LAST_FACTORY_LOOKUP = new AtomicReference<>();
    private static final AtomicReference<Result> LAST_NON_DEFAULT_FACTORY_LOOKUP = new AtomicReference<>();
    private static final AtomicInteger FACTORY_LOOKUP_COUNT = new AtomicInteger();

    private DelosTableScanProviderLookup()
    {
    }


    /**
     * Phase F3.1 proof hook used from GenericResultSetFactory.  It deliberately
     * observes the exact future native table-scan branch point without changing
     * the returned Derby result set yet.
     */
    public static void observeFactoryLookupIfEnabled(Activation activation, String tableName)
            throws StandardException
    {
        if (!Boolean.getBoolean(FACTORY_PROBE_PROPERTY)) { return; }
        Optional<Result> resolved = find(activation, tableName);
        if (resolved.isEmpty()) { return; }

        Result result = resolved.get();
        FACTORY_LOOKUP_COUNT.incrementAndGet();
        LAST_FACTORY_LOOKUP.set(result);
        if (!result.isDefaultStorageProvider()) {
            LAST_NON_DEFAULT_FACTORY_LOOKUP.set(result);
        }
    }

    public static void resetFactoryLookupForTesting()
    {
        FACTORY_LOOKUP_COUNT.set(0);
        LAST_FACTORY_LOOKUP.set(null);
        LAST_NON_DEFAULT_FACTORY_LOOKUP.set(null);
    }

    public static int factoryLookupCountForTesting()
    {
        return FACTORY_LOOKUP_COUNT.get();
    }

    public static Optional<Result> lastFactoryLookupForTesting()
    {
        return Optional.ofNullable(LAST_FACTORY_LOOKUP.get());
    }

    public static Optional<Result> lastNonDefaultFactoryLookupForTesting()
    {
        return Optional.ofNullable(LAST_NON_DEFAULT_FACTORY_LOOKUP.get());
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
