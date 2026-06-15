/*

   Derby - Class org.apache.derby.impl.sql.execute.IndexRowToBaseRowResultSetParameters

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

/**
 * Constructor payload for the index-row-to-base-row execution bridge.
 *
 * <p>This keeps the generated result-set factory from passing a long list of
 * similarly typed saved-object indexes and booleans directly into the result
 * set constructor.</p>
 */
final class IndexRowToBaseRowResultSetParameters
{
    final long conglomId;
    final int scociItem;
    final Activation activation;
    final NoPutResultSet source;
    final int resultRowAllocator;
    final int resultSetNumber;
    final String indexName;
    final int heapColRefItem;
    final int allColRefItem;
    final int heapOnlyColRefItem;
    final int indexColMapItem;
    final GeneratedMethod restriction;
    final boolean forUpdate;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;
    final int baseColumnCount;

    IndexRowToBaseRowResultSetParameters(
            long conglomId,
            int scociItem,
            Activation activation,
            NoPutResultSet source,
            int resultRowAllocator,
            int resultSetNumber,
            String indexName,
            int heapColRefItem,
            int allColRefItem,
            int heapOnlyColRefItem,
            int indexColMapItem,
            GeneratedMethod restriction,
            boolean forUpdate,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost,
            int baseColumnCount)
    {
        this.conglomId = conglomId;
        this.scociItem = scociItem;
        this.activation = activation;
        this.source = source;
        this.resultRowAllocator = resultRowAllocator;
        this.resultSetNumber = resultSetNumber;
        this.indexName = indexName;
        this.heapColRefItem = heapColRefItem;
        this.allColRefItem = allColRefItem;
        this.heapOnlyColRefItem = heapOnlyColRefItem;
        this.indexColMapItem = indexColMapItem;
        this.restriction = restriction;
        this.forUpdate = forUpdate;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
        this.baseColumnCount = baseColumnCount;
    }
}
