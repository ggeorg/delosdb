/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosDeleteResultSet

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
import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.ResultSet;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Delos DELETE result set for the Phase F native execution seam.
 *
 * <p>F6 keeps Derby's generated activation and {@code ResultSetFactory}
 * method shape intact.  Under a proof property, {@link GenericResultSetFactory}
 * replaces the normal heap {@link DeleteResultSet} for {@code delos_mvcc}
 * catalog tables.  The WHERE equality still comes from Derby's generated
 * source tree and its native {@link DelosTableScanResultSet}; this class owns
 * only the provider mutation boundary:</p>
 *
 * <pre>
 * DelosTableScanResultSet Qualifier[][]
 *   -> DelosPredicate equality
 *   -> EngineMvccTableAccess.scan(...)
 *   -> DelosRowIdentity
 *   -> EngineMvccTableAccess.delete(...)
 * </pre>
 *
 * <p><strong>MODULE5A bridge status:</strong> transitional Derby execution
 * seam, not final store/access integration. Current role: prove Derby DELETE
 * execution can turn a scan-visible MVCC row identity into a provider delete.
 * Replacement path: Derby delete/conglomerate/store-access dispatch deletes
 * through the MVCC provider directly. Delete after: normal Derby DELETE over
 * MVCC-backed tables no longer needs this proof result set.</p>
 */
final class DelosDeleteResultSet extends NoRowsResultSetImpl
{
    static final String NATIVE_DELETE_EQUALITY_PROPERTY =
            "delosdb.storage.phaseF6.nativeMvccDeleteEquality";

    private final NoPutResultSet source;
    private final DelosTableScanResultSet nativeScanSource;
    private final DelosTableScanProviderLookup.Result providerLookup;
    private long rowCount;

    private DelosDeleteResultSet(
            NoPutResultSet source,
            Activation activation,
            DelosTableScanResultSet nativeScanSource,
            DelosTableScanProviderLookup.Result providerLookup)
    {
        super(activation);
        this.source = source;
        this.nativeScanSource = nativeScanSource;
        this.providerLookup = providerLookup;
    }

    static Optional<ResultSet> createIfEnabled(NoPutResultSet source, Activation activation)
            throws StandardException
    {
        if (!Boolean.getBoolean(NATIVE_DELETE_EQUALITY_PROPERTY)) {
            return Optional.empty();
        }
        if (source == null || activation == null) {
            return Optional.empty();
        }
        if (!(activation.getConstantAction() instanceof DeleteConstantAction constants)) {
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
        rejectUnsupportedDerbyDeleteFeatures(constants);

        Optional<DelosTableScanResultSet> nativeScan = DelosTableScanResultSet.findIn(source);
        if (nativeScan.isEmpty()) {
            throw StandardException.plainWrapException(new UnsupportedOperationException(
                    "F6 native MVCC DELETE equality requires a DelosTableScanResultSet source"));
        }
        if (!lookup.get().equals(nativeScan.get().providerLookupForMutation())) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "F6 native DELETE target table does not match the native scan provider lookup"));
        }
        return Optional.of(new DelosDeleteResultSet(source, activation, nativeScan.get(), lookup.get()));
    }

    @Override
    public void open() throws StandardException
    {
        setup();
        beginTime = getCurrentTimeMillis();
        rowCount = 0L;
        DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess = null;
        DelosScan scan = null;
        try {
            List<DelosPredicate> predicates = nativeScanSource.equalityPredicatesForNativeMutation();
            nativeAccess = DelosNativeResultSetSupport.openNativeTableAccess(
                    nativeScanSource.tableDescriptorForNativeRegistry(),
                    providerLookup);
            scan = nativeAccess.tableAccess().scan(
                    nativeAccess.context(),
                    predicates,
                    DelosProjection.all());
            while (scan.next()) {
                DelosRow row = scan.row();
                DelosRowIdentity rowIdentity = row.rowIdentity().orElseThrow(() ->
                        new IllegalStateException("F6 native DELETE scan row has no DelosRowIdentity"));
                rowCount += nativeAccess.delete(rowIdentity);
            }
            scan.close();
            scan = null;
            nativeAccess.close();
            nativeAccess = null;
        } catch (VersionedWriteConflictException e) {
            DelosNativeResultSetSupport.abortNativeAccess(nativeAccess, e);
            throw DelosMutationConflictMapper.transactionConflict(
                    e,
                    "DELETE",
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
            if (scan != null) {
                scan.close();
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

    private static void rejectUnsupportedDerbyDeleteFeatures(DeleteConstantAction constants)
            throws StandardException
    {
        if (constants.deferred || constants.getFKInfo() != null || constants.getTriggerInfo() != null) {
            throw StandardException.plainWrapException(new UnsupportedOperationException(
                    "F6 native MVCC DELETE equality does not support deferred deletes, triggers, or FK checks yet"));
        }
    }
}
