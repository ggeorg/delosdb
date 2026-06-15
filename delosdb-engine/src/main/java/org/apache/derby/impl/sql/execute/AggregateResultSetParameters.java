/*

   Derby - Class org.apache.derby.impl.sql.execute.AggregateResultSetParameters

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

import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;

/**
 * Constructor payload shared by aggregate result sets.
 */
final class AggregateResultSetParameters
{
    final NoPutResultSet source;
    final boolean isInSortedOrder;
    final int aggregateItem;
    final int orderingItem;
    final Activation activation;
    final int rowAllocator;
    final int maxRowSize;
    final int resultSetNumber;
    final boolean singleInputRow;
    final boolean rollup;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    AggregateResultSetParameters(
            NoPutResultSet source,
            boolean isInSortedOrder,
            int aggregateItem,
            int orderingItem,
            Activation activation,
            int rowAllocator,
            int maxRowSize,
            int resultSetNumber,
            boolean singleInputRow,
            boolean rollup,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost)
    {
        this.source = source;
        this.isInSortedOrder = isInSortedOrder;
        this.aggregateItem = aggregateItem;
        this.orderingItem = orderingItem;
        this.activation = activation;
        this.rowAllocator = rowAllocator;
        this.maxRowSize = maxRowSize;
        this.resultSetNumber = resultSetNumber;
        this.singleInputRow = singleInputRow;
        this.rollup = rollup;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }
}
