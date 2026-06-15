/*

   Derby - Class org.apache.derby.impl.sql.execute.TableScanResultSetParameters

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

import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;

/**
 * Shared constructor payload for table-scan result sets.
 *
 * <p>The generated result-set factory has several entry points that differ
 * only in the concrete scan class they instantiate. Keeping the shared scan
 * fields in one package-private value object makes those paths easier to
 * compare without changing scan semantics, optimizer choices, locking, or
 * access-method behavior.</p>
 */
final class TableScanResultSetParameters
{
    static final int NON_BULK_ROWS_PER_READ = 1;

    final long conglomId;
    final StaticCompiledOpenConglomInfo scoci;
    final Activation activation;
    final int resultRowTemplate;
    final int resultSetNumber;
    final GeneratedMethod startKeyGetter;
    final int startSearchOperator;
    final GeneratedMethod stopKeyGetter;
    final int stopSearchOperator;
    final boolean sameStartStopPosition;
    final Qualifier[][] qualifiers;
    final String tableName;
    final String userSuppliedOptimizerOverrides;
    final String indexName;
    final boolean isConstraint;
    final boolean forUpdate;
    final int colRefItem;
    final int indexColItem;
    final int lockMode;
    final boolean tableLocked;
    final int isolationLevel;
    final int rowsPerRead;
    final boolean oneRowScan;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    TableScanResultSetParameters(
            long conglomId,
            StaticCompiledOpenConglomInfo scoci,
            Activation activation,
            int resultRowTemplate,
            int resultSetNumber,
            GeneratedMethod startKeyGetter,
            int startSearchOperator,
            GeneratedMethod stopKeyGetter,
            int stopSearchOperator,
            boolean sameStartStopPosition,
            Qualifier[][] qualifiers,
            String tableName,
            String userSuppliedOptimizerOverrides,
            String indexName,
            boolean isConstraint,
            boolean forUpdate,
            int colRefItem,
            int indexColItem,
            int lockMode,
            boolean tableLocked,
            int isolationLevel,
            int rowsPerRead,
            boolean oneRowScan,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost)
    {
        this.conglomId = conglomId;
        this.scoci = scoci;
        this.activation = activation;
        this.resultRowTemplate = resultRowTemplate;
        this.resultSetNumber = resultSetNumber;
        this.startKeyGetter = startKeyGetter;
        this.startSearchOperator = startSearchOperator;
        this.stopKeyGetter = stopKeyGetter;
        this.stopSearchOperator = stopSearchOperator;
        this.sameStartStopPosition = sameStartStopPosition;
        this.qualifiers = qualifiers;
        this.tableName = tableName;
        this.userSuppliedOptimizerOverrides = userSuppliedOptimizerOverrides;
        this.indexName = indexName;
        this.isConstraint = isConstraint;
        this.forUpdate = forUpdate;
        this.colRefItem = colRefItem;
        this.indexColItem = indexColItem;
        this.lockMode = lockMode;
        this.tableLocked = tableLocked;
        this.isolationLevel = isolationLevel;
        this.rowsPerRead = rowsPerRead;
        this.oneRowScan = oneRowScan;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }

    TableScanResultSetParameters withRowsPerRead(int rowsPerRead)
    {
        if (this.rowsPerRead == rowsPerRead) { return this; }

        return new TableScanResultSetParameters(
                conglomId,
                scoci,
                activation,
                resultRowTemplate,
                resultSetNumber,
                startKeyGetter,
                startSearchOperator,
                stopKeyGetter,
                stopSearchOperator,
                sameStartStopPosition,
                qualifiers,
                tableName,
                userSuppliedOptimizerOverrides,
                indexName,
                isConstraint,
                forUpdate,
                colRefItem,
                indexColItem,
                lockMode,
                tableLocked,
                isolationLevel,
                rowsPerRead,
                oneRowScan,
                optimizerEstimatedRowCount,
                optimizerEstimatedCost);
    }
}
