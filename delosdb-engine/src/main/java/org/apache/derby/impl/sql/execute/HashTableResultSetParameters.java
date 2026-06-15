/*

   Derby - Class org.apache.derby.impl.sql.execute.HashTableResultSetParameters

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
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.store.access.Qualifier;

/**
 * Constructor payload for hash-table result sets.
 */
final class HashTableResultSetParameters
{
    final NoPutResultSet source;
    final Activation activation;
    final GeneratedMethod singleTableRestriction;
    final Qualifier[][] nextQualifiers;
    final GeneratedMethod projection;
    final int resultSetNumber;
    final int mapRefItem;
    final boolean reuseResult;
    final int keyColumnItem;
    final boolean removeDuplicates;
    final long maxInMemoryRowCount;
    final int initialCapacity;
    final float loadFactor;
    final boolean skipNullKeyColumns;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    HashTableResultSetParameters(
            NoPutResultSet source,
            Activation activation,
            GeneratedMethod singleTableRestriction,
            Qualifier[][] nextQualifiers,
            GeneratedMethod projection,
            int resultSetNumber,
            int mapRefItem,
            boolean reuseResult,
            int keyColumnItem,
            boolean removeDuplicates,
            long maxInMemoryRowCount,
            int initialCapacity,
            float loadFactor,
            boolean skipNullKeyColumns,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost)
    {
        this.source = source;
        this.activation = activation;
        this.singleTableRestriction = singleTableRestriction;
        this.nextQualifiers = nextQualifiers;
        this.projection = projection;
        this.resultSetNumber = resultSetNumber;
        this.mapRefItem = mapRefItem;
        this.reuseResult = reuseResult;
        this.keyColumnItem = keyColumnItem;
        this.removeDuplicates = removeDuplicates;
        this.maxInMemoryRowCount = maxInMemoryRowCount;
        this.initialCapacity = initialCapacity;
        this.loadFactor = loadFactor;
        this.skipNullKeyColumns = skipNullKeyColumns;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }
}
