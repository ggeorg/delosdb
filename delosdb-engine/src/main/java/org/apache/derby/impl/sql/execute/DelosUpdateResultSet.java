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

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.ResultSet;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Delos UPDATE result set for the Phase F native execution seam.
 *
 * <p>F7 kept Derby's generated activation and {@code ResultSetFactory}
 * method shape intact behind a proof property.  MODULE5F removes that proof
 * gate for {@code delos_mvcc} catalog tables: provider identity now selects
 * this result set automatically, while default heap tables continue to use
 * Derby's normal {@link UpdateResultSet}.  Derby still computes the replacement
 * row through the generated source tree; this result set owns only the provider
 * mutation boundary:</p>
 *
 * <pre>
 * DelosTableScanResultSet Qualifier[][]
 *   -> Derby source row with before/after columns
 *   -> DelosRowIdentity from the current native scan row
 *   -> replacement DelosRow values
 *   -> EngineMvccTableAccess.update(...)
 * </pre>
 *
 * <p><strong>MODULE5A bridge status:</strong> transitional Derby execution
 * seam, not final store/access integration. Current role: prove Derby UPDATE
 * execution can convert a scan-visible MVCC row identity plus replacement row
 * into a provider update. Replacement path: Derby update/conglomerate/store-
 * access dispatch updates the MVCC provider directly. Delete after: normal
 * Derby UPDATE over MVCC-backed tables no longer needs this proof result set.</p>
 */
final class DelosUpdateResultSet extends NoRowsResultSetImpl
{
    /**
     * Legacy proof property retained for compatibility with earlier smokes.
     * MODULE5F no longer uses it as a route guard; delos_mvcc UPDATE now routes
     * by persisted table/provider identity.
     */
    static final String NATIVE_UPDATE_EQUALITY_PROPERTY =
            "delosdb.storage.phaseF7.nativeMvccUpdateEquality";

    private final NoPutResultSet source;
    private final GeneratedMethod generationClauses;
    private final GeneratedMethod checkGM;
    private final DelosTableScanResultSet nativeScanSource;
    private final DelosTableScanProviderLookup.Result providerLookup;
    private final UpdateConstantAction constants;
    private final int baseColumnCount;
    private long rowCount;

    private DelosUpdateResultSet(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation,
            DelosTableScanResultSet nativeScanSource,
            DelosTableScanProviderLookup.Result providerLookup,
            UpdateConstantAction constants,
            int baseColumnCount)
    {
        super(activation);
        this.source = source;
        this.generationClauses = generationClauses;
        this.checkGM = checkGM;
        this.nativeScanSource = nativeScanSource;
        this.providerLookup = providerLookup;
        this.constants = constants;
        this.baseColumnCount = baseColumnCount;
    }

