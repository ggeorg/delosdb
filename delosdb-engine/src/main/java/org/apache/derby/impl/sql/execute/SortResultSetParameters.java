/*

   Derby - Class org.apache.derby.impl.sql.execute.SortResultSetParameters

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
 * Constructor payload for sort result sets.
 */
final class SortResultSetParameters
{
    final NoPutResultSet source;
    final boolean distinct;
    final boolean isInSortedOrder;
    final int orderingItem;
    final Activation activation;
    final int rowAllocator;
    final int maxRowSize;
    final int resultSetNumber;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    SortResultSetParameters(
            NoPutResultSet source,
            boolean distinct,
            boolean isInSortedOrder,
            int orderingItem,
            Activation activation,
            int rowAllocator,
            int maxRowSize,
            int resultSetNumber,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost)
    {
        this.source = source;
        this.distinct = distinct;
        this.isInSortedOrder = isInSortedOrder;
        this.orderingItem = orderingItem;
        this.activation = activation;
        this.rowAllocator = rowAllocator;
        this.maxRowSize = maxRowSize;
        this.resultSetNumber = resultSetNumber;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }
}
