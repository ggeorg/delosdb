/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosUpdateResultSet

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

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.ResultSet;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Delos UPDATE result set for the Phase F native execution seam.
 *
 * <p>F7 keeps Derby's generated activation and {@code ResultSetFactory}
 * method shape intact.  Under a proof property, {@link GenericResultSetFactory}
 * replaces the normal heap {@link UpdateResultSet} for {@code delos_mvcc}
 * catalog tables.  Derby still computes the replacement row through the
 * generated source tree; this result set owns only the provider mutation
 * boundary:</p>
 *
 * <pre>
 * DelosTableScanResultSet Qualifier[][]
 *   -> Derby source row with before/after columns
 *   -> DelosRowIdentity from the current native scan row
 *   -> replacement DelosRow values
 *   -> EngineMvccTableAccess.update(...)
 * </pre>
 */
final class DelosUpdateResultSet extends NoRowsResultSetImpl
{
    static final String NATIVE_UPDATE_EQUALITY_PROPERTY =
            "delosdb.storage.phaseF7.nativeMvccUpdateEquality";

    private final NoPutResultSet source;
    private final GeneratedMethod generationClauses;
    private final GeneratedMethod checkGM;
    private final DelosTableScanResultSet nativeScanSource;
    private final DelosTableScanProviderLookup.Result providerLookup;
    private final int baseColumnCount;
    private long rowCount;

    private DelosUpdateResultSet(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation,
            DelosTableScanResultSet nativeScanSource,
            DelosTableScanProviderLookup.Result providerLookup,
            int baseColumnCount)
    {
        super(activation);
        this.source = source;
        this.generationClauses = generationClauses;
        this.checkGM = checkGM;
        this.nativeScanSource = nativeScanSource;
        this.providerLookup = providerLookup;
        this.baseColumnCount = baseColumnCount;
    }

    static Optional<ResultSet> createIfEnabled(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation)
            throws StandardException
    {
        if (!Boolean.getBoolean(NATIVE_UPDATE_EQUALITY_PROPERTY)) {
            return Optional.empty();
        }
        if (source == null || activation == null) {
            return Optional.empty();
        }
        if (!(activation.getConstantAction() instanceof UpdateConstantAction constants)) {
            return Optional.empty();
        }

        TableDescriptor targetTable = targetTableDescriptor(activation, constants);
        if (targetTable == null) {
            return Optional.empty();
        }
        Optional<DelosTableScanProviderLookup.Result> lookup =
                DelosTableScanProviderLookup.find(
                        activation.getLanguageConnectionContext(),
                        qualifiedName(targetTable));
        if (lookup.isEmpty() || lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }
        rejectUnsupportedDerbyUpdateFeatures(constants);

        Optional<DelosTableScanResultSet> nativeScan = DelosTableScanResultSet.findIn(source);
        if (nativeScan.isEmpty()) {
            throw StandardException.plainWrapException(new UnsupportedOperationException(
                    "F7 native MVCC UPDATE equality requires a DelosTableScanResultSet source"));
        }
        if (!lookup.get().equals(nativeScan.get().providerLookupForMutation())) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "F7 native UPDATE target table does not match the native scan provider lookup"));
        }
        return Optional.of(new DelosUpdateResultSet(
                source,
                generationClauses,
                checkGM,
                activation,
                nativeScan.get(),
                lookup.get(),
                targetTable.getNumberOfColumns()));
    }

    @Override
    public void open() throws StandardException
    {
        setup();
        beginTime = getCurrentTimeMillis();
        rowCount = 0L;
        VersionedStorageSqlBridge.NativeExecutionTableAccess nativeAccess = null;
        boolean sourceOpen = false;
        try {
            source.openCore();
            sourceOpen = true;
            nativeAccess = VersionedStorageSqlBridge.openNativeExecutionTableAccess(
                            providerLookup.schemaName(),
                            providerLookup.tableName())
                    .orElseThrow(() -> StandardException.plainWrapException(
                            new IllegalStateException("No delos_mvcc native table access registered for "
                                    + providerLookup.schemaName() + "." + providerLookup.tableName())));

            ExecRow row;
            while ((row = source.getNextRowCore()) != null) {
                evaluateGenerationClauses(generationClauses, activation, source, row, true);
                DelosRowIdentity rowIdentity = nativeScanSource.currentDelosRowIdentityForNativeMutation();
                rowCount += nativeAccess.update(rowIdentity, replacementNativeValues(row, baseColumnCount));
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
                source.close();
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
        source.close();
    }

    String tableNameForTesting()
    {
        return providerLookup.tableName();
    }

    String storageProviderNameForTesting()
    {
        return providerLookup.storageProviderName();
    }

    private static TableDescriptor targetTableDescriptor(
            Activation activation,
            UpdateConstantAction constants) throws StandardException
    {
        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        DataDictionary dataDictionary = lcc.getDataDictionary();
        return dataDictionary.getTableDescriptor(constants.targetUUID);
    }

    private static void rejectUnsupportedDerbyUpdateFeatures(UpdateConstantAction constants)
            throws StandardException
    {
        if (constants.deferred || constants.getFKInfo() != null || constants.getTriggerInfo() != null) {
            throw StandardException.plainWrapException(new UnsupportedOperationException(
                    "F7 native MVCC UPDATE equality does not support deferred updates, triggers, or FK checks yet"));
        }
        if (constants.hasAutoincrement()) {
            throw StandardException.plainWrapException(new UnsupportedOperationException(
                    "F7 native MVCC UPDATE equality does not support autoincrement updates yet"));
        }
    }

    private static List<Object> replacementNativeValues(ExecRow row, int baseColumnCount) throws StandardException
    {
        int resultWidth = row.nColumns();
        if (baseColumnCount <= 0) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "F7 native UPDATE could not resolve a positive base-column count"));
        }

        int firstReplacementColumn;
        if (resultWidth == baseColumnCount + 1) {
            // Simple generated UPDATE sources may expose the after-image plus RowLocation only.
            firstReplacementColumn = 1;
        } else if (resultWidth == (baseColumnCount * 2) + 1) {
            // Derby's full UPDATE convention is before-image, after-image, RowLocation.
            firstReplacementColumn = baseColumnCount + 1;
        } else if (resultWidth > baseColumnCount) {
            // Keep the seam defensive for compact source rows: the replacement image is the
            // base-column-sized slice immediately before the trailing RowLocation column.
            firstReplacementColumn = resultWidth - baseColumnCount;
        } else {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "F7 native UPDATE source row is too narrow for replacement extraction; result width="
                            + resultWidth + ", base columns=" + baseColumnCount));
        }

        List<Object> values = new ArrayList<>(baseColumnCount);
        for (int column = firstReplacementColumn; column < firstReplacementColumn + baseColumnCount; column++) {
            DataValueDescriptor value = row.getColumn(column);
            values.add(value == null ? null : value.getObject());
        }
        return List.copyOf(values);
    }

    private static void abortNativeAccess(
            VersionedStorageSqlBridge.NativeExecutionTableAccess nativeAccess,
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

    private static String qualifiedName(TableDescriptor tableDescriptor)
    {
        return tableDescriptor.getSchemaName() + "." + tableDescriptor.getName();
    }
}