    static Optional<ResultSet> createIfEnabled(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation)
            throws StandardException
    {
        if (source == null || activation == null) {
            return Optional.empty();
        }
        if (!(activation.getConstantAction() instanceof UpdateConstantAction constants)) {
            return Optional.empty();
        }

        TableDescriptor targetTable = DelosNativeResultSetSupport.tableDescriptor(activation, constants.targetUUID);
        if (targetTable == null) {
            return Optional.empty();
        }
        Optional<DelosTableScanProviderLookup.Result> lookup =
                DelosTableScanProviderLookup.find(
                        activation.getLanguageConnectionContext(),
                        DelosNativeResultSetSupport.qualifiedName(targetTable));
        if (lookup.isEmpty() || lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }
        rejectUnsupportedDerbyUpdateFeatures(constants);

        Optional<DelosTableScanResultSet> nativeScan = DelosTableScanResultSet.findIn(source);
        if (nativeScan.isEmpty()) {
            throw StandardException.plainWrapException(new UnsupportedOperationException(
                    "MODULE5F native MVCC UPDATE requires a DelosTableScanResultSet source"));
        }
        if (!lookup.get().equals(nativeScan.get().providerLookupForMutation())) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "MODULE5F native UPDATE target table does not match the native scan provider lookup"));
        }
        return Optional.of(new DelosUpdateResultSet(
                source,
                generationClauses,
                checkGM,
                activation,
                nativeScan.get(),
                lookup.get(),
                constants,
                targetTable.getNumberOfColumns()));
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
            source.openCore();
            sourceOpen = true;
            nativeAccess = DelosNativeResultSetSupport.openNativeTableAccess(
                    activation,
                    nativeScanSource.tableDescriptorForNativeRegistry(),
                    providerLookup);

            ExecRow row;
            while ((row = source.getNextRowCore()) != null) {
                evaluateGenerationClauses(generationClauses, activation, source, row, true);
                DelosRowIdentity rowIdentity = nativeScanSource.currentDelosRowIdentityForNativeMutation();
                rowCount += nativeAccess.update(rowIdentity, replacementNativeValues(
                        row,
                        baseColumnCount,
                        constants,
                        nativeScanSource.currentDelosNativeValuesForNativeMutation()));
            }
            nativeAccess.close();
            nativeAccess = null;
        } catch (VersionedWriteConflictException e) {
            DelosNativeResultSetSupport.abortNativeAccess(nativeAccess, e);
            throw DelosMutationConflictMapper.transactionConflict(
                    e,
                    "UPDATE",
                    providerLookup.schemaName() + "." + providerLookup.tableName());
        } catch (SQLException e) {
            DelosNativeResultSetSupport.abortNativeAccess(nativeAccess, e);
            throw StandardException.plainWrapException(e);
        } catch (RuntimeException e) {
            DelosNativeResultSetSupport.abortNativeAccess(nativeAccess, e);
            throw StandardException.plainWrapException(e);
        } catch (StandardException e) {
            DelosNativeResultSetSupport.abortNativeAccess(nativeAccess, e);
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

    private static void rejectUnsupportedDerbyUpdateFeatures(UpdateConstantAction constants)
            throws StandardException
    {
        if (constants.deferred || constants.getFKInfo() != null || constants.getTriggerInfo() != null) {
            throw StandardException.plainWrapException(new UnsupportedOperationException(
                    "MODULE5F native MVCC UPDATE does not support deferred updates, triggers, or FK checks yet"));
        }
        if (constants.hasAutoincrement()) {
            throw StandardException.plainWrapException(new UnsupportedOperationException(
                    "MODULE5F native MVCC UPDATE does not support autoincrement updates yet"));
        }
    }

    private static List<Object> replacementNativeValues(
            ExecRow row,
            int baseColumnCount,
            UpdateConstantAction constants,
            List<Object> currentNativeValues) throws StandardException
    {
        int resultWidth = row.nColumns();
        if (baseColumnCount <= 0) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "F7 native UPDATE could not resolve a positive base-column count"));
        }
        if (currentNativeValues.size() != baseColumnCount) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "F7 native UPDATE current row value count does not match delos_mvcc table column count; current="
                            + currentNativeValues.size() + ", base columns=" + baseColumnCount));
        }

        int[] changedColumnIds = constants.changedColumnIds;
        if (changedColumnIds == null || changedColumnIds.length == 0) {
            return replacementNativeValuesFromFullAfterImage(row, baseColumnCount);
        }

        List<Object> replacement = new ArrayList<>(currentNativeValues);
        int firstChangedValueColumn = firstChangedValueColumn(row, baseColumnCount, changedColumnIds.length);
        for (int changedIndex = 0; changedIndex < changedColumnIds.length; changedIndex++) {
            int baseColumnId = changedColumnIds[changedIndex];
            if (baseColumnId < 1 || baseColumnId > baseColumnCount) {
                throw StandardException.plainWrapException(new IllegalStateException(
                        "F7 native UPDATE changed column id is outside base row: " + baseColumnId));
            }
            DataValueDescriptor value = row.getColumn(firstChangedValueColumn + changedIndex);
            replacement.set(baseColumnId - 1, value == null ? null : value.getObject());
        }
        return List.copyOf(replacement);
    }

    private static List<Object> replacementNativeValuesFromFullAfterImage(ExecRow row, int baseColumnCount)
            throws StandardException
    {
        int resultWidth = row.nColumns();
        int firstReplacementColumn;
        if (resultWidth == baseColumnCount + 1) {
            firstReplacementColumn = 1;
        } else if (resultWidth == (baseColumnCount * 2) + 1) {
            firstReplacementColumn = baseColumnCount + 1;
        } else if (resultWidth > baseColumnCount) {
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

    private static int firstChangedValueColumn(ExecRow row, int baseColumnCount, int changedColumnCount)
            throws StandardException
    {
        int resultWidth = row.nColumns();
        int rowLocationColumn = resultWidth;
        if (changedColumnCount <= 0 || resultWidth < changedColumnCount + 1) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "F7 native UPDATE source row is too narrow for changed-column extraction; result width="
                            + resultWidth + ", changed columns=" + changedColumnCount));
        }
        if (resultWidth == (changedColumnCount * 2) + 1) {
            // Compact Derby UPDATE source: old changed-column values, new changed-column values, RowLocation.
            return changedColumnCount + 1;
        }
        if (resultWidth == baseColumnCount + changedColumnCount + 1) {
            // Full current row, changed-column after-image, RowLocation.
            return baseColumnCount + 1;
        }
        if (resultWidth == (baseColumnCount * 2) + 1) {
            // Full before-image, full after-image, RowLocation.
            return baseColumnCount + 1;
        }
        return rowLocationColumn - changedColumnCount;
    }
}
