/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosTableScanResultSet

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

import java.util.Optional;

import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Phase F3.2 Delos table-scan result-set skeleton.
 *
 * <p>This class is intentionally not a working MVCC scan yet.  Its purpose is
 * to prove that Derby's existing generated table-scan factory call can return a
 * Delos-owned {@link NoPutResultSet} at the ResultSetFactory seam, without
 * changing {@code FromBaseTable.generate()}, {@code AsmJava}, or the generated
 * bytecode method shape.</p>
 *
 * <p>The branch is property-gated in F3.2.  If accidentally executed, the
 * skeleton fails loudly instead of pretending to scan MVCC rows.  F4 replaces
 * this open-time sentinel with real {@code EngineMvccTableAccess.scan(...)}
 * row materialization.</p>
 */
final class DelosTableScanResultSet extends NoPutResultSetImpl
{
    static final String SKELETON_BRANCH_PROPERTY =
            "delosdb.storage.phaseF32.delosTableScanSkeleton";

    static final String SKELETON_REACHED_MESSAGE =
            "DelosTableScanResultSet skeleton reached; F4 must implement real MVCC scan materialization";

    private final TableScanResultSetParameters params;
    private final DelosTableScanProviderLookup.Result providerLookup;

    private DelosTableScanResultSet(
            TableScanResultSetParameters params,
            DelosTableScanProviderLookup.Result providerLookup)
    {
        super(params.activation,
                params.resultSetNumber,
                params.optimizerEstimatedRowCount,
                params.optimizerEstimatedCost);
        this.params = params;
        this.providerLookup = providerLookup;
        recordConstructorTime();
    }

    static Optional<NoPutResultSet> createIfEnabled(TableScanResultSetParameters params)
            throws StandardException
    {
        if (!Boolean.getBoolean(SKELETON_BRANCH_PROPERTY)) {
            return Optional.empty();
        }

        Optional<DelosTableScanProviderLookup.Result> lookup =
                DelosTableScanProviderLookup.find(params.activation, params.tableName);
        if (lookup.isEmpty() || lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }

        return Optional.of(new DelosTableScanResultSet(params, lookup.get()));
    }

    @Override
    public void openCore() throws StandardException
    {
        throw StandardException.plainWrapException(
                new UnsupportedOperationException(SKELETON_REACHED_MESSAGE));
    }

    @Override
    public ExecRow getNextRowCore() throws StandardException
    {
        throw StandardException.plainWrapException(
                new UnsupportedOperationException(SKELETON_REACHED_MESSAGE));
    }

    @Override
    public long getTimeSpent(int type)
    {
        return constructorTime + openTime + nextTime + closeTime;
    }

    @Override
    public int getScanIsolationLevel()
    {
        return params.isolationLevel;
    }

    String tableNameForTesting()
    {
        return params.tableName;
    }

    String storageProviderNameForTesting()
    {
        return providerLookup.storageProviderName();
    }
}
