/*

   Derby - Class org.apache.derby.impl.sql.execute.HashScanResultSetParameters

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
 * Shared constructor payload for hash-backed scan result sets.
 *
 * <p>The generated result-set factory has separate entry points for normal
 * hash scans and distinct scans. Keeping the common scan/hash fields in this
 * package-private value object removes another fragile long positional
 * constructor path without changing scan semantics or optimizer choices.</p>
 */
final class HashScanResultSetParameters
{
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
    final Qualifier[][] scanQualifiers;
    final Qualifier[][] nextQualifiers;
    final int initialCapacity;
    final float loadFactor;
    final int maxCapacity;
    final int hashKeyItem;
    final String tableName;
    final String userSuppliedOptimizerOverrides;
    final String indexName;
    final boolean isConstraint;
    final boolean forUpdate;
    final int colRefItem;
    final int lockMode;
    final boolean tableLocked;
    final int isolationLevel;
    final boolean skipNullKeyColumns;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    HashScanResultSetParameters(
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
            Qualifier[][] scanQualifiers,
            Qualifier[][] nextQualifiers,
            int initialCapacity,
            float loadFactor,
            int maxCapacity,
            int hashKeyItem,
            String tableName,
            String userSuppliedOptimizerOverrides,
            String indexName,
            boolean isConstraint,
            boolean forUpdate,
            int colRefItem,
            int lockMode,
            boolean tableLocked,
            int isolationLevel,
            boolean skipNullKeyColumns,
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
        this.scanQualifiers = scanQualifiers;
        this.nextQualifiers = nextQualifiers;
        this.initialCapacity = initialCapacity;
        this.loadFactor = loadFactor;
        this.maxCapacity = maxCapacity;
        this.hashKeyItem = hashKeyItem;
        this.tableName = tableName;
        this.userSuppliedOptimizerOverrides = userSuppliedOptimizerOverrides;
        this.indexName = indexName;
        this.isConstraint = isConstraint;
        this.forUpdate = forUpdate;
        this.colRefItem = colRefItem;
        this.lockMode = lockMode;
        this.tableLocked = tableLocked;
        this.isolationLevel = isolationLevel;
        this.skipNullKeyColumns = skipNullKeyColumns;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }
}
