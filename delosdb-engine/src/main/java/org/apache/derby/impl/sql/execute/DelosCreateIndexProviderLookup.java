/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosCreateIndexProviderLookup

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.sql.dictionary.TableDescriptor;

/**
 * Provider lookup proof seam for Phase G5 native CREATE INDEX.
 *
 * <p>The production CREATE INDEX route remains Derby-owned: JavaCC parses the
 * statement, binding resolves the target table, and {@link CreateIndexConstantAction}
 * executes from a {@link TableDescriptor}.  This helper records, under an
 * explicit proof property, the storage-provider identity read from that table
 * descriptor.  It does not parse SQL text and it does not call the transitional
 * retired SQL bridge/router.</p>
 */
public final class DelosCreateIndexProviderLookup
{
    /** Test/proof gate for Phase G5 native CREATE INDEX provider metadata resolution. */
    public static final String NATIVE_CREATE_INDEX_PROPERTY =
            "delosdb.storage.phaseG5.nativeCreateIndex";

    private static final String DEFAULT_INDEX_PROVIDER_NAME = "btree";

    private static final AtomicReference<Result> LAST_CREATE_INDEX_LOOKUP = new AtomicReference<>();
    private static final AtomicReference<Result> LAST_NON_DEFAULT_CREATE_INDEX_LOOKUP = new AtomicReference<>();
    private static final AtomicInteger CREATE_INDEX_LOOKUP_COUNT = new AtomicInteger();

    private DelosCreateIndexProviderLookup()
    {
    }

    static void observeIfEnabled(
            TableDescriptor tableDescriptor,
            String indexName,
            String[] columnNames,
            int[] baseColumnPositions,
            String indexProviderName)
    {
        if (!Boolean.getBoolean(NATIVE_CREATE_INDEX_PROPERTY)) {
            return;
        }
        Objects.requireNonNull(tableDescriptor, "tableDescriptor");
        Result result = new Result(
                tableDescriptor.getSchemaName(),
                tableDescriptor.getName(),
                tableDescriptor.getStorageProviderName(),
                indexName,
                columnNames(columnNames),
                columnPositions(baseColumnPositions),
                normalizeIndexProviderName(indexProviderName));
        CREATE_INDEX_LOOKUP_COUNT.incrementAndGet();
        LAST_CREATE_INDEX_LOOKUP.set(result);
        if (!result.isDefaultStorageProvider()) {
            LAST_NON_DEFAULT_CREATE_INDEX_LOOKUP.set(result);
        }
    }

    public static void resetForTesting()
    {
        CREATE_INDEX_LOOKUP_COUNT.set(0);
        LAST_CREATE_INDEX_LOOKUP.set(null);
        LAST_NON_DEFAULT_CREATE_INDEX_LOOKUP.set(null);
    }

    public static int lookupCountForTesting()
    {
        return CREATE_INDEX_LOOKUP_COUNT.get();
    }

    public static Optional<Result> lastLookupForTesting()
    {
        return Optional.ofNullable(LAST_CREATE_INDEX_LOOKUP.get());
    }

    public static Optional<Result> lastNonDefaultLookupForTesting()
    {
        return Optional.ofNullable(LAST_NON_DEFAULT_CREATE_INDEX_LOOKUP.get());
    }

    private static List<String> columnNames(String[] columnNames)
    {
        Objects.requireNonNull(columnNames, "columnNames");
        List<String> names = new ArrayList<>(columnNames.length);
        for (String columnName : columnNames) {
            names.add(Objects.requireNonNull(columnName, "columnName"));
        }
        return List.copyOf(names);
    }

    private static List<Integer> columnPositions(int[] baseColumnPositions)
    {
        Objects.requireNonNull(baseColumnPositions, "baseColumnPositions");
        List<Integer> positions = new ArrayList<>(baseColumnPositions.length);
        for (int baseColumnPosition : baseColumnPositions) {
            positions.add(baseColumnPosition);
        }
        return List.copyOf(positions);
    }

    private static String normalizeStorageProviderName(String providerName)
    {
        if (providerName == null) {
            return TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME;
        }
        String normalized = providerName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME : normalized;
    }

    private static String normalizeIndexProviderName(String providerName)
    {
        if (providerName == null) {
            return DEFAULT_INDEX_PROVIDER_NAME;
        }
        String normalized = providerName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? DEFAULT_INDEX_PROVIDER_NAME : normalized;
    }

    public record Result(
            String schemaName,
            String tableName,
            String tableStorageProviderName,
            String indexName,
            List<String> columnNames,
            List<Integer> baseColumnPositions,
            String indexProviderName)
    {
        public Result
        {
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(tableName, "tableName");
            tableStorageProviderName = normalizeStorageProviderName(tableStorageProviderName);
            Objects.requireNonNull(indexName, "indexName");
            columnNames = List.copyOf(Objects.requireNonNull(columnNames, "columnNames"));
            baseColumnPositions = List.copyOf(Objects.requireNonNull(baseColumnPositions, "baseColumnPositions"));
            indexProviderName = normalizeIndexProviderName(indexProviderName);
        }

        public boolean isDefaultStorageProvider()
        {
            return TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME.equals(tableStorageProviderName);
        }

        public boolean isStorageProvider(String providerName)
        {
            return tableStorageProviderName.equals(normalizeStorageProviderName(providerName));
        }
    }
}
