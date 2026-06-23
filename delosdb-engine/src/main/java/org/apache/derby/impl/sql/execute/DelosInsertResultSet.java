/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosInsertResultSet

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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import org.apache.derby.iapi.sql.ResultSet;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.SchemaDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Delos INSERT result set for the Phase F native execution seam.
 *
 * <p>F5 keeps the generated activation and {@code ResultSetFactory} method
 * shape intact.  Under a proof property, {@link GenericResultSetFactory}
 * replaces the normal Derby heap {@link InsertResultSet} with this result set
 * for {@code delos_mvcc} catalog tables.  The source row still comes from
 * Derby's generated execution tree; this class only owns the provider mutation
 * boundary:</p>
 *
 * <pre>
 * source ExecRow
 *   -> native Java values
 *   -> DelosNativeTableRegistry.NativeExecutionTableAccess.insert(...)
 *   -> EngineMvccTableAccess.insert(...)
 * </pre>
 */
final class DelosInsertResultSet extends NoRowsResultSetImpl
{
    static final String NATIVE_INSERT_PROPERTY =
            "delosdb.storage.phaseF5.nativeMvccInsert";

    private final InsertResultSetParameters params;
    private final DelosTableScanProviderLookup.Result providerLookup;
    private long rowCount;

    private DelosInsertResultSet(
            InsertResultSetParameters params,
            DelosTableScanProviderLookup.Result providerLookup)
    {
        super(params.activation);
        this.params = params;
        this.providerLookup = providerLookup;
    }

    static Optional<ResultSet> createIfEnabled(InsertResultSetParameters params)
            throws StandardException
    {
        if (!Boolean.getBoolean(NATIVE_INSERT_PROPERTY)) {
            return Optional.empty();
        }
        Optional<DelosTableScanProviderLookup.Result> lookup =
                DelosTableScanProviderLookup.find(
                        params.activation,
                        qualifiedName(params.schemaName, params.tableName));
        if (lookup.isEmpty() || lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }
        return Optional.of(new DelosInsertResultSet(params, lookup.get()));
    }

    @Override
    public void open() throws StandardException
    {
        setup();
        beginTime = getCurrentTimeMillis();
        rowCount = 0L;
        DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess = null;
        boolean sourceOpen = false;
        try {
            params.source.openCore();
            sourceOpen = true;
            nativeAccess = DelosNativeTableRegistry.openNativeExecutionTableAccess(tableDescriptor())
                    .orElseThrow(() -> StandardException.plainWrapException(
                            new IllegalStateException("No delos_mvcc native table access registered for "
                                    + providerLookup.schemaName() + "." + providerLookup.tableName())));

            ExecRow row;
            while ((row = params.source.getNextRowCore()) != null) {
                rowCount += nativeAccess.insert(nativeValues(row));
            }
            nativeAccess.close();
            nativeAccess = null;
        } catch (SQLException e) {
            abortNativeAccess(nativeAccess, e);
            throw StandardException.plainWrapException(e);
        } catch (RuntimeException e) {
            abortNativeAccess(nativeAccess, e);
            throw StandardException.plainWrapException(e);
        } catch (StandardException e) {
            abortNativeAccess(nativeAccess, e);
            throw e;
        } finally {
            if (sourceOpen) {
                params.source.close();
            }
            endTime = getCurrentTimeMillis();
        }
    }

    @Override
    public long modifiedRowCount()
    {
        return rowCount;
    }

    @Override
    public void close() throws StandardException
    {
        close(false);
    }

    @Override
    public void cleanUp() throws StandardException
    {
        params.source.close();
    }

    String tableNameForTesting()
    {
        return params.tableName;
    }

    String storageProviderNameForTesting()
    {
        return providerLookup.storageProviderName();
    }


    private TableDescriptor tableDescriptor() throws StandardException
    {
        LanguageConnectionContext lcc = params.activation.getLanguageConnectionContext();
        DataDictionary dataDictionary = lcc.getDataDictionary();
        TransactionController transactionController = lcc.getTransactionExecute();
        SchemaDescriptor schema = dataDictionary.getSchemaDescriptor(
                providerLookup.schemaName(),
                transactionController,
                true);
        TableDescriptor table = dataDictionary.getTableDescriptor(
                providerLookup.tableName(),
                schema,
                transactionController);
        if (table == null) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "No TableDescriptor for Delos native insert: "
                            + providerLookup.schemaName() + "." + providerLookup.tableName()));
        }
        return table;
    }

    private static List<Object> nativeValues(ExecRow row) throws StandardException
    {
        List<Object> values = new ArrayList<>(row.nColumns());
        for (int column = 1; column <= row.nColumns(); column++) {
            DataValueDescriptor value = row.getColumn(column);
            values.add(value == null ? null : value.getObject());
        }
        return List.copyOf(values);
    }

    private static void abortNativeAccess(
            DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess,
            Throwable failure)
    {
        if (nativeAccess == null) {
            return;
        }
        try {
            nativeAccess.abort();
        } catch (SQLException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    private static String qualifiedName(String schemaName, String tableName)
    {
        if (schemaName == null || schemaName.isBlank()) {
            return tableName;
        }
        return schemaName + "." + tableName;
    }
}
